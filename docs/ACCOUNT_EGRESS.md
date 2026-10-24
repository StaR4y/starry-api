# 账号出站代理与路由限制

更新：2026-10-24。实现覆盖现有 OpenAI API、Codex、Anthropic API、Kiro 的上游调用、账号测试，以及已保存账号的令牌刷新。没有增加新的 Anthropic OAuth / Setup Token 账号类型。

## 管理入口

在「系统设置 → 出站代理」添加节点或订阅。一个节点就是一个可选择的出口，订阅只负责提供节点，不参与自动选路。

- 手动节点接受 `http://`、`https://`（到代理本身的 TLS，按系统信任库与节点主机名校验）、`socks5://`、`socks5h://` URL（支持用户名密码）或单个 Clash YAML 节点。URL 必须显式带端口。`socks5` 与 `socks5h` 都把目标主机名原样交给代理解析，本机不解析。
- 订阅接受 URL 或 YAML。读取 `proxies`，并展开 `proxy-providers` 中的 HTTP、inline 来源；不执行规则、代理组、脚本或文件来源。
- 朴素的 HTTP CONNECT / HTTPS / SOCKS5 节点（只含类型、地址、端口、账号密码，HTTP 可含 `tls: true`）由本站 Java 直接连接节点，不依赖 Mihomo，Mihomo 关闭或重载时仍可用；通道的 `route` 为 `DIRECT_PROXY`。其余协议（SS、SSR、VMess、VLESS、Trojan、Hysteria、Hysteria2、TUIC、Snell、AnyTLS，以及带证书选项、自定义头等本站无法等价执行选项的 HTTP / SOCKS5 节点）由 Mihomo 执行，`route` 为 `MIHOMO`；具体加密与协议选项仍须被固定版本 Mihomo 支持。
- 每次最多 500 个节点、16 个 provider，节点配置总量最多 4 MiB。下载地址及每次重定向只允许公网地址，并固定经过检查的 DNS 结果。内网站点可改为上传 YAML。
- 相同订阅内按连接配置去重；仅改名字不会更换节点 ID。服务器、端口或凭据改变会形成新节点，需要明确重新绑定账号。
- 来源中删除的节点保留记录和账号绑定，显示不可用；不会把账号悄悄改成直连。无法解析的刷新保留上一版节点。
- YAML 导入不是远程来源，因此不显示同步按钮。URL 来源通过「同步」主动更新，不后台定时下载。
- 「测试出口」走与账号请求相同的路径请求 `api.ipify.org`（`STARRY_EGRESS_PROBE_URL` 可改），显示出口 IP 和耗时，并保存最近一次结果（时间、结果、出口 IP、耗时、固定错误码）。加载成功只代表配置已应用，测试成功才证明探测目标可达。
- 订阅可重命名、可删除；任一节点仍有账号绑定时删除返回 409 并列出账号。管理员改过名的节点，后续同步不会覆盖名称。
- Mihomo 监听端口（20000..29999）只分配给需要 Mihomo 的节点，节点被来源移除、出现配置问题或不再需要 Mihomo 时释放，按最小空闲端口复用；端口耗尽时导入返回 409 `PROXY_PORT_RANGE_FULL`。
- 需要 Mihomo 的节点要先部署 Mihomo，再在页面顶部的 Mihomo 开关里打开才能使用，见下文「部署」。

账号新建 / 编辑页在「出站代理」选择节点；「路由与限制」默认使用 系统设置 → 路由与限制 中该类别的值，打开「自定义此账号」才单独覆盖。已有 `proxyUrl` 仍兼容；节点绑定优先于旧 URL；要直连须同时清除节点和旧 URL。绑定节点不可删除，可先解除绑定。

## 默认值与限流语义

| 新建账号类型 | 并发 | 平均 RPM | 突发容量 | 会话上限 |
| --- | ---: | ---: | ---: | ---: |
| 官方 OpenAI / Anthropic API | 14 | 0 | 0 | 0 |
| 非官方 API、DeepSeek、其他 API | 0 | 0 | 0 | 0 |
| Codex | 4 | 60 | 4 | 0 |
| Claude OAuth / Setup Token（Anthropic 非 API Key） | 2 | 30 | 2 | 4 |
| Kiro 及其他非 API Key | 2 | 30 | 2 | 0 |

