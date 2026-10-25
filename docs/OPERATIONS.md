# 运维手册

这份手册面向**管理员和运维**，按「你要完成的任务」组织：启动服务、导入和测试上游账号、调度与熔断、备份恢复、日志保留、在线充值。每个任务都给出可以直接复制的命令；控制台里能点的操作，也会指向对应的 [控制台使用手册](CONSOLE_GUIDE.md) 章节。

> Starry API 启动后**本身就是反向代理**，没有单独的「代理进程」要另外启动。

**目录**：[启动服务](#启动服务) · [登录管理员](#登录管理员) · [导入账号](#导入单个账号) · [测试账号](#测试上游账号) · [账号分配控制](#账号分配控制) · [号源分级](#号源分级) · [实验性分级熔断](#实验性分级熔断) · [创建 API Key 与客户端配置](#创建-api-key-与客户端配置) · [数据库备份与数据传输](#数据库备份与数据传输) · [请求日志的聚合与保留](#请求日志的聚合与保留) · [配置在线充值](#配置在线充值)

---

## 启动服务

### 准备数据库（只需一次）

在全新的 PostgreSQL 17 集群上创建数据库和最小权限角色：

```bash
export POSTGRES_USER=postgres
export POSTGRES_DB=starry_api
export STARRY_POSTGRES_MIGRATION_PASSWORD='<强密码-迁移用>'
export STARRY_POSTGRES_APP_PASSWORD='<强密码-应用用>'
createdb --username "$POSTGRES_USER" --encoding=UTF8 "$POSTGRES_DB"
./docker/postgresql/init/001-starry-api-roles.sh
```

在 Compose 之外启动时，还要设置：`POSTGRES_HOST`、`POSTGRES_PORT`、`POSTGRES_DATABASE=starry_api`、`POSTGRES_USERNAME=starry_api`、`POSTGRES_PASSWORD`、`POSTGRES_MIGRATION_USERNAME=starry_api_migration`、`POSTGRES_MIGRATION_PASSWORD`。

### 启动

```bash
./scripts/start-proxy.sh                      # 用 Gradle 启动
SERVER_PORT=8081 ./scripts/start-proxy.sh      # 换端口
STARRY_RUN_MODE=jar ./scripts/start-proxy.sh     # 用打好的 boot jar 启动
```

**首次启动**时，服务会创建 Root（用户名 `admin`）和一个随机初始密码，只在「Root 标记未分配、且 `admin_users` 表为空」时在启动日志里打印**一次**。Docker 部署不用翻日志：在服务器上执行 `sudo starry-api info` 即可看到，详见 [服务器上的 `starry-api` 命令](#服务器上的-starry-api-命令)。

---

## 登录管理员

控制台里直接登录最方便（见 [快速开始](QUICKSTART.md#第-1-步登录并修改初始密码)）。忘了密码或找不到初始密码，先看下一节的 `starry-api` 命令。用命令行的话：

```bash
curl -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<初始密码>"}'

export ADMIN_TOKEN="adm-..."     # 取返回的 data.accessToken
```

首次登录必须先改密码，才能用其他管理接口：

```bash
curl -X POST http://127.0.0.1:8080/auth/password \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"oldPassword":"<初始密码>","newPassword":"<新密码>"}'
```

改密码会让该账号**所有**控制台会话失效，请重新登录并替换 `ADMIN_TOKEN`。

### 服务器上的 `starry-api` 命令

一键安装脚本会把 `starry-api` 命令装到 `/opt/starry-api/starry-api`，并链接到 `/usr/local/bin/starry-api`；用仓库里的 `docker-compose.yml` 部署时，在仓库根目录用 `./scripts/starry-api.sh` 即可（用法相同）。请在服务器上以 root 运行，或用能操作 Docker 的用户。输出语言跟随系统语言，也可以用 `--lang zh|en` 指定。

| 命令 | 作用 |
| --- | --- |
| `sudo starry-api info` | （默认）控制台地址和反向代理目标、服务容器状态和 `/health`、Root 用户名与邮箱、Root 密码状态、数据管理安全密钥的状态、Mihomo 部署与开关状态 |
| `sudo starry-api info --show-secrets` | 同上，并显示 `.env` 里的密钥（数据库密码、`STARRY_EGRESS_CONTROLLER_SECRET`、`STARRY_TLS_SECRET` 及其他名称含 `PASSWORD` / `SECRET` / `TOKEN` / `KEY` 的项），前面带警告。默认**不显示**，也不会写入任何文件 |
| `sudo starry-api reset-password` | 为 Root 设置新的临时密码（下面详述） |

**Root 密码**以加盐 PBKDF2 哈希保存，**永远读不回来**。`info` 只在两个条件同时成立时显示初始密码：Root 还没有改过密码（`first_login` 为真），并且当前容器的启动日志里还留着那一行；它还会核对日志里的密码**仍是当前密码**（重置过就不是了），核对不上就不显示。其余情况会告诉你原因——已被管理员修改，或日志已随容器重建（更新、`--force-recreate`）消失——并建议 `reset-password`。

**`reset-password`** 会：

1. 说明将发生的事，要求你在终端里输入 `RESET` 确认（没有终端时必须加 `--yes`）；
2. 为 Root 生成新的随机临时密码，写入数据库，下次登录必须先改密码（12–128 个字符）；
3. **重启服务**（几秒到一两分钟，期间无法访问）：控制台会话只存在服务内存里，无法从数据库撤销，重启才能让所有旧会话失效；
4. 等 `/health` 恢复后，**只显示一次**用户名和临时密码。如果重启失败，仍会显示密码，并告诉你手动重启的命令。

数据管理处于锁定状态时站点在维护模式，所有登录都返回 503，新密码此时也进不去；命令会提醒你先按 [恢复被锁定的数据管理](#恢复被锁定的数据管理) 处理。

**数据管理安全密钥（`dmsk-…`）无法从服务器显示、找回或重置**——服务器上只保存它的加盐校验值，`starry-api` 也故意不提供这类操作。`info` 只显示**状态**：未生成 / 生成于何时（以及连续失败次数）/ 连续失败 5 次后已锁定 / 状态文件丢失或无效。密钥丢了、被锁定或状态文件异常时，请按 [恢复被锁定的数据管理](#恢复被锁定的数据管理) 处理，**不要**删除或重建安全目录里的文件。

---

## 导入单个账号

控制台里点「账号管理 → 新建账号」（[详见](CONSOLE_GUIDE.md#新建账号)）。命令行等价如下。

### Kiro 账号

最小请求体，只有 `accessToken` 必填，`region` 默认 `us-east-1`：

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"email":"user@example.com","accessToken":"kiro-access-token","region":"us-east-1"}'
```

### DeepSeek 官方账号

设置 `provider=DeepSeek`。服务会把认证方式规范成 `api-key`，缺省的 `baseUrl` 是 `https://api.deepseek.com/v1`：

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"email":"deepseek@example.com","provider":"DeepSeek","authMethod":"api-key","accessToken":"sk-..."}'
```

- 官方路由请用目录里的 `deepseek-chat`、`deepseek-reasoner`、`deepseek-v4-flash`、`deepseek-v4-pro`；`starry/` 前缀形式也接受。四个模型都支持 Chat Completions；**Responses 目前只支持 `deepseek-v4-flash`**，其它 DeepSeek 模型在该端点返回 `400`。
- 厂商级的 API Key 会拒绝不在已启用目录里的模型 ID，即使底层路由解析器认识那个旧别名。
- DeepSeek 请求**只在 DeepSeek 账号池内**调度，不会回退到 OpenAI 或 Codex。为了兼容，`baseUrl` 主机是 `api.deepseek.com` 的老账号，即使存的 provider 是 `OpenAI-compatible`，也会被识别成 DeepSeek，普通 OpenAI / Codex 选择会排除它。

### Anthropic 原生账号

设置 `provider=Anthropic`、`authMethod=api-key`，缺省 `baseUrl` 是 `https://api.anthropic.com/v1`：

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"email":"claude@example.com","provider":"Anthropic","authMethod":"api-key","accessToken":"sk-ant-..."}'
```

Anthropic Messages 请求和 `anthropic/` 模型路由**优先**使用原生 Anthropic 账号，不可用时回退到兼容的 Kiro / AWS 账号。OpenAI 兼容端点上的裸 Claude 别名仍走 Kiro 路由，除非某条模型映射选择了 `anthropic/` 目标。

### 第三方 OpenAI 兼容账号

用 `provider=OpenAI-compatible`，站点的 API Key 作为 `accessToken`，站点 API 根作为 `baseUrl`：

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"email":"openrouter@example.com","provider":"OpenAI-compatible","authMethod":"api-key","baseUrl":"https://openrouter.ai/api/v1","accessToken":"sk-or-..."}'
```

要使用第三方模型，需要三步配合：

1. 在「系统设置 → 模型管理」里，把**公开模型 ID** 和 `openai-compatible/<真实模型名>` **目标模型**登记到**同一个厂商**下；
2. 为该厂商创建一把 API Key；
3. 添加映射。例如，两个 ID 都启用后，把 `llama-3.3-70b` 映射到 `openai-compatible/meta-llama/llama-3.3-70b-instruct`。

Starry API 会在发往上游前去掉路由前缀。厂商级 Key 会校验映射的两端，拒绝未知或跨厂商的目标。`baseUrl` 可以是站点根（`https://joystar.cc`）或带版本的 API 根（`https://openrouter.ai/api/v1`）；路径没有以版本段结尾时，运行时和账号测试都会自动用 `/v1/chat/completions`。

### OpenAI 官方 Key 与 Codex

OpenAI 官方 Key 示例：

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"email":"team@example.com","accessToken":"sk-...","authMethod":"api-key","provider":"OpenAI","baseUrl":"https://api.openai.com/v1"}'
```

> ⚠️ 只有 `baseUrl` 主机为 `api.openai.com` 的才算「官方 OpenAI API Key」账号，才会服务普通 `gpt-*` 请求。

Codex（ChatGPT 账号）推荐用控制台的「**账号管理 → 新建账号 → ChatGPT 浏览器授权**」，**不需要复制任何 Token**。使用前需要：

- ChatGPT 个人账号在安全设置里**开启设备码登录**；工作区账号则由工作区管理员在权限里开启。
- 服务器能访问 `auth.openai.com` 和 `chatgpt.com`。
- 临时登录状态保存在内存里，与发起的管理员绑定，**15 分钟**过期，重启服务会作废未完成的登录（已保存的账号不受影响）。每个管理员最多同时 3 个登录。
- 它只创建 `provider=OpenAI`、`authMethod=codex` 的账号，对应 `https://chatgpt.com/backend-api/codex`。它不会自动化 ChatGPT 网页，也不会创建 OpenAI API Key 账号。
- OAuth 的 access / refresh / ID Token 是敏感的数据库凭据：保护好数据库备份，限制数据库访问，**不要**把账号行或 Token 放进日志和工单。重新授权时，如果无法确认新旧 ChatGPT 身份是同一个，会拒绝覆盖。
- **只适合可信的私有部署**。面向公开、多租户、CI 等不可信的程序化场景，请用 OpenAI Platform API Key 账号。请只授权你有权操作的账号，并遵守 OpenAI 的条款。

---

## 批量导入账号

控制台里：「账号管理 → 批量导入」（[详见](CONSOLE_GUIDE.md#批量导入)）。命令行方式：

```bash
ADMIN_TOKEN="$ADMIN_TOKEN" ./scripts/import-accounts.sh                       # 导入 examples/accounts.import.json
ADMIN_TOKEN="$ADMIN_TOKEN" ./scripts/import-accounts.sh /path/to/accounts.json  # 导入别的文件
```

**支持的导入格式**：

- Kiro Account Manager 的 JSON 导出；
- 单个账号对象、账号数组，或 `{ "accounts": [...] }`；
- 多个导入文档：`{ "documents": [...] }`，每个文档可以是任意受支持的格式；
- Codex Token JSON：`type: "codex"`，`access_token`、`refresh_token`、`id_token`；
- OpenAI 兼容配置块：`openai-compatibility[].api-key-entries[]`，含 `api-key`，可选 `base-url`、`proxy-url`。

Kiro Account Manager 的格式可以直接粘贴：

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
      "profileArn": "arn:aws:codewhisperer:us-east-1:638616132270:profile/AAAACCCCXXXX",
      "machineId": "optional-machine-id",
      "proxyUrl": "http://user:pass@host:port",
      "credentials": {
        "accessToken": "kiro-access-token",
        "refreshToken": "optional-refresh-token",
        "clientId": "optional-client-id",
        "clientSecret": "optional-client-secret",
        "region": "us-east-1",
        "authMethod": "IdC",
        "provider": "BuilderId"
      },
      "usage": { "limit": 1000000 }
    }
  ],
  "groups": [],
  "tags": []
}
```

**导入规则**

| 情形 | 行为 |
| --- | --- |
| `upsert=false` 或省略 | 邮箱已存在的条目被**跳过** |
| `upsert=true` | 邮箱已存在的条目**更新**那个账号 |
| 重复判断依据 | **规范化后的邮箱**。Kiro 的 `id` 只当作外部 `accountId` 接受 |
| `id` / `accountId` 缺失或与现有行冲突 | 服务生成新的内部 `accountId`，所以邮箱不同的会作为**另一个账号**创建 |
| 必填 | `credentials.accessToken` 或顶层 `accessToken` |
| 带刷新凭据的账号 | 默认每小时后台刷新一次；如果 Kiro 仍报 Token 无效，会立刻刷新一次并重试。IdC / BuilderId 账号还需要 `clientId` 和 `clientSecret` |
| 每条的结果 | `CREATED`、`UPDATED`、`SKIPPED`、`FAILED` |

控制台可以一次上传多个 `.json` 文件，在浏览器里解析，每个文件提交一次导入请求，全部完成后汇总结果。

---

## 测试上游账号

控制台里点账号行的「**测试**」。命令行批量测试：

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts/test \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"onlyEnabled": true, "model": "simple-task", "prompt": "Reply OK in one short sentence.", "maxConcurrency": 3}'
```

