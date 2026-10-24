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

账号新建 / 编辑页选择「代理出口节点」，在「路由与限制」调整策略。已有 `proxyUrl` 仍兼容；节点绑定优先于旧 URL；要直连须同时清除节点和旧 URL。绑定节点不可删除，可先解除绑定。

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

API 账号直接按配置容量准入；Codex / Kiro 保留原有分阶段预热，初期实际并发可能低于配置上限。迁移不覆盖已有账号手动设置，编辑页可选择「应用此类型的默认策略」。修改已有账号 Base URL 也不会自动覆盖其限额。

Anthropic 可设置 `maxSessions` 与 `sessionIdleSeconds`：例如 600 秒内最多 2 个不同会话，A/B 已占用时，A 可继续请求，C 被当前账号拒绝并交给其他候选账号。每个会话的活动会延长自己的窗口。会话键包括 API Key 身份，避免不同用户混用同一个会话 ID。

识别显式会话请求头、请求内会话字段，以及 Claude Code metadata 中的 `session_id`。普通 `metadata.user_id` 不作为会话 ID。没有可靠标识时，每个请求按独立会话计数，不允许靠省略标识绕过限制。会话容量只对 Anthropic 生效。

`cooldownSeconds=0` 使用原有自动冷却；1..86400 秒覆盖容量 / 传输类自动冷却，上游 `Retry-After` 是最短等待时间。凭据隔离不受缩短冷却影响。晚返回的成功请求不能清除另一个请求刚触发的冷却。

## 部署

可选组件默认关闭。普通直连部署不增加进程；旧 `proxyUrl` 的 HTTP / SOCKS5 连接由 Java 直接执行，不依赖 Mihomo。

在部署使用的 `.env` 中设置两个不同的随机密钥：`STARRY_EGRESS_CONTROLLER_SECRET`、`STARRY_TLS_SECRET`。建议各用 `openssl rand -hex 32` 生成；控制密钥限定字母、数字、下划线、短横线。

源码部署：

```bash
docker compose -f docker-compose.yml -f docker-compose.egress.yml up -d --build
```

新版预构建 bundle 包含 overlay、`docker/egress` 和本文；在解压目录运行：

```bash
docker compose -f docker-compose.artifact.yml -f docker-compose.egress.yml up -d --build
```

Mihomo 与 TLS bridge 共享 `service` 网络命名空间，控制端口 9090、bridge 9091、节点监听 20000..29999 全部绑定 `127.0.0.1`，不映射公网端口。两组件分别限额 256 MiB / 512 MiB，容器只读文件系统、无附加 capability。TLS bridge 非 root 运行，最多 16 个请求并发、64 MiB 请求体总预算、单请求 16 MiB，并关闭环境代理和自动重定向。

在 IDE / jar 环境运行时，需自行把 Mihomo 与 bridge 放在服务可访问的本地地址，并配置：

| 变量 | 默认值 |
| --- | --- |
| `STARRY_EGRESS_MIHOMO_ENABLED` | `false` |
| `STARRY_EGRESS_CONTROLLER_URL` | `http://127.0.0.1:9090` |
| `STARRY_EGRESS_CONTROLLER_BIND` | `127.0.0.1:9090` |
| `STARRY_EGRESS_CONTROLLER_SECRET` | 空；启用时必填 |
| `STARRY_EGRESS_LISTENER_HOST` / `STARRY_EGRESS_LISTENER_BIND` | `127.0.0.1` |
| `STARRY_EGRESS_TLS_ENABLED` | `false` |
| `STARRY_EGRESS_TLS_URL` | `http://127.0.0.1:9091` |
| `STARRY_TLS_SECRET` | 空；启用时必填 |
| `STARRY_EGRESS_PROBE_URL` | `https://api.ipify.org?format=json` |
| `STARRY_EGRESS_ACTIVATION_FAILURE_BACKOFF` | `5s` |

不能把独立 Docker 容器中的 `127.0.0.1` 当成宿主机回环地址。推荐直接使用 overlay，避免遗漏监听端口与网络配置。

## TLS 与失败处理

Anthropic 的 TLS 配置可选 `chrome136`、`safari18_4`，后者在 curl_cffi 0.16.3 内映射到 `safari184`。改变的是上游 TLS / HTTP 握手，不伪造业务身份，不保证上游接受账号。证书验证保持启用；业务认证头原样传递，内部路由头不传给上游。

绑定节点禁用、来源移除、运行组件不可用，或者指定 TLS 组件不可用时，请求失败，不直连。代理配置错误不作为账号凭据吊销证据，也不计入账号健康（不触发冷却、熔断或自动停用）。请求写出之前的失败（路由查找、节点未激活、代理 CONNECT / SOCKS 握手、到代理的 TLS、TLS 组件拒绝或不可达）确定未发送：释放计费预留、可换其他账号，但不会在同一账号重试，也不会直连。请求可能已写出后的失败（经代理的响应超时、请求中途重置）按直连超时同样处理：不换账号、不自动重放，计费保持待人工复核，避免重复生成和计费。

路由连接按路由复用：每条路由（代理类型与地址、凭据哈希；Mihomo 路由还包括通道、监听端口和节点指纹）有自己的连接池，最多缓存 300 条路由，空闲 20 秒回收，未使用 5 分钟的池整体释放。连接池只复用 socket，每次派发仍重新读取绑定，节点修改后的新路由不会拿到旧连接。Mihomo 每 15 秒检查并恢复配置；配置热加载失败会标记错误，保留数据库配置供修正；失败后的 5 秒内（`STARRY_EGRESS_ACTIVATION_FAILURE_BACKOFF`）请求路径直接返回失败，不再逐个请求重新应用配置，同一时刻的应用请求合并。目录接口的 `runtime` 字段给出控制器可达性、最近激活时间、激活节点数和最近一次固定错误码。Mihomo 整份配置校验失败可能暂时阻塞全部托管节点；停用异常节点后重新应用。已有进行中的请求不会因改节点被强制中断，后续派发读取最新绑定。

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