上表是内置值。管理员可在 `GET/PUT /admin/accounts/allocation-defaults` 按类别（`OFFICIAL_API_KEY`、`COMPATIBLE_API_KEY`、`CODEX`、`CLAUDE_OAUTH`、`KIRO`）覆盖全部策略字段，保存在系统设置 `account_allocation_default_<类别>`（每类一个 JSON）。数据库触发器直接读取这些设置，因此创建接口、导入和 Codex 授权都使用同一份默认值；设置缺失或不合法时回到内置值。已有账号不会被改动。会话上限与 TLS 配置只对 Anthropic 账号生效。`POST /admin/accounts/allocation-defaults/resolve` 按表单的提供方、认证方式、Base URL 返回类别与新账号起始策略，界面不必自行分类。

官方地址要求 HTTPS、精确的 `api.openai.com` / `api.anthropic.com` 主机及默认端口；空地址使用对应官方默认地址。相似域名、HTTP、非标准端口不算官方。

`safeConcurrency=0` 表示账号并发不限，`safeRpm=0` 表示关闭 RPM 和突发令牌桶。RPM 是持续补充的令牌桶，不是硬性自然分钟计数；RPM 大于 0 且 `burstCapacity=0` 时只限制 RPM。全站请求体预算、在途 token、API Key 限额仍有效。

API 账号直接按配置容量准入；Codex / Kiro 保留原有分阶段预热，初期实际并发可能低于配置上限。迁移不覆盖已有账号手动设置，编辑页可点「恢复为默认值」按当前类别默认值填入，保存后生效。修改已有账号 Base URL 也不会自动覆盖其限额。

Anthropic 可设置 `maxSessions` 与 `sessionIdleSeconds`：例如 600 秒内最多 2 个不同会话，A/B 已占用时，A 可继续请求，C 被当前账号拒绝并交给其他候选账号。每个会话的活动会延长自己的窗口。会话键包括 API Key 身份，避免不同用户混用同一个会话 ID。

识别显式会话请求头、请求内会话字段，以及 Claude Code metadata 中的 `session_id`。普通 `metadata.user_id` 不作为会话 ID。没有可靠标识时，每个请求按独立会话计数，不允许靠省略标识绕过限制。会话容量只对 Anthropic 生效。

`cooldownSeconds=0` 使用原有自动冷却；1..86400 秒覆盖容量 / 传输类自动冷却，上游 `Retry-After` 是最短等待时间。凭据隔离不受缩短冷却影响。晚返回的成功请求不能清除另一个请求刚触发的冷却。

## 部署

Mihomo 是可选组件，只有 Clash 订阅里的 SS、VMess、VLESS、Hysteria2 等 Java 无法自己执行的协议才需要。HTTP / HTTPS / SOCKS5 节点（含旧 `proxyUrl`）由 Java 直接连接，不依赖它。部署时拉取官方镜像 `metacubex/mihomo`（GPL-3.0，固定为 `v1.19.32`）；本项目的产物不包含 Mihomo 二进制。

「部署」和「启用」是两件事：

- **部署**：Mihomo 容器在运行，并且后端拿到了控制端密钥（`STARRY_EGRESS_CONTROLLER_SECRET` 非空）。后端据此认为 Mihomo「可用」。
- **启用**：管理员在「系统设置 → 出站代理」顶部的 Mihomo 开关里打开它，结果保存为系统设置 `egress_mihomo_enabled`（`true` / `false`）。后端只有在「可用且已启用」时才使用 Mihomo。

一键安装脚本默认把 Mihomo 部署成**空闲状态**：常驻内存约 20 MB，不加载任何节点，不影响任何请求。要不要用它由管理员在控制台决定，不用改 `.env`，也不用重启。

### 控制台开关

页面顶部状态条里的 Mihomo 有三种状态：

| 状态 | 含义 | 可以做什么 |
| --- | --- | --- |
| 未部署 | 没有控制端密钥 | 只给出部署提示（重新运行安装脚本并设置 `ENABLE_MIHOMO=true`），不能在控制台打开 |
| 未启用 | 已部署，开关关闭 | 打开开关，需要 Mihomo 的节点随即可用 |
| 已启用 | 开关打开 | 显示控制端是否可连接、活动节点数、最近一次错误；可以关闭 |

