# 服务器一键安装

这是**最省事的生产部署方式**：在一台 Linux 服务器上执行一条命令，脚本会自动装好 Docker、下载预构建好的程序包、生成数据库密码并把服务跑起来。服务器上**不需要**安装 Java、Gradle 或 Node.js，也不会在服务器上编译任何东西。

```text
你的域名（HTTPS）
   │  Nginx / 1Panel 反向代理
   ▼
服务器IP:18080
   │
   ▼
starry-api 服务容器（内嵌控制台）  ──►  PostgreSQL 17 容器
```

## 开始前

| 需要 | 说明 |
| --- | --- |
| 一台 Linux 服务器 | 建议至少 **2 GiB 可用内存**、SSD 硬盘；有 root / sudo 权限 |
| 能访问 GitHub | 脚本要从仓库下载预构建产物 |
| （可选）一个域名 | 用于 HTTPS 访问；也可以先用 IP 试 |

> **安全提示**：`curl ... | sudo bash` 会直接以 root 执行下载到的脚本。习惯谨慎的话，先把脚本下载下来看一遍再执行：
>
> ```bash
> curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh -o install-server.sh
> less install-server.sh        # 检查内容
> sudo bash install-server.sh
> ```

---

## 一、一行命令安装

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

如果你已经有域名，建议同时告诉它（只用于在日志里显示正确的登录地址）：

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://api.example.com bash
```

### 脚本会帮你做什么

1. 如果缺少 `curl`、`openssl`、Docker、Docker Compose，自动安装。
2. 下载 `artifacts/starry-api-bundle.tar.gz`，并用 `.sha256` 校验。
3. 解压出：预构建好的 jar、控制台静态文件、Compose 文件、经校验的恢复工具、`starry-api` 管理命令、构建信息。
4. 生成 `.env`，里面是三个**互不相同**的随机 PostgreSQL 密码（超级用户 / 迁移用 / 运行用）。
5. 写出 `docker-compose.artifact.yml`。
6. 用 Docker Compose 启动 PostgreSQL 17 和服务容器。
7. 等待 `/actuator/health` 通过。
8. 清理上一版 Compose 留下的孤儿容器（在新服务健康之后）。
9. 把 `starry-api` 命令装到 `/opt/starry-api/starry-api`（校验和核对通过才会安装），并在 `/usr/local/bin` 存在且没有同名的无关文件时链接为 `starry-api`。
10. 打印最近的日志——**首次安装时，日志里有只出现一次的 admin 初始密码**。错过了也没关系，之后随时可以用 `sudo starry-api info` 查看，见下文。

默认安装目录是 `/opt/starry-api`，PostgreSQL 的数据在 `/opt/starry-api/data/postgresql`。

---

## 二、安装完成后做什么

### 1. 查看 admin 登录信息

```bash
sudo starry-api info
```

它会显示控制台地址、反向代理该指向哪里、服务状态，以及 Root 用户（`admin`）的**初始密码**。密码以加盐哈希保存、读不回来，所以初始密码只在「Root 还没改过密码，并且服务日志里还留着首次启动打印的那一行」时才显示；否则会告诉你原因。**初始密码只能用一次：登录后控制台会强制你改密码。**

初始密码找不到了，或者忘了新密码：

```bash
sudo starry-api reset-password
```

输入 `RESET` 确认后，它会为 Root 设置新的临时密码、重启服务（所有控制台会话随之登出），并**只显示一次**新密码；下次登录必须改密码。用法、`--show-secrets` 和更多细节见 [运维手册：服务器上的 starry-api 命令](OPERATIONS.md#服务器上的-starry-api-命令)。

> 数据管理的安全密钥（`dmsk-…`）**不能**在服务器上显示、找回或重置，`starry-api` 只显示它的状态（未生成 / 已生成 / 已锁定）。锁定或丢失见下文「数据管理被锁定了怎么办」和 [故障排查：首次启动密码丢了](TROUBLESHOOTING.md#首次启动密码丢了)。

### 2. 配置反向代理，启用 HTTPS

安装脚本默认把服务端口绑定在 `0.0.0.0:18080`，方便容器化的 1Panel / OpenResty 通过宿主机端口访问。**请用防火墙 / 安全组限制这个端口**，只通过 HTTPS 反向代理对外开放。

以 1Panel 为例：「网站 → 创建网站 → 反向代理」：

| 设置 | 值 |
| --- | --- |
| 代理地址 | `http://<服务器IP>:18080` |
| 域名 | 你的域名 |
| HTTPS | 在 1Panel 里启用 |

