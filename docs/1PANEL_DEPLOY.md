# 1Panel / Docker Compose 部署

这份文档适合两类人：

- 用 **1Panel** 管理服务器，想把 Starry API 放在 1Panel 的反向代理后面；
- 想**从源码构建镜像**（而不是下载预构建产物）的人。

如果你只想最快跑起来，直接用 [服务器一键安装](INSTALL_SERVER.md)，它已经包含了下面大部分步骤；装好后回到本文的「配置 1Panel 反向代理」即可。

```text
1Panel 反向代理 / HTTPS
   ▼
服务器IP:18080
   ▼
starry-api 服务容器  ──►  PostgreSQL 17 容器
```

> **为什么不能用 `127.0.0.1` 当上游**：容器化的 1Panel / OpenResty 看到的 `127.0.0.1` 是它**自己**，不是宿主机。所以服务默认绑定 `0.0.0.0:${HOST_HTTP_PORT}`，反向代理目标要用服务器 IP。请用防火墙 / 安全组限制 `${HOST_HTTP_PORT}`，只通过 1Panel 的 HTTPS 站点对外。

---

## 方式一：一键安装（推荐）

在全新的 Linux 服务器上：

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://你的域名 bash
```

装完后跳到 [配置 1Panel 反向代理](#配置-1panel-反向代理)。

---

## 方式二：从源码构建（手动 6 步）

### 1. 准备目录并拉代码

打开 1Panel 终端或 SSH：

```bash
mkdir -p /opt/starry-api
cd /opt/starry-api
git clone https://github.com/StaR4y/starry-api.git .
```

以后更新源码：

```bash
cd /opt/starry-api
git pull
docker compose up -d --build service
```

> 服务镜像里已经包含最新构建的控制台。如果更新后 1Panel 里打开的还是旧页面，无缓存重建镜像并刷新浏览器缓存：
>
> ```bash
> cd /opt/starry-api
> docker compose build --no-cache service
> docker compose up -d service
> docker compose logs --tail=160 service
> ```

### 2. 配置环境变量

```bash
cp .env.example .env
nano .env
```

**必须**设置三个**互不相同**的强密码：

```dotenv
POSTGRES_BOOTSTRAP_PASSWORD=换成强密码-超级用户
POSTGRES_MIGRATION_PASSWORD=换成强密码-迁移用
POSTGRES_PASSWORD=换成强密码-应用用
```

- `POSTGRES_BOOTSTRAP_PASSWORD` 保护的是数据库层面的应急超级用户，应用**从不使用**它，请限制并审计对它的访问。
- 数据库名和角色名由最小权限的初始化脚本固定，不用改。

推荐的默认值：

```dotenv
POSTGRES_MAX_CONNECTIONS=100
POSTGRES_SHARED_BUFFERS=256MB
POSTGRES_EFFECTIVE_CACHE_SIZE=768MB
POSTGRES_POOL_INITIAL_SIZE=4
POSTGRES_POOL_MAX_SIZE=20
HOST_BIND_ADDR=0.0.0.0
HOST_HTTP_PORT=18080
STARRY_ALLOW_ANONYMOUS_PROXY=false
STARRY_ADMIN_SESSION_TTL=12h
STARRY_RATE_LIMIT_PER_MINUTE=120
STARRY_WEBUI_URL=https://你的域名/#/login
```

⚠️ **公开服务绝对不要把 `STARRY_ALLOW_ANONYMOUS_PROXY` 设成 `true`**（那样不需要 API Key 就能调代理）。数据库文件持久化在 `./data/postgresql`；以上数据库默认值假定至少 2 GiB 可用内存，业务增长后再根据监控调整。

### 3. 启动

```bash
./scripts/deploy-1panel.sh
```

这个脚本会：检查 Docker Compose 是否可用、**拒绝使用默认数据库密码**、构建 Spring Boot 服务镜像、启动 PostgreSQL 17 和服务、打印最近的服务日志。

等价的手动命令：

```bash
docker compose up -d --build
docker compose logs -f service
```

### 4. 保存 admin 初始密码

首次启动成功后，日志里会有：

```text
Default admin user created. Save this password now.
Username: admin
Password: ...
```

密码**只在 Root 标记未分配且 `admin_users` 表为空时打印一次**。如果第二次启动时发现 admin 仍是首次登录状态，日志只会提醒：

```text
Default admin user already exists and still uses the first-login password.
The password was printed only during first startup and will not be shown again.
```

不用翻日志：在仓库根目录执行 `./scripts/starry-api.sh info`（Root 还没改过密码、且日志里还留着那一行时，会显示初始密码），忘了密码就执行 `./scripts/starry-api.sh reset-password`，输入 `RESET` 确认后得到新的临时密码（服务会重启）。数据管理安全密钥无法找回或重置，该命令只显示它的状态。详见 [运维手册](OPERATIONS.md#服务器上的-starry-api-命令)。

### 5. 配置 1Panel 反向代理

[→ 跳到下一节](#配置-1panel-反向代理)

### 6. 登录并改密码

在浏览器打开 `https://你的域名/#/login`，登录并按提示改密码。接着按 [快速开始指南](QUICKSTART.md) 添加账号和 Key。