- 打开后立即把当前的节点配置加载进 Mihomo。加载失败不算开关失败：开关保持打开，错误显示在状态条里，每 15 秒重试一次。
- 关闭前会确认，并告诉你有多少账号绑定了需要 Mihomo 的节点。关闭后这些节点显示不可用，账号绑定保留；这些账号的请求不会发出（不会直连），而是改由其他账号处理，直到重新打开。关闭不会停止 Mihomo 容器，也不会删除任何节点。
- 开关保存在数据库里，多个实例共用：当前实例立即生效，其他实例最迟 15 秒内跟上。
- 兼容旧部署：开关从未被使用过时，`.env` 里的 `STARRY_EGRESS_MIHOMO_ENABLED=true` 仍作为初始状态，已经按旧方式启用的部署不用改动。管理员第一次使用开关之后以保存的值为准。
- 接口：`PUT /admin/proxy-channels/runtime`，请求体 `{"mihomoEnabled": true}`（或 `false`），返回更新后的目录，结构与 `GET /admin/proxy-channels` 相同：`mihomoAvailable` 表示已部署，`mihomoEnabled` 表示实际生效。未部署时打开返回 409 `PROXY_RUNTIME_UNAVAILABLE`。与其他出站代理管理接口一样需要管理员身份。

### 部署 Mihomo

一键安装脚本的 `ENABLE_MIHOMO`：

| 值 | 全新安装 | 已有 `.env` |
| --- | --- | --- |
| `auto`（默认） | 部署 Mihomo（空闲） | 不改动，升级不会悄悄多出一个容器 |
| `true` | 部署 Mihomo | 追加缺少的配置，旧文件先保存为 `.env.previous` |
| `false` | 不部署 | 不改动；已经部署的保持原样 |

「部署」就是在 `.env` 里写入 `COMPOSE_PROFILES=mihomo` 和随机生成的 `STARRY_EGRESS_CONTROLLER_SECRET`。脚本不会打印密钥，也不写 `STARRY_EGRESS_MIHOMO_ENABLED`；已有 `.env` 里的密钥、`STARRY_EGRESS_MIHOMO_ENABLED` 和其他 Compose profile 都保持不变，重复执行不产生重复项。下载到的部署包若不含 `mihomo` 服务（旧版本），`auto` 会记一行日志并跳过 Mihomo，`true` 则报错退出。安装结束的摘要会写明 Mihomo 已部署还是未部署。

**已有的部署**想加上 Mihomo，重新运行安装脚本并加 `ENABLE_MIHOMO=true`：

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env ENABLE_MIHOMO=true bash
```

**源码部署或手动部署**在使用的 `.env` 里加两行（密钥只含字母、数字、下划线、短横线，例如 `openssl rand -hex 24`），再启动：

```dotenv
COMPOSE_PROFILES=mihomo
STARRY_EGRESS_CONTROLLER_SECRET=<随机十六进制>
```

```bash
docker compose -f docker-compose.artifact.yml up -d   # 服务器 / 预构建部署包
docker compose up -d --build                          # 源码部署，同一个 profile
```

之后到「系统设置 → 出站代理」打开开关。

Compose 把脚本挂载进容器，所以 `docker/egress/mihomo-entrypoint.sh` 必须存在于部署目录：源码部署自带；服务器上由安装脚本在每次更新时安装，缺失时重新运行安装脚本即可（否则 Docker 会在该路径建一个空目录，容器无法启动）。

Compose 从 `.env` 读取 `COMPOSE_PROFILES`，之后的 `logs`、`down` 等命令都会包含 `mihomo`。Mihomo 与 `service` 共享网络命名空间，因此重建 `service` 时必须一起重建：用不带服务名的 `up -d`，或 `up -d --force-recreate service mihomo`（只重建 `service` 会让 Mihomo 留在旧的命名空间里）。安装脚本已按此处理。

控制端口 9090、节点监听 20000..29999 全部绑定 `127.0.0.1`，不映射公网端口。容器只读文件系统、无附加 capability、`no-new-privileges`；配置里 `geo-auto-update: false`、日志级别 `silent`，不会主动下载地理数据。

### 移除

只是暂时不用，在控制台把开关关掉即可，容器保持空闲。要连容器一起去掉：删掉 `.env` 里的 `COMPOSE_PROFILES` 中的 `mihomo` 和 `STARRY_EGRESS_CONTROLLER_SECRET`（旧的 `STARRY_EGRESS_MIHOMO_ENABLED` 也可以删），然后：

```bash
docker compose -f docker-compose.artifact.yml rm -sf mihomo
docker compose -f docker-compose.artifact.yml up -d
```

源码部署去掉 `-f` 参数。Compose 不把未启用 profile 的容器当作孤儿，`up --remove-orphans` 不会删除它，所以要显式 `rm -sf`。移除后控制台显示「未部署」，需要 Mihomo 的节点显示不可用，账号绑定保留，这些账号的请求不会直连，而是改由其他账号处理。之后重新运行安装脚本（`auto` 或 `false`）不会把它加回来。

### 资源

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MIHOMO_MEM_LIMIT` | `256m` | 容器内存上限 |
| `MIHOMO_GOMEMLIMIT` | `200MiB` | Go 运行时软上限，应低于容器上限 |
| `MIHOMO_VERSION` | `v1.19.32` | 官方镜像标签；更换版本前先自行验证 |

