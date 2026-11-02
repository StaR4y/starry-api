# 故障排查

按下面的**症状目录**找到最像的一条，每条都按「现象 → 原因 → 怎么解决」来写。

**先用这几条命令摸清现状：**

```bash
curl http://127.0.0.1:8080/health              # 服务进程是否在
curl http://127.0.0.1:8080/system/status       # 是否处于维护锁定
curl http://127.0.0.1:8080/actuator/health     # 详细健康状态（含数据库）
curl http://127.0.0.1:8080/v1/models           # 模型目录是否正常
```

> 在控制台里，**请求日志**页是排查调用问题最快的入口：每条请求都记录了状态码、模型、使用的上游账号和 Key。

## 症状目录

| 你看到的 | 跳到 |
| --- | --- |
| 启动失败 / 数据库连不上 | [启动与数据库](#启动与数据库) |
| 登录不了 / 提示先改密码 / 密码丢了 | [登录与权限](#登录与权限) |
| 调接口返回 `401` `403` `429` `503` | [调用接口报错](#调用接口报错) |
| 整站返回 `503 SYSTEM_MAINTENANCE` | [整站维护锁定](#整站维护锁定503-system_maintenance) |
| 更新后界面没变 | [部署与更新](#部署与更新) |
| 某个上游报 `PKIX path building failed` | [部署与更新](#某个上游报-pkix-path-building-failed) |
| 发邮件失败（验证码、工单通知） | [邮件发送](#邮件发送) |

---

## 启动与数据库

### 报错 `R2DBC connection pooling configuration ... both have been used`

**原因**：`spring.r2dbc.url` 用了 `r2dbc:pool:` 前缀，同时又配置了 `spring.r2dbc.pool.*`，两种连接池配置冲突。

**解决**：URL 不要带 `pool:`，连接池参数单独配置：

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/starry_api?schema=starry_api
    pool:
      initial-size: 4
      max-size: 20
```

### 一直在重试连接 PostgreSQL

**可能原因**：

- PostgreSQL 17 没启动，或不在你配置的主机 / 端口上。
- JVM 通过系统 SOCKS 代理去连数据库。**macOS** 上，给 `127.0.0.1` 配置的代理会拦截数字形式的回环地址，即使 `localhost` 在 Java 的免代理名单里也一样。所以直接本机运行时默认用 `localhost`。

**排查**：

```bash
pg_isready -h localhost -p 5432 -d starry_api
brew services list | grep postgresql        # macOS + Homebrew
```

如果 `pg_isready` 正常，但 Flyway 报了嵌套的 `UnknownHostException`（主机是 `127.0.0.1`），去掉你手动设置的 `POSTGRES_HOST=127.0.0.1`，或者改成：

```bash
export POSTGRES_HOST=localhost
```

启动 Homebrew 的 PostgreSQL 17：`brew services start postgresql@17`。全新数据库还要先[运维手册：准备数据库](OPERATIONS.md#准备数据库只需一次)。

### 报错 `relation "model_mappings" does not exist`

**可能原因**：Flyway 还没执行；连的不是 `POSTGRES_DATABASE` 指定的库；连接的 `search_path` 里没有 `starry_api` schema；上次启动失败留下了不一致的迁移历史。

**排查**：

```bash
# 看表是否存在
PGPASSWORD="$POSTGRES_PASSWORD" psql -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
  -U "${POSTGRES_USERNAME:-starry_api}" -d starry_api -c '\dt starry_api.*'

# 看迁移历史
PGPASSWORD="$POSTGRES_PASSWORD" psql -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
  -U "${POSTGRES_USERNAME:-starry_api}" -d starry_api \
  -c 'SELECT installed_rank, version, description, success FROM starry_api.flyway_schema_history ORDER BY installed_rank'
```

**全新的本地库**最简单的办法是重建（⚠️ 会丢掉库里所有数据，**绝不要对生产库这么做**）：

```bash
dropdb --if-exists starry_api
createdb --owner=starry_api_owner --encoding=UTF8 starry_api
psql --dbname=starry_api <<'SQL'
REVOKE ALL ON DATABASE starry_api FROM PUBLIC;
GRANT CONNECT ON DATABASE starry_api TO starry_api_migration, starry_api_runtime;
CREATE SCHEMA starry_api AUTHORIZATION starry_api_owner;
REVOKE ALL ON SCHEMA starry_api FROM PUBLIC;
GRANT USAGE ON SCHEMA starry_api TO starry_api_runtime;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
./gradlew :service:bootRun
```

### PostgreSQL 容器一直 unhealthy

**原因**：数据库还在初始化；数据卷无法访问；或者配置的密码和**已经初始化过**的数据目录不一致。

```bash
cd /opt/starry-api
docker compose ps postgres
docker compose logs --tail=200 postgres
docker compose exec postgres pg_isready -U starry_api -d starry_api
```

要点：**改 `.env` 里的 `POSTGRES_PASSWORD` 并不会改动已初始化集群里的角色密码**。要么在 PostgreSQL 里显式改角色密码，要么把 `.env` 恢复成原值。**千万不要为了「省事」删生产数据卷**（`./data/postgresql`）。

### 报错 `Port 8080 was already in use`

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN        # 看谁占了
SERVER_PORT=8081 ./gradlew :service:bootRun   # 换端口
```

### macOS 上的 Netty DNS 警告

```text
Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider
```

项目已包含 Apple Silicon 的原生依赖（`netty-resolver-dns-native-macos ... osx-aarch_64`）。如果你用的是 **Intel Mac**，把依赖的 classifier 换成 `osx-x86_64`。

---

## 登录与权限

### 首次启动密码丢了

初始 `admin`（也就是应用的 **Root**）密码只在**首次启动**、并且「Root 标记未分配 + 用户表为空」时打印**一次**：

```text
Default admin user created. Save this password now.
Username: admin
Password: ...
```

- **普通账号**（非 Root）忘了密码：由管理员重置。在控制台「用户管理」里点「重置密码」，或调接口 `POST /admin/users/{userId}/reset-password`。
- **Root** 不一样：其他管理员**不能**重置或替换它。如果还有一个有效的 Root 会话，Root 可以自己改密码或自助重置（之后所有 Root 会话被撤销）。
- 如果 Root 的密码**和**所有会话都丢了：应用**故意**不会自动创建替代账号，但能操作服务器上 Docker 的人可以用 `starry-api` 命令重置：
  - **Docker 部署**（一键安装，或用仓库里的 `docker-compose.yml`）：先执行 `sudo starry-api info`。Root 还没改过密码、日志里还有那一行时，它会直接显示初始密码。否则执行 `sudo starry-api reset-password`，输入 `RESET` 确认，会得到一个新的临时密码（只显示一次，下次登录必须改）。服务会重启几秒到一两分钟，所有控制台会话被登出。详见 [运维手册](OPERATIONS.md#服务器上的-starry-api-命令)。
  - 仅限**一次性本地测试库**（没有 Docker）：删掉整个库再重建，触发首次启动流程。
  - **其他生产环境**：停止流量，由数据库所有者做有审计的恢复，或恢复一份可信备份。
- **数据管理安全密钥**丢了不是这样：它无法在服务器上显示、找回或重置（只保存加盐校验值）。用你保存的原始密钥操作；被锁定见 [整站维护锁定](#整站维护锁定503-system_maintenance)。

### 管理接口返回 `403 FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED`

**原因**：账号用「首次启动密码 / 被重置后的密码」登录，但还没改密码。这种会话只允许改密码，其它管理接口都会拒绝。

**解决**：在控制台按提示改密码，或者：

```bash
curl -X POST http://127.0.0.1:8080/auth/password \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer adm-...' \
  -d '{"oldPassword":"<当前密码>","newPassword":"<新密码>"}'
```

注意：新密码 **12–128 个字符**；改密码会让该账号所有控制台会话失效，改完要重新登录。**管理员新建的用户第一次登录也一样要改密码。**

### 管理接口返回 `401`

**原因**：没带管理员会话令牌，或会话已过期（默认 12 小时，见 `STARRY_ADMIN_SESSION_TTL`），或配置了旧的静态令牌 `STARRY_ADMIN_TOKEN` 但没对上。

```bash
# 重新登录拿令牌
curl -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<你的密码>"}'

# 带着返回的 data.accessToken 调用
curl http://127.0.0.1:8080/admin/accounts -H 'Authorization: Bearer adm-...'
```

### 「插件开发」「数据管理」入口不见了

这两个页面**只有初始 Root 管理员**能看到并使用。其他管理员（包括你新建的 `ADMIN` 账号）登录后菜单里没有它们，直接访问接口也会返回 `403`。请用 Root 登录。

---

## 调用接口报错

### 代理接口返回 `401`

**原因**：缺少或写错了 API Key。

```bash
curl http://127.0.0.1:8080/v1/models -H 'Authorization: Bearer sk-...'
# 或者
curl http://127.0.0.1:8080/v1/models -H 'X-Api-Key: sk-...'
```

没有 Key 的话，[在控制台创建一把](QUICKSTART.md#第-3-步创建一个-api-key)。

### 返回 `403 API_KEY_MODEL_PROVIDER_FORBIDDEN`

**原因**：这把 Key 的「模型分类」和你请求的模型不属于同一家。比如 Key 绑定的是 `OpenAI`，却去请求 `claude-sonnet-4.5`。

**解决**：换一把对应分类的 Key，或为每家各建一把。

### 返回 `400 MODEL_NOT_AVAILABLE` / `MAPPED_MODEL_NOT_AVAILABLE`

**原因**：请求的模型（或它经[模型映射](CONSOLE_GUIDE.md#模型映射)后的目标模型）不在这把 Key 的分类所对应的**已启用模型目录**里。

**解决**：到「模型 ID」页确认模型存在并已启用；用了映射的话，源模型和目标模型要**都**在同一厂商目录下。

### 返回 `503 NO_AVAILABLE_ACCOUNT`

**原因**：没有可用的上游账号来服务这个请求。常见情形：

| 情形 | 说明与处理 |
| --- | --- |
| 根本没添加该厂商的账号 | 去「账号管理」添加 |
| 账号被停用 / 暂停 / 冷却中 | 在账号行点「…」重置，或等待冷却结束 |
| **`gpt-*` 请求但只有「自定义 Base URL」的 OpenAI 账号** | 只有 Base URL 主机为 `api.openai.com` 的才算官方 OpenAI 账号。用中转站请选「第三方 OpenAI 兼容 API Key」，并配置[模型映射](CONSOLE_GUIDE.md#模型映射) |
| 账号刚添加，还在预热阶段 | 新的 Codex、Kiro 账号开始只开放 10% 的并发和 RPM，随成功请求逐级放开（API 账号不预热）；高并发打新账号会被准入控制拒绝。放慢请求或等一会儿 |
| 并发 / RPM 打满 | 每个上游账号有保护上限（新建时的默认值：官方 API 并发 14、不限 RPM；Codex 4、60 RPM；Claude OAuth 和 Kiro 2、30 RPM，见 [出站代理与路由限制](ACCOUNT_EGRESS.md)）。在账号的「分配策略」里按需调高 |
| DeepSeek 池空了 | DeepSeek 请求只走 DeepSeek 账号，**不会**回退到别的厂商 |

排查命令：

```bash
curl http://127.0.0.1:8080/admin/accounts -H 'Authorization: Bearer adm-...'
# 看某个账号的分配状态（lifecycleState / cooldownUntil / activeConcurrency）
curl http://127.0.0.1:8080/admin/accounts/<accountId>/allocation-policy -H 'Authorization: Bearer adm-...'
# 重置被暂停/冷却的账号
curl -X POST http://127.0.0.1:8080/admin/accounts/<accountId>/reset -H 'Authorization: Bearer adm-...'
```

### 返回 `429`

**可能原因**：

- 这把 Key 的**累计额度上限**（`creditsLimit`）用完了。
- 触发了**每分钟限流**（`STARRY_RATE_LIMIT_PER_MINUTE`，默认 120/分钟/Key；设为 `0` 关闭）。
- 上游自己返回了限流 / 容量错误。上游的 `429` 只是**暂时的**，不会让账号被判定为额度耗尽。

```bash
curl http://127.0.0.1:8080/admin/api-keys -H 'Authorization: Bearer adm-...'   # 看额度使用情况
```

### 返回 `402`（余额不足）

普通用户的 Key 在请求发出前会预留余额。余额或匹配的订阅额度不够时，**在转发到上游之前**就返回 `402`。让用户[充值或使用兑换码](CONSOLE_GUIDE.md#余额充值与兑换码)即可。

### 返回 `409 BILLING_REVIEW_REQUIRED`

之前有请求在**无法确定上游有没有计费**的情况下结束（请求已经发出，但读超时、连接被重置、响应不完整）。系统保留了冻结金额，需要管理员核对处理，这期间该用户的**所有 Key** 的后续调用都会被拒绝。管理员在计费复核里按「结算」或「豁免」处理。详见 [API 参考：Coding Plan](API.md#coding-plan-订阅)。

以下情况**不会**再触发复核，冻结金额会直接释放：上游明确返回了错误响应（400/401/403/404/422/429，以及 500/502/503/529），或者请求根本没发出去（无法连接、连接超时、TLS 握手失败、连接池耗尽）。如果仍然频繁出现，多半是上游响应超过了 `STARRY_REQUEST_TIMEOUT`（默认 300 秒）；请调大它，或让用户改用响应更快的模型。出现在 Codex（`/v1/responses`，客户端没写输出上限）上时，一轮最长可以写到 `STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS`（默认 16000 个令牌，按每秒约 60 个令牌约 270 秒）：调大时限，或把这个默认值调小；调大这个默认值时要同时调大时限。

### 返回 `503 PROXY_OVERLOADED`

同时在处理的请求体太大，超过了 `STARRY_PROXY_MAX_INFLIGHT_BODY_BYTES`。响应带有 `Retry-After: 1`，客户端 SDK 会自动重试，通常不用处理。如果经常出现：确认客户端是不是在并发发送很大的上下文，或者在内存允许时调大这个值（以及 `-Xmx`）。

### 返回 `429 LOGIN_RATE_LIMITED`

同一个客户端地址一分钟内登录尝试太多（默认 20 次）。响应带有 `Retry-After`。放在反向代理后面时，请让代理传 `X-Forwarded-For`，否则所有人会共用代理的地址。

---

## 整站维护锁定（503 SYSTEM_MAINTENANCE）

**现象**：几乎所有接口都返回 `503 SYSTEM_MAINTENANCE`，只有静态页面、`/system/status` 和健康检查还能访问。

**原因**：「数据管理」的安全密钥**连续输错 5 次**，系统永久锁定并进入故障关闭的维护模式，以防止有人暴力猜测。

**先确认**是不是这个原因，而不是普通的上游故障：

```bash
curl -fsS http://127.0.0.1:18080/system/status
```

返回里有 `"maintenance":true` 和 `"code":"SYSTEM_MAINTENANCE"` 就是它。在服务器上执行 `sudo starry-api info`，「数据管理安全密钥」一节会显示「已锁定」或状态文件异常。

安全密钥无法在服务器上找回或重置，只能用你保存的原始密钥恢复；Root 重置密码也解决不了维护锁定。**此时不要删安全状态文件，也不要反复重启**。让公网流量保持隔离，确认只有**一个** `service` 容器在运行，然后用镜像里自带的恢复工具：

```bash
cd /opt/starry-api
docker compose exec service \
  /app/bin/recover-data-management --lang zh
# 想要英文提示用 --lang en

docker compose restart service
# 等服务启动完，再验证：
docker compose exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming && echo "恢复密钥文件已消耗"'
```

关键规则：

- 必须在**真实终端**里运行，**不要加 `exec -T`**：工具要隐藏输入并让你把原始 `dmsk-...` 密钥输入两遍。
- 密钥**不能**写在命令行参数、管道、`.env` 里，也**不要**用 `curl ... | sh` 下载恢复脚本。
- 输入匹配的密钥：解除维护、清零失败次数，并销毁恢复用的临时文件。
- 输入了格式正确但不匹配的密钥：会被审计记录（`RECOVERY_FAILED`），临时文件同样被销毁，站点仍保持维护。请用**正确的密钥**再来一遍。

**如果解锁后仍处于维护，或者两个文件还在**：说明清理没能确认，恢复**没有完成**。不要再跑工具，也不要手动删文件。查看安全卷和日志（**不要**打印密钥内容）：

```bash
docker compose exec service \
  sh -c 'ls -ld /app/security; ls -l /app/security/recovery.key /app/security/.recovery.key.consuming 2>/dev/null || true'
docker compose logs --tail=200 service
```

修复安全卷的属主、权限、剩余空间或文件系统错误后重启 `service`。启动时会重试清理，直到清理、删除、目录同步全部成功前，服务保持故障关闭。如果修好存储后仍清不掉，请升级给有权限的运维做有审计的恢复。

直接在宿主机 / 用 jar 运行的恢复方法见 [运维手册](OPERATIONS.md#直接在宿主机用-jar-运行时的恢复)。

---

## 部署与更新

### 更新后控制台界面没变

**原因**：服务还在跑旧镜像，或者浏览器缓存了旧页面。

```bash
cd /opt/starry-api
grep STARRY_IMAGE .env                                   # .env 里写的版本
docker inspect --format '{{.Config.Image}}' starryapi-service   # 容器正在跑的镜像
docker compose logs --tail=160 service
```

两个不一致时重新运行安装脚本。一致但不是你想要的版本，用 `STARRY_VERSION` 指定，见 [升级与回滚](DEPLOYMENT.md#升级与回滚)。从源码构建的部署改用 `docker compose build --no-cache service`，再 `docker compose up -d service`。最后强制刷新浏览器（`Ctrl/⌘ + Shift + R`）。

### 1Panel 反向代理 `502 Bad Gateway`

**原因**：1Panel 的 OpenResty 通常跑在 Docker 里，它眼中的 `127.0.0.1` 是**它自己的容器**，而不是宿主机。

**解决**：反向代理目标用**服务器的内网 / 公网 IP**，如 `http://<服务器IP>:18080`。只有 Nginx 直接跑在宿主机上时，才能用 `http://127.0.0.1:18080`。

### 某个上游报 `PKIX path building failed`

**原因**：为应对 CVE-2026-91403（eMudhra 八个中间 CA 的私钥可被推算出来，而当前 JDK 仍然信任它们），镜像构建时从 JRE 信任库里删除了所有 emSign 根证书和 `AAA Certificate Services` 根证书。内置的上游（OpenAI、Anthropic、AWS/Kiro、DeepSeek、Cloudflare、GitHub 等）都不依赖这些根；使用 Sectigo 证书的站点会通过 USERTrust 根正常验证。只有证书链最终挂在 emSign 根上的自定义上游才会握手失败。

**解决**：让该上游换用其他 CA 签发的证书（eMudhra 也在协助受影响的用户迁移）。不要把这些根证书加回信任库：在 JDK 发布修复之前，它们签发的任何证书都可能是伪造的。

---

## 邮件发送

控制台发验证码、系统通知、工单提醒都走「SMTP 设置」里配的同一套发件配置。

### 腾讯企业邮箱：SSL 握手失败

```text
Could not connect to SMTP host: smtp.exmail.qq.com, port: 465
javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
```

腾讯企业邮箱的 `smtp.exmail.qq.com:465` 必须用**隐式 SSL**。如果仍握手失败，检查这个域名是否被解析成了本机代理的 fake-IP：

```bash
dig +short smtp.exmail.qq.com
openssl s_client -connect smtp.exmail.qq.com:465 -servername smtp.exmail.qq.com -tls1_2 </dev/null
```

如果解析到的地址在 `198.18.0.0/15`，说明域名被本机代理 / DNS 的 fake-IP 模式接管了，请求根本没到腾讯。给 `smtp.exmail.qq.com` 和 `hwsmtp.exmail.qq.com` 加 DIRECT 或 fake-ip-filter 规则，或对 SMTP 域名关闭 fake-IP。

推荐配置：

| 项 | 值 |
| --- | --- |
| SMTP 主机 | `smtp.exmail.qq.com` |
| 端口 | `465` |
| 加密 | SSL / TLS |
| 用户名 | 完整的企业邮箱地址 |
| 发件邮箱 | **和用户名相同** |

### 腾讯企业邮箱：`501 mail from address must be same as authorization user`

企业邮箱要求 SMTP 信封里的发件人必须等于登录用户名。把「发件邮箱」设成和「用户名」相同的邮箱（如都是 `admin@company.com`）；显示名称（`smtpFromName`）可以不同。

> 如果 DNS 里看到 `198.18.0.0/15` 的 fake-IP，但服务器已经返回了这个 501，说明请求**已经到达腾讯**，只是发件人地址被拒，和 fake-IP 无关。

---

## 常用验证命令

```bash
./gradlew test
./gradlew build
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/v1/models
curl http://127.0.0.1:8080/actuator/health
```

还是解决不了？在控制台提交一张[工单](SUPPORT_TICKETS.md)，附上时间、客户端名称和**已隐藏密钥**的错误信息。
