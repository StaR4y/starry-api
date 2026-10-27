# 配置参考

Starry API 的运行时配置主要来自**环境变量**，由 `service/src/main/resources/application.yml` 读取。控制台里「系统设置」下的内容（支付、注册、SMTP、模型目录……）则保存在数据库里，**不是**环境变量。

> **旧的 `KIRO_` 变量名**：网关的通用设置以前沿用历史上的 `KIRO_` 前缀（如 `KIRO_WEBUI_URL`），现在统一为 `STARRY_` 前缀（如 `STARRY_WEBUI_URL`）；Kiro 专属的端点选择由 `KIRO_PREFERRED_ENDPOINT` 改名为 `STARRY_KIRO_PREFERRED_ENDPOINT`。旧名字**仍然有效**：新名字没有设置时，服务会读取对应的 `KIRO_` 变量，所以已有的 `.env` 不用改。两个都设置时以 `STARRY_` 为准。新部署请只用 `STARRY_` 名字。

**目录**：[最常用的配置](#最常用的配置) · [服务器与 JVM](#服务器与-jvm) · [出站代理组件](#出站代理组件可选) · [PostgreSQL 17](#postgresql-17) · [插件](#插件运行时) · [备份与恢复](#数据库备份与恢复) · [模型目录与价格](#模型目录与官方价格) · [上游运行时](#上游运行时与适配器) · [注册与 SMTP](#注册与-smtp-设置) · [支付与计费](#支付与计费) · [工单通知](#工单通知) · [请求分析与保留](#请求分析与保留) · [本地环境示例](#本地环境示例) · [生产环境建议](#生产环境建议)

---

## 最常用的配置

多数部署只需要关心下面这些：

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 服务监听端口 |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DATABASE` | `localhost` / `5432` / `starry_api` | 数据库位置 |
| `POSTGRES_PASSWORD`、`POSTGRES_MIGRATION_PASSWORD` | 空 | 运行 / 迁移角色的密码（可信的本机开发之外必填） |
| `STARRY_ALLOW_ANONYMOUS_PROXY` | `false` | 是否允许**不带 API Key** 调用代理。公网服务请保持 `false` |
| `STARRY_ADMIN_SESSION_TTL` | `12h` | 管理员会话有效期 |
| `STARRY_RATE_LIMIT_PER_MINUTE` | `120` | 每个 API Key 每分钟请求上限，`0` 表示不限制 |
| `STARRY_WEBUI_URL` | 空 | 控制台的**公开地址**，如 `https://你的域名/#/login`。启动日志会显示它，**注册验证邮件和 SMTP 验证邮件里的链接也用它生成**（不设置时这些链接会退回到请求里的 `Host` 头，而这个头可以被伪造，见下方说明）；鲸鱼助手引用使用文档时，也用它的协议、主机和端口替换文档里的 `{{site_url}}` 和 `{{api_base_url}}`，**不设置时助手不会替换，也不读 `Host` 头**，只请用户换成自己浏览器里看到的地址 |
| `JAVA_OPTS` | `-Xms64m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`（Compose / 安装脚本） | JVM 参数，见下 |
| `SERVICE_MEM_LIMIT` | `2g` | Compose 里服务容器的内存上限 |

---

## 服务器与 JVM

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_ADDRESS` | `127.0.0.1` | 直接运行时的 HTTP 监听地址。容器镜像会覆盖为 `0.0.0.0`，是否对外暴露由 `HOST_BIND_ADDR` 控制 |
| `SERVER_PORT` | `8080` | HTTP 端口 |
| `SPRING_CODEC_MAX_IN_MEMORY_SIZE` | `16MB` | WebFlux 编解码器的内存内请求体上限 |
| `JAVA_OPTS` | 见下 | 容器 / 脚本使用的 JVM 参数 |
| `SERVICE_MEM_LIMIT` | `2g` | Compose 的服务容器内存上限（`mem_limit`）。堆 768 MiB + 直接缓冲 256 MiB + 元空间、代码缓存、线程栈，实测最重的压测下常驻内存约 1.5 GiB |

**内存预算**：Compose、一键安装脚本和预构建部署包现在默认带上下面这套参数，并给服务容器设了 `2g` 上限和健康检查：

```bash
JAVA_OPTS="-Xms64m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
```

各参数的作用：

- `-Xms64m`：初始堆从小开始。空闲时真正存活的对象只有约 40 MiB，写成 `-Xms256m` 会让 256 MiB 的堆一直占着。
- `-XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent`：如果 5 分钟内没有发生过任何 GC（也就是服务基本空闲），做一次完整回收并把空出来的堆**还给操作系统**。没有它，一次突发流量撑大的堆会一直留着。
- `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`：回收后最多保留 30% 的空闲堆，其余归还。

`-XX:+ExitOnOutOfMemoryError` 很重要：一旦发生 `OutOfMemoryError`，JVM 会**直接退出**，由 `restart: unless-stopped` 拉起。没有它，进程会带着已经坏掉的线程继续运行、不再响应，而 Docker 只会在进程退出时才重启容器。想在故障后留下堆转储，可以追加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/backups`（注意磁盘空间）。

并发更高或有大量长时间流式响应时，把 `SERVICE_MEM_LIMIT` 调到 `3g` 并相应调大 `-Xmx`。**不要**把一个没有上限的宿主机 / 容器的 75% 都分给 Java 堆：Netty 的直接缓冲、元空间、代码缓存、线程栈和原生库都需要堆之外的内存。实测的数据基线、压测结果、监控阈值见 [性能与内存基线](PERFORMANCE.md)。

**用户文档附件目录**：`STARRY_DOCUMENTATION_ATTACHMENT_DIR`（默认 `${user.home}/.starry-api/documentation`）。Compose 把 `./data/documentation` 挂载到 `/app/documentation`。请保持持久，并**与 PostgreSQL 分开备份**。详见 [用户文档系统](USER_DOCUMENTATION.md)。

---

## 出站代理组件（可选）

账号出站代理里，HTTP / HTTPS / SOCKS5 节点由后端直接连接，不需要额外组件。Mihomo（Clash.Meta）只用于 Java 无法自己执行的协议（SS、VMess、VLESS、Hysteria2 等）。一键安装脚本默认把它**部署为空闲状态**（约 20 MB 内存），由管理员在「系统设置 → 出站代理」里打开开关后才使用；部署步骤、移除方法和资源建议见 [账号出站代理](ACCOUNT_EGRESS.md#部署)。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ENABLE_MIHOMO` | `auto` | 仅一键安装脚本使用。`auto`：全新安装时部署 Mihomo，已有 `.env` 不改动；`true`：全新安装或已有 `.env` 都部署（已有的先保存为 `.env.previous`）；`false`：不部署，已有的保持不变 |
| `COMPOSE_PROFILES` | 空 | Compose 读取的 profile 列表。含 `mihomo` 时才会启动 Mihomo 容器 |
| `STARRY_EGRESS_CONTROLLER_SECRET` | 空 | Mihomo 控制端密钥，只含字母、数字、`_`、`-`。非空即表示 Mihomo 已部署（控制台可以打开开关）。安装脚本自动生成，不会打印 |
| `STARRY_EGRESS_MIHOMO_ENABLED` | `false` | 仅是控制台开关的初始状态：开关从未被使用过时，`true` 表示一开始就启用。管理员在控制台用过开关之后，以保存的值为准 |
| `MIHOMO_VERSION` | `v1.19.32` | 官方镜像 `metacubex/mihomo` 的标签 |
| `MIHOMO_MEM_LIMIT` | `256m` | Mihomo 容器的内存上限（`mem_limit`） |
| `MIHOMO_GOMEMLIMIT` | `200MiB` | Mihomo 的 Go 运行时内存软上限，应低于 `MIHOMO_MEM_LIMIT` |

部署只需要 `.env` 里的两行：`COMPOSE_PROFILES=mihomo` 和 `STARRY_EGRESS_CONTROLLER_SECRET=<随机值>`；之后在控制台打开开关。开关保存在系统设置 `egress_mihomo_enabled`，多个实例共用，不需要改 `.env` 或重启。默认的 256m / 200MiB 适合约 2000 路并发流和普通请求体；多 MB 上传与大量并发流同时出现时用 `512m` / `400MiB`。其余 `STARRY_EGRESS_*` 变量保持应用默认值，一般不用改。

---

## PostgreSQL 17

PostgreSQL 17 是生产基线。服务用 JDBC 跑 Flyway 迁移，用 R2DBC 跑应用查询。数据库和应用 schema 都叫 `starry_api`。**不要把应用表放进 `public`。**

### 连接与连接池

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `POSTGRES_HOST` | `localhost` | 直接本机运行时的主机。Compose 会覆盖成 `postgres` 服务名 |
| `POSTGRES_PORT` | `5432` | 端口 |
| `POSTGRES_DATABASE` | `starry_api` | 数据库名 |
| `POSTGRES_SSL_MODE` | `disable` | 同时传给 R2DBC 和 Flyway：`disable`、`allow`、`prefer`、`require`、`verify-ca`、`verify-full`（以驱动支持为准）。连远程生产库请用证书校验 |
| `POSTGRES_USERNAME` | `starry_api` | 应用使用的数据库角色 |
| `POSTGRES_PASSWORD` | 空 | 应用角色密码；可信的本机开发之外必填 |
| `POSTGRES_MIGRATION_USERNAME` | `starry_api_migration` | 专用的 Flyway 与维护角色 |
| `POSTGRES_MIGRATION_PASSWORD` | 空 | 迁移角色密码；可信的本机开发之外必填 |
| `POSTGRES_BOOTSTRAP_PASSWORD` | 无 | **仅 Compose 使用**：集群超级用户 `starry_api_bootstrap` 的密码。应用从不使用该角色。请当作数据库「破窗」凭据保管，并在有审计的维护流程里轮换 |
| `POSTGRES_POOL_INITIAL_SIZE` | `4` | R2DBC 连接池初始大小 |
| `POSTGRES_POOL_MAX_SIZE` | `20` | 连接池最大值；请对照服务器的连接预算设置 |
| `POSTGRES_POOL_MAX_ACQUIRE_TIME` | `5s` | 从池里取连接的最长等待 |
| `POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME` | `5s` | 建立连接的最长时间 |
| `POSTGRES_POOL_MAX_LIFE_TIME` | `2h` | 池中连接的最长寿命 |
| `POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES` | `256` | 每个连接的预编译语句缓存上限 |
| `POSTGRES_STATEMENT_TIMEOUT` | `PT2M` | 应用 SQL 语句的最长执行时间 |
| `POSTGRES_LOCK_WAIT_TIMEOUT` | `PT5S` | 应用语句等待数据库锁的最长时间 |
| `FLYWAY_ENABLED` | `true` | 启动时是否执行 schema 迁移 |
| `FLYWAY_CONNECT_RETRIES` | `10` | 启动时 Flyway 连接重试次数 |

### 数据库服务端参数（仅 Compose 容器）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `POSTGRES_SHM_SIZE` | `256mb` | PostgreSQL 容器的共享内存 |
| `POSTGRES_MAX_CONNECTIONS` | `100` | 服务端连接上限；要为迁移、维护、监控预留 |
| `POSTGRES_SHARED_BUFFERS` | `256MB` | 共享缓冲区缓存 |
| `POSTGRES_EFFECTIVE_CACHE_SIZE` | `768MB` | 规划器对 PostgreSQL + 操作系统页缓存可用内存的估计，**不会实际分配内存** |
| `POSTGRES_WORK_MEM` | `4MB` | 每个排序 / 哈希操作的内存上限；一条查询可能多次使用 |
| `POSTGRES_MAINTENANCE_WORK_MEM` | `64MB` | 维护操作可用内存 |
| `POSTGRES_AUTOVACUUM_WORK_MEM` | `64MB` | 每个 autovacuum 进程的内存上限 |
| `POSTGRES_AUTOVACUUM_MAX_WORKERS` | `4` | autovacuum 并发进程数上限 |
| `POSTGRES_AUTOVACUUM_NAPTIME` | `30s` | autovacuum 启动器每轮间隔 |
| `POSTGRES_DEFAULT_STATISTICS_TARGET` | `200` | 规划器统计的默认详细程度 |
| `POSTGRES_WAL_COMPRESSION` | `on` | 压缩 WAL 中的整页镜像，减少写入量 |
| `POSTGRES_MIN_WAL_SIZE` / `POSTGRES_MAX_WAL_SIZE` | `512MB` / `2GB` | WAL 保留与增长上限 |
| `POSTGRES_CHECKPOINT_COMPLETION_TARGET` | `0.9` | 把检查点写入分散到大部分间隔里 |
| `POSTGRES_TRACK_IO_TIMING` | `on` | 收集 I/O 计时，便于生产诊断 |
| `POSTGRES_IDLE_IN_TRANSACTION_SESSION_TIMEOUT` | `5min` | 终止持有未完成事务却空闲的会话 |

Compose 默认值是一套保守的 OLTP 基线，面向**至少 2 GiB 可用内存**和 SSD 的节点。扩容副本前，请根据实测负载和宿主限制重新计算缓冲、缓存、WAL 和连接数。所有服务副本的 R2DBC 连接池最大值之和，要小于 `POSTGRES_MAX_CONNECTIONS`，并给 Flyway、备份 / 恢复和运维会话留出余量。

### 创建数据库和角色

在全新的 PostgreSQL 集群上：

```bash
export POSTGRES_USER=postgres
export POSTGRES_DB=starry_api
export STARRY_POSTGRES_MIGRATION_PASSWORD='<强密码-迁移用>'
export STARRY_POSTGRES_APP_PASSWORD='<强密码-应用用>'

createdb --username "$POSTGRES_USER" --encoding=UTF8 "$POSTGRES_DB"
./docker/postgresql/init/001-starry-api-roles.sh
```

R2DBC 和 Flyway 的 URL 都把 `starry_api` 设成当前 / 默认 schema。

引导脚本会让 `starry_api_owner` 继承具体登录角色 `starry_api` 的权限（但不允许 `SET ROLE starry_api`）。这让数据传输维护所用的所有者能在恢复期间**只终止 Starry API 应用后端连接**，而不是被授予整个集群范围的 `pg_signal_backend`。独立的 `starry_api_bootstrap` 登录依然是 PostgreSQL 超级用户（容器入口需要一个集群所有者，运维也需要数据库层面的应急通道），但它只存在于 Compose 内部网络，服务从不配置它。请单独保护它、限制 `.env` 访问、监控它的使用，怀疑泄露后立即轮换。

---

## 插件运行时

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `STARRY_PLUGIN_DIR` | `${user.home}/.starry-api/plugins` | 持久的插件包和数据目录。Compose 把容器路径固定为 `/app/plugins`，并挂载 `./data/plugins` |
| `STARRY_PLUGINS_DISABLED` | `false` | **应急**启动开关。`true` 会阻止加载任何插件；`false` **不会**自动开启开发者模式 |

开发者模式在全新数据库上是**关闭**的，只能通过「Root 密码 + 邮箱验证」流程开启。详见 [插件开发](PLUGINS.md)。

---

## 数据库备份与恢复

数据传输接口使用 PostgreSQL 17 的 `pg_dump` / `pg_restore` 自定义格式归档。容器运行时已包含匹配版本的客户端工具；直接用 jar 运行的宿主机需要自己提供。

「数据管理」和「插件开发」**只有初始应用 Root 可见、可用**。每一个数据传输接口——包括状态、备份列表、导出和授权签发——都要求该 Root 有效的、数据库支撑的会话；其他管理员和静态管理员令牌无法使用此模块。Root 由 `application_root_user_id` 识别，控制台用 `/auth/permissions` 决定可见性。原有的安全密钥和插件的「密码 + 邮箱」验证要求依然生效。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_BACKUP_DIR` | `${user.home}/.starry-api/backups` | 服务端 `.zip` 备份目录。每个包含 `database.dump` 和元数据。Compose 映射到 `./data/backups` |
| `STARRY_MAX_IMPORT_BYTES` | `268435456` | 上传的 `.dump` / `.zip` 最大字节数（256 MiB） |
| `STARRY_DATA_TRANSFER_PROCESS_TIMEOUT` | `30m` | 每个 `pg_dump` / `pg_restore` 进程的超时 |
| `STARRY_DATA_MANAGEMENT_SECURITY_FILE` | `${user.home}/.starry-api/security/data-management.json` | 仅属主可读的文件，包含加盐的密钥校验值、锁定状态和有界的、已脱敏的事件缓冲。**请持久化它所在的整个目录**，包括自动创建的 `.initialized` 标记；两个文件都不含明文密钥 |
| `STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED` | `false` | 当安全状态和标记**都不存在**时故障关闭。只有首次初始化时才留 `false`；持久化生成的状态之后，设为 `true` 并重启，这样整个安全卷丢失就不会被误当成全新部署 |
| `STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE` | 空（直接宿主机）；`/app/security/recovery.key`（内置 Compose） | 可写、仅属主可读的**一次性**文件，内含原始数据管理密钥，用于第 5 次失败永久锁定后的启动恢复。Docker 用户只应通过镜像的 `/app/bin/recover-data-management --lang zh\|en` 创建它。**不要用符号链接或只读的 secret 挂载** |

**重要规则：**

- ZIP 的导出和导入用 `database.dump` 作为归档条目。归档**只含应用 schema `starry_api`**；只追加的 `starry_security` 审计 / 发件箱 schema 被故意排除，这样恢复应用数据不会抹掉之后的安全证据。
- 备份取 PostgreSQL 会话级**共享**咨询锁，恢复取对应的**排他**锁，所以跨实例的维护操作也受约束：冲突的跨实例维护会被拒绝，而不是让备份和恢复快照互相重叠。
- 导入只接受 PostgreSQL 17 的自定义归档：TOC 标明数据库和 schema 为 `starry_api`，含 `flyway_schema_history` 和必需的核心表，且没有来自其他 schema 的对象。然后 `pg_restore --clean --single-transaction` 替换该 schema。这与固定的 PostgreSQL 17 客户端匹配，故意比「接受任何 PostgreSQL 自定义归档」更严格。
- 恢复期间，迁移连接会撤销 `starry_api_runtime` 的 `CONNECT`，终止现有 `starry_api` 后端连接，从静默的数据库创建回滚备份，并在恢复完成前保持运行时连接禁用。重新开放访问前，同一个所有者会话会要求 **Flyway V1–V28** 的成功历史（版本、脚本名、迁移类型、校验和）与恢复前捕获的完全一致；校验所有必需的核心关系；验证不可变的 `application_root_user_id` 标记解析到恰好一个已启用的 `ADMIN` Root；检查 V5 熔断纪元列、设置和有效的支撑索引；校验外部安全 schema 的只追加触发器、最小权限 ACL 和无密钥列；然后运行 `ANALYZE`，避免新恢复的表带着空的规划器统计服务流量。旧历史、未知的未来迁移、改名的脚本和校验和不匹配都会被拒绝。
- 如果传入的 `pg_restore` 已提交但任何后置条件失败，服务会**自动恢复恢复前的备份**并再次校验和分析；回滚失败会作为被抑制的诊断信息保留。`CONNECT` 在 `finally` 里恢复，失败的恢复也一样。
- 只要本实例进入过恢复隔离窗口，就会清空所有源自数据库的缓存和会话；其他实例在它们的 PostgreSQL 通知监听器重连时做同样的事。缓存代数防止更早的在途刷新在失效后重新填充状态。**管理员和用户应预期在途请求会失败，且管理员会话在恢复后需要重新登录。**
- 数据管理安全目前要求**恰好一个活动服务实例**：校验值 / 锁定状态和动作授权是实例本地的，不要在多副本或滚动重叠部署里启用。完整的恢复步骤见 [运维手册](OPERATIONS.md#恢复被锁定的数据管理)。
- 风险邮件使用共享的系统 SMTP 传输。投递优先使用事件里记录的 Root 邮箱快照；快照为空时，每次重试都通过 `application_root_user_id` 解析当前邮箱，所以之后更新 Root 邮箱可以放出待发的告警。已脱敏的事件**总是**先进入原子的本地缓冲，再进入只追加的 `starry_security` 审计 schema 和持久的、带租约的发件箱，即使当前没有配置收件人。SMTP 失败会指数退避重试，并输出结构化的高风险日志，但不会削弱或回滚锁定。投递是**至少一次**：SMTP 服务器接收后崩溃可能产生带相同 `X-Starry-Security-Event-Id` 的重复邮件。

默认 / 初始管理员就是应用级的 **Root**。它的密码和会话必须当作全服务的凭据保护；数据管理的二次验证并不包含或限制已经控制了 Root 身份的攻击者。Flyway V6 把它的 `user_id` 持久化在不可变的 `application_root_user_id` 设置里，数据库触发器阻止身份替换、改名、禁用、降级或删除。其他管理员不能修改或重置该账号。Root 密码修改或自助重置会撤销所有 Root 控制台会话。Root 只在用户表为空时创建；库里已有用户却缺失 / 无效的 Root，**启动会中止**，而不是悄悄重建。

---

## 模型目录与官方价格

### 模型目录

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_MODEL_ICON_DIR` | `${user.home}/.starry-api/model-icons` | 管理员上传的模型图标目录（需要属主可写） |
| `STARRY_MODEL_MAX_ICON_BYTES` | `2097152` | PNG / JPEG / WebP 图标最大字节数（2 MiB） |

模型的自定义保存在 `system_settings` 里；上传的**图标文件不在 PostgreSQL 里，也不包含在数据库导出里**。直接部署的宿主机需要单独备份图标目录。**内置的 Compose 文件目前没有挂载默认图标目录**，所以在容器被替换后还想保留自定义图标，请先加一个显式的持久卷，并把 `STARRY_MODEL_ICON_DIR` 传给服务。

### 官方价格

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_MODEL_PRICING_REMOTE_REFRESH_ENABLED` | `true` | 是否从配置的远程源刷新官方美元价格目录 |
| `STARRY_MODEL_PRICING_SYNC_CATALOG_IDS` | `false` | 把价格库里新发布的一线聊天模型追加进托管目录。已停用的 ID 不会被重新启用 |
| `STARRY_MODEL_PRICING_SOURCE_URL` | `Wei-Shaw/model-price-repo` 的 raw JSON | 兼容 LiteLLM 的模型价格目录地址 |
| `STARRY_MODEL_PRICING_CHECKSUM_URL` | 对应的 `.sha256` 文件 | 替换当前目录前使用的 SHA-256 文档 |
| `STARRY_MODEL_PRICING_REFRESH_INTERVAL` | `PT10M` | 两次经校验的刷新之间的间隔 |
| `STARRY_MODEL_PRICING_MAX_CATALOG_BYTES` | `4194304` | 接受的最大目录大小 |
| `STARRY_MODEL_PRICING_DIR` | `~/.starry-api/model-pricing` | 最近一次可用目录的缓存目录 |

jar 里包含一份已验证的快照，所以**离线也能启动**。网络、解析、大小或校验和任何一项失败，都会保留最近一次已验证的目录。运维可以在「系统设置 → 模型管理 → 价格倍率」里折叠的「数据源」区域查看同步状态，并立即触发刷新。

定价先查模型自己的精确 / 规范化官方条目，再沿着配置的目录目标走（带环检测）。已有的路由兼容目标**不会**取代一个模型自己经过验证的价格。「按族 / 厂商 / 全局取中位数猜一个价」以及「未知模型 16.67/MTok 兜底」**不再**用于 token 计费。没有价格的模型会显示为「待定价」，计费请求会在预留资金**之前**以 `MODEL_PRICE_UNAVAILABLE` 失败；显式的旧版绝对价格规则仍然可以给这样的模型定价。

---

## 上游运行时与适配器

OpenAI、Codex、Anthropic、DeepSeek 的凭据，作为上游账号记录**保存在 PostgreSQL 里**，而不是部署环境变量。`provider=DeepSeek` 的账号默认 `authMethod=api-key`、`baseUrl=https://api.deepseek.com/v1`，所以启用官方厂商**不需要**新增服务端密钥或 URL 变量。

### 运行时变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_ADMIN_TOKEN` | 空 | 可选的旧版静态管理员令牌。**优先使用本地管理员登录** |
| `STARRY_WEBUI_URL` | 空 | 启动日志里显示的控制台登录地址 |
| `STARRY_ADMIN_SESSION_TTL` | `12h` | 本地管理员会话有效期 |
| `STARRY_ALLOW_ANONYMOUS_PROXY` | `false` | 允许不带 API Key 调用代理。**公网请保持 `false`** |
| `STARRY_REQUEST_TIMEOUT` | `300s` | 上游请求超时。目前向上游发的是**非流式**请求，要等整段回答生成完才有第一个字节，所以这个值就是单次生成的时间上限；推理模型较慢时不要调得太小 |
| `STARRY_MAX_RETRIES` | `2` | 可恢复错误的上游重试次数 |
| `STARRY_RATE_LIMIT_PER_MINUTE` | `120` | 每个 API Key 的固定窗口限流；`0` 关闭限流 |
| `STARRY_ACCOUNT_SELECTION_STRATEGY` | `round_robin` | 旧版回退，仅在 PostgreSQL 分配排序器缺失时使用（如直接构造的测试）。生产选择使用感知生命周期的排序 |
| `STARRY_SESSION_AFFINITY_ENABLED` | `true` | 同一会话提示尽量路由到同一个上游账号 |
| `STARRY_SESSION_AFFINITY_TTL` | `10m` | 会话亲和有效期 |
| `STARRY_SESSION_AFFINITY_MAXIMUM_SIZE` | `100000` | 每个应用实例最多保留的会话亲和条目数 |
| `STARRY_BASE_COOLDOWN` | `60s` | 可恢复账号错误的基础冷却时间 |
| `STARRY_QUOTA_RESET` | `1h` | 上游报告额度耗尽、而账号没有明确重置时间时，分配的默认总额度重置时长 |
| `STARRY_PROBABILISTIC_RETRY` | `0.1` | 提前探测一个冷却中账号的概率 |
| `STARRY_MAX_REQUEST_BODY_BYTES` | `10485760` | 单个入站请求体最大字节数（10 MiB）。JSON 请求体另外最多嵌套 500 层（Jackson 3 的上限），更深的返回 `400` |
| `STARRY_PROXY_MAX_INFLIGHT_BODY_BYTES` | 自动（最大堆的 1/4） | **同时在处理的**代理请求体字节数总上限。一个请求会被缓冲、解析成 JSON 树并保留到上游返回，慢模型可能是几分钟，所以并发 × 请求体大小会直接吃堆。超过上限的新请求立刻得到 `503 PROXY_OVERLOADED` 和 `Retry-After: 1`（SDK 会自动重试），而不是把整个服务拖到内存溢出。单个比整个上限还大的请求在没有其他请求时仍会放行 |
| `STARRY_RESPONSES_STATE_MAX_TOTAL_BYTES` | `67108864`（64 MiB） | `/v1/responses` 为支持 `previous_response_id` 而保存的对话历史，**压缩后**占用的总字节上限，超出按最旧优先淘汰 |
| `STARRY_RESPONSES_STATE_MAX_ENTRY_BYTES` | `8388608`（8 MiB） | 单条历史压缩后的上限，更大的不保存 |
| `STARRY_AUTH_LOGIN_ATTEMPTS_PER_MINUTE` | `20` | 每个客户端地址每分钟允许的登录尝试次数，超出返回 `429 LOGIN_RATE_LIMITED`；`0` 关闭。不对用户名加锁，避免有人故意把真正的管理员锁在外面 |
| `STARRY_AUTH_LOGIN_GLOBAL_ATTEMPTS_PER_MINUTE` | `600` | 全站每分钟登录尝试总数的兜底上限 |
| `STARRY_TOKEN_REFRESH_INTERVAL_MS` | `3600000` | 符合条件的基于 Token 的账号（含 Kiro 和即将过期的 Codex 凭据）的后台刷新间隔 |
| `STARRY_TOKEN_REFRESH_INITIAL_DELAY_MS` | `60000` | 第一次刷新前的延迟 |
| `STARRY_KIRO_PREFERRED_ENDPOINT` | `codewhisperer` | 首选 Kiro / AWS 端点：`codewhisperer`、`amazonq`、`amazonq-cli` |

### ChatGPT 浏览器授权

管理员可以从「账号管理 → 新建账号 → ChatGPT 授权」添加 Codex OAuth 账号。Starry API 使用 OpenAI 的**设备授权**流程：管理员在浏览器里打开 `https://auth.openai.com/codex/device` 并输入显示的一次性验证码，服务端同时轮询对应的 OpenAI 端点。所以部署需要**出站 HTTPS** 到 `auth.openai.com` 和 `chatgpt.com`；**不需要**公网回调 URL，也不需要额外的 OAuth 环境变量。

请求验证码之前，ChatGPT 用户必须先在个人账号的安全设置里**开启设备码登录**；托管工作区则由其管理员在工作区权限里开启。该前提未满足时 OpenAI 会拒绝浏览器流程——先开启，再重新请求验证码。

- 临时登录状态有界、保存在内存、绑定发起的管理员，**15 分钟**后过期。重启服务会让未完成的登录失效，但不影响已保存的账号。
- OAuth 的 access、refresh、ID Token 都是敏感的数据库凭据：保护数据库备份、限制数据库访问，**绝不**把账号行或 Token 放进日志和工单。如果无法确认库里存的和新授权的 ChatGPT 身份是同一个，重新授权会拒绝替换账号。
- 浏览器授权只创建 `provider=OpenAI`、`authMethod=codex` 的账号，对应 `https://chatgpt.com/backend-api/codex`。它不会自动化 ChatGPT 网站，也不会创建官方 OpenAI API Key 账号。各账号 / 地区的可用性可能不同。请只使用你有权管理的账号，并遵守 OpenAI 的条款。
- 这种模式适合**可信的私有部署**。公开、多租户、CI 或其他不可信的程序化场景，请改用 OpenAI Platform API Key 账号。

### 账号选择

生产环境的选择**先隔离厂商 / 账号子类型**，再应用「模型族的主 / 备」熔断策略，并过滤不可用的生命周期状态。原生 Anthropic 池从最低分配优先级里选，再把健康和压力风险降到最小。OpenAI 家族的池会综合健康、延迟、并发、令牌压力、优先级、配置的权重和额度余量打分，再在**最好的三个**之间做带权随机。

只有绑定的账号仍然可用时，才会在常规排序之前检查会话亲和。安全的默认路由：隔离 Codex 账号、普通 GPT 流量走官方 OpenAI API Key、仅在上游成功之后才绑定显式会话、不为裸 API Key 绑定。这些开关和「换账号次数上限」在「系统设置 → 账号池设置 → 账号路由策略」里管理。`STARRY_ACCOUNT_SELECTION_STRATEGY` 只在分配排序器不存在时作为兼容回退。

---

## 客户端配置生成

控制台的 API Key 页会为每把 Key 生成客户端配置：

| 客户端 | 生成的配置 |
| --- | --- |
| Codex CLI | `~/.codex/config.toml` 加最简的 `~/.codex/auth.json`，使用 HTTP Responses API 和直接的 Starry API Key Bearer 令牌 |
| Claude Code | Shell 环境变量：`ANTHROPIC_BASE_URL`、`ANTHROPIC_API_KEY`、`ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_MODEL`、`ANTHROPIC_SMALL_FAST_MODEL` 以及 Claude Code 流量开关 |
| OpenCode | 项目级 `opencode.json`，带一个 `starry` 的 OpenAI 兼容供应商 |
| CC Switch | 面向 Claude Code、Codex CLI、OpenCode 的 `ccswitch://v1/import?resource=provider...` 深链接 |

这些客户端设置是**按 Key** 生成的，不会取代服务端的运行时变量。对 Codex CLI，如果它报告 refresh token 已被使用，请用生成的 API Key JSON 替换陈旧的 ChatGPT OAuth `auth.json` 内容。

---

## 控制台里的系统设置

管理员管理的设置都在 `系统设置` 菜单下，**保存在数据库里**：

| 分类 | 路径 | 说明 |
| --- | --- | --- |
| 支付设置 | 系统设置 → 支付设置 | 在线充值的业务配置 |
| 注册设置 | 系统设置 → 注册设置 | 注册、邮箱策略、Turnstile、邀请码 |
| SMTP 设置 | 系统设置 → SMTP 设置 | 共享的邮件传输配置 |
| 模型管理 | 系统设置 → 模型管理 | 模型目录、显示顺序、官方价格倍率、「模型 ID」画廊样式；添加模型在「模型管理 → 添加模型」 |
| 账号池设置 | 系统设置 → 账号池设置 | 路由策略和自动复测配置 |
| UI 自定义 | 系统设置 → UI 自定义 | 站点名、Logo、配色、控制台风格 |

支付设置和添加模型的旧路径仍然作为重定向保留，以兼容书签；它们不是单独的设置位置。

### 注册与 SMTP 设置

注册在「系统设置 → 注册设置」配置，SMTP 传输在「系统设置 → SMTP 设置」单独配置。两个页面都用 `GET/PATCH /admin/users/registration-settings`；PATCH 的字段都是可选的，所以每个页面只更新自己负责的字段，并保留另一类。

| 设置键 | 用途 |
| --- | --- |
| `registration_enabled` | 开放公开自助注册 |
| `email_verify_enabled` | 公开自助注册需要邮箱验证 |
| `invite_code_enabled` | 开放一次性邀请码注册 |
| `registration_email_suffix_whitelist` | 可选的 JSON 邮箱后缀白名单 |
| `smtp_security` | 共享的 SMTP 加密模式：`auto`、`ssl`、`starttls`、`none` |

开启邀请码注册后，管理员可以在注册设置页生成一次性码。用户把 `inviteCode` 传给 `POST /auth/register`；有效的邀请码允许直接注册，**即使公开注册是关闭的**。

腾讯企业邮：配置 `smtp.exmail.qq.com:465` + `smtp_security=ssl`，或 `smtp.exmail.qq.com:587` + `smtp_security=starttls`。常见错误见 [故障排查：邮件发送](TROUBLESHOOTING.md#邮件发送)。

---

## 支付与计费

在线充值的业务规则在控制台里配置（[截图](CONSOLE_GUIDE.md#支付设置)）。对账工作线程另有部署级的调度变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_PAYMENT_RECONCILIATION_INTERVAL` | `PT30S` | 本地待支付订单对账之间的间隔 |
| `STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY` | `PT30S` | 第一次本地对账前的延迟 |
| `STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL` | `PT30S` | 支付网关对账之间的间隔 |
| `STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY` | `PT45S` | 第一次网关对账前的延迟 |

**业务设置**：

- 管理员路径：「系统设置 → 支付设置」；用户路径：「余额充值」；网关：EasyPay 兼容；回调：`GET/POST /payment/webhook/easypay`。

设置沿用 sub2api 风格的键，保存在 `system_settings`：

| 键 | 用途 |
| --- | --- |
| `payment_enabled` | 开放用户创建充值订单 |
| `MIN_RECHARGE_AMOUNT`、`MAX_RECHARGE_AMOUNT` | 单笔充值金额范围。`MAX_RECHARGE_AMOUNT = 0` 表示无上限 |
| `DAILY_RECHARGE_LIMIT` | 每个用户每日已支付金额上限。`0` 表示无限制 |
| `ORDER_TIMEOUT_MINUTES` | 待支付订单的过期时间 |
| `MAX_PENDING_ORDERS` | 每个用户最多同时待支付订单数 |
| `ENABLED_PAYMENT_TYPES` | 逗号分隔的 `alipay,wxpay` |
| `BALANCE_RECHARGE_MULTIPLIER` | 到账余额倍率 |
| `MODEL_PRICE_BILLING_MULTIPLIER` | 应用在官方美元 token 成本上的倍率，默认 `1.0`。API 兼容字段仍叫 `apiBillingMultiplier` |
| `RECHARGE_FEE_RATE` | 加到应付金额上的额外手续费率 |
| `EASYPAY_API_BASE`、`EASYPAY_PID`、`EASYPAY_PKEY` | EasyPay 网关地址和商户凭据 |
| `EASYPAY_PAYMENT_MODE` | `popup` 跳转收银台页面，`qrcode` 走 `mapi.php` |

`EASYPAY_PKEY` **只接受来自管理员更新接口的写入，从不返回给控制台**；管理页只显示它是否已配置。

### 价格是怎么算的

「模型 ID」页展示有效目录价格，默认显示一个**总参考价**（50% 输入 + 50% 输出；悬停提示会说明实际是逐项计费）。管理员在「系统设置 → 模型管理 → 价格倍率」里管理官方价格倍率。

没有覆盖规则时，运行时计费会按**逐项**统计输入、输出、缓存写入、缓存读取四类 token（使用经验证的官方目录），再应用 `MODEL_PRICE_BILLING_MULTIPLIER`。**一旦有规则匹配，它就替换全局倍率**：`1.0` 保持官方价，`0.1` 收官方价的 10%，**不会再乘一次全局倍率**。

**规则选择**：

- 按优先级（0–10000）升序选取。优先级相同时，顺序是：精确模型 → 目录族 → 目录厂商 → 通配符；字面字符更多的通配符胜过更少的。
- 精确的**请求别名**规则优先于精确的**目标模型**规则。最后的并列按模式和规则 ID 确定性地选择。
- 已知的厂商前缀会被规范化后再做精确匹配；重复的规范化精确模式、厂商 / 族范围或规则 ID 会被拒绝。
- 草稿的价格预览会显示胜出的规则、其他匹配的规则、验证过的定价模型和官方 / 有效费率，且不保存。

**厂商与系列**：「厂商与系列」区块直接编辑某厂商的默认倍率和每个目录族的覆盖。未设置的族倍率继承厂商默认；未设置的厂商继承全局倍率。这些是持久的元数据范围，所以新增的模型会自动继承其目录厂商 / 族的设置。厂商归属来自**被请求的目录条目**，绝不来自跨厂商的路由目标。精确的模型例外和显式更高优先级的规则依然有效。重置某个范围只移除那一个覆盖。

**通配符**：模型模式除非含 `*`，否则都是**精确匹配**：`gpt` 和 `gpt5` 不会匹配 `gpt-5.5`。想按族设置，请用「OpenAI / GPT-5」的族控件，或显式用 `gpt-*` / `gpt-5*` 做文本前缀匹配。规则编辑器在你输入了目录里不存在的精确 ID 时会提示。

**其他**：

- 「恢复默认」会保存一份空的覆盖列表，从而恢复配置的全局倍率。旧版的固定总价和逐项价格仍然有效，并按实际收费显示，包括兜底规则。编辑其他规则或旧版元数据时会保留它们原始的每 token 数值；「改为倍率」需要明确确认和正常的规则保存。新规则的倍率取 0.000001–1000。同步源失败时会保留规则草稿。旧的 `/model-pricing` 哈希路由会重定向到「模型 ID」。
- 旧的 `API_BILLING_MULTIPLIER` 数据库行被**有意忽略**，避免升级后把类似 `0.0000166666666667` 的每 token 数值误当成新的官方价格倍率。
- 倍率可用 6 位小数；最多 200 条规则。

---

## 工单通知

在「系统设置 → 工单通知」里配置。通知默认**关闭**，并复用系统 SMTP。选择启用的管理员收件人（空表示全部）、仅提醒还是含对话内容、提醒间隔（默认 240 分钟；允许 15–10080），以及可选的控制台基础地址（用于直达链接）。

持久的 PostgreSQL 调度器每 30 秒检查一次，重启后继续。可选的 Spring 环境变量覆盖是 `STARRY_TICKETS_NOTIFICATION_POLL_INTERVAL` 和 `STARRY_TICKETS_NOTIFICATION_INITIAL_DELAY`，默认都是 `PT30S`。错误和手动重试在同一个设置页里。工单附件在 PostgreSQL 里，不需要额外的文件系统挂载。限制、存储预算和投递语义见 [工单系统](SUPPORT_TICKETS.md)。

---

## 性能指标

服务启动后每 **30 秒**往 `performance_metrics` 写一个性能样本：系统 CPU、进程 CPU、JVM 堆和非堆内存、系统内存、Linux 网络收发计数和每秒速率。管理员仪表盘通过 `GET /admin/performance` 读取并显示最近 120 个样本。超过 7 天的样本每天 03:15 UTC 清理。

---

## 请求分析与保留

已完成小时的请求日志会被增量汇总到小时表和日表。PostgreSQL 咨询锁和 `aggregation_watermarks` 防止工作线程重叠。每个批次在自己的短事务里推进有限个小时，并重建一个可配置的回看窗口来吸收延迟提交。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_STATS_AGGREGATION_ENABLED` | `true` | 启用请求用量聚合和分区维护 |
| `STARRY_STATS_AGGREGATION_INTERVAL` | `PT1M` | 增量聚合的间隔 |
| `STARRY_STATS_AGGREGATION_INITIAL_DELAY` | `PT30S` | 启动后第一次聚合前的延迟 |
| `STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS` | `1` | 每个事务最多推进的新小时数；范围 1–168 |
| `STARRY_STATS_AGGREGATION_LOOKBACK_HOURS` | `6` | 每个推进批次重建的已完成小时数，用来吸收延迟提交；范围 1–168 |
| `STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN` | `6` | 每次计划运行的最大连续短事务数；范围 1–100 |
| `STARRY_STATS_MAINTENANCE_CRON` | `0 25 3 * * *` | 分区 / 保留维护的 UTC cron |
| `STARRY_STATS_PARTITION_MONTHS_AHEAD` | `12` | 预创建的 `request_logs` 月分区数；范围 1–60 |
| `STARRY_STATS_RAW_RETENTION_MONTHS` | `4` | 保留的原始月分区数；强制的 4 个整月下限保证至少 90 天 |
| `STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE` | `10000` | 每个短事务从原始默认分区删除的过期行数；范围 100–100000 |
| `STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES` | `20` | 每次维护最多的默认分区清理事务数；范围 1–100 |
| `STARRY_STATS_HOURLY_RETENTION_DAYS` | `90` | 小时聚合保留天数；范围 7–3650。日聚合继续支持更长区间的分析 |
| `STARRY_STATS_HOURLY_DELETE_BATCH_SIZE` | `10000` | 每个短事务删除的过期小时聚合数；范围 100–100000 |
| `STARRY_STATS_HOURLY_DELETE_MAX_BATCHES` | `20` | 每次维护最多的小时聚合清理事务数；范围 1–100 |
| `STARRY_STATS_USAGE_EVENT_RETENTION_DAYS` | `400` | 计费幂等事件保留天数；范围 30–3650。不可变的余额账本**不会**被清理 |
| `STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE` | `10000` | 每个短保留事务删除的行数；范围 100–100000 |
| `STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES` | `100` | 每次维护最多的保留事务数；范围 1–100 |

**生产环境请保持聚合开启。**关闭它也会同时关闭自动分区创建和保留维护。聚合水位仍有积压时，原始 `request_logs` 分区**绝不会**被丢弃，所以长时间故障后的恢复优先保证汇总完整，而不是立即回收空间。

### 账号分配的保留

账号准入的清理独立于请求分析：它使用有界的短事务，并在数据管理维护被锁定时跳过计划运行。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED` | `true` | 启用终态租约、分配事件、过期会话、作用域冷却和 OpenAI 响应归属的计划清理 |
| `STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON` | `0 35 * * * *` | 账号分配清理的 UTC cron，默认**每小时**一次（每次最多清 20 批 × 1 万行）。每个请求都会写入租约和事件，一天一次的清理在平均超过约 2 请求/秒时就追不上 |
| `STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS` | `7` | 终态租约保留天数；范围 1–3650 |
| `STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS` | `90` | 分配审计事件保留天数；范围 30–3650 |
| `STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE` | `10000` | 每个事务从每张支持的表最多删除的行数；范围 100–100000 |
| `STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES` | `20` | 每次计划运行最多的清理事务数；范围 1–100 |

过期的会话、作用域冷却和响应归属行**不使用**租约 / 事件的保留窗口——它们的过期时间一过就可以清理。

---

## 本地环境示例

```bash
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT=8080
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DATABASE=starry_api
export POSTGRES_USERNAME=starry_api
export POSTGRES_PASSWORD="$STARRY_POSTGRES_APP_PASSWORD"
export POSTGRES_MIGRATION_USERNAME=starry_api_migration
export POSTGRES_MIGRATION_PASSWORD="$STARRY_POSTGRES_MIGRATION_PASSWORD"
export POSTGRES_POOL_INITIAL_SIZE=4
export POSTGRES_POOL_MAX_SIZE=20
export STARRY_WEBUI_URL=http://127.0.0.1:5173/#/login
export STARRY_ADMIN_SESSION_TTL=12h
export STARRY_ALLOW_ANONYMOUS_PROXY=false
export STARRY_RATE_LIMIT_PER_MINUTE=120

./gradlew :service:bootRun
```

---

## 生产环境建议

- **首次启动后，立刻保存并修改 `admin` 初始密码。**
- 日常管理用本地管理员登录；`STARRY_ADMIN_TOKEN` 只用于应急或自动化场景。
- 保持 `STARRY_ALLOW_ANONYMOUS_PROXY=false`。
- 把服务放在 **TLS** 后面。
- 把专用的 `starry_api` 运行时角色和 `starry_api_migration` Flyway 角色分开，**不要给运行时角色 DDL 权限**。
- 保持 `starry_api` schema 排在 `search_path` 第一位，并监控连接饱和、慢查询、autovacuum 和复制 / 备份健康。
- 监控 `/actuator/health` 和 `/actuator/prometheus`。
- 公网的生产环境，如有需要请关闭 Swagger / OpenAPI：

  ```yaml
  springdoc:
    api-docs:
      enabled: false
    swagger-ui:
      enabled: false
  ```