空闲（未启用，或没有需要 Mihomo 的节点）时常驻内存约 20 MB。默认值（256m / 200MiB）实测可承载约 2000 路并发流和普通请求体，满载时常驻内存约 130 MB 且无错误。多 MB 请求体的上传与大量并发流同时出现时，改用 `MIHOMO_MEM_LIMIT=512m`、`MIHOMO_GOMEMLIMIT=400MiB`。单个实例建议不超过约 500 个需要 Mihomo 的节点（软上限），不要超过 1000 个（硬上限）。

### 配置变量

| 变量 | 默认值 |
| --- | --- |
| `ENABLE_MIHOMO` | `auto`（仅安装脚本） |
| `COMPOSE_PROFILES` | 空；含 `mihomo` 时 Compose 才启动 Mihomo 容器 |
| `STARRY_EGRESS_CONTROLLER_SECRET` | 空；非空即视为已部署，容器启动也需要它 |
| `STARRY_EGRESS_MIHOMO_ENABLED` | `false`；只是控制台开关的初始状态，管理员使用过开关之后不再起作用 |
| `STARRY_EGRESS_CONTROLLER_URL` | `http://127.0.0.1:9090` |
| `STARRY_EGRESS_CONTROLLER_BIND` | `127.0.0.1:9090` |
| `STARRY_EGRESS_LISTENER_HOST` / `STARRY_EGRESS_LISTENER_BIND` | `127.0.0.1` |
| `STARRY_EGRESS_PROBE_URL` | `https://api.ipify.org?format=json` |
| `STARRY_EGRESS_ACTIVATION_FAILURE_BACKOFF` | `5s` |

Compose 只传 `STARRY_EGRESS_MIHOMO_ENABLED` 和 `STARRY_EGRESS_CONTROLLER_SECRET`，其余沿用应用默认值（均指向 `127.0.0.1`）。在 IDE / jar 环境运行时，需自行把 Mihomo 放在服务可访问的本地地址并设置这些变量（至少是控制端密钥）。不能把独立 Docker 容器中的 `127.0.0.1` 当成宿主机回环地址，推荐直接使用 Compose profile。

### TLS bridge（实验性）

Anthropic 的 TLS 指纹 bridge（curl_cffi）是单独的实验性 overlay，与 Mihomo 互不依赖，也不是默认部署的一部分。在 `.env` 设置 `STARRY_TLS_SECRET`（`openssl rand -hex 32`）后叠加使用，之后的每条 `docker compose` 命令都要带同样的 `-f`：

```bash
docker compose -f docker-compose.yml -f docker-compose.tls-bridge.yml up -d --build
docker compose -f docker-compose.artifact.yml -f docker-compose.tls-bridge.yml up -d --build
```

预构建 bundle 包含 overlay、`docker/egress` 和本文，在解压目录运行第二条。bridge 与 `service` 共享网络命名空间，监听 `127.0.0.1:9091`，限额 512 MiB，容器只读文件系统、无附加 capability，非 root 运行，最多 16 个请求并发、64 MiB 请求体总预算、单请求 16 MiB，并关闭环境代理和自动重定向。

| 变量 | 默认值 |
| --- | --- |
| `STARRY_EGRESS_TLS_ENABLED` | `false`；overlay 设为 `true` |
| `STARRY_EGRESS_TLS_URL` | `http://127.0.0.1:9091` |
| `STARRY_TLS_SECRET` | 空；启用时必填 |

## TLS 与失败处理

Anthropic 的 TLS 配置可选 `chrome136`、`safari18_4`，后者在 curl_cffi 0.16.3 内映射到 `safari184`。改变的是上游 TLS / HTTP 握手，不伪造业务身份，不保证上游接受账号。证书验证保持启用；业务认证头原样传递，内部路由头不传给上游。