> 想用命令行？首次登录的会话**只允许改密码**，其他管理接口都返回 `403 FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED`：
>
> ```bash
> # 登录，取 data.accessToken
> curl -X POST https://你的域名/auth/login -H 'Content-Type: application/json' \
>   -d '{"username":"admin","password":"<首次启动密码>"}'
>
> # 改密码
> curl -X POST https://你的域名/auth/password -H 'Content-Type: application/json' \
>   -H "Authorization: Bearer $ADMIN_TOKEN" \
>   -d '{"oldPassword":"<首次启动密码>","newPassword":"<新密码，12-128字符>"}'
> ```
>
> 改密码会让该账号所有会话失效，需要用新密码重新登录。

---

## 配置 1Panel 反向代理

在 1Panel：

```text
网站 → 创建网站 → 反向代理
```

填写：

| 设置 | 值 |
| --- | --- |
| 代理地址 | `http://<服务器IP>:18080` |
| 域名 | 你的域名 |
| HTTPS | 在 1Panel 里启用证书 |

如果 OpenResty / Nginx 直接运行在宿主机上（不是容器），也可以把 `.env` 里的 `HOST_BIND_ADDR` 设成 `127.0.0.1`，然后代理到 `http://127.0.0.1:18080`。**对默认的容器化 OpenResty 用 `127.0.0.1` 通常会得到 `502 Bad Gateway`**。

建议把 `STARRY_WEBUI_URL` 设成控制台登录页，这样启动日志里提示的就是浏览器登录地址，而不仅仅是 API 登录端点：

```dotenv
STARRY_WEBUI_URL=https://你的域名/#/login
```

如果后面要使用「使用文档」里的大文件上传，反向代理的请求体上限至少要 **65 MiB**（Nginx：`client_max_body_size 65m;`），见 [用户文档系统](USER_DOCUMENTATION.md#图片视频与附件)。

---

## 导入上游账号（命令行方式）

控制台的「账号管理 → 批量导入」是更方便的方式（见 [控制台手册](CONSOLE_GUIDE.md#批量导入)）。如果想用命令行，在服务器上准备导入文件：

```bash
nano accounts.import.json
```

直接用 Kiro Account Manager 的导出格式：

```json
{
  "version": "kiro-account-manager",
  "exportedAt": 1760000000000,
  "upsert": true,
  "accounts": [
    {
      "id": "kiro-main",
      "email": "user@example.com",
      "idp": "BuilderId",
      "credentials": {
        "accessToken": "kiro-access-token",
        "refreshToken": "optional-refresh-token",
        "region": "us-east-1",
        "authMethod": "IdC",
        "provider": "BuilderId"
      }
    }
  ],
  "groups": [],
  "tags": []
}
```

导入并检查：

```bash
curl -X POST https://你的域名/admin/accounts/import \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  --data-binary @accounts.import.json

curl https://你的域名/admin/accounts -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 创建客户端 API Key（命令行方式）

```bash
curl -X POST https://你的域名/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"default","creditsLimit":1000,"modelProvider":"OpenAI"}'
```

保存返回的 `data.key`。新建的 Key 保存在所有者名下，之后也可以在 API Key 页复制；很早以前只存了哈希的旧 Key 无法找回。客户端里这样配：

```text
Base URL: https://你的域名/v1
API Key:  sk-...
```

---

## 日常运维

```bash
docker compose logs -f service       # 看日志
docker compose restart service       # 重启
git pull && ./scripts/deploy-1panel.sh   # 更新
docker compose down                  # 停止
```

> ⚠️ 不要删除 `./data/postgresql`，除非这是可丢弃的部署。删掉它就永久删除了整个 PostgreSQL 集群。

### 数据管理被锁定

如果「数据管理」的安全密钥连续输错 5 次，整站会被锁定。保持公网流量隔离，确认只有一个 `service` 实例，然后从 1Panel 终端或交互式 SSH 里执行恢复工具：

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
docker compose -f docker-compose.artifact.yml restart service
```

源码方式部署（用的是 `docker-compose.yml`）时去掉 `-f docker-compose.artifact.yml`。要点（必须交互终端、不要 `-T`、不要把密钥放进参数、不要 `curl | sh`）和验证步骤见 [运维手册：恢复被锁定的数据管理](OPERATIONS.md#恢复被锁定的数据管理)。