只测指定账号时加 `accountIds`：

```json
{ "accountIds": ["kiro-main"], "onlyEnabled": true }
```

要点：

- DeepSeek 账号测试默认用 `deepseek-v4-flash`，账号行也可以选别的目录里的 DeepSeek ID 来测。
- 对所有厂商来说，普通的 `429` 表示**暂时的**容量或限流，不是永久额度耗尽。手动和定时测试会让 `errorCount` 加一，但**不会**设置 `quotaExhaustedAt`、禁用或挂起账号。
- 测试方式：Kiro 走 Kiro 上游客户端；OpenAI 和 DeepSeek API Key 账号走 `/chat/completions`；Codex 走 `/responses`；Anthropic API Key 走 `/messages`。Google / Gemini 的 Key 记录可以存储，但直连测试目前对该账号返回 `501`（没有接 Google 上游客户端）。
- `updateStatus` 默认 `true`；只有想让测试**不改变账号状态**时才设成 `false`。测试成功会重新启用账号并清除挂起 / 额度状态。

---

## 账号分配控制

编辑账号 →「**分配策略**」标签页（[详见](CONSOLE_GUIDE.md#分配策略)）。下面是原理和运维建议。

### 它做什么

调度器在 PostgreSQL 里**原子地**决定每次尝试能不能被某个账号接收。请求在发往上游之前，可能因为并发、RPM、突发、会话、在途令牌容量，或匹配的模型 / 端点冷却而被拒绝；此时代理会**换一个**符合条件的账号，**不会**把被拒绝的账号算作上游失败。

- 已派发的租约最多预留容量 **10 分钟**，并在成功 / 失败时核销。取消和派发后的本地失败会立刻放弃租约。进程意外退出时，过期的预留会在下次考虑该账号时被回收。
- 数值越小越优先。API 账号使用完整配置容量；Codex / Kiro 保留 10% → 25% → 50% → 100% 分阶段预热。

### 诊断一个账号

```bash
curl http://127.0.0.1:8080/admin/accounts/<accountId>/allocation-policy -H "Authorization: Bearer $ADMIN_TOKEN"
```

调高限额之前，先看 `lifecycleState`、`cooldownUntil`、`activeConcurrency` 和预留令牌。`DEGRADED` 仍可被选中；`COOLDOWN`、`QUARANTINED`、`DISABLED` 不接收流量。冷却 / 隔离到期后回到第 1 阶段预热。普通的 `429` **不会**永久禁用账号，也不会设置额度耗尽；只有明确的凭据吊销证据才会让 `401/403` 禁用账号。诊断模型 / 端点级的 `429`，可以查询 `account_scoped_cooldowns`，把 `scope_key` 与上游模型 ID 或请求端点对比。

### 无状态与会话

非 Anthropic 账号不使用会话容量。Anthropic 在设置上限后按最近活动窗口统计不同会话；缺少可靠标识时按独立请求计数。延迟反馈仍衡量整次上游尝试，不是 TTFT。已支持节点出口、HTTP / SOCKS5 及 Anthropic TLS 指纹，部署和限流语义见 [账号出站代理](ACCOUNT_EGRESS.md)。Anthropic OAuth / Setup Token 凭据暂未提供。

### 账号路由策略

控制台不再提供这个面板，默认值适合绝大多数部署；确需调整时用 `PATCH /admin/accounts/settings` 修改 `routingPolicy`（字段见 [API：路由策略](API.md#路由策略)）。默认：

- Codex 账号与官方 OpenAI API Key **隔离**；普通 GPT 请求只用官方 `sk-` 账号；
- 准入时**不**建立亲和；单独的 API Key **不会**被当成会话；已派发的 Codex 请求只尝试**一个**账号；
- 显式的会话亲和在上游成功后才提交。

只在普通 GPT 流量应该消耗 Codex 账号时，才开启 `gptUsesCodexAccounts`；再关闭 `isolateCodexPool` 就允许混合回退。默认的「换账号次数上限」：Codex 1、OpenAI / DeepSeek 3、Anthropic / Kiro 2。并发和 RPM 仍然是每个账号自己的分配限制（OpenAI 系默认 4 / 60）。

### 保留与清理

账号分配的清理默认在 **03:35 UTC** 运行。每个事务对每张支持的表最多删除 `STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE` 行，一次运行最多 `STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES` 个事务。默认保留终态租约 7 天、分配事件 90 天；过期的会话绑定、作用域冷却和响应归属行**立即**可清理。在调大保留期或批次数之前，请关注表的增长和 `Failed account-allocation retention maintenance` 警告。

---

## 号源分级

「系统设置 → **号源分级**」把一个厂商的账号分成有序的**号池**（级别，1 最好），每个号池有名称和价格系数。典型用法：Anthropic 的 1 级放官方 API Key，2 级放 Kiro 与 Claude OAuth 并给 0.6 的系数；OpenAI 的 1 级放官方 Key，2 级放 Codex（需要先关闭 `isolateCodexPool`，否则 Codex 与官方 Key 不会在同一个请求里互相回退）。

- **选号**：新会话从 1 级开始。一个请求里 1 级的账号**全部**不可用——停用、被挂起、额度耗尽、冷却，也包括并发、限速、在途 token 已满的**繁忙**——才会用到 2 级。
- **降级后停留**：会话真实降级后停在降级后的号池，直到空闲 10 分钟（可按厂商关闭）。1 级的账号只是额度耗尽，或者并发、限速、冷却，也算 1 级不可用，会话照样停在 2 级，恢复后也不回去。而被停用的账号不在账号缓存里，被挂起的账号也是（挂起会同时停用账号）：整个 1 级的账号都被停用或挂起时，1 级对请求就像这条路由上没有账号一样，请求由 2 级服务，但这不算降级，会话不会停在 2 级，账号恢复后回到 1 级；这条路由根本用不到某个号池的账号（例如只有 Codex 能服务的模型对只放官方 Key 的 1 级）同样不算降级。号池比 1 级贵时，已降级的会话在 1 级恢复后仍按低级价计费，直到空闲。客户端固定了号池的请求不读也不改会话停留的号池，但算作会话在使用（所有入口一样）：一个一直在发固定请求的会话不算空闲，停留的号池不会因此过期。它产生的响应成了 Responses 链的最近一环、它的账号成了链的 owner 也一样不改：之后的自动请求（链可以在别的账号上重放时）只在 owner 正好在它本来要开始的号池（会话停留的号池，没有停留就是 1 级）里才先用 owner，否则忽略 owner，从那个号池开始并按它计价，所以固定请求留下的 owner 不会把链留在固定的号池里，会话空闲满 10 分钟回到 1 级后，链也跟着回到 1 级。**多节点部署时「停留」只部分成立**：会话所在的号池只记在处理它的那个节点的内存里，与会话亲和一样，节点之间不同步、重启即忘（空闲时长是 `STARRY_SESSION_AFFINITY_TTL`，默认 10 分钟；`STARRY_SESSION_AFFINITY_ENABLED=false` 时完全不停留）。负载均衡若不把同一个会话固定到同一个节点，落到另一个节点的请求会从 1 级重新开始，并按 1 级计费。要让「停留」可靠，请在负载均衡上按会话标识做会话粘性：Claude Code 的 `x-claude-code-session-id`、OpenCode 的 `x-session-affinity`、Codex 的 `session-id` 请求头（核对过的版本见 [API：号源分级](API.md#号源分级)）。网关怎么认出同一个会话（这些头，没有时用对话开头的内容指纹）也写在那里的「会话键与 D4」。
- **计费**：单价 = 标准价 × **实际服务这个请求的号池**的系数。预留按这个厂商可能用到的最贵号池定额，结算时按服务的号池退回多余的部分。客户端固定了号池的请求只按固定的号池（带 `+` 时按它和它以下的号池）预留；续写一个不能离开 owner 账号的 Responses 链的请求只能由 owner 所在的号池服务，也只按那一个号池预留；余额不够自动请求的预留时，返回的 `402` 会列出预留更少的可选号池（厂商没有打开「公开号池名称」时，用户在 Key 表单里也选不了号池，这时只说明预估是按可能服务的最贵号池算的，不列出号池；不限厂商的旧 Key 没有号源偏好，提示里不让它去 Key 的设置里选）。「用户可选」关闭的号池不能被客户端固定（`403 SOURCE_TIER_NOT_SELECTABLE`），也不会出现在这条提示里。
- **自动复查**：打开「账号不可用时自动复查」（账号页的停用账号定期复检）后，分级请求在一轮选号全部落空时复查一次——只复查这个厂商已启用的号池里、这条路由用得上的停用、被挂起或额度耗尽的账号，不是整个平台的——有账号恢复就再选一轮；每个请求至多一次。请求已经离开、被限制在别的号池或被某号池拒绝太多次的号池不为它复查；而请求计划里因为没有任何账号能服务（全部被挂起、额度耗尽或停用）而被略去的号池，它的账号也在复查之列，否则那个号池不会恢复——只是这个请求不会改用它们，下一个请求才会。
- **预占租约**：按号源分级服务的请求，每次派发都会把预占的租约续到「从现在起 `max(15 分钟, STARRY_REQUEST_TIMEOUT × (STARRY_MAX_RETRIES + 1) + 60 秒)`」（默认 16 分钟），只延长、不缩短，也不会超过预占创建后的 1 小时。Kiro 自己按 `STARRY_REQUEST_TIMEOUT` 限制每次重试，已在租约之内。Anthropic、OpenAI、Codex 的流式响应只受「两次读取之间」的超时限制，可以一直有数据而超过租约，所以派发还在进行时，网关每隔租约的四分之一（默认 4 分钟）续一次，派发结束（完成、失败或客户端断开）就停止；某次续期失败（例如数据库暂时不可用）会在下一次再试，连续失败两次租约仍有四分之一窗口的余量（连续失败三次租约就可能到期，清扫器随后会把它转为「待核对」，流结束时的结算会清掉）。清扫器是先选出过期的预占、再另行转为待核对，转换时不再检查是否刚被续上，所以恰好在这个间隙里被续上的租约仍可能被转为「待核对」，同样由流结束时的结算清掉。因此一个请求可以持续到预占创建后 1 小时而不被网关打断，答案照常按服务它的号池结算。超过 1 小时的派发租约无法再续：清扫器会把它的预占转为「待核对」并保留冻结额度，这期间该用户的新请求会被拦住；流结束时的结算会清掉这个待核对并按服务的号池扣费（未分级的请求超过 15 分钟租约时也是这样）。**多节点部署**：续期由处理这个请求的节点发出、写在共享的数据库里；如果这个节点中途停止，没有人续期，租约会在一个窗口内到期，由任一节点的清扫器转为待核对。没有预占的匿名调用没有租约。没有配置分级的厂商不变。
- **没有配置的厂商**路由、价格、结算和响应都与分级出现之前完全一样。号池被删光、只剩厂商一行的厂商同样算没有配置：模型名里的 `@` 留在 id 里，请求头和 Key 的偏好被忽略，响应不加号源头。配置先保存、再「启用」才生效；当前版本还不能运行的设置（页面顶部有提示）不能保存。
- **当前版本能分的是原生号池**：同一个厂商里，这条路由本来就能用的账号（官方 Key、Kiro、Claude OAuth、Codex 等）按类别或手动指定分到各级，分级不会给路由增加它原本用不到的账号。客户端可以用 `模型@标识` 或请求头 `X-Starry-Tier` 自己指定号池（见 [API：号源分级](API.md#号源分级) 的「指定号池」），点号池表里的「复制」就得到写法。后缀是对这一个请求的要求，号池用不了（不存在、这条路由在里面没有账号）就被拒绝；请求头和 Key 的偏好是客户端或 Key 的默认选择，用不上时忽略，请求照常按自动服务，响应带 `X-Starry-Tier-Status: ignored`，所以控制台生成的客户端配置（写请求头）不会因为选了一个这个客户端用不上的号池而整条失效；跨协议中转在后续版本提供，页面上对应的设置现在不能打开。Anthropic 的「全端点统一」是唯一会改变路由的开关，见下一条。
- **全端点统一**（只有 Anthropic，默认关闭）：Chat 和 Responses 入口上的 Claude 模型原本只由 Kiro 服务，官方 API Key 不在它们的路由里，所以放官方 Key 的 1 级号池对这两个入口是空的——这些请求只落在 Kiro 那一级，按那一级的系数计价。在 Anthropic 页面打开「全端点统一」（预设和向导不会替你勾选）后，这两个入口上的 Claude 目录模型和 Messages 入口一样走 Anthropic 路由：每一级里 Anthropic API Key（官方或第三方）在前、Kiro 在后，Claude Code 和 OpenCode 因此看到同样的号池顺序、同样的降级和同样的价格；派发、换账号的次数上限（「账号路由策略」里 Anthropic 那一项，不再是 Kiro 那一项）和预留也都按这条路由。**成本可能上升**：原来只用 Kiro 的 Chat 和 Responses 请求现在先用官方 Key。它只改变这些请求：Gemini 入口、`auto` 和 `simple-task` 这样的路由预设、CodeWhisperer 内部 ID、Gemini 等其他厂商的模型 ID、已经带 `anthropic/` 或 `openai/` 前缀的模型都不受影响；厂商没有启用分级时，开关保存了也不起作用。**所有节点都升级到支持它的版本之后再打开**：旧节点仍只用 Kiro 服务这两个入口。
- **Key 的号源偏好**：用户在「API Key」的新建 / 编辑表单里为一把 Key 选一个号池（或「及以下」），它的请求在没有写后缀和请求头时就按这个选择服务。**普通用户只有在厂商打开了「公开号池名称」时才看得到可选的号池**（管理员总能看到）；没有打开时普通用户的表单里没有可选的号池：这一项只在这把 Key 已经设了偏好时出现，显示当前值（标明「这里没有列出它」），用户只能保留它或清回「自动」，换不成别的号池；已经设好的偏好照常生效。偏好是 Key 的默认选择，所以不适用就忽略（号池被删除、停用、改成不可选，或这个入口 / 这个 Responses 链用不上它），响应带 `X-Starry-Tier-Status: ignored`；选定后只用这个号池，它的账号都不可用（忙、额度耗尽，也包括都被停用或挂起——健康检查失败会挂起账号）时请求失败（`503 SOURCE_TIER_UNAVAILABLE`，不会改由别的号池按另一个价格服务），要换到更低的号池须选「及以下」。号池里这条路由根本没有账号类型时才忽略偏好、按自动服务。旧的不限厂商的 Key（`*`）没有偏好，也存不了偏好（`400 API_KEY_TIER_PREFERENCE_INVALID`，须在同一次编辑里先选模型分类）。偏好属于 Key 当前的模型分类：通过 API 把 Key 改到另一个模型分类又没有带新的偏好时，偏好清回「自动」（控制台编辑里换模型分类也是这样），不会让另一个厂商里同名或同级的号池悄悄固定住这把 Key。通过 API 按级别写的偏好（`l2`、`2+`），控制台在号池列出时显示成那一级的号池并注明是按级别选的。
- 所有节点都升级到支持分级的版本之后，再启用分级。

### 看状态

页面下方的**运行状态**每 15 秒刷新一次，应答节点的视角：

| 列 | 含义 |
| --- | --- |
| 账号：就绪 / 繁忙 / 冷却 / 不可用 / 已停用 | 号池收下的每个账号只计入第一个让它不能服务的层：已停用；被挂起或额度耗尽；分配生命周期里冷却或隔离中；并发或在途 token 已到上限；其余为就绪。每分钟请求数、突发和会话数的限制，以及按模型的冷却，只在请求到来时才知道，这里不计 |
| 现在服务 | 厂商已启用、号池已启用、至少一个账号就绪 |
| 最近 15 分钟：服务 / 失败 | 取自请求日志，覆盖所有节点。1 级失败又由 2 级服务的请求，算 1 级一次失败、2 级一次服务 |
| 降级率 | 网关自己选号池的请求里，由低于「已启用的最好一级」服务的比例。长期偏高说明 1 级经常不可用，先看它的「繁忙」和「冷却」 |

账号数是**这个节点**的（账号缓存最多晚 30 秒）；多节点部署请对每个节点分别看，配置则由所有节点共用，保存后各节点立即刷新，最迟 30 秒。

### 在别处看到号源

- **账号页**：「号源级别」列显示账号在每个已配置厂商里的号池（手动指定的带回形针图标；悬停可看它为什么在这个号池，落入最低级的会特别说明；当前没有按它服务的号池显示为灰色）；可按号池筛选，勾选账号后用「设为号池…」批量指定或取消指定。没有配置任何号池时不显示这些。
- **请求日志**：新增「号源」列，显示服务这次尝试的号池名称、级别和系数（客户端指定的另有标记）；请求有多次尝试（1 级失败后换 2 级）时每次一行。点开一行还能看到选号方式、派发方式、用量来源、停止原因等号源细节。没有任何按号池服务的请求时不显示这一列。
- **计费核对**（Coding Plan 页的「费用核对」）：按号源分级服务的请求，列表和对话框显示实际服务（或最后派发到）的号池，以及**该号池的报价和估算**，核对金额的默认值也是这个估算。核对时请按它，不要按预占金额——预占是按最贵号池留的。
- **定价**（系统设置 → 模型管理 → 价格倍率 → 价格预览）：预览启用了分级的厂商的模型时，另列每个号池的价格。规则只命中**一条**，号池系数在它之后再乘。
- **`/v1/models`**：开启了分级的厂商的模型按它的第一个号池标价；打开「公开号池名称」才会列出号池，见 [API 参考](API.md#号源分级)。

### 报账

按号池汇总的收入：

```sql
SELECT source_tier_ref, sum(credits) FROM api_usage_events GROUP BY 1;
```

管理员核对后结算、或租约过期的请求，归到它最后派发的号池。

## 实验性分级熔断

「账号池设置」页里的「**账号池分级熔断策略**」是**实验性**功能，**默认关闭**。在关键业务上请先测试好主 / 备容量、告警和回滚再启用：误熔断、路由抖动和监控口径变化都仍有可能发生。

- 熔断策略、模型池监控和开关都在这张「账号池分级熔断策略」卡片里；账号页始终是普通的账号管理，不会因为启用而变成只读。
- 启用需要确认「不稳定」提示；卡片里始终显示实验标签和不稳定警告。
- 这个策略只作用于**没有配置[号源分级](#号源分级)**的厂商：已启用号源分级的厂商不进入熔断，它的请求不带熔断纪元，也不会返回 `ACCOUNT_POOL_CIRCUIT_OPEN`。两套「分级」互相独立，别把「账号池分级熔断策略」当成号源分级。

### 启用前：先分好主 / 备

分层是**按账号**存的，对该账号所属厂商的所有模型族生效。**每个需要在熔断期间保持可用的厂商，至少要有一个健康的备用（secondary）账号**：

```bash
curl -X PATCH http://127.0.0.1:8080/admin/accounts/openai-secondary \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"poolTier":"secondary","poolWeight":100}'
```

分层都配好之后再启用。每次从关到开都会创建**新的熔断纪元**和激活边界，所以第一次 30 秒对账从一个干净的观察窗口开始，只包含这次激活之后的请求：

```bash
curl -X PATCH http://127.0.0.1:8080/admin/accounts/settings \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"poolPolicy":{"enabled":true}}'
```

### 它怎么运转

每个解析后的上游模型被分到一个有界的 `厂商:模型族` 熔断器，如 `openai:gpt-5`、`deepseek:deepseek-v4`；未知模型归入 `厂商:other`，不会分配任意的键。三种状态：

| 状态 | 含义 |
| --- | --- |
| `CLOSED` | 主账号处理流量；窗口达到 `minSampleSize` 和 `failoverThresholdPercent` 后熔断器打开 |
| `OPEN` | 在完整的 `retestIntervalMinutes` 冷却期内，只有备用账号处理流量 |
| `HALF_OPEN` | 最多 `retestMaxConcurrency` 个**真实客户端请求**去探测主账号，其余流量仍走备用。达到 `recoveryThresholdPercent` 则关闭，否则重新打开 |

`retestIntervalMinutes` 和 `retestMaxConcurrency` 两个字段名为了 API 兼容而保留，现在的含义分别是「半开前的冷却」和「探测额度」；`retestThresholdPercent` 同样只为兼容，不会独立触发状态变化。状态机**从不**向所有账号群发合成测试。手动账号测试和单独配置的「禁用账号定时测试」仍然是独立的管理功能。

### 监控

```bash
curl http://127.0.0.1:8080/admin/accounts/pool-status -H "Authorization: Bearer $ADMIN_TOKEN"
```

以 `data.modelPools` 为准。建议告警：`state != CLOSED`；某个必须有备用的厂商缺少 `secondaryAccountCount`；备用账号持续失败；探测反复回到 `OPEN`。`nextProbeAt`、`halfOpenInFlight`、`halfOpenCompleted`、`halfOpenSucceeded` 可以区分是在冷却还是在主动恢复。`activeTier`、`primaryDisabled` 这类聚合字段只为老客户端保留，可能同时代表多个模型池。

每条请求日志会持久化 `circuit_epoch`、`circuit_key`、`circuit_tier`、`upstream_model` 和可空的 `upstream_success`。`success` 是客户端看到的最终结果；`upstream_success=true` 还涵盖「上游成功但本地用量结算失败」的情况，避免把责任算到上游头上。诊断某次状态变化时，只看**当前纪元**和激活边界：

```sql
WITH policy AS (
    SELECT
        NULLIF(max(setting_value) FILTER (WHERE setting_key = 'account_pool_policy_epoch'), '')::uuid AS circuit_epoch,
        NULLIF(max(setting_value) FILTER (WHERE setting_key = 'account_pool_policy_activation_started_at'), '')::timestamptz AS activation_started_at
    FROM starry_api.system_settings
)
SELECT
    circuit_key,
    circuit_tier,
    count(*) AS requests,
    count(*) FILTER (WHERE upstream_success IS FALSE) AS failures,
    round(100.0 * count(*) FILTER (WHERE upstream_success IS FALSE) / NULLIF(count(*), 0), 2) AS failure_rate_percent
FROM starry_api.request_logs
CROSS JOIN policy
WHERE request_logs.circuit_epoch = policy.circuit_epoch
  AND created_at >= GREATEST(CURRENT_TIMESTAMP - INTERVAL '5 minutes', policy.activation_started_at)
  AND created_at < CURRENT_TIMESTAMP
  AND circuit_key IS NOT NULL
  AND upstream_success IS NOT NULL
GROUP BY circuit_key, circuit_tier
ORDER BY circuit_key, circuit_tier;
```

**同步细节**：本地结果立刻更新熔断器；每 **30 秒**有一次不重叠的对账，读取带索引的 PostgreSQL 窗口，策略设置变更的通知也会触发一次立即对账。查询按当前 UUID 纪元、明确的上下时间边界和可空的 `upstream_success` 过滤。它统计成功调用、可重试的传输失败、上游声明可重试的 `408`、`409`、`5xx`；账号级的 `401`、`402`、`403`、`429` 和不可重试的解析失败是 `NULL`，被排除。它**不会**调用任何上游 API。多实例部署时，熔断状态是各实例本地的，通常会在一个对账周期内通过共享日志窗口收敛，故障突发后各实例的路由行为可能短暂不一致。

策略设置通过 PostgreSQL 的 `LISTEN/NOTIFY`（通道 `starry_api_system_settings`）单独、即时地同步。每个实例在就绪前同步加载策略和带索引的窗口，之后用专用的监听连接和单飞保护处理匹配的 `account_pool_policy_*` 更新。监听重连后的完全失效会取消过期的刷新，但保留最近一次可用的策略和熔断状态，直到替代查询成功，所以临时的刷新失败不会悄悄恢复成「所有层级都路由」。请监控 `PostgreSQL setting notification listener disconnected; reconnecting`，并确认它能恢复；重试退避上限 30 秒。

**回滚**：关闭策略。这会重置本地熔断状态并恢复普通账号管理，所有厂商可用账号不再按熔断层级过滤。之后再次启用会创建新纪元，不会恢复上一次激活的窗口：

```bash
curl -X PATCH http://127.0.0.1:8080/admin/accounts/settings \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"poolPolicy":{"enabled":false}}'
```

---

## 创建 API Key 与客户端配置

控制台里的操作见 [快速开始：创建 API Key](QUICKSTART.md#第-3-步创建一个-api-key)。命令行：

```bash
curl -X POST http://127.0.0.1:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"default","creditsLimit":1000,"modelProvider":"OpenAI"}'
```

`modelProvider` 必须匹配 `/v1/models` 返回的某个已启用厂商。**请求的模型和任何映射目标，都必须在该厂商已启用的目录里。**保存返回的 `data.key`，然后用它调用：

```bash
export STARRY_API_KEY="sk-..."
curl http://127.0.0.1:8080/v1/models -H "Authorization: Bearer $STARRY_API_KEY"
```

OpenAI 兼容客户端：

```text
Base URL: http://127.0.0.1:8080/v1
API Key:  sk-...
```

控制台的 API Key 页可以生成现成的客户端配置（[详见](CONSOLE_GUIDE.md#客户端配置)）。下面是它们的内容，方便你手动配置。

**Codex CLI**（`~/.codex/config.toml`）：

```toml
model = "gpt-5.5"
review_model = "gpt-5.5"
model_provider = "starry"
model_reasoning_effort = "xhigh"
model_verbosity = "medium"
disable_response_storage = true
windows_wsl_setup_acknowledged = true

[model_providers.starry]
name = "Starry API"
base_url = "http://127.0.0.1:8080/v1"
wire_api = "responses"
experimental_bearer_token = "sk-REPLACE_ME"
supports_websockets = false
```

`~/.codex/auth.json`：

```json
{ "OPENAI_API_KEY": "sk-REPLACE_ME" }
```

> 如果 Codex 报 `Your access token could not be refreshed because your refresh token was already used`，说明本机 `auth.json` 里还留着旧的 ChatGPT OAuth 字段。备份后换成上面的最简 JSON，或者先运行 `codex logout`。

**Claude Code**：

```bash
export ANTHROPIC_BASE_URL='http://127.0.0.1:8080'
export ANTHROPIC_API_KEY='sk-REPLACE_ME'
export ANTHROPIC_AUTH_TOKEN='sk-REPLACE_ME'
export ANTHROPIC_MODEL='claude-sonnet-4.5'
export ANTHROPIC_SMALL_FAST_MODEL='claude-haiku-4.5'
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1'
export CLAUDE_CODE_ATTRIBUTION_HEADER='0'
```

Windows CMD 把每行改成 `set "名称=值"`；PowerShell 改成 `$env:名称 = '值'`。

**OpenCode**（`opencode.json`）：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "model": "starry/gpt-5.5",
  "small_model": "starry/gpt-5.4-mini",
  "provider": {
    "starry": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Starry API",
      "options": { "baseURL": "http://127.0.0.1:8080/v1", "apiKey": "sk-REPLACE_ME" }
    }
  }
}
```

控制台生成的 OpenCode 配置还会带上 `"enabled_providers": ["starry"]`，防止 OpenCode 在选中 Starry Key 时回退去用本机 / 全局的 OpenAI 凭据。老的、只保存了前缀的 Key 不能直接生成脚本（`REPLACE_REST_OF_KEY` 占位符无法通过认证），需要粘贴原始完整 Key 或重建。

**CC Switch** 一键导入链接形如：

```text
ccswitch://v1/import?resource=provider&app=claude&name=Starry+API+Claude&homepage=http%3A%2F%2F127.0.0.1%3A8080&endpoint=http%3A%2F%2F127.0.0.1%3A8080&apiKey=sk-REPLACE_ME&model=claude-sonnet-4.5&icon=claude&enabled=true&haikuModel=claude-haiku-4.5&sonnetModel=claude-sonnet-4.5&opusModel=claude-opus-4.5
```

**测试一次对话**：

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $STARRY_API_KEY" \
  -d '{"model":"claude-sonnet-4.5","messages":[{"role":"user","content":"Say hello in one sentence."}],"max_tokens":256}'
```

---

## 数据库备份与数据传输

**仅初始 Root 管理员**能使用「数据管理」。控制台操作见 [控制台手册：数据管理](CONSOLE_GUIDE.md#数据管理)（含完整截图流程）。本节讲背后的规则和命令行用法。

### 安全模型一览

- 页面一开始是**锁定**的：必须先输入数据管理安全密钥，才会加载任何数据库信息或备份文件。首次使用时 Root 可以生成并保存密钥，再验证进入。
- 进入页面用的是单独的 `VIEW_DATA_MANAGEMENT` 授权，由 `/admin/data-transfer/security/unlock` 签发。`GET /admin/data-transfer/status` 和 `GET /admin/data-transfer/backups` 都要求在 `X-Data-Management-Token` 里带这个授权，它绑定 Root 会话，有效期为配置的解锁时长（默认 **5 分钟**）。**页面授权不能用来导出、下载、创建、删除或恢复**。控制台只把它放在页面内存里，到期、Root 手动锁定、离开或刷新页面时会清空显示的数据。
- 受保护的操作各自要**动作绑定的授权**和确认。`DOWNLOAD_BACKUP` 和 `DELETE_BACKUP` 的授权还绑定**具体文件名**；**删除和恢复的授权是一次性的**。
- 数据管理（含状态、备份列表、导出、初始化、恢复导入）**只有 Root 能用**。普通管理员在控制台里看不到这个模块，直接调接口也会被拒绝，即使他们有安全密钥。Root 的 `user_id` 被锚定在 `application_root_user_id`，数据库触发器阻止替换、改名、禁用、降级、删除或修改标记；其他管理员不能修改或重置 Root。
- 启动时只在**空用户库**里创建 Root，就绪前同步校验；如果库里已有用户却缺少 Root，**启动会中止**，而不是悄悄重建。

> **请把 Root 的密码和会话当作整个服务的凭据**保护：一旦泄露，就假定整个服务被攻破并轮换所有受影响的凭据。启动日志里的初始密码只在首次启动交接时出现一次，必须立即修改；除此之外，事件管线**从不**记录或邮件发送登录密码、密码哈希 / 盐、数据管理密钥、错误的提交值，或会话 / 数据管理令牌。数据管理密钥是高风险数据库操作的**额外确认**，并不能防住已经控制了 Root 账号的攻击者。

### 初始化与保存密钥

初始化返回形如 `dmsk-...` 的安全密钥，**只显示一次**。请直接复制到受控的密码管理器，并另存一份加密的离线副本。控制台**故意不提供明文下载**。服务只在「仅属主可读」的状态文件里保存一个加盐的 PBKDF2 校验值，不保存密钥本身——所以**无法在服务器上显示、找回或重置**；在服务器上用 `sudo starry-api info` 只能查看它的状态。

- 受保护操作前，用密钥在 `/security/unlock` 换取一个 `dmt-...` 短期令牌，它绑定当前管理员会话、请求的动作和可选的备份文件名；把它放在 `X-Data-Management-Token` 头里。
- 支持的动作：`EXPORT_DATABASE`、`EXPORT_BACKUP`、`CREATE_BACKUP`、`DOWNLOAD_BACKUP`、`DELETE_BACKUP`、`RESTORE_DATABASE`。
- **只运行一个服务实例**：校验值 / 锁定状态和授权都是实例本地的，不支持多副本或滚动重叠。持久化整个安全目录（含相邻的 `.initialized` 标记），保持仅属主权限，并在初始化和备份验证后设置 `STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED=true` 再重启。**永远不要通过删文件来恢复**：JSON 缺失但标记还在、或整个卷丢失而该开关已开启，都会故障关闭。

### 密钥输错 5 次会怎样

每次输错都会先和计数 / 锁定状态一起提交到属主专有的安全文件，再幂等地同步到只追加的 `starry_security` 审计库和持久的 SMTP 发件箱。事件包含发起账号的快照、是否 Root、直连远端地址、校验过的转发 IP 字面量、不可逆的 User-Agent / 请求 ID / 会话指纹、端点、第几次尝试和锁定状态；**即使 Root 没有邮箱也会入队**。如果配置了系统 SMTP，会优先发给事件里记录的 Root 邮箱，否则每次重试都会解析当前 Root 邮箱，所以之后补上邮箱就能放出待发的告警。发送失败会指数退避重试，并以 `alert_delivery=NOT_DELIVERED` 记录日志；标准 SMTP 是「至少一次」，收件后崩溃可能重复一封带相同 `X-Starry-Security-Event-Id` 的邮件。

**第 5 次失败会永久锁定数据管理，并让整个站点进入维护模式**，不等审计或 SMTP：只有静态控制台资源、`/system/status` 和健康检查可用；所有认证、管理、代理、支付调用都返回 `503 SYSTEM_MAINTENANCE`。Kubernetes 风格的就绪探针报告 `OUT_OF_SERVICE`，而存活探针保持 `UP`，避免自动重启绕过锁定。

维护模式下业务后台任务也**故障关闭**：账号 Token 刷新、账号池评估、禁用账号测试、支付对账、用量聚合 / 保留、性能指标写入、账号缓存刷新、注册令牌清理都会被跳过。锁定状态刷新、待处理安全事件持久化、SMTP 安全告警发件箱、健康检查和内存里的会话 / 限流清理继续运行。已经通过闸门的任务不会被强行中断；如果需要立刻终止已在进行的外部调用，请隔离部署。

### 恢复被锁定的数据管理

先在服务器上执行 `sudo starry-api info`，「数据管理安全密钥」一节会告诉你当前是已锁定，还是状态文件丢失 / 无效。

#### Docker Compose 部署

运行时镜像里在固定路径 `/app/bin/recover-data-management` 带有经过审查的恢复工具——**事故当天不要再去下载恢复脚本，更不要 `curl ... | sh`**。它要求交互式 TTY，在关闭回显的情况下读取原始 `dmsk-...` 密钥**两遍**，并且只把它写入仅属主可读的一次性文件 `/app/security/recovery.key`。它拒绝命令行或管道传入的密钥，所以密钥不会出现在 shell 历史、进程参数或 `docker inspect` 里。

先停止正常流量，确认只有**一个** `service` 容器在运行。对于装在 `/opt/starry-api` 的服务器：

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# 英文提示：把 --lang zh 换成 --lang en

docker compose -f docker-compose.artifact.yml restart service
```

**不要加 Compose 的 `-T`**。工具会先确认站点当前确实处于维护状态，才创建固定的恢复文件；它不会重启自己的容器，所以需要显式重启。源码部署用仓库里的 `docker-compose.yml`，命令去掉 `-f docker-compose.artifact.yml`。

服务启动完成后，验证公开状态和一次性文件是否被消耗：

```bash
docker compose -f docker-compose.artifact.yml exec service curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming && echo "恢复密钥文件已消耗"'
```

| 情形 | 结果 |
| --- | --- |
| 密钥匹配 | `"maintenance":false`，失败次数清零，记录 `RECOVERY_SUCCEEDED`，两个恢复路径都被消耗 |
| 格式正确但不匹配 | 记录 `RECOVERY_FAILED`，两个路径都被消耗，服务**仍处于维护**——用正确的密钥重新运行工具并再重启一次 |

**不要只凭一条审计记录就宣布恢复或消耗已完成。**如果截断、删除或同步一次性文件失败，那么即使匹配的状态提交和 `RECOVERY_SUCCEEDED` 记录已经存在，当前进程仍保持故障关闭。在**不打印内容**的情况下检查名称和权限：

```bash
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'ls -ld /app/security; ls -l /app/security/recovery.key /app/security/.recovery.key.consuming 2>/dev/null || true'
docker compose -f docker-compose.artifact.yml logs --tail=200 service
```

清理还没解决期间，**不要**再放另一个密钥，也不要手动删除任何一个文件。先修正安全卷的属主、权限、剩余空间或文件系统错误，再重启 `service`；启动时会重试清理 `/app/security/.recovery.key.consuming`。只有 `/system/status` 报告 `"maintenance":false` **并且**两个路径都不存在，恢复才算完成。如果进程在有效的恢复状态提交**之前**停止（而不是在清理期间），下次启动会把暂存文件恢复到 `/app/security/recovery.key` 并安全地重试事务。

#### 直接在宿主机用 jar 运行时的恢复

停止正常流量和所有服务副本，把原始密钥**单独**放在一个可写、仅属主可读（`chmod 600`）的普通文件里，把 `STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE` 设成它的绝对路径，只启动**一个**服务实例。验证成功后，服务会原子地暂存该文件、提交恢复后的状态，再截断、删除并同步暂存的一次性密钥。**在取消该环境变量之前**，确认有恢复审计事件、`"maintenance":false`，以及配置的文件和它的 `.<文件名>.consuming` 兄弟文件都已消失。格式正确但不匹配的密钥通常会被记录并消耗，同时维护保持。清理失败会保持故障关闭维护，并在启动时重试，处理方式同上。**不要用符号链接或只读的 secret 挂载**，也**绝对不要**在日常运行时留着明文恢复密钥的配置。

### 恢复流程

导入数据是被刻意保护的：控制台要求你键入 `RESTORE_DATABASE`。每次导入前，服务都会用 `pg_restore` 校验自定义归档，再检查归档的目录（TOC）。**只接受 PostgreSQL 17、数据库 / schema 为 `starry_api` 的归档**；恢复后，它们的 Flyway V1–V29 脚本和校验和必须与正在运行的应用**完全一致**，不可变的 Root 标记必须解析到唯一一个已启用的 `ADMIN` Root，核心应用表必须存在，且不含其他 schema 的对象。独立的 `starry_security` 审计 / 发件箱 schema 故意不在归档里，也不会被应用恢复影响。运行时镜像已带 PostgreSQL 17 客户端工具；直接用 jar 运行的宿主机需要在 `PATH` 里提供 PostgreSQL 17 的 `pg_dump` 和 `pg_restore`。

> **升级到带 V29（号源分级）的版本后，请立即做一次新备份。** V29 新增五张 `source_tier_*` 表并列入必需的核心表，迁移历史必须与正在运行的应用完全一致，所以 V28 及更早的备份升级之后不能再从控制台恢复。需要用旧备份时，先在隔离环境升级到同一版本，再从那里重新导出。

备份用 PostgreSQL **会话级共享咨询锁**，恢复用对应的**排他锁**。这些锁协调**所有服务实例**，而不只是一个 JVM 里的线程。获得排他锁后，恢复按这个顺序进行：

1. 撤销 `starry_api_runtime` 的数据库 `CONNECT` 权限，并终止现有 `starry_api` 后端连接。
2. 从已静默的数据库创建一份服务端**回滚备份**。
3. 对 `starry_api` schema 运行 `pg_restore --clean --if-exists --single-transaction --exit-on-error`。
4. 校验：schema `starry_api`、所有必需的核心表、精确的 Flyway V1–V29 历史和校验和、V5 熔断纪元的列 / 索引 / 设置、恰好一个与 `application_root_user_id` 匹配的已启用 `admin` Root；还要校验 `starry_security` 保留了只追加触发器、最小权限的运行时 ACL 和不含密钥的结构；然后在运行时访问**仍被禁用**时运行 `ANALYZE`。如果传入的恢复已提交，但校验或分析失败，会自动用恢复前的备份做一次干净恢复，并在重新开放访问前校验、分析回滚结果。
5. 在 `finally` 里恢复 `CONNECT`，然后清空本地会话和所有源自数据库的缓存——即使恢复在进入隔离后失败也是如此。
6. 让其他实例重连它们的 PostgreSQL 通知监听器；每次成功重连都会执行同样的本地状态整体失效。代数检查防止恢复前的异步缓存加载在之后发布过期状态。

⚠️ **恢复窗口是故意有破坏性的。**不要给这个部署路由新流量；预期在途的 API / 数据库请求会失败，管理员之后需要重新登录。即使恢复是事务性的，它依然是破坏性的：除了自动生成的回滚包，请再保留一份**离线备份**。

无论恢复成功还是失败，都应该用迁移 / 超级用户会话验证运行时访问和连接恢复：

```sql
SELECT pg_catalog.has_database_privilege('starry_api_runtime', 'starry_api', 'CONNECT') AS runtime_connect_restored;

SELECT pid, usename, application_name, state
FROM pg_catalog.pg_stat_activity
WHERE datname = 'starry_api'
ORDER BY pid;
```

`runtime_connect_restored` 必须是 `true`。如果主连接和兜底的 `finally` 连接都在数据库 / 网络故障期间失败了，请在重启应用实例之前，以数据库所有者或集群管理员身份恢复访问：

```sql
GRANT CONNECT ON DATABASE starry_api TO starry_api_runtime;
```

### 部署备注

- Docker 与预构建产物部署把备份挂载到 `./data/backups:/app/backups`；用 `STARRY_BACKUP_DIR` 可以覆盖备份目录。
- 用 `STARRY_MAX_IMPORT_BYTES`（字节）覆盖上传 / 导入的大小限制，默认 `268435456`（256 MiB）。
- **文件不在数据库里**：用户文档附件目录（`STARRY_DOCUMENTATION_ATTACHMENT_DIR`）和模型图标目录（`STARRY_MODEL_ICON_DIR`）需要**单独备份**。

### 命令行示例

```bash
# 1) 初始化（只返回一次密钥！）
curl -X POST http://127.0.0.1:8080/admin/data-transfer/security/init -H "Authorization: Bearer $ADMIN_TOKEN"

# 2) 用密钥换一个导出动作的令牌
curl -X POST http://127.0.0.1:8080/admin/data-transfer/security/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"securityKey":"<离线保存的密钥>","action":"EXPORT_DATABASE","target":null}'

# 3) 带令牌导出
curl -OJ http://127.0.0.1:8080/admin/data-transfer/database.dump \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "X-Data-Management-Token: $DATA_MANAGEMENT_TOKEN"

# 恢复：先换 RESTORE_DATABASE 的令牌，再带 confirm 导入
curl -X POST http://127.0.0.1:8080/admin/data-transfer/security/unlock \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"securityKey":"<离线保存的密钥>","action":"RESTORE_DATABASE","target":null}'

curl -X POST 'http://127.0.0.1:8080/admin/data-transfer/import?confirm=RESTORE_DATABASE' \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "X-Data-Management-Token: $DATA_MANAGEMENT_TOKEN" \
  -F "file=@/path/to/starry-api-database.dump"
```

从每次 unlock 响应的 `data.accessToken` 读出令牌放进 `DATA_MANAGEMENT_TOKEN`。**不要开启 shell 跟踪，也不要把安全密钥或授权令牌留在命令历史里。**

---

## 请求日志的聚合与保留

服务只把**已完成的 UTC 小时**聚合进 `request_usage_hourly`，再把受影响的增量应用到 `request_usage_daily`。`aggregation_watermarks` 记录进度，一个可配置的回看窗口会重建以吸收延迟提交，PostgreSQL 咨询锁防止并发的维护重复执行。每个批次单独提交，所以追赶工作不会长时间占着一个事务。

**默认行为**：

- 增量聚合在启动后延迟 30 秒开始，之后**每分钟**一次；每个事务推进 1 个新小时，一次运行最多 6 个事务；
- 维护任务每天 **03:25 UTC** 运行；
- 预创建未来 **12 个月**的 `request_logs` 月分区；
- 保留 **4 个整月**的原始分区（保证至少 90 天）；默认分区里偶发的过期旧行会在有界事务里清理；
- 小时聚合保留 **90 天**，在有界事务里清理，更长的区间由日聚合承担；
- API 计费的幂等事件保留 **400 天**，分批清理；不可变的**余额账本永久保留**。

原始 `request_logs` 分区只有在聚合水位完全追上之后才会被丢弃。长时间故障后，请在有界批次恢复期间监控水位和磁盘使用；积压追到最新的已完成小时后，保留清理会自动恢复。

配置项见 [配置参考：请求分析与保留](CONFIGURATION.md#请求分析与保留)。**请在生产环境保持聚合开启**——同一个工作线程还负责分区创建和保留清理。

---

## 配置在线充值

管理员登录控制台，打开「**系统设置 → 支付设置**」（[截图](CONSOLE_GUIDE.md#支付设置)）。

**必填的 EasyPay 字段**：

| 字段 | 说明 |
| --- | --- |
| 接口地址 | 网关地址，如 `https://pay.example.com`。以 `/submit.php`、`/mapi.php`、`/api.php` 结尾的值会被自动规范化 |
| 商户 PID | EasyPay 商户 ID |
| 商户密钥 | EasyPay 签名密钥。已经配置过时，编辑时留空即可保持不变 |
| 支付模式 | 「跳转收银台」用 `submit.php`；「二维码接口」用 `mapi.php` |

启用在线充值后，普通用户可以在「余额充值」页创建订单，通过返回的支付链接或二维码支付。**EasyPay 需要配置回调地址**：

```text
https://<你的域名>/payment/webhook/easypay
```

回调签名有效、且上游交易状态成功时，服务把订单标记为完成，并给用户加上余额；**重复的成功回调在第一次完成后会被忽略**。

**代理调用怎么扣钱**：按逐项 token 用量扣余额。没有生效的覆盖规则时，使用 [`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) 里经过验证的官方美元价格，乘以「系统设置 → 支付设置 → **模型官方价格计费倍率**」。默认 `1.0`，即按官方成本、不加价。快速模式保留额外的 `2×` 系数。

服务以内置目录启动，启动时以及之后每 10 分钟尝试一次**经校验和验证的刷新**；刷新失败**绝不会**清空或部分替换当前生效的目录。「系统设置 → 模型管理 → 价格倍率」里的精确 / 通配规则会用直接的官方价格倍率覆盖全局计费倍率。如果请求的模型**既没有官方价格、也没有显式的旧版固定价格**，它会在「模型 ID」页显示为「待定价」，计费请求会在预留余额和请求上游**之前**就返回 `503 MODEL_PRICE_UNAVAILABLE`——不会再用「估个中位数」或旧的兜底价去乱收费。

价格与倍率的完整规则见 [配置参考：支付与计费](CONFIGURATION.md#支付与计费)。