绑定节点禁用、来源移除、运行组件不可用，或者指定 TLS 组件不可用时，请求失败，不直连。代理配置错误不作为账号凭据吊销证据，也不计入账号健康（不触发冷却、熔断或自动停用）。请求写出之前的失败（路由查找、节点未激活、代理 CONNECT / SOCKS 握手、到代理的 TLS、TLS 组件拒绝或不可达）确定未发送：释放计费预留、可换其他账号，但不会在同一账号重试，也不会直连。请求可能已写出后的失败（经代理的响应超时、请求中途重置）按直连超时同样处理：不换账号、不自动重放，计费保持待人工复核，避免重复生成和计费。

同一路由连续 2 次在发送前连接失败（拒绝连接、连接超时、CONNECT / SOCKS 握手失败），之后 15 秒内该路由的账号请求直接以 `PROXY_ROUTE_SUSPENDED` 失败并立即换其他账号，不再每次等满连接超时；暂停结束后只放一个请求试探，成功收到任何响应即恢复。连接池占满导致的取连接超时不计入。管理员的出口探测走独立连接，节点被暂停时仍可测试。

路由连接按路由复用：每条路由（代理类型与地址、凭据哈希；Mihomo 路由还包括通道、监听端口和节点指纹）有自己的连接池，最多缓存 2000 条路由，空闲 20 秒回收，未使用 5 分钟的池整体释放（被淘汰的池 30 秒后才关闭，已经取到客户端的请求不受影响）。连接池只复用 socket，每次派发仍重新读取绑定，节点修改后的新路由不会拿到旧连接。Mihomo 每 15 秒检查并恢复配置；配置热加载失败会标记错误，保留数据库配置供修正；失败后的 5 秒内（`STARRY_EGRESS_ACTIVATION_FAILURE_BACKOFF`）请求路径直接返回失败，不再逐个请求重新应用配置，同一时刻的应用请求合并。目录接口的 `runtime` 字段给出控制器可达性、最近激活时间、激活节点数和最近一次固定错误码。Mihomo 拒绝配置时若指明了出错的节点（`proxy N: ...`），只把该节点标为配置问题并释放其监听端口，其余节点照常激活；订阅刷新或在控制台重新启用该节点会再次尝试。拒绝信息未指明节点时，整份配置仍按失败处理。已有进行中的请求不会因改节点被强制中断，后续派发读取最新绑定。

组件日志只记录数量、状态和异常类型；不输出订阅 URL、节点凭据、请求正文或认证头。数据库备份包含节点配置和订阅 URL，按凭据备份保护。V28 恢复要求备份迁移历史完全匹配，旧版备份应先在隔离环境升级再迁移。

## 验证与边界

```bash
./gradlew :service:test -PskipWebuiBuild=true
cd webui
npm run test:i18n
npm run build
# 项目根目录，Docker 可用时：
bash scripts/test-egress-runtime.sh
```

macOS Colima 的数据库测试需要设置 `DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` 与 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`。检查 XML 中 `skipped=0` 后才能把数据库测试视为通过。

验证覆盖：14 路并发原子准入、非官方不限并发、不同会话窗口、自定义冷却与晚成功竞态、Retry-After、订阅去重和失效绑定、HTTP CONNECT / SOCKS5 认证与远端域名、Mihomo 两出口隔离、TLS ClientHello、bridge 流式响应和失败不直连。组件测试使用虚构节点及本地服务，不调用真实账号或订阅。UI 用隔离数据检查中英文与桌面 / 手机布局。

本次验证结果：后端全量回归 933 项通过、41 项因 Coding Plan / 插件 / 工单独立数据库开关未开启而跳过，没有失败。账号准入的 11 项 PostgreSQL 用例全部执行；后续针对节点导入事务、重复刷新不消耗监听端口、账号与策略原子保存补充并执行了数据库回归。英文文案测试 6 项通过，Vue 类型检查及生产构建通过，真实 Mihomo / curl_cffi 组件联调通过。未使用真实付费模型账号验证公网出口；实际部署后使用页面的「测试出口」检查运营节点。

ChatGPT（Codex）设备授权可在开始时选择代理节点（`proxyChannelId`）：本站执行的初始化、轮询和令牌交换走该节点，生成的账号绑定它；重新授权未指定时沿用账号自己的绑定。仍沿用现有产品边界：服务端现有上游客户端仍整体读取模型响应，bridge 支持分块转发不代表整个产品已支持端到端实时透传。插件任意自建的 HTTP 客户端不自动接入账号出口。没有实现自动测速选节点、Clash 完整规则系统或定时刷新订阅。