> ⚠️ **如果 1Panel 的 OpenResty 跑在 Docker 里，不要用 `127.0.0.1`**：对它来说 `127.0.0.1` 是它自己的容器，会导致 `502 Bad Gateway`。请用安装脚本打印出来的服务器内网 / 公网 IP。只有 Nginx 直接运行在宿主机上时，才可以用 `http://127.0.0.1:18080`（并可以设置 `HOST_BIND_ADDR=127.0.0.1`）。

### 3. 登录控制台

打开 `https://你的域名/#/login`，用 `admin` 和初始密码登录，**立即改密码**。接着按 [快速开始指南](QUICKSTART.md) 继续。

---

## 配置变量

所有变量都在命令前用 `env` 传入，例如：

```bash
sudo env \
  INSTALL_DIR=/opt/starry-api \
  HOST_BIND_ADDR=0.0.0.0 \
  HOST_HTTP_PORT=18080 \
  PUBLIC_BASE_URL=https://api.example.com \
  bash scripts/install-server.sh
```

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `INSTALL_DIR` | `/opt/starry-api` | 安装目录 |
| `HOST_BIND_ADDR` | `0.0.0.0` | 宿主机端口绑定地址。仅当反向代理跑在宿主机上时才设成 `127.0.0.1` |
| `HOST_HTTP_PORT` | `18080` | 宿主机对外端口 |
| `SERVER_PORT` | `8080` | 容器内 Spring Boot 端口 |
| `PUBLIC_BASE_URL` | 空 | 公网 HTTPS 地址，用于启动日志里的提示 |
| `FORCE_ENV` | `false` | 在数据库初始化**之前**重新生成 `.env`；或重新启用上次失败部署保留的凭据。已初始化的集群见下文 |
| `INSTALL_DOCKER` | `true` | 缺少 Docker 时是否自动安装 |
| `ENABLE_MIHOMO` | `auto` | 可选的 Mihomo 出站组件。`auto`：全新安装时部署（空闲，约 20 MB），已有 `.env` 不改动，所以升级不会悄悄多出容器；`true`：也给已有 `.env` 追加（旧文件先保存为 `.env.previous`，重复执行不产生重复项）；`false`：不部署，已有的保持不变。部署 = 在 `.env` 写入 `COMPOSE_PROFILES=mihomo` 和随机生成的 `STARRY_EGRESS_CONTROLLER_SECRET`（不会打印）并拉取官方 `metacubex/mihomo` 镜像；是否使用由管理员在「系统设置 → 出站代理」打开开关。下载到的部署包不含 `mihomo` 服务（旧版本）时，`auto` 跳过它，`true` 报错退出。详见 [账号出站代理](ACCOUNT_EGRESS.md#部署) |
| `CLI_LINK_PATH` | `/usr/local/bin/starry-api` | `starry-api` 命令的链接位置。只在目录存在、且该路径不存在或已经是指向某个安装目录的链接时才创建 / 更新，**绝不覆盖**无关文件；设成空字符串则不创建链接（仍可直接运行 `${INSTALL_DIR}/starry-api`） |
| `REPO_URL` | `https://github.com/StaR4y/starry-api.git` | 用来推导 raw 下载地址的仓库 |
| `BRANCH` | `main` | 用来推导 raw 下载地址的分支 |

**高级：直接指定下载地址**（一般不用动，适合自建镜像源）

| 变量 | 默认值 |
| --- | --- |
| `REPO_RAW_BASE` | 由 `REPO_URL` 和 `BRANCH` 推导 |
| `BUNDLE_URL` / `BUNDLE_CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api-bundle.tar.gz` / 加 `.sha256` |
| `ARTIFACT_URL` / `CHECKSUM_URL` | 单独的 jar 及校验和（仅在整包不可用时的回退） |
| `BUILD_INFO_URL` | `${REPO_RAW_BASE}/artifacts/build-info.json` |
| `RECOVERY_TOOL_URL` / `RECOVERY_TOOL_CHECKSUM_URL` | 恢复工具及校验和（仅回退路径使用） |
| `CLI_TOOL_URL` / `CLI_TOOL_CHECKSUM_URL` | `starry-api` 管理命令及校验和，默认 `${REPO_RAW_BASE}/artifacts/starry-api.sh` / 加 `.sha256`。整包自带时不下载；整包或发布里没有它（旧版本）时只提示并跳过，校验和不符则中止 |
| `MIHOMO_ENTRYPOINT_URL` | Mihomo 启动脚本（仅回退路径且启用 Mihomo 时使用），默认 `${REPO_RAW_BASE}/docker/egress/mihomo-entrypoint.sh` |

---

## 数据库配置

脚本生成的 `.env` 里包含一份适合生产的基线：

```dotenv
POSTGRES_BOOTSTRAP_PASSWORD=<随机生成>
POSTGRES_MIGRATION_PASSWORD=<随机生成>
POSTGRES_PASSWORD=<随机生成>
POSTGRES_MAX_CONNECTIONS=100
POSTGRES_SHARED_BUFFERS=256MB
POSTGRES_EFFECTIVE_CACHE_SIZE=768MB
POSTGRES_POOL_INITIAL_SIZE=4
POSTGRES_POOL_MAX_SIZE=20
```

要知道的事：

- 数据库和 schema 的名字固定是 `starry_api`；Flyway 用 `starry_api_migration` 角色迁移，应用日常用 `starry_api` 角色。
- `starry_api_bootstrap` 是 PostgreSQL 的超级用户，只用于集群归属和数据库层面的应急恢复，**服务从不使用它**，也**不会**暴露在 Compose 内部网络之外。请单独保管并审计它的密码。
- 上述数据库参数假定**至少 2 GiB 可用内存**和 SSD。每个服务副本的 `POSTGRES_POOL_MAX_SIZE` 加起来要小于 `POSTGRES_MAX_CONNECTIONS`，并为迁移、维护、监控、运维连接留出余量。
- ⚠️ **集群一旦初始化（出现 `data/postgresql/PG_VERSION`），安装脚本就拒绝再生成新密码**，因为光改 `.env` 并不能改变已初始化集群里的角色密码。要轮换密码，请先在 PostgreSQL 里改角色密码，再手动改 `.env`。唯一的自动例外：首次部署失败回滚时保留下来的 `.env.postgresql-retry`，此时 `FORCE_ENV=true` 会重新启用那份凭据，而不是生成新的。

---

## 日常运维

```bash
cd /opt/starry-api

sudo starry-api info                                              # 登录信息、服务与密钥状态
sudo starry-api reset-password                                    # 重置 Root 密码（会重启服务）
docker compose -f docker-compose.artifact.yml logs -f service     # 看日志
docker compose -f docker-compose.artifact.yml restart service     # 重启服务
docker compose -f docker-compose.artifact.yml down                # 停止全部
```

> ⚠️ **不要删除 `./data/postgresql`**，除非你打算永久清空数据库。

### 更新到最新版本

重新执行同一条安装命令即可：

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

更新会下载最新的整包并重启服务（`starry-api` 命令也一并更新）。**更新很安全**：脚本用原子重命名安装部署文件，更新前把 `.env`（权限 `0600`）、当前 jar 及校验和、`docker-compose.artifact.yml`、`postgres-init/001-starry-api-roles.sh`、恢复工具及校验和保存为相邻的 `.previous` 文件。之后任何一步失败，都会**恢复整套旧部署**；如果 Compose 已经开始改动容器，还会重建旧版本、移除新的孤儿容器并确认健康。自动回滚每次运行最多尝试一次。

- 如果期间 PostgreSQL 在一次从旧部署的过渡中被初始化了，生成的凭据会保留为 `.env.postgresql-retry`，同时恢复旧 `.env`；之后用 `FORCE_ENV=true` 重试会复用这份凭据。
- **首次安装**没有「上一版」可恢复。首次失败会原样保留生成的凭据、已校验的产物和恢复工具、Compose 文件和 PostgreSQL 数据，方便确定性地重试；它**不会**把一次部分安装冒充成「回滚成功」。

**更新后界面还是旧的？**说明产物没有刷新。检查：

```bash
cd /opt/starry-api
cat app/build-info.json
docker compose -f docker-compose.artifact.yml logs --tail=160 service
```

见 [故障排查：更新后控制台界面没变](TROUBLESHOOTING.md#更新后控制台界面没变)。

### 数据管理被锁定了怎么办

先用 `sudo starry-api info` 看「数据管理安全密钥」一节：它会告诉你是已锁定，还是状态文件丢失 / 无效。**安全密钥无法在服务器上找回或重置**——服务器只保存加盐的校验值——所以必须用你自己保存的原始密钥来恢复。

安装的服务镜像里自带一个交互式（中英双语）恢复工具。服务处于维护模式时，在**真实终端**里执行，把原始 `dmsk-...` 密钥输入两遍，然后**只重启 `service`**：

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# 英文提示用 --lang en

docker compose -f docker-compose.artifact.yml restart service
# 等服务起来后验证：
docker compose -f docker-compose.artifact.yml exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming && echo "恢复密钥文件已消耗"'
```

**不要加 `exec -T`**（工具需要 TTY 来隐藏并复核输入）。只有当响应里是 `"maintenance":false`，并且两个恢复文件都不存在时，才算恢复成功。**绝对不要**用 `curl ... | sh` 下载恢复脚本——工具已经内置在镜像的 `/app/bin/recover-data-management`。完整流程（含清理失败的处理）见 [运维手册：恢复被锁定的数据管理](OPERATIONS.md#恢复被锁定的数据管理)。
