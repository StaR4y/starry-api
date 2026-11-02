# API 参考

Starry API 对外提供两类接口：

| 类别 | 给谁用 | 怎么认证 | 例子 |
| --- | --- | --- | --- |
| **代理接口**（公开） | 你的客户端、程序 | **API Key**（`sk-...`） | `/v1/chat/completions`、`/v1/messages` |
| **控制台与管理接口** | 控制台前端、运维脚本 | **管理员 / 用户会话**（`adm-...`） | `/auth/login`、`/admin/accounts` |

默认的服务地址：

```text
http://127.0.0.1:8080
```

**交互式 OpenAPI 文档**（Swagger UI）：

```http
GET /docs
GET /v3/api-docs
```

> 想看界面操作而不是接口？请看 [控制台使用手册](CONSOLE_GUIDE.md)。想快速发出第一个请求？请看 [快速开始](QUICKSTART.md)。

**目录**：[认证](#认证) · [响应格式与状态码](#响应格式与状态码) · [代理接口](#代理接口) · [登录与账号](#登录与账号) · [上游账号管理](#上游账号管理) · [API Key](#api-key) · [模型目录](#模型目录管理) · [模型价格](#模型价格) · [模型映射](#模型映射) · [支付与余额](#支付与余额) · [Coding Plan 订阅](#coding-plan-订阅) · [工单](#工单) · [系统状态](#公开系统状态) · [数据传输](#postgresql-数据传输) · [用量日志与用户](#用量日志与登录账号)

---

## 认证

### 代理接口：API Key

除非开启了匿名代理模式（不建议），代理接口都需要客户端 API Key。任选一种请求头：

```http
Authorization: Bearer sk-...
X-Api-Key: sk-...
```

### 管理接口：本地会话

```http
Authorization: Bearer adm-...
```

用 `POST /auth/login` 换取 `adm-...` 令牌（见下文）。

### 旧版静态令牌

配置了 `STARRY_ADMIN_TOKEN` 时，仍支持旧版的静态管理员令牌：

```http
X-Admin-Token: change-me
Authorization: Bearer change-me
```

> 旧版令牌**不能**用于「数据管理」和「插件开发」，也不会被识别为 Root。

---

## 响应格式与状态码

### 管理接口的统一响应

成功：

```json
{
  "success": true,
  "data": {},
  "timestamp": "2026-06-06T12:00:00Z"
}
```

失败：

```json
{
  "success": false,
  "error": { "code": "UNAUTHORIZED", "message": "Missing API key" },
  "timestamp": "2026-06-06T12:00:00Z"
}
```

> 代理接口（`/v1/...`）尽量返回**客户端所用平台的原生响应结构**，所以 OpenAI 协议返回 OpenAI 的格式，Anthropic 协议返回 Anthropic 的格式。

### 常见状态码

| 状态码 | 含义 |
| --- | --- |
| `400` | 请求体无效，或缺少模型 / 消息；模型后缀引用的号池不存在或用不上（`SOURCE_TIER_UNKNOWN`、`SOURCE_TIER_NOT_OFFERED`），或用了固定的号池里没有任何号源支持的功能（`SOURCE_TIER_FEATURE_UNSUPPORTED`），见 [号源分级的错误码](#号源分级) |
| `401` | 缺少或无效的 API Key / 管理员令牌 |
| `402` | 余额或订阅额度不足（在转发到上游之前就拒绝） |
| `403` | 需要先改首次登录密码；管理员权限不足；API Key 的模型厂商限制；模型后缀固定了不允许客户端选择的号池（`SOURCE_TIER_NOT_SELECTABLE`） |
| `409` | 并发冲突（版本号过期）、幂等键冲突、计费待复核；续写的 Responses 链不在固定的号池里（`RESPONSE_OWNER_TIER_MISMATCH`），或这个节点展开不了、而这次能用的号池里只剩中转（`RESPONSE_CHAIN_NOT_PORTABLE`） |
| `413` | 请求体超过配置的上限 |
| `429` | API Key 限流、额度上限，或上游容量 / 限流。上游的 `429` 是**暂时**的，本身不会让账号被标记为额度耗尽 |
| `502` | 上游返回了不可恢复的错误 |
| `503` | 没有可用的上游账号（中转拒绝了自己的 Key、又没有别的账号或下一级可去时也是它：用户永远收不到中转的 `401` / `403`）；固定的号池暂时没有可用账号（`SOURCE_TIER_UNAVAILABLE`，带 `Retry-After`）；或数据管理安全锁定后整站的 `SYSTEM_MAINTENANCE` |

---

## 代理接口

### 健康检查

```http
GET /health
GET /v1/health
```

```json
{ "status": "ok", "service": "starryapi-service", "uptime_ms": 12345 }
```

### 列出模型

```http
GET /v1/models
GET /models
```

返回与 OpenAI 兼容的模型列表，并带有平台元数据。它保留一小组必要的路由别名，并从内置的 `model-price-repo` 快照派生默认的 OpenAI、Google / Gemini、Anthropic、DeepSeek ID。快照里的 ID 会在需要确定性路由时带上厂商前缀。

```json
{
  "object": "list",
  "data": [
    {
      "id": "gemini-2.5-pro",
      "object": "model",
      "created": 1770000000,
      "owned_by": "starryapi",
      "provider": "Google",
      "family": "Gemini 2.5",
      "targetModelId": "claude-sonnet-4.5",
      "name": "Gemini 2.5 Pro compatible alias",
      "supportedInputTypes": ["TEXT", "IMAGE"],
      "maxInputTokens": 200000,
      "maxOutputTokens": 64000,
      "context_length": 200000,
      "billingMode": "input_output",
      "inputTokensPer100Balance": 80000000,
      "outputTokensPer100Balance": 10000000,
      "totalPricePerMillionTokens": 5.625,
      "inputPricePerMillionTokens": 1.25,
      "outputPricePerMillionTokens": 10.0,
      "cacheWritePricePerMillionTokens": 1.25,
      "cacheReadPricePerMillionTokens": 0.125,
      "icon": null,
      "requestFormat": "OPENAI_CHAT_COMPLETIONS",
      "endpoint": "/v1/chat/completions"
    }
  ],
  "models": [ "…同 data，为兼容性保留的别名…" ]
}
```

- `models` 是 `data` 的兼容别名。管理员停用的条目在两个数组里都省略。顺序遵循管理员保存的全局显示顺序。
- 每个条目既给出「每 100 余额能买多少 token」，也给出精确的**每 100 万 token 有效价格**。默认情况下，服务从 [`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) 读取输入 / 输出 / 缓存写 / 缓存读的美元成本，再乘以管理员的「模型标价计费倍率」，默认 `1.0`，所以余额的数字扣费等于厂商标价（美元）。
- 逐项计费的模型会填充四个详细的 `*PricePerMillionTokens` 字段，同时把 `totalPricePerMillionTokens` 作为面向用户的「50% 输入 + 50% 输出」参考价。**实际计费仍按真实的 token 类别**。旧版固定总价的规则（包括通配规则）则给出真实的固定总价。
- **价格解析是确定的**：启用的规则按优先级升序，同优先级时按精确模型 → 目录系列 → 目录厂商 → 通配符的顺序；通配符按字面长度降序。请求别名的精确匹配先于目标的精确匹配；其余并列按模式和规则 ID 决定。**只有胜出的规则生效**，它的倍率直接替换全局倍率，所以 `0.1` 永远表示标价的 10%。已知前缀（`starry/`、`openai-compatible/`、`openai/`、`anthropic/`、`google/`、`gemini/`、`deepseek/`）在精确规则和价格查找时都会规范化。优先使用模型自己的精确 / 规范化厂商标价，否则沿着配置的目录目标走；**不会**使用系列 / 厂商中位数。
- 既没有经验证的价格、也没有显式旧版固定价的条目，会暴露 `billingMode: "unavailable"` 且不含数值价格；计费请求会在预留和派发之前返回 `503 MODEL_PRICE_UNAVAILABLE`。
- **号源分级**（见下文[号源分级](#号源分级)）：没有开启分级的厂商，列表**逐字节**不变。开启了分级的厂商，它的模型按「请求最先会被送去的号池」标价：标准价（上面价格解析的结果）乘该号池的价格系数，号池是**第一个有账号能服务这个模型的**。只有 Codex 账号能服务的模型，按第一个有 Codex 账号的号池标价，排在它前面的官方 Key 号池不起作用。该厂商打开「公开号池名称」时，条目另带 `sourceTiers` 数组，按级别从好到坏列出能服务这个模型的号池：`id`（号池标识）、`level`、`name`、`priceFactor`、`selectable` 和乘过系数的 `pricing`（结构同条目自己的价格字段）；不含账号、协议或中转信息。

**当前的厂商分组**：

| 厂商 | 系列 |
| --- | --- |
| Anthropic | Routing Preset、Claude 4、Claude 3、CodeWhisperer Internal |
| OpenAI | GPT-5、GPT-4、GPT-3.5、OpenAI Reasoning、OpenAI OSS、Audio、Image |
| Google | Gemini 3.7 / 3.6 / 3.5 / 3.1 / 3、Gemini 2.5 / 2.0 / 1.5、Gemma、Lyria、Google |
| DeepSeek | DeepSeek、DeepSeek V4 |

管理员可以通过 `/admin/models` 增加厂商和系列，所以客户端应使用 API 返回的 `provider` 和 `family`，不要把上表当成封闭的枚举。

### OpenAI Chat Completions

```http
POST /v1/chat/completions
POST /chat/completions
```

```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are concise."},
    {"role": "user", "content": "Say hello."}
  ],
  "temperature": 0.7,
  "top_p": 1,
  "max_tokens": 1024,
  "stream": false,
  "conversation_id": "optional-session-id"
}
```

| 字段 | 说明 |
| --- | --- |
| `model` | **必填**。模型映射会在上游路由之前应用。最长 256 字符 |
| `messages` | **必填**。支持 `system`、`user`、`assistant`、`tool` 角色 |
| `temperature`、`top_p`、`max_tokens` | 转发给上游的推理配置 |
| `stream` | 目前在上游完成**之后**才以 SSE 输出，真正的上游透传尚未实现 |
| `conversation_id`、`conversationId`、`thread_id`、`session_id` | 会话亲和的提示 |
| `tools` | 基本的 OpenAI 函数工具 schema 转换 |
| `thinking` | 作为额外的上游模型请求字段转发 |

DeepSeek 目录里的 `deepseek-chat`、`deepseek-reasoner`、`deepseek-v4-flash`、`deepseek-v4-pro` 都通过独立的 DeepSeek 账号池支持 Chat Completions。官方路由请用裸名；`starry/` 也接受，并会在查找前去掉。厂商级的 API Key 会拒绝不在已启用目录里的、仅供路由的别名。`openai-compatible/` 只有在该带前缀的模型 ID 已登记到 Key 的厂商目录之后，才会选中通用的第三方 OpenAI 兼容池。

### OpenAI Responses

```http
POST /v1/responses
POST /responses
```

```json
{
  "model": "gpt-4o",
  "input": "Say hello.",
  "instructions": "You are concise.",
  "temperature": 0.7,
  "top_p": 1,
  "max_output_tokens": 1024,
  "stream": false
}
```

`platform/openai/OpenAiRequestAdapter` 会在路由前把 Responses 的输入、函数调用和函数输出转换成内部的对话结构。对 DeepSeek，Responses 目前**只支持 `deepseek-v4-flash`**；用这个端点请求 `deepseek-v4-pro` 会返回 `400`，所以 Pro 的调用方必须使用 Chat Completions。

请求没写 `max_output_tokens`（也没写 `max_completion_tokens`、`max_tokens`）时，网关给它加上一个默认的输出上限：默认 16,000 个令牌，可用 `STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS` 调整，并且不超过这个模型自己的最大输出，也就是模型列表与厂商标价目录中较小的那个：

- 大多数 Claude 和 GPT 别名得到默认的 16,000。
- 价格目录列得比默认值小的模型得到那个数，例如 `gpt-4` 和 `gpt-4o-2024-05-13` 是 4,096，`gemini-2.0-flash` 是 8,192。`deepseek-v4-*` 在随包的价格目录里也是 8,192，但默认部署会刷新价格目录，刷新后的目录写 393,216，所以它们得到默认的 16,000。
- `gpt-4o` 一类价格目录列 16,384 的，只有把默认值调到 16,384 以上才被它封住，默认设置下是 16,000。
- 模型目录没有列出的模型、管理员自己加的模型、用 `openai-compatible/` 前缀写的模型和管理员改成指向自己厂商的内置模型是 4,096。

同一个数用于预留和发给原生号源（Kiro、官方 OpenAI Key、Anthropic Key）的请求体；Codex 从不写这个字段，Codex ChatGPT 账号照旧去掉它。

中转收到的不是这个数：映射写了「最大输出」（`maxOutputTokens`）的中转收到映射写的数，但不超过上面这个数；没写的收到 4,096。中转用量按它收到的数钳制。

写了上限的请求用它自己的值；Chat、Messages 和 Gemini 入口没写上限时仍是 4,096。详见[运维手册](OPERATIONS.md#跨协议中转)和[配置](CONFIGURATION.md)。

### Anthropic Messages

```http
POST /v1/messages
POST /messages
```

```json
{
  "model": "claude-sonnet-4.5",
  "system": "You are concise.",
  "messages": [
    { "role": "user", "content": [{"type": "text", "text": "Say hello."}] }
  ],
  "max_tokens": 1024,
  "stream": false
}
```

`AnthropicRequestAdapter` 会把文本块、工具调用、工具结果和工具声明转换成内部的对话结构。

Anthropic Messages 请求和 `anthropic/` 模型 ID 会**先**选择原生的 `provider=Anthropic`、`authMethod=api-key` 账号，并使用 `AnthropicUpstreamClient`。这部分账号不可用时，路由回退到兼容的 Kiro / AWS 账号。OpenAI 兼容端点上的裸 Claude 模型 ID 继续走 Kiro 路由，除非某条模型映射选择了 `anthropic/` 目标。

### Gemini GenerateContent

```http
POST /v1beta/models/{model}:generateContent
POST /v1/models/{model}:generateContent
POST /models/{model}:generateContent
```

```json
{
  "systemInstruction": { "parts": [{"text": "You are concise."}] },
  "contents": [ { "role": "user", "parts": [{"text": "Say hello."}] } ],
  "generationConfig": { "temperature": 0.7, "topP": 1, "maxOutputTokens": 1024 }
}
```

响应形状：

```json
{
  "candidates": [
    { "content": {"role": "model", "parts": [{"text": "Hello."}]}, "finishReason": "STOP", "index": 0 }
  ],
  "usageMetadata": { "promptTokenCount": 10, "candidatesTokenCount": 20, "totalTokenCount": 30 },
  "modelVersion": "gemini-2.5-pro",
  "responseId": "gen-..."
}
```

`GeminiRequestAdapter` 支持文本 part、`systemInstruction`、`generationConfig`、`functionDeclarations`、`functionCall` 和 `functionResponse`。

> **Gemini 目前只是入站兼容层**：内置的 Gemini 目录别名在构造 Kiro payload 时解析成 Kiro 兼容的目标模型。直连 Google / Gemini API Key 的上游派发、直连账号测试和 `streamGenerateContent` **都没有实现**。例外是配置了号源分级的 `google` 和 `anthropic` 厂商：登记在那里的 OpenAI 格式中转可以服务 `:generateContent` 请求（试用中，见「号源分级」的跨协议中转；`countTokens` 和流式在以后的阶段）。

---

## 登录与账号

### 首次启动的 admin

首次启动时，服务创建：

```text
username: admin
password: <首次启动日志里只打印一次的随机密码>
```

这个默认 / 初始管理员是**应用层面的信任根（Root）**。它的登录密码或会话一旦泄露，整个 Starry API 服务都应视为已被攻破。下文的数据管理安全密钥是高风险数据库操作的第二重确认，**不是** Root 被攻破之后仍然成立的安全边界。启动日志里的初始密码**只出现这一次**，以便完成首次登录；请保护好这份输出并立刻修改密码。**登录密码和数据管理密钥绝不应出现在之后的日志、工单、审计备注或告警消息里。**

### 登录

```http
POST /auth/login
Content-Type: application/json
```

```json
{ "username": "admin", "password": "startup-random-password" }
```

返回的 `data.accessToken` 就是 `adm-...` 令牌，`data.user` 是当前用户。

### 当前账号与权限

```http
GET /auth/me
Authorization: Bearer adm-...
```

返回当前登录账号，包含最新的 `balance`。

`GET /auth/permissions` 返回 `data: {"root": true|false}`，带 `Cache-Control: no-store`。Root 由不可变的 `application_root_user_id` 和账号当前的「已启用 / 管理员 / 密码已修改」状态解析而来，**从不**依据用户名或浏览器存储。普通管理员、用户和旧版静态管理员令牌得到 `root: false`。控制台用这个响应来隐藏和保护「数据管理」与「插件开发」；服务端会在每个请求上独立校验它们的权限。

### 首次登录必须改密码

如果 `user.firstLogin=true`，**只允许改密码**，其他管理接口返回 `403 FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED`：

```http
POST /auth/password
Authorization: Bearer adm-...
Content-Type: application/json
```

```json
{ "oldPassword": "startup-random-password", "newPassword": "new-strong-password" }
```

- 改密码成功会撤销该账号的**所有**控制台会话（包括当前这个），请重新登录。
- 新注册、手动指定的初始密码和替换密码都必须是 **12–128 个字符**；登录仍接受较短的历史密码，以便把它们轮换掉。
- 管理员新建的用户第一次登录同样会被要求改密码。

### 公开注册

公开的管理员注册**默认关闭**。客户端不需要令牌就能查看公开的注册策略：

```http
GET /auth/registration-settings
```

当 `registrationEnabled=true` 时，这样注册：

```http
POST /auth/register
Content-Type: application/json
```

```json
{
  "username": "user",
  "email": "user@example.com",
  "displayName": "User",
  "password": "strong-password",
  "verifyCode": "123456",
  "inviteCode": "INV-XXXX-XXXX-XXXX"
}
```

当 `emailVerifyEnabled=true` 时，先请求一个注册邮箱验证码：

```http
POST /auth/send-verify-code
Content-Type: application/json
```

```json
{ "email": "user@example.com" }
```

管理员通过 `GET/PATCH /admin/users/registration-settings` 配置注册和 SMTP。SMTP 字段使用下面这些设置键（小写加下划线）：`email_verify_enabled`、`smtp_host`、`smtp_port`、`smtp_username`、`smtp_password`、`smtp_from_email`、`smtp_from_name`、`smtp_use_tls`、`smtp_security`。API 的 JSON 使用 camelCase 字段名，响应里只暴露 `smtpPasswordConfigured`，**绝不返回**已保存的密码。`smtpSecurity` 取 `auto`、`ssl`、`starttls`、`none`；腾讯企业邮用 `smtp.exmail.qq.com:465` + `ssl`，或 587 端口 + `starttls`。

当 `inviteCodeEnabled=true` 时，管理员可以从 `/admin/users/invite-codes` 生成一次性邀请码。有效的 `inviteCode` 允许用户直接注册，**即使公开的 `registrationEnabled=false`**；邀请码注册会跳过邮箱验证，并原子地消耗该码。

---

## 上游账号管理

上游账号池管理需要 `ADMIN` 会话。`USER` 账号会收到 `403 ADMIN_ROLE_REQUIRED`。

```http
GET    /admin/accounts
POST   /admin/accounts
POST   /admin/accounts/chatgpt-oauth/start
POST   /admin/accounts/chatgpt-oauth/{loginId}/poll
DELETE /admin/accounts/chatgpt-oauth/{loginId}
GET    /admin/accounts/settings
PATCH  /admin/accounts/settings
GET    /admin/accounts/pool-status
PATCH  /admin/accounts/{accountId}
GET    /admin/accounts/{accountId}/openai-quota
DELETE /admin/accounts/{accountId}
DELETE /admin/accounts
POST   /admin/accounts/import
POST   /admin/accounts/test
GET    /admin/accounts/test-models
POST   /admin/accounts/disabled-scheduled-test/run
POST   /admin/accounts/{accountId}/suspend
POST   /admin/accounts/{accountId}/reset
GET    /admin/accounts/{accountId}/allocation-policy
PATCH  /admin/accounts/{accountId}/allocation-policy
```

### 创建账号

```json
{
  "accountId": "optional-stable-id",
  "email": "user@example.com",
  "accessToken": "kiro-access-token",
  "refreshToken": "optional-refresh-token",
  "clientId": "optional-client-id",
  "clientSecret": "optional-client-secret",
  "region": "us-east-1",
  "authMethod": "idc",
  "provider": "BuilderID",
  "baseUrl": null,
  "upstreamAccountId": null,
  "profileArn": "arn:aws:codewhisperer:us-east-1:...",
  "machineId": "optional-machine-id",
  "proxyUrl": "http://user:pass@host:port",
  "quotaLimit": 1000000,
  "quota5hLimit": 200000,
  "quota7dLimit": 800000
}
```

基本的 Kiro 上游账号只有 `accessToken` 必填。**各类账号的最小请求体：**

| 账号类型 | 请求体要点 |
| --- | --- |
| 官方 OpenAI API Key | `{"accountId":"openai-main","email":"…","accessToken":"sk-...","authMethod":"api-key","provider":"OpenAI","baseUrl":"https://api.openai.com/v1"}` |
| 官方 DeepSeek | `provider=DeepSeek`；服务把 `authMethod` 规范成 `api-key`，缺省 `baseUrl` 为 `https://api.deepseek.com/v1` |
| 原生 Anthropic | `provider=Anthropic`、`authMethod=api-key`；缺省 `baseUrl` 为 `https://api.anthropic.com/v1` |
| 第三方 OpenAI 兼容 | `provider=OpenAI-compatible`、`authMethod=api-key`，站点的 Key 作 `accessToken`，站点 API 根作 `baseUrl` |
| Codex OAuth Token | `authMethod=codex`、`provider=OpenAI`、`accessToken`，可选 `refreshToken`、`idToken`、`upstreamAccountId`；缺省 `baseUrl` 为 `https://chatgpt.com/backend-api/codex` |

要点：

- **只有 `baseUrl` 主机为 `api.openai.com` 的才算「官方 OpenAI API Key」账号**，才服务普通 `gpt-*` 请求。
- `baseUrl` 主机为 `api.deepseek.com` 的账号为了兼容也被识别为 DeepSeek（即使之前标成 `OpenAI-compatible`）。官方 DeepSeek 模型请求只选这个池；普通 OpenAI / Codex 选择排除它；它在自己的池不可用时**绝不**跨厂商回退。
- 通用的 OpenAI 兼容账号，`baseUrl` 可以是站点根（如 `https://relay.example.com`）或带版本的 API 根（如 `https://openrouter.ai/api/v1`）。路径不是以 API 版本段结尾时，默认调用 `/v1/chat/completions` 和 `/v1/responses`。
- 第三方模型 ID 通过**模型映射**路由，目标写成 `openai-compatible/<真实模型名>`。创建映射前，先把源模型 ID 和带前缀的目标 ID 作为已启用的 `/admin/models` 条目，登记到**同一个厂商**下，再为该厂商创建 API Key。例如把 `llama-3.3-70b` 映射到 `openai-compatible/meta-llama/llama-3.3-70b-instruct`；Starry API 用这个前缀选中通用 OpenAI 兼容账号，并把去掉前缀的模型 ID 发给上游。厂商级 Key 拒绝未知模型和跨厂商映射。

### ChatGPT 浏览器授权（Codex）

控制台可以通过 OpenAI 的**设备授权流程**拿到 Codex 凭据，管理员不需要提取或粘贴 Token。用一个空对象开始新的登录，或传入已有的 Codex 账号 ID 来重新授权：

```http
POST /admin/accounts/chatgpt-oauth/start
Content-Type: application/json

{"accountId": null}
```

前提：个人账号需要在 ChatGPT 安全设置里先**开启设备码登录**，工作区账号则由管理员在工作区权限里开启。如果 OpenAI 报告设备授权已禁用，开启后重新开始登录以获得新码。

响应包含服务端生成的 `loginId`、固定的 OpenAI 验证 URL、一次性的 `userCode`、`expiresAt` 和建议的轮询间隔。在管理员的浏览器里打开验证 URL、输入验证码，然后不断调用 `POST /admin/accounts/chatgpt-oauth/{loginId}/poll`，直到状态是 `SUCCEEDED`、`FAILED`、`EXPIRED` 或 `CANCELLED`。`PENDING` 响应带有 `retryAfterSeconds`，客户端必须遵守。删除这个登录资源即可取消一个进行中的流程。

- 登录会话只保存在有界的内存缓存里，**15 分钟**过期，绑定创建它的管理员，应用重启后丢失。每个管理员最多同时 3 个等待或完成中的登录。
- Access、Refresh、ID Token **从不**由这些端点返回；成功的凭据直接写入已有的账号记录。
- 重新授权选中的账号时，会先校验 ChatGPT 的用户 / 工作区身份再替换凭据。身份数据不足的旧导入账号，必须作为新的浏览器授权添加，而不是被覆盖。
- 该流程只授权既有的 Codex 上游 `https://chatgpt.com/backend-api/codex`，**不会**代理 ChatGPT 网页应用。OpenAI 可能对特定账号或地区禁用设备授权。运维要对使用的授权账号及遵守 OpenAI 的条款和政策负责。手动导入 Codex Token 仍作为恢复手段保留。把浏览器授权的账号保留在可信的私有部署上；公开、多租户、CI 等不可信的程序化负载应改用 OpenAI Platform API Key 账号。

### 导入

`/admin/accounts/import` 接受：

- Kiro Account Manager 的 JSON 导出；
- 单个账号对象、账号数组，或 `{ "accounts": [...] }`；
- 多个导入文档：`{ "documents": [...] }`，每个文档可以是任意受支持的形状；
- Codex Token JSON：`type: "codex"`、`access_token`、`refresh_token`、`id_token`；
- OpenAI 兼容配置块：`openai-compatibility[].api-key-entries[]`，含 `api-key`、可选 `base-url`、可选 `proxy-url`。

创建 / 导入时，账号身份按**规范化后的邮箱**匹配：`upsert=false` 在邮箱已存在时跳过该导入项；`upsert=true` 更新那条已有的邮箱记录。导入的 `accountId` 只当作外部 / 来源标识：如果请求的 `accountId` 与已有行冲突但邮箱不同，服务会用生成的内部 `accountId` 创建一个新账号。控制台可以上传多个 `.json` 文件，在浏览器里解析，每个文件提交一次导入请求，全部完成后汇总结果。

账号额度跟踪支持总额度，外加 5 小时和 7 天的滚动窗口。`quotaUsed`、`quota5hUsed`、`quota7dUsed` 随成功的上游 token 用量增加；每个滚动窗口重新开始时，`quota5hResetAt` 和 `quota7dResetAt` 往前推进。控制台把这三项都渲染成「剩余额度」进度条。

### 账号分配策略

用 `GET /admin/accounts/{accountId}/allocation-policy` 读取账号的分配策略和实时准入快照。用 `PATCH` 更新一项或多项限额；省略的字段保持当前值，每次成功更新会让 `policyVersion` 加一：

```json
{
  "allocationPriority": 50,
  "safeConcurrency": 6,
  "safeRpm": 120,
  "burstCapacity": 8,
  "maxSessions": 12,
  "sessionIdleSeconds": 900,
  "maxInflightTokens": 2000000,
  "cooldownSeconds": 60,
  "tlsProfile": "chrome136"
}
```

校验范围：优先级 `1..10000`、并发 `0..10000`、RPM `0..1000000`、突发 `0..100000`、会话数 `0..100000`、会话窗口 `60..86400` 秒、在途 token `1..1000000000`、冷却 `0..86400` 秒。并发 / RPM / 会话数的 0 表示不限制；RPM 为 0 时突发桶关闭；冷却 0 表示自动。`allocationPriority` 越小越优先。会话与 TLS 指纹仅对 Anthropic 生效；TLS 接受空字符串（关闭）、`chrome136`、`safari18_4`。

响应还包含 `lifecycleState`、`stateVersion`、`warmupStage`、`cooldownUntil`、`recentErrorRate`、`recentLatencyMs`（整次尝试）、`activeConcurrency`、token 预留和 `updatedAt`。新建官方 OpenAI / Anthropic API 默认 `14/0/0/0`，非官方 API 默认 `0/0/0/0`，Codex `4/60/4/0`，Kiro `2/30/2/0`。已有账号保留策略。默认优先级 100、窗口 600 秒、在途 token 1,000,000。详见 [路由与限流](ACCOUNT_EGRESS.md)。

新建与 PATCH 账号支持 `proxyChannelId` 和嵌套 `allocationPolicy`，账号与策略在同一事务保存。PATCH 使用 `clearProxyChannel:true` 清除节点，`proxyUrl:""` 清除旧 URL。节点绑定优先于旧 URL。

管理员节点接口：`GET/POST /admin/proxy-channels`，`PATCH/DELETE /admin/proxy-channels/{id}`，`POST /admin/proxy-channels/{id}/test`，`POST /admin/proxy-channels/subscriptions`（`name` 与 `url` / `yaml` 二选一），`POST /admin/proxy-channels/subscriptions/{id}/sync`。目录响应包含节点、来源、组件启用状态和支持的 TLS profile，不返回订阅 URL 或节点凭据。

### 路由策略

账号路由策略作为 `routingPolicy` 保存在同一个设置文档里。默认让 Codex OAuth 账号与官方 OpenAI API Key 分池、让普通 GPT 的 Chat / Responses 只走官方 OpenAI API Key 账号、只在上游成功后绑定显式会话、没有会话标识时不按 API Key 绑定。Codex 在派发后最多重试一个账号（`codexMaxRetryAccounts=1`）。每个账号的并发和 RPM 不变。

```json
{
  "routingPolicy": {
    "isolateCodexPool": true,
    "gptUsesCodexAccounts": false,
    "bindSessionOnAdmission": false,
    "bindSessionByApiKey": false,
    "codexMaxRetryAccounts": 1,
    "openAiMaxRetryAccounts": 3,
    "deepSeekMaxRetryAccounts": 3,
    "anthropicMaxRetryAccounts": 2,
    "kiroMaxRetryAccounts": 2
  }
}
```

各 `*MaxRetryAccounts` 取 1–5，限制**调用已经派发之后**最多还能尝试多少个不同的上游账号；准入容量拒绝仍会尝试另一个账号，且**不计为重试**。要走官方 API Key 路由，请保持 `gptUsesCodexAccounts` 的默认值 `false`；只有普通 GPT 流量应该消耗 Codex 账号时才设成 `true`。

### 号源分级

**号源分级**把一个厂商（Anthropic、OpenAI、Google、DeepSeek 或自定义厂商）的账号分成有序的**号池**（级别，1 最好），每个号池有名称和价格系数。新会话从 1 级开始；一个请求里 1 级的账号**全部**不可用（停用、挂起、额度耗尽、冷却，也包括并发 / 限速 / 在途 token 已满的繁忙）才会用到 2 级。单价是标准价（唯一命中规则的价格，或全局倍率价，快速模式再 ×2）乘**实际服务这个请求的号池**的系数，所以用户从不会为没有服务过他的号池付费。会话真实降级后会停在降级后的号池，直到空闲（可按厂商关闭）；这个状态只记在处理该会话的节点的内存里，多节点部署且负载均衡不把同一个会话固定到同一个节点时，只部分成立，见[运维手册：号源分级](OPERATIONS.md#号源分级)。没有配置的厂商，路由、价格、结算和响应都与分级出现之前完全相同。

这些端点需要管理员会话（`/me` 除外，它给 API Key 表单用，任何登录用户都能调用）：

```http
GET /admin/source-tiers
PUT /admin/source-tiers/{vendor}
POST /admin/source-tiers/{vendor}/members
POST /admin/source-tiers/{vendor}/members/{accountId}/verify
GET /admin/source-tiers/{vendor}/members/{accountId}/upstream-models
GET /admin/source-tiers/status
GET /admin/source-tiers/evidence?days={days}
GET /admin/source-tiers/placements
GET /admin/source-tiers/me?provider={provider}
```

- `GET /admin/source-tiers` 返回 `configVersion`（所有厂商共用，各节点据此刷新）、`runtime`（这个版本能运行什么）和每个厂商的已保存配置。
  - `runtime.nativeTiers` 为真表示这个版本能按号池选号：厂商的路由本来就能用的账号（官方 Key、Kiro、Claude OAuth、Codex 等）可以分级，`enabled` 能打开；为假时 `enabled` 打不开（保存返回 `409 SOURCE_TIER_RUNTIME_UNSUPPORTED`，`data` 列出被拒的设置）。
  - `unifyEndpoints` 为真表示这个版本能运行 Anthropic 的「全端点统一」开关（见下文「全端点统一」）。
  - `xpMembers` 为真表示这个版本接受跨协议成员行（把一把 OpenAI 兼容 Key 登记为 OpenAI 格式中转，成员行的 `protocol` 为 `OPENAI_CHAT`）。登记后这把 Key 默认是专用的（`exclusive: true`），不再服务 GLM 等未分级的兼容模型请求，也不再作为 Responses 链的 owner；把 `exclusive` 设为 `false` 它才继续服务这些请求。
  - `pairs` 列出每个（入口，厂商）对的状态：
    - 九个对现在是 `EXPERIMENTAL`（试用中：门控只能设为 `KEYS`，只对名单里的 Key 把请求派给中转，设为 `ON` 返回 `409 SOURCE_PAIR_GATE_FORBIDDEN`）：Chat、Messages 和 Responses 入口对 anthropic、openai 厂商的六个，Chat 入口对 google 厂商的一个，Gemini 入口对 anthropic、google 厂商的两个。
    - 其余（Gemini 入口对 openai，Messages 和 Responses 入口对 google）是 `NOT_OFFERED`，把门控设为 `KEYS` 或 `ON` 返回 `400 SOURCE_PAIR_NOT_OFFERED`。
  - `selectors` 表示客户端能自己指定号池（`模型@标识` 写法、`X-Starry-Tier` 请求头、API Key 的号源偏好，见下文「指定号池」）。它不拒绝任何设置，只决定控制台是否提供复制「模型@标识」的按钮，以及 API Key 表单里的「号源偏好」。
  - 厂商里的 `tierAccounts` 是每个号池已收下的启用账号数。
- `PUT /admin/source-tiers/{vendor}` **整体替换**一个厂商的开关、号池、成员行和门控，请求带 `expectedVersion`（从未配置过的厂商为 `0`）；期间被别人改过返回 `409 SOURCE_TIER_VERSION_CONFLICT`（`data.currentVersion` 是现在的版本）。号池的 `tierId` 是小写字母、数字和连字符（至多 32 位），不能是 `auto`、`L2` 或纯数字；`priceFactor` 大于 0、至多 1000、至多 6 位小数，默认 1；`memberCategories` 取 `OFFICIAL_API_KEY`、`COMPATIBLE_API_KEY`、`CODEX`、`CLAUDE_OAUTH`、`KIRO`，一个类别只能属于一个号池。账号归级的规则：有成员行取其号池；否则取列出该账号类别的号池（类别只匹配本厂商的账号）；都没有则落入**最低一级**。
- `POST /admin/source-tiers/{vendor}/members` 只增改、移除成员行（账号列表批量设置号池用它），带同样的 `expectedVersion`，结果必须是合法的整体配置。
- `POST /admin/source-tiers/{vendor}/members/{accountId}/verify` **验证**一个跨协议成员（中转）的映射：用请求真正使用的客户端和解析器向中转发探针，全部通过的映射才算「已验证」，未验证的映射不会进入任何请求的计划。
  - 请求体可省略，也可以带 `{"modelIds":["claude-sonnet-4.5"],"cacheProbe":true}`：`modelIds` 指定要验证的映射（至多 8 个；省略时验证已启用的映射，它们多于 8 个就必须指定），`cacheProbe` 额外跑缓存探针（默认 `false`）。
  - 每个映射依次发这些探针，结果在 `models[].probes[]`（`status` 是 `PASSED`、`FAILED` 或 `SKIPPED`，`message` 只有网关自己的话，失败时是中转的状态和类型，如 `Source error 404 (model_not_found)`，从不含中转的原文）：
    - `basic` 是 `max_tokens` 16 的短问题（不流式，要求有 `finish_reason` 和用量；GPT-5 一类用 `max_completion_tokens` 的模型留 512 个令牌给推理）。
    - `tool_call` 是 `tool_choice: auto` 加明确指令的一次函数调用（不用 `required`，Claude 开启思考时会拒绝它）。
    - `tool_loop` 是函数调用、结果、再回答的两轮循环。
    - `stream`（成员用上游流式时）是带 `include_usage` 的事件流。
    - `vision`（成员支持图片时）是一像素 PNG。
    - `cache_control`（成员透传 `cache_control` 时）是带 `{type: ephemeral}` 标记的短问题（Claude Code 的请求就带着它，拒绝标记的中转会因此验证不通过）。
  - `basic` 不通过时其余探针不发（`SKIPPED`），以免白花钱。探针请求和真实请求走同一条白名单、规范化和严格检查，工具探针按成员的流式设置读答案。
  - 全部必需的探针通过后，写入 `verified_at` 和 `verified_hash`（成员的设置、目录模型、中转给它的名字、账号的 provider 和 base URL 的指纹）。映射在这些没变之前都算已验证，账号改了 base URL 或映射改了名字，验证就自动失效。有任何一个探针失败，就把原来的验证收回，因为中转已经不是验证时的样子。探针要几秒钟，期间配置若被改动（指纹变了），那个映射不写入，结果里标 `stale: true`。
  - 缓存探针不影响是否通过，只给建议：把约 1,200 个令牌的同一个提示问两次（成员透传 `cache_control` 时提示带标记，只认标记的中转也看得到缓存），从两次的 `prompt_tokens` 和缓存字段判断中转的 prompt 是否已经含缓存读 / 写。`cache.readInPrompt` / `writeInPrompt` 是 `true`、`false` 或 `null`（数字不足以判断，`cache.message` 说明原因），`readEvidence` / `writeEvidence` 是依据的数字。
  - 一次验证整体最长 4 分钟：回答不超过 128 个令牌的探针（缓存探针的每一次调用也算）各给 30 秒，其余（如给推理留 512 个令牌的 `basic`）60 秒。剩余时间少于该探针的限时就以剩余时间为限，不足 1 秒的探针不再发出，标为 `SKIPPED`（`Not asked: the verification ran out of time`，不算通过）。所以一个每次都拖到限时才回答的中转不会让一次请求占着账号的验证名额近一小时。
  - 探针由运营者付费，所以同一个账号 30 秒只能验证一次，不能同时验证两次（`429 SOURCE_VERIFY_RATE_LIMITED`，`data.retryAfterSeconds`；限流是每个节点各自的）；账号不是这个厂商的中转返回 `404 SOURCE_TIER_MEMBER_NOT_FOUND`。
- **「不含」口径需要证据**：成员的 `usageCacheReadInPrompt` / `usageCacheWriteInPrompt` 设为 `false`（中转的 `prompt_tokens` **不含**缓存读 / 写，计费时把它们加到输入上）只有缓存探针在这个中转、这个名字上得出 `false` 才能保存，否则 `400 SOURCE_TIER_CACHE_EVIDENCE_REQUIRED`；设成 `true`（含）不需要证据，因为设错了只会少收。
  - 证据逐个启用的映射检查，随验证存进映射的 `verify_summary`，在保存时保留；改了账号的 base URL、中转的名字或上游流式设置，证据就不再成立。
  - 只有这次保存**改动了**的映射才会被要求出示证据（两个缓存口径开关、上游流式、中转给模型的名字之一变了，或是新增的映射）。这个厂商原样保留的映射不再被问，所以账号后来换了 base URL，也不会让这个厂商此后的每次保存（号池系数、别的账号的指定）都被拒绝；那个映射的验证已经随指纹失效，不会收请求，要重新验证才行，而重新验证写入结果时仍要过证据这一关。
  - 已保存的证据在**同一个**中转、同一个名字上证明设置是错的（例如设成「不含」而证据说「含」）时，原样保存同样被拒绝。
  - 所以顺序是：先用 `true` 登记，跑带缓存探针的验证，再按建议改成 `false` 保存（这个改动会让验证失效，再验证一次）。
- `GET /admin/source-tiers/{vendor}/members/{accountId}/upstream-models` 经账号的出口向中转取 `{base}/models`，给映射表预填 wire 名和上下文窗口：`{"models":[{"id":"relay/sonnet","contextLength":200000}],"truncated":false}`。只列映射能用的名字（每个一次，至多 2000 个，`truncated` 表示中转列得更多），`contextLength` 取中转写的窗口（`context_length`、`context_window`、`max_model_len`、`max_input_tokens` 等，没写就缺省）。账号不必已经是成员，只要是 OpenAI 兼容 Key；同一个账号 5 秒只能取一次。中转不返回模型列表时是 `502 SOURCE_UPSTREAM_MODELS_FAILED`，信息里只有状态，没有中转的原文。
- `GET /admin/source-tiers/status` 返回**应答节点**此刻的状态和最近 15 分钟的服务情况：

```json
{
  "success": true,
  "data": {
    "at": "2026-10-25T08:00:00Z", "configVersion": 7, "windowMinutes": 15,
    "vendors": [
      {
        "vendor": "anthropic", "label": "Anthropic", "enabled": true, "serving": true,
        "autoServed": 100, "degraded": 30,
        "tiers": [
          { "tierId": "official", "level": 1, "name": "官方", "enabled": true, "serves": true,
            "accounts": { "disabled": 0, "unavailable": 1, "resting": 1, "busy": 1, "ready": 2 },
            "served": 76, "servedPinned": 6, "failed": 4 }
        ]
      }
    ]
  }
}
```

  - `accounts` 把号池收下的每个账号只计入第一个让它不能服务的层：`disabled`（已停用）、`unavailable`（被挂起或额度耗尽）、`resting`（分配生命周期里冷却或隔离中）、`busy`（并发或在途 token 已到上限；每分钟请求数、突发和会话数的限制，以及按模型的冷却，只在请求到来时才知道，不在这里计）、`ready`。数字是**这个节点**的（账号缓存最多晚 30 秒）。
  - 号池里登记的中转（跨协议成员）和别的账号一样按层计入。中转多一层 `unopened`：没有任何入口的门控对它开放（对这个版本提供的对、状态不是 `OFF`），或没有已验证的映射，所以请求不会派给它；检查在账号自己的各层之前，因为它只有管理员动手才会变。只有号池里有中转时 `accounts` 才有 `unopened`。
  - `serves`：这个节点现在会从该号池服务（厂商已启用、版本能运行、号池已启用、至少一个 `ready` 账号）；`serving` 是厂商一级的前两个条件。
  - `served`、`servedPinned`、`failed` 取自请求日志，覆盖**所有节点**：最近 15 分钟里该号池服务成功的尝试数（其中客户端指定号池的）和失败的尝试数。一个请求在 1 级失败又由 2 级服务，算 1 级一次失败、2 级一次服务。`autoServed` 是由网关自己选号池服务的请求数，`degraded` 是其中由低于「已启用的最好一级」服务的数；降级率 = `degraded / autoServed`。
  - 厂商登记了中转时，厂商项多一个 `xp`；没有登记中转的厂商没有 `xp`。
    - `relays` 是每个中转所在的号池（`tierId`）、`layer`（`disabled`、`unopened`、`unavailable`、`resting`、`busy` 或 `ready`）、已启用的映射数 `mappings` 和其中已验证的 `verified`，以及它最近 15 分钟的 `figures`。
    - `pairs` 是这个版本提供的、或已有门控、或有过尝试的（入口，厂商）对，带 `status`、门控状态 `gate` 和名单里的 Key 数 `allowedKeys`、该入口的 `figures`，以及被服务的尝试丢掉的请求特性 `degradedFeatures`（从多到少，最多 5 个）。
    - `skips` 是**应答节点**自 `skipsSince` 起，因为哪个原因没有把请求交给某个号池的中转，数的是被跳过的中转次数而不是请求数（被跳过的中转在日志里没有痕迹，所以只有这个节点自己知道）。原因有 `GATE`（Key 不在一个对它开放的门控名单里；这个厂商的门控不开放的入口不计这一项）、`NOT_A_RELAY`、`NO_MAPPING`、`UNVERIFIED`、`FEATURE`、`CONVERSION`、`CONTEXT`、`INTERRUPTED`。`INTERRUPTED` 放在最后判断，只记本来会给这个请求的中转。
  - `figures` 取自请求日志，覆盖**所有节点**，数的是发给中转的尝试（`source_dispatch` 为 `OPENAI_CHAT` 的行）：`attempts` 全部、`served` 已服务、`failed` 失败。
    - 失败里网关知道种类的单独数，种类写在日志的 `stop_reason` 里，其余失败只在 `failed` 里：
      - `interrupted`（`INTERRUPTED`：中转可能已经生成、没人为它付费，由运营者承担，升级标准里这一项叫 `unbilled`）。
      - `conversionRejected`（`CONVERSION_REJECTED`：中转拒绝了转换后的请求）。
      - `contextExceeded`（`CONTEXT_EXCEEDED`）。
      - `modelNotServed`（`MODEL_NOT_SERVED`）。
      - `keyRefused`（`KEY_REFUSED`：中转拒绝了账号自己的 Key，401 / 403 或 200 里的认证 / 权限错误；请求已经去了别的账号或下一级，这个账号隔离 10 分钟）。
      - `withheld`（`WITHHELD`：中转答了、网关把应答扣下的，只有 Responses 入口会有：内容过滤停下的应答，或在网关默认的输出上限处丢了调用、只有推理的应答；没人为它付费，但不是中转的故障，所以不算在 `interrupted` 里）。
      - `withheldInputTokens` 与 `withheldOutputTokens`（中转为这些被扣下的应答报告的令牌之和，和已服务应答的用量同一读法：运营者承担的成本，用户没被收费，也不在用户统计的用量里；中转没有报告用量时由网关估算，和已服务的应答一样：输入按发给中转的体，输出按应答的字节数；只有用量读不出来的行日志里没有这个数，这样的行不计）。
    - `estimated`、`clamped` 是用量不是中转报的那样的已服务尝试（日志的 `usage_source`，同时估算又钳制的只算 `estimated`），`degraded` 是丢掉了请求特性的已服务尝试，`p95Ms` 是已服务尝试延迟的第 95 百分位（没有已服务的尝试时缺省）。
    - 入口取自日志里的客户端路径（`/v1/chat/completions` 为 `CHAT`，`/v1/messages` 为 `MESSAGES`，`/v1/responses` 为 `RESPONSES`，`/v1beta/models/…` 为 `GEMINI`）。
    - 连接失败、429 等网关不知道种类的失败没有种类；客户端在中转还在生成时断开的请求不写日志行，所以不在这些数字里。
    - 「带内错误」（200 里带错误）和「疑似少报」（`suspectedUnderReport`）目前没有计数，只在服务端日志里。
  - 配置从未加载成功时返回 `503 TIER_CONFIG_UNAVAILABLE`。
- `GET /admin/source-tiers/evidence?days=30` 是升级一个对（EXPERIMENTAL → VERIFIED，门控才能对所有 Key 打开）要看的证据，覆盖最近 `days` 天（1 到 90，默认 30；其他返回 `400 SOURCE_EVIDENCE_WINDOW_INVALID`，请求日志只保留三个多月）里这个版本提供的每个对（`status` 不是 `NOT_OFFERED`）。每个对带：
  - `figures`（同上）。
  - `members`（按号池和中转分开的同样的数字）。
  - `lintSkips`（该厂商的中转在这个对的入口上，因转换后的请求没通过严格检查、或转换本身失败而被跳过的次数；按入口分开数，一个入口的转换失败不挡同一厂商别的入口的升级；**应答节点**自 `skipsSince` 起的计数，多节点时请每个节点各看一次）。
  - `criteria`：统一的升级标准，每条有 `name`、`rule`、`value`（个数或百分比，没有可除的数时缺省）和 `met`。标准是 `requests` 至少 200 次尝试、`lintFailures` 为 0、`conversionRejected` 不到 1%（占尝试）、`estimated` 不到 5%（占已服务的尝试）、`unbilled` 不到 2%（`interrupted` 占尝试，`withheld` 不在内）；没有可除的数的条件判不了，算未达标。`meetsStandard` 是全部达标。
  - 试用中的对的门控只能对名单里的 Key 打开，所以这些尝试都来自名单里的 Key。
  - `lintFailures` 只有在应答节点的计数覆盖整个窗口时才判：节点自 `skipsSince` 起计数，`skipsSince` 比窗口的起点（`since`）晚时，这一项没有 `value`、算未达标，要等节点运行满窗口或换更短的窗口再看。
  - `unbilled` 是下限：中转生成到一半、客户端先断开的请求不进请求日志，所以实际的中断只会更多。
  - 控制台的「升级证据」对话框显示这些数字并可下载成 JSON。
- 厂商的 `warnings` 是已保存配置里允许、但多半不是本意的情况：`LOWEST_TIER_COLLECTS_OFFICIAL_KEYS`（没有号池列出的官方 API Key 落入最低级，按它的系数计费）、`MEMBER_NOT_SERVING_VENDOR`（被指定的账号不是这个厂商的路由会用到的账号，指定无效）和 `PIN_ON_EXCLUSIVE_RELAY`（被指定的账号在某个厂商登记为专用中转，它不是任何厂商的原生成员，指定无效；`exclusive: false` 的中转不在此列）。
- `GET /admin/source-tiers/placements` 返回每个账号所在的号池，供账号列表显示「号源级别」列：`{"configVersion":7,"accounts":[{"accountId":"…","tiers":[{"vendor":"anthropic","tierId":"kiro","level":2,"name":"kiro","rule":"CATEGORY","relay":false,"active":true}]}]}`。`rule` 是 `MEMBER_ROW`（手动指定）、`CATEGORY` 或 `LOWEST_TIER`（没有号池列出它的类别，落入最低级）；`active` 是现在是否按这个号池服务；中转（`relay: true`）还要有一个这个版本提供的（入口，厂商）对的门控不是 `OFF`，并且至少一个启用的映射已验证，否则它放在号池里但什么请求都不收。不在任何号池的账号不出现。
- `GET /admin/source-tiers/me?provider=Anthropic` 返回这个模型分类（API Key 的 `modelProvider`）所属厂商里**客户端可以指定**的号池（已启用、「用户可选」打开的），从好到差：`{"provider":"Anthropic","referenceModel":"claude-sonnet-4.5","tiers":[{"id":"kiro","level":2,"name":"kiro","priceFactor":0.6,"pricing":{…}}]}`。每个号池只有 `id`（Key 偏好、请求头和模型后缀都用它）、`level`、`name`、`priceFactor` 和 `pricing`，不含账号、类别或协议。`pricing` 是 `referenceModel`（这个厂商的模型列表里第一个有价格的模型）乘上号池系数后的价格，写法同 `/v1/models` 的 `sourceTiers[].pricing`，两者都可能缺省。`tiers` 为空表示这个厂商没有启用分级、当前版本不能运行分级，或厂商没有打开「公开号池名称」（普通用户只能看到打开了它的厂商的号池，管理员总能看到）。缺少 `provider` 返回 `400`。

**可见性**：按号池服务的请求，响应带 `X-Starry-Tier`（号池标识）、`X-Starry-Tier-Level`、`X-Starry-Tier-Name`（UTF-8 百分号编码）、`X-Starry-Tier-Mode`（`auto` 或 `pinned`）和 `X-Starry-Tier-Status`（`served`，客户端指定的号池没有用上时为 `ignored`），这些头都在 CORS 的 `exposedHeaders` 里；没有分级的厂商不会有这些头。`/admin/logs` 的每一行（每次尝试一行）在按号池服务时带 `sourceTierRef`（`厂商:号池标识`）、`sourceTierLevel`、`sourceTierName`、`priceFactor` 和 `tierSelection`（`AUTO` 或 `PINNED`），其余号源字段（`sourceDispatch`、`wireModel`、`usageSource`、`stopReason`、`degradedFeatures`）只在号源不是原生账号时出现；没有分级的行不含这些字段。用户自己的日志（`/admin/logs/me`）只含号池的级别、名称和系数。收入按号池汇总：`SELECT source_tier_ref, sum(credits) FROM api_usage_events GROUP BY 1`。

**全端点统一**：Anthropic 厂商有一个默认关闭的开关 `unifyEndpoints`（`PUT /admin/source-tiers/anthropic`，控制台的「全端点统一」）。Chat 和 Responses 入口上的 Claude 模型本来只由 Kiro 服务，这条路由用不到官方 API Key，分级里放官方 Key 的号池对它们是空的（`模型@官方` 返回 `400 SOURCE_TIER_NOT_OFFERED`）。打开后，这两个入口上的 Claude **目录模型**（`claude-sonnet-4.5`、`sonnet` 等）和 Messages 入口一样走 Anthropic 路由：同一号池里 Anthropic API Key 在前、Kiro 在后，派发、换账号的次数上限（账号路由策略里 Anthropic 那一项）、预留和按号池的价格都按这条路由。请求和响应的格式不变，客户端只会多看到 `X-Starry-Tier*` 响应头里的号池变成官方 Key 的那个。**这会让这些请求先用到官方 Key，成本可能上升。**开关只改这一件事：Gemini 入口（`:generateContent`）、`auto` 和 `simple-task` 这样的路由预设、CodeWhisperer 的内部模型 ID、Gemini 等其他厂商的模型 ID，以及已经带 `anthropic/`、`openai/` 等前缀的模型，都和以前一样；厂商没有启用分级时开关保存了也不起作用。

**与「实验性的分级熔断」的关系**：两者互相独立。按号源分级服务的厂商不进入旧的熔断（它的请求不带熔断纪元，不产生 `ACCOUNT_POOL_CIRCUIT_OPEN`，`pool_tier` 与熔断指标不变）；旧熔断只作用于没有配置号源分级的厂商。

**指定号池**：客户端可以自己指定要用的号池，写在模型名后面（`claude-sonnet-4.5@kiro`、`gpt-5.5@L2`、`claude-opus-4.5@1+`；Chat、Responses、Messages 的 `model` 和 Gemini 路径里的模型都行）或写在请求头 `X-Starry-Tier` 里，或设成 API Key 的**号源偏好**（见下）。

- 同时有几处时，模型后缀优先于请求头，请求头优先于 Key 的偏好；`@auto`（或请求头 `auto`）回到自动，并盖过优先级更低的那几处。
- 写法是 `auto`、号池标识、`L<N>`（按级别）或纯数字，后面可以加 `+`：不加，请求只由这一个号池服务，也只按它预留和计价；加了，由它和它以下的号池服务，预留按其中预留最多的一个，结算按实际服务的那个。
- 可以固定到比 1 级更贵的号池，价格仍是标准价乘它的系数；固定不绕过 Key 的厂商限制和 Codex 隔离。
- 固定的请求不读也不改会话停留的号池，但算作会话在使用：一个一直在发固定请求的会话不算空闲，它停留的号池不会因此过期，各个入口都一样。
  - 固定的请求产生的响应成了 Responses 链最近的一环、它的账号成了链的 owner，也不会让之后的自动请求留在那个号池：续写一条可以在别的账号上重放的链时，自动请求只有在 owner 正好在它本来要开始的号池（会话停留的号池，没有停留就是 1 级）里才先用 owner，否则忽略它，从那个号池开始，按它预留和计价；会话空闲满 10 分钟回到 1 级之后同样如此。
  - 带 `+` 的固定请求也一样：它从自己写的号池开始（那里没有可用账号时，从它以下第一个有账号的号池开始），owner 只有在这个号池里才先用。
  - 所以某一轮因为忙而改由更低的号池服务、或某一轮固定到了更低的号池之后，之后的轮次只要写的号池能服务，就回到写的号池，不会一直留在更低的号池、一直按它的系数计价。
- 固定的请求不会换到别的号池：号池里的账号都不可用时返回 `503 SOURCE_TIER_UNAVAILABLE`（带 `Retry-After`，冻结的额度整笔退回）；号池里的中转刚中断了这个会话的应答、2 分钟内不再给它时也是这样（只是暂时用不了，不算「没有可用的账号类型」，所以不是 `400 SOURCE_TIER_NOT_OFFERED`）。
- 请求头和 Key 的偏好固定的号池，账号都被停用或挂起时，或中转刚中断了这个会话的应答时也是这样（不会改由别的号池按另一个价格服务）；模型后缀固定的号池，账号都被停用或挂起时是 `400 SOURCE_TIER_NOT_OFFERED`（见下表）。
- 续写一个不能离开 owner 账号的 Responses 链时，owner 在号池里但暂时用不了（额度耗尽、冷却中）返回的是 owner 的说明（`503 NO_AVAILABLE_ACCOUNT`，owner 的并发满了是 `503 RESPONSE_OWNER_UNAVAILABLE`），不是号池的，也不带 `Retry-After`。

模型后缀只有在它确实是选择器时才会被取走，由**服务这个模型的厂商**判断（Key 的模型映射把模型映到哪个厂商就看哪个厂商）。满足下面任一条才是选择器：

- `@` 前面是目录里的模型，而且那个厂商配置过号源分级；
- `@` 前面不是目录里的模型（比目录新的带日期的 Claude ID、目录里没有的 Codex 模型），但 `@` 后面是那个厂商的一个号池（标识、`L<N>`、数字或 `auto`）。

这里的厂商是**服务这个请求的账号**的厂商，与入口无关：Chat 和 Responses 送上 Kiro 路由的以 `claude` 开头的模型，由 Kiro 账号服务，算 Anthropic 厂商；Responses 上只有 Codex 账号能服务的模型（`codex-mini-latest` 这样不以 `gpt` 开头的）算 OpenAI 厂商。因此这些模型的请求按它们所在号池的系数计价，也带 `X-Starry-Tier*` 响应头。

其余的整个字符串仍是普通的模型 ID，和以前完全一样：从未配置过的厂商不认识选择器（后缀留在模型名里，请求头也被忽略，响应不加任何号源头；号池被删光、只剩厂商一行的厂商同样算没有配置），目录外的模型加 `@20250805` 这样的日期后缀也留在模型名里。

目录里的模型加日期后缀（`claude-sonnet-4@20250514`）按不存在的号池拒绝，除非 Key 的模型映射有一条规则的源模型自己带 `@` 而且接得住这个完整字符串：整串（`claude-sonnet-4@20250514`），或围着 `@` 写的带通配符的写法（`claude-sonnet-4@*`、`*@cheap`）。这时它就是一个映射源，不当选择器。单独的 `*` 和 `claude-*` 这样自己不带 `@` 的写法不算：它们照常映射 `@` 前面的模型，后缀仍然可以是选择器。

映射只问一次：`@` 前面的模型映到哪个厂商，就由那个厂商决定后缀是不是选择器，也由那个厂商服务；加权随机的映射（`loadbalance`）不会因此在判断后缀和选号时各抽一次而落到两个厂商。

后缀的错误都在预留之前返回：

| 情况 | 结果 |
|---|---|
| 引用的号池不存在，或写法不对 | `400 SOURCE_TIER_UNKNOWN` |
| 号池已停用，或这个端点 / 模型上它没有可用的账号类型（账号被停用或挂起的也不算：挂起会同时停用账号，号池的账号全被停用或挂起时就是这一行，不是 `503`） | `400 SOURCE_TIER_NOT_OFFERED` |
| 号池的中转都因为这个请求放不下被跳过（提示比映射的上下文上限长，或比这个中转拒绝过的长度大），而号池里没有原生账号（只是忙或额度耗尽的也算有，见下表） | `400 UPSTREAM_ERROR`，信息是 `prompt is too long: …`：与中转自己拒绝这个长度时的回答相同（中转拒绝的是 4,096 的请求，或客户端自己写了上限的请求；拒绝的是带着映射最大输出的请求时，回答不是它，而是下面可以重试的 `503`），第一次和以后每次一样，Claude Code 这样的客户端据此压缩对话 |
| 号池不允许用户选择（`userSelectable` 关闭） | `403 SOURCE_TIER_NOT_SELECTABLE` |
| 续写的 Responses 链不能离开它的 owner 账号，而 owner 不在固定的号池里 | `409 RESPONSE_OWNER_TIER_MISMATCH` |

请求头和 Key 的偏好（见下）是提示，不是要求：引用的号池不存在、写法不对、已停用或不可选，或者这个请求用不到它（这个端点的路由在号池里没有账号类型；续写的 Responses 链不能离开它的 owner 账号，而 owner 不在那个号池里）时不报错，请求按自动服务，响应的 `X-Starry-Tier-Status` 是 `ignored`（其余 `X-Starry-Tier*` 头照常说明由哪个号池服务）。

这个路由在号池里**有**账号、只是都被停用或挂起，或号池的中转刚中断了这个会话的应答时不算「用不到」：请求仍固定在这个号池，返回 `503 SOURCE_TIER_UNAVAILABLE`，不改由别的号池服务。停用、挂起或刚中断的中转只在它本来会服务这个请求时才这样算：提示比它的上下文上限（或它拒绝过的长度）长、转换不了的请求，请求头和偏好不固定，按自动服务。

只有写在模型名后面的后缀是对这一个请求的要求，上表的情况都会被拒绝。

厂商配置过但没有启用号源分级时，后缀被取走并忽略，请求带了选择器（后缀、请求头或 Key 的偏好，`auto` 除外）才会有 `X-Starry-Tier-Status: ignored`。

自动请求余额不足以支付预留（按可能用到的号池里预留最多的那个定额）时，`402 INSUFFICIENT_BALANCE` 的说明里会列出预留更少的可选号池和写法，固定其中一个就只按它预留。说明的写法：

- 各号池预留的输出相同（厂商没有只放中转的号池，或请求自己写了输出上限），并且预留更少的号池的系数都比预留最多的那个低时，预留更少就是更便宜，说明里写「更便宜的号池」。
- 预留的输出不同，或预留更少的号池里有一个系数不低于预留最多的那个（例如中转看得见一张只给了地址的图片，它所在的号池按 100 万令牌的输入预留）时，写「预留更少的号池」，不说更便宜，每个号池后面写着它的系数。
- `模型@标识` 写的是请求里的模型名，Key 的模型映射名、`openai/` 这样带厂商前缀的写法也照原样写。
- 厂商没有打开「公开号池名称」时，用户在 Key 表单里也选不了号池，说明里只讲预估按可能服务的号池里预留最多的那个算（各号池预留的输出相同时写「最贵号池」），不列出号池。
- `modelProvider` 为 `*` 或没有的旧 Key 没有号源偏好，说明里只列后缀和请求头两种写法，不让它去 Key 的设置里选。
- 请求的模型 ID 本身是 Key 的映射里围着 `@` 写的规则的源（`claude-sonnet-4@*`，只有不限厂商的旧 Key 能发这样的 ID）时，`模型@标识` 整串仍是映射源，不会被当作选择器，说明里就不再写后缀，号池按标识列出，请用请求头。
- 续写一个不能离开 owner 账号的 Responses 链的请求只能由 owner 所在的号池服务，计划、预留都只算那一个号池，没有预留更少的号池可改，说明里也不列。

匿名调用（控制台助手，或部署允许不带 Key 调用时）不能指定号池：厂商配置了分级时，模型后缀照样被取走（上游收到的是不带后缀的模型名），后缀和请求头都被当作用不上的提示，请求按自动服务，响应的 `X-Starry-Tier-Status` 是 `ignored`（`auto` 除外）；厂商没有配置过分级时和别的调用一样，后缀留在模型名里。

**Key 的号源偏好**：给不方便改模型名和请求头的客户端用。

- 在 `POST /admin/api-keys` 或 `PATCH /admin/api-keys/{keyId}` 里带 `tierPreference`。写法同后缀，保存时转成小写，按级别写的去掉前导零：`L02` 存成 `l2`、`002+` 存成 `2+`。创建时省略表示 `auto`，PATCH 里省略表示不改（换模型分类除外，见下），`auto` 清除偏好。Key 列表里的 `tierPreference` 只在设置过时才有。
- 写法不对（包括超过 40 个字符）返回 `400 API_KEY_TIER_PREFERENCE_INVALID`；号池存不存在不在保存时检查，请求遇到时再处理。
- 偏好属于 Key 当前的模型分类（厂商）：PATCH 把 `modelProvider` 改成另一个分类、又没有带 `tierPreference` 时，偏好被清回 `auto`，不会带到新厂商去（改成同一个分类不算）。要在新厂商里固定号池，请在同一次 PATCH 里一起带上。
- 偏好生效时和后缀一样固定号池：请求只由它服务（带 `+` 为它和它以下的号池），按它的系数计价，号池的账号都不可用（包括都被停用或挂起）时返回 `503 SOURCE_TIER_UNAVAILABLE`，也不读不改会话停留的号池。

偏好是这把 Key 的默认选择，同一把 Key 可能从不同的客户端和入口调用，所以和请求头一样，**不适用就忽略**：

- 引用的号池不存在、已停用、不可选或写法不对，请求按自动服务，响应的 `X-Starry-Tier-Status` 是 `ignored`。
- 这个请求的路由在号池里没有账号类型（例如偏好官方 Key 的 Key 从 Chat 入口调用 Claude 模型，而「全端点统一」没有打开，那条路由只用 Kiro），或续写的 Responses 链不能离开它的 owner 账号而 owner 不在偏好的号池里，也按自动、`ignored`；而写在模型名后面的后缀在这些情况下会被拒绝（`400 SOURCE_TIER_NOT_OFFERED`、`409 RESPONSE_OWNER_TIER_MISMATCH`）。

需要时，请求里的 `@auto` 或请求头 `X-Starry-Tier: auto` 随时盖过偏好。

匿名调用和 `modelProvider` 为 `*` 的旧 Key 没有偏好，也存不了：给 `*` 的旧 Key 设偏好返回 `400 API_KEY_TIER_PREFERENCE_INVALID`，要在同一次 `PATCH` 里一起选好 `modelProvider`（旧 Key 在编辑里选了模型分类之后才有）。厂商从未配置分级时偏好无效，响应不加任何号源头；配置过但没有启用时，响应只带 `X-Starry-Tier-Status: ignored`。

**客户端怎么写**：同一个号池，三种写法的差别在它不适用的时候。

- 模型后缀是对这一个请求的要求：号池不存在或这条路由用不到它就被拒绝（`400 SOURCE_TIER_UNKNOWN`、`400 SOURCE_TIER_NOT_OFFERED`）。
- 请求头和 Key 的偏好是客户端或 Key 的默认选择，同一份配置会用在不同的端点、模型和续写的响应链上，不适用时只会被忽略（请求按自动服务，响应带 `X-Starry-Tier-Status: ignored`）。

控制台生成的客户端配置（API Key 的「客户端配置」里选「号池」）因此用请求头：选的号池对这个客户端用不上（别的厂商的号池，或这个客户端的端点走的路由在号池里没有账号，例如给 OpenCode 选只放官方 Key 的号池，而它的 Claude 请求走 Kiro），请求照常服务，只是不按所选号池，不会整条配置失效。选了一个号池而它有账号、却都暂时不可用（忙、额度耗尽、被停用或挂起）时，请求不会换到别的号池，返回 `503 SOURCE_TIER_UNAVAILABLE`；选「及以下」才会改用更低的号池。

CC Switch 的 Claude Code 链接放不下请求头，写在模型名后面，那是后缀，是对每个请求的要求：号池用不了（账号都被停用或挂起、管理员关闭了它或不再允许选择）就被拒绝（`400` 或 `403`），不像请求头那样照常服务，控制台在链接旁边写了这一点。Codex 的链接带的是同一份 `config.toml`，OpenCode 的链接不写号池（在 Key 里设号源偏好，或用 `opencode.json`）。

| 客户端 | 请求头（控制台生成的写法） | 模型后缀 |
|---|---|---|
| Claude Code | 环境变量 `ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: kiro'` | `ANTHROPIC_MODEL=claude-sonnet-4.5@kiro`，或 `--model claude-opus-4.5@official` |
| Codex CLI | `config.toml` 里 `[model_providers.starry]` 下 `http_headers = { "X-Starry-Tier" = "kiro" }` | `model = "gpt-5.5@codex"` |
| OpenCode | `opencode.json` 里 `provider.starry.options.headers`：`{ "X-Starry-Tier": "kiro" }` | 要先把带后缀的 id 写进 `provider.starry.models`，再 `starry/claude-sonnet-4.5@kiro`；没有声明过的模型 OpenCode 自己就拒绝，请求发不出来 |
| SDK、curl | `default_headers` / `defaultHeaders` / `-H 'X-Starry-Tier: kiro'` | `model` 字段 |

模型 ID 页给每个有号池的模型列出号池、系数和乘过系数的价格（厂商打开「公开号池名称」时），点一下复制 `模型@标识`。

**核对记录**（2026-10-25，在本机用真实的客户端程序连一个只记录请求的本地服务，Key 是占位值；记录的是客户端实际发出的请求）：

| 客户端（版本） | 模型后缀 | 自定义请求头 | 会话标识（网关读到的） |
|---|---|---|---|
| Claude Code 2.1.168，`claude -p` | `ANTHROPIC_MODEL`、`--model` 里的 `@kiro`、`@official`、`@1+` 原样出现在 `/v1/messages` 的 `model` 里 | `ANTHROPIC_CUSTOM_HEADERS` 作为 `X-Starry-Tier` 发出 | 每个请求带头 `X-Claude-Code-Session-Id`，`metadata.user_id`（JSON 字符串）里的 `session_id` 是同一个值；工具往返的两个请求相同，新进程换新值。会话提示已经读这个头，不需要改 |
| Codex CLI 0.142.5，`codex exec` | `model = "claude-sonnet-4.5@kiro"` 原样发出（客户端本地会提示找不到这个模型的元数据，不影响请求） | `http_headers` 作为 `X-Starry-Tier` 发出 | 头 `session-id`（连字符，不是 `session_id`）和 `thread-id`，另有 `x-client-request-id`、`x-codex-window-id`（`会话:序号`），请求体里的 `prompt_cache_key` 和 `client_metadata.session_id` 也是同一个值；`exec resume` 的后续轮次相同，新会话换新值。`disable_response_storage = true` 时 `store` 为 `false`、每轮带完整历史、没有 `previous_response_id`。**会话提示一个都不读**，所以号池的会话键另读 `session-id`（见下） |
| OpenCode 1.18.16，`opencode run`，`@ai-sdk/openai-compatible` 供应商（控制台生成的那种） | 声明在 `models` 里的 `claude-sonnet-4.5@kiro` 原样发出；没有声明的模型 id（带不带 `@` 都一样）OpenCode 自己拒绝，没有请求发出 | `options.headers` 作为 `X-Starry-Tier` 发出 | 头 `x-session-affinity` 和 `x-session-id`（同一个值），标题生成用的小模型请求也带；`--continue` 的后续轮次相同，新会话换新值。会话提示已经读 `x-session-affinity`；`x-opencode-session` 只有 OpenCode 自己的供应商才发，自定义供应商没有 |

没有核对的：Claude Code 交互模式里的 `/model`（`--model` 走同一个字符串，没有逐个驱动）、Codex 的子代理（程序里有 `x-openai-subagent`、`x-codex-parent-thread-id`）和 WebSocket 传输、上面三个版本以外的版本，以及 CC Switch 导入后的实际写入。头名和字段名随客户端版本会变，升级客户端后请重新核对。

**会话键**：「降级后停留」要认得出同一个会话。分级请求按下面的顺序给会话起名，用的只是号池这一层的绑定，账号亲和（会话提示）和未分级厂商都不受影响：

1. 客户端自己给的会话提示：请求头 `x-claude-code-session-id`（只在 Messages 入口读）、`x-opencode-session`（只在其他入口读）、`x-session-affinity`、`x-conversation-id`，请求体里的 `conversation_id`、`conversationId`、`thread_id`、`session_id`，Messages 请求 `metadata` 里的 `session_id`（或 `user_id` 里编码的会话）；没有时再用 Codex 的请求头 `session-id`（每个入口都读；空白的忽略，超过 128 个字符的截断）。
2. Responses 请求续写的响应（`previous_response_id`）：本次产生的每个响应 id 都登记为属于这一轮所在的会话（链的第一轮的名字），下一轮按它找到这个名字，所以一条链的各轮是同一个会话。会话的所有绑定（各个模型各一条）和 2 分钟的中转排除都跟着整条链，中间换成别的模型（包括未分级厂商的模型，或不保留会话的厂商的模型）的一轮也不会把链切开。登记随绑定的空闲时间过期，不受 `STARRY_SESSION_AFFINITY_ENABLED` 影响。
3. 都没有（客户端不发会话标识，例如手写的 SDK 脚本、curl，或没有核对过的客户端版本）时，用对话开头的**内容指纹**：这把 Key、模型、system 文本前 2048 个字符和第一条 user 文本前 2048 个字符的哈希，图片和文件不计。开头相同的两个会话共用一个绑定，其中一个降级，另一个也停在低级；system 文本每轮都变的客户端每轮是一个新会话，哪里也不停留。

按上面核对的结果，Claude Code、Codex 和 OpenCode 在这三个版本上都发会话标识，不依赖内容指纹；依赖它的是不发会话标识的客户端。绑定只记在各节点的内存里，会话的任何一个被服务的请求（包括客户端固定了号池的）都算在使用，空闲 10 分钟过期，多节点见[运维手册：号源分级](OPERATIONS.md#号源分级)。

**跨协议中转**（OpenAI 格式的兼容 Key 当别的厂商某一级的号源）：运营步骤、门控、费用和排错见[运维手册：跨协议中转](OPERATIONS.md#跨协议中转)，这里写接口，以及客户端和管理员能观察到什么。

登记一个中转、并对一把 Key 打开 Messages 入口，是对 `PUT /admin/source-tiers/anthropic` 带上成员行和门控（整体替换，下面只写相关字段）：

```json
{
  "expectedVersion": 7,
  "enabled": true,
  "tiers": [
    { "tierId": "relay", "level": 1, "name": "relay" },
    { "tierId": "kiro", "level": 2, "name": "kiro", "priceFactor": 0.6, "memberCategories": ["KIRO", "CLAUDE_OAUTH"] }
  ],
  "members": [
    {
      "accountId": "acc-relay-1", "tierId": "relay", "protocol": "OPENAI_CHAT", "exclusive": true,
      "vision": false, "upstreamStream": true, "reasoningMode": "NONE", "maxDurationSeconds": 540,
      "usageCacheReadInPrompt": true, "usageCacheWriteInPrompt": true,
      "models": [
        { "modelId": "claude-sonnet-4.5", "upstreamModel": "relay/sonnet", "maxContextTokens": 200000, "imageTokenCap": 8192, "maxOutputTokens": 8192 }
      ]
    }
  ],
  "gates": [
    { "sourceProtocol": "OPENAI_CHAT", "entryEndpoint": "MESSAGES", "state": "KEYS", "allowKeyIds": ["<你自己的 Key 的 keyId>"] }
  ]
}
```

- 带 `protocol: "OPENAI_CHAT"` 的成员行是中转，账号必须是 OpenAI 兼容 Key，厂商只能是 `anthropic`、`openai`、`google`（`deepseek` 和自定义厂商返回 `400 SOURCE_PROFILE_NOT_APPLICABLE`）。
  - `google` 厂商只有 Chat 和 Gemini 两个入口的对（没有客户端用 Messages 或 Responses 问 Gemini 模型）；Gemini 入口上问 Claude 模型的请求在 `anthropic` 厂商上有对，问 gpt 模型的没有。
  - `usageCacheReadInPrompt` 和 `usageCacheWriteInPrompt` 必填。
  - 至少一条映射，每条映射的 `maxContextTokens` 必填、`imageTokenCap` 默认 8192、`maxOutputTokens`（1 到 1000000）可选、`upstreamModel` 原样发给中转。
  - `maxOutputTokens` 是中转的上游给这个模型写的最大输出：Responses 请求没写输出上限时，中转收到的输出上限是它（不超过这个请求的默认输出，窗口装不下它和提示时收到 4,096），不写则是 4,096；它不属于验证所证明的东西，改它不清除验证。
  - 不带 `protocol` 的成员行只是把原生账号钉在某个号池。
- 成员的 `vision` 和 `cacheControlPassthrough` 决定 Messages 入口（Claude Code）的请求里哪些内容到达中转：
  - 用户轮里的图片（`base64` 源、http / https 的 `url` 源）在 `vision` 为 `true` 时作为 `image_url` 发出，否则中转收到 `[image omitted]` 的标记。
  - Claude Code 放在 `system` 块、最近几轮文字、工具结果和带调用的助手轮上的 `cache_control` 在 `cacheControlPassthrough` 为 `true` 时随之发出，否则整个 system 和每一轮是一整段文字、标记被丢掉。
  - `tool_result` 的 `is_error` 写在结果文字的开头（`Error: …`）。
  - 工具结果里的图片放不进 tool 消息，中转收到 `[image omitted]`（不再是 `[no output]`）。
  - 丢掉的内容记在请求日志的 `degradedFeatures`（`image`、`tool_output_image`、`cache_control`）。
  - Kiro 和官方 Key 收到的请求不变。
- 保存**不发探针**：新的或改过的映射 `verified` 为 `false`，用 `POST …/verify` 验证后才进入请求的计划。成员行一保存，`exclusive` 就生效（`true` 时这把 Key 不再服务未分级的兼容模型请求）。
- 门控 `entryEndpoint` 取 `CHAT`、`MESSAGES`、`RESPONSES`、`GEMINI`，`state` 取 `OFF`、`KEYS`、`ON`；`allowKeyIds` 是 API Key 的 `keyId`，至多 50 个，去重；不带 Key 的调用者（允许匿名代理时的无 Key 请求、控制台自己的助手）不能列入，试用中的对不会派给它们。同一个（来源协议，入口）只能有一行；没有行就是关闭。对没有提供的对设 `KEYS` / `ON` 返回 `400 SOURCE_PAIR_NOT_OFFERED`，对试用中的对设 `ON` 返回 `409 SOURCE_PAIR_GATE_FORBIDDEN`。门控与号池配置在同一个事务里保存，保存即经数据库通知刷新所有节点，所以设为 `OFF` 就是紧急开关：之后的请求不再派给中转，在飞的请求按冻结的计划完成。
- **客户端看到的**：请求和响应的格式不变，仍按入口的格式渲染，响应头 `X-Starry-Tier*` 说明由哪个号池服务。
  - 工具调用的 id 由网关重新生成（Messages 是 `toolu_…`，Chat、Responses 和 Gemini 是 `call_…`），不是中转给的。
  - Gemini 模型（`google` 厂商）签过名的工具调用，签名随调用一起交给客户端（Gemini 入口写在带 `functionCall` 的 part 的 `thoughtSignature` 上，Chat 入口写在调用的 `extra_content.google.thought_signature` 上），客户端下一个请求带回来，网关再交给中转。
  - 中转因长度或内容过滤结束时，Messages 的 `stop_reason` 是 `max_tokens` / `refusal`，Chat 的 `finish_reason` 是 `length` / `content_filter`，Gemini 的 `finishReason` 是 `MAX_TOKENS` / `SAFETY`。Responses 只有在客户端自己写了 `max_output_tokens`（或 `max_completion_tokens`、`max_tokens`）并用尽它时，才是 `status: "incomplete"` 加 `incomplete_details.reason: "max_output_tokens"`，而且这样的应答不带工具调用（客户端会重试它，调用不能跑两次）。
  - Responses 请求没有写 `max_output_tokens` 时（Codex 从不写，也没有设置能让它写），网关给请求加上一个默认的输出上限：环境变量 `STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS`，默认 16,000 个令牌（4,096 到 1,000,000，超出范围服务启动失败），并且不超过这个模型自己的最大输出。
    - 模型自己的最大输出是模型列表里它的 `max_output_tokens` 和厂商标价目录给这个模型列的上限，两个都有时取较小的，只有一个有数时取那一个。例如 `gpt-4` 的价格目录写 4,096，模型列表对大多数别名仍写 64,000；DeepSeek 的 `deepseek-v4-*` 模型列表写 384,000，随包的价格目录写 8,192，刷新后的价格目录写 393,216，取较小的：默认部署刷新价格目录，得到默认值，关掉刷新的部署得到 8,192。价格目录的数是目录当前的数。
    - 模型目录没有列出这个模型的是 4,096，由运营者自己的后端服务的模型就是这样：运营者自己加的自定义模型，和管理员在控制台改成指向自己厂商的内置模型（模型列表给它们写的 64,000 是占位，不是后端的上限）。Key 的模型映射把内置模型名指到它们（或指到目录没有列的模型）时也是，按映射之后的模型算，不按客户端写的名字。
    - 原生号源（Kiro、官方 OpenAI Key、Anthropic Key）收到这个上限（只有 Codex 账号去掉这个字段），预留也按它算。预留不起时，在派发之前得到平常的 `402 INSUFFICIENT_BALANCE`；设了额度上限的 Key 剩余额度不够一次预留时是 `429 CREDITS_LIMIT_EXCEEDED`，Coding Plan 额度不够时是 `402 CODING_PLAN_QUOTA_EXCEEDED`。每个在飞的请求各占一份预留，并行的几轮会比一轮的真实成本更早碰到这些上限。请求里写一个自己的 `max_output_tokens` 就按那个预留，能避开这些，Codex 写不了。
    - **中转收到的不是这个数**：网关不知道中转的上游最多写多少，所以映射写了 `maxOutputTokens` 的中转收到映射写的数（不超过这个上限），没写的收到 4,096，请求体和网关还不加默认输出上限时逐字节相同。
    - 预留是各号池里最大的那个的上界，每个号池按它能被发出的输出算（可以放原生账号的号池按默认的输出上限，只有中转的号池按它的中转收到的数，所以固定到中转的请求按中转的数预留；最低的已启用号池算可以放原生账号的，只放中转的厂商、或中转在最后一级的厂商，固定到它的请求也按默认的输出上限预留）。结算按实际服务的号源和用量，中转用量的钳制是它收到的数。
    - 输出上限大于 4,096 时，估算的输入加这个输出要装得下映射的 `maxContextTokens`；装不下的，中转这一轮收到 4,096 的请求，窗口只量提示，和以前一样；这个节点见过中转拒绝这样大的、带着最大输出的请求时也一样（[学到的上限](SOURCE_TIERS.md#学到的上限)）。
    - Kiro 和官方 OpenAI Key 在这个入口上是阻塞调用，一轮最长 `STARRY_REQUEST_TIMEOUT`（默认 300 秒），默认的 16,000 个令牌按每秒约 60 个令牌约 270 秒；把默认值调大时要同时调大时限，否则超时的一轮在派发之后留作待核对（运维手册「跨协议中转」）。
    - Chat、Messages 和 Gemini 入口不变，没写上限时仍是 4,096。
  - 中转在**网关给它的**输出上限（4,096，或映射的 `maxOutputTokens`）处停下，只有文字时按完成（`completed`）返回，已经完整的工具调用保留（和原生账号的应答一样；`incomplete` 对 Codex 是流的失败，它会把同一个请求再发五次，每一次中转的应答都要付费）。
  - 丢了一个被截断的工具调用，或没有文字也没有完整调用的应答，以及被中转的内容过滤停下的应答，被网关扣下（`502`，不收费，这个会话 2 分钟内不再给这个中转，不是中转的故障：不冷却、不计账号错误）。客户端的重试去下一个号源，那里不一定写得完：只有 Codex 账号没有输出上限的限制，Kiro 等被限在默认的输出上限内（它通常比中转收到的大）。
  - 请求带了这个中转承载不了的功能（`web_search` 等服务端工具、多于 4 个的停止序列、gpt 类请求的图片等）时，中转被跳过，由原生号池服务；没有原生号池能服务时返回 `400 SOURCE_TIER_FEATURE_UNSUPPORTED`（`data.feature` 是功能名）。
    - Responses 入口上，Claude 模型请求的 `web_search` 等内置工具、图片、音频 / 文件只是被丢掉并记录，中转照常服务（原生 Kiro 同样不读它们），gpt 模型请求则要跳过中转（只有 `file_id` 的图片中转读不了 OpenAI 的文件，同样是这样处理：Claude 请求丢掉图片并记录，gpt 请求跳过中转）。
    - Responses 请求里的 `conversation`、保存的 `prompt`、`item_reference` 和 `compaction` 项（上下文留在 OpenAI 那边，中转拿不到）对任何模型都让中转被跳过。
  - 有中转成员的厂商上，客户端把 `apply_patch` 声明成自定义工具时（Codex 就是），Kiro 和 Anthropic Key 被告知的也是中转拿到的补丁函数（一个字符串参数 `patch`），它们对 `apply_patch` 的调用同样渲染成 `custom_tool_call`（`input` 是补丁本身），不论哪一级答的，Codex 都能应用；声明成函数的客户端、没有中转的厂商，一切照旧（`function_call`）。
  - 中转可能已经生成就断了（读超时、单次时限、流中断）时返回 `502`，不再派给别的账号或级别，用户不付费，即使中转在断开前报告了用量；这个会话 2 分钟内的重试也不再给同一个中转。
  - 中转拒绝了账号自己的 Key（401 / 403，或 200 里的认证 / 权限错误）时，这个账号隔离 10 分钟，请求换下一个账号或下一级（按服务的号池计价）；没有别的号源时返回没有可用账号的 `503`，客户端永远收不到中转的 `401` / `403`。
  - 发给中转的 Messages 请求里，Claude Code 每个请求都变的计费头（system 的第一块 `x-anthropic-billing-header: …`）整块不发，好让中转自己的前缀缓存命中；发给 Kiro 和官方 Key 的请求不变。
  - `verbosity` 为 `low` / `high` 的请求照样可以由中转服务：这个参数不发给中转，只记在 `degradedFeatures` 里。
- **日志**（只给管理员，`/admin/logs`，每次尝试一行）：按中转服务的行另有下面这些字段。
  - `sourceDispatch`（`OPENAI_CHAT`）。
  - `wireModel`（发给中转的模型名）。
  - `usageSource`（`REPORTED`、`ESTIMATED` 网关估算了缺的用量、`CLAMPED` 钳到了上限）。
  - `stopReason`（成功时 `END_TURN`、`TOOL_USE`、`MAX_TOKENS`、`CONTENT_FILTER`；失败时 `INTERRUPTED`、`CONVERSION_REJECTED`、`CONTEXT_EXCEEDED`、`MODEL_NOT_SERVED`、`KEY_REFUSED`、`WITHHELD`，其余失败为空）。
  - `degradedFeatures`（丢掉的请求特性，逗号分隔；也有网关对请求体做的修补，如 Claude Code 每个请求都变的计费头 `billing_header_dropped`，它不是特性；anthropic 厂商的中转收到的采样参数被改过时，记 `temperature`（大于 1 改成 1）和 `top_p`（与 `temperature` 同写时去掉 `top_p`））。
  - 被网关扣下的应答（`stopReason` 为 `WITHHELD`）的行另有 `withheldInputTokens`、`withheldOutputTokens`：中转报告的用量，运营者承担的成本；这一行的 `inputTokens`、`outputTokens` 和 `credits` 仍是 0（用户没被收费，也没有用量），所以这两个字段不会进用户的统计。
  - 原生号源的行这些字段为空；用户自己的日志（`/admin/logs/me`）看不到它们。中转的原文错误不会出现在客户端的消息和这些日志里。
- **所有节点都升级到支持跨协议的版本之后再保存非 `OFF` 的门控**：旧节点把不认识的对当作未提供，不会派给中转，同一个会话落在新旧节点上得到的服务不同。升级到带 V32 的版本后请立即重新备份（见 [运维手册：恢复流程](OPERATIONS.md#恢复流程)）。

**号源分级的错误码**

代理接口（`/v1/...`，都在预留之前返回，不扣费，除非另有说明）：

| 状态码 | 错误码 | 何时 |
|---|---|---|
| `400` | `SOURCE_TIER_UNKNOWN` | 模型后缀引用的号池不存在，或写法不对 |
| `400` | `SOURCE_TIER_NOT_OFFERED` | 号池已停用，或这个端点 / 模型上它没有可用的账号类型，或后缀固定的号池的账号全被停用或挂起 |
| `400` | `SOURCE_TIER_FEATURE_UNSUPPORTED` | 请求用了一个功能，这次能用的号池里没有任何号源支持它（`data.feature` 是功能名）；固定到只有中转的号池时最常见 |
| `400` | `UPSTREAM_ERROR`，信息 `prompt is too long: …` | 提示比这次能用的中转的上下文上限长（映射的上限，或这个中转拒绝过的长度），而号池里没有原生账号；中转自己在派发时拒绝这个长度、又没有原生账号可跨时的回答也是它，第一次和以后每次一样；**除了**中转拒绝的是带着映射最大输出的请求（Responses 入口没写输出上限、映射的 `maxOutputTokens` 大于 4,096、中转收到的是它的体）：这时回答不是它，而是可以重试的 `503`（自动的请求 `NO_AVAILABLE_ACCOUNT`，固定了号池的请求 `SOURCE_TIER_UNAVAILABLE`，模型后缀固定到只有中转的号池也一样），重试发的是 4,096 的请求，窗口放得下就由中转服务，不让客户端为一段中转放得下的对话去压缩。有原生账号、只是忙或额度耗尽时不是它（请求已超过 240 秒、预留的租期不够再派一次，或重试名额用完又没有预留槽而不能跨到原生账号时也一样）：自动的请求回 `503 NO_AVAILABLE_ACCOUNT`，固定了号池的请求回 `503 SOURCE_TIER_UNAVAILABLE`，都可以重试，重试时这个节点已知道中转放不下，由原生账号服务，不让客户端为一个原生账号照样能接的提示去压缩对话（中转拒绝的是带着映射最大输出的请求时除外：它没有被剪掉，只学到这个宽体放不下，重试发给它的是 4,096 的请求，按它所在号池的系数计价（中转 1 级系数 1.0、Kiro 2 级系数 0.6 时是 1.0，不是 0.6），输出至多 4,096，窗口放得下就由它服务，请求头 / Key 偏好固定到只有中转的号池时提示也不丢；4,096 的请求或客户端自己写了上限的请求被拒之后，重试才由原生账号服务）。模型后缀固定到只有中转的号池是对这个号池的要求，被拒的是 4,096 的请求，或客户端自己写了上限的请求时，始终是 `prompt is too long`；被拒的是带最大输出的请求时是上面的 `503 SOURCE_TIER_UNAVAILABLE`，不是它 |
| `403` | `SOURCE_TIER_NOT_SELECTABLE` | 后缀固定到「用户可选」关闭的号池 |
| `409` | `RESPONSE_CHAIN_NOT_PORTABLE` | 续写的 Responses 链这个节点展开不了，中转只会拿到最后一轮所以不接它，而这次能用的号池里只剩中转。展开不了的原因：重启、换了节点、超过 6 小时，或者被这个节点的历史上限挤掉（每个节点最多保存 512 条历史、共 64 MB，满了先删最旧的，压缩后超过 8 MB 的历史根本不保存；Codex 这样每轮都存完整历史的流量会很快占满它），可用配置 `starry.responses.state-max-total-bytes`、`starry.responses.state-max-entry-bytes` 调大 |
| `409` | `RESPONSE_OWNER_TIER_MISMATCH` | 续写的 Responses 链不能离开它的 owner 账号，而 owner 不在固定的号池里（或不在能服务这个请求的号池里） |
| `503` | `SOURCE_TIER_UNAVAILABLE` | 固定的号池暂时没有可用账号（包括只有中转、而它们刚中断了这个会话的应答、2 分钟内不再给，或它们拒绝了自己的 Key 而被隔离）；带 `Retry-After`，冻结的额度整笔退回 |
| `503` | `RESPONSE_OWNER_UNAVAILABLE` | 续写的 Responses 链的 owner 账号并发已满 |
| `503` | `TIER_CONFIG_UNAVAILABLE` | 号源分级配置从未加载成功（失败关闭，不按系数 1 静默计费） |
| `500` | `SOURCE_TIER_SELECTION_INVALID` | 选中的账号不属于为它选定的号池（内部一致性检查，不应出现；派发之前返回，预留全额退回） |
| `400` | `API_KEY_TIER_PREFERENCE_INVALID` | 创建或编辑 API Key 时（`/admin/api-keys`）号源偏好写法不对，或给 `*` 的旧 Key 设偏好 |

管理接口（`/admin/source-tiers/...`）：

| 状态码 | 错误码 | 何时 |
|---|---|---|
| `400` | `SOURCE_TIER_VENDOR_UNKNOWN` | 厂商不是模型目录里的提供方（`anthropic`、`openai`、`google`、`deepseek` 或自定义厂商） |
| `400` | `SOURCE_TIER_INVALID` | 号池配置不合法：号池标识、级别、名称、系数、类别重复，或启用了没有启用号池的厂商等 |
| `400` | `SOURCE_TIER_MEMBER_INVALID` | 成员行不合法：账号重复、引用了不存在的号池、映射的字段不合法，或对该账号不适用 |
| `400` | `SOURCE_TIER_MEMBER_PROTOCOL_INVALID` | 成员行的 `protocol` 不是这个版本支持的（目前只有 `OPENAI_CHAT`） |
| `400` | `SOURCE_TIER_GATE_INVALID` | 门控行不合法：来源协议、入口或状态不在取值里，名单的 Key 过长、多于 50 个或列了 `anonymous`（不带 Key 的调用者用这个 ID，它不是 API Key），或同一个对出现两次 |
| `400` | `SOURCE_PROFILE_NOT_APPLICABLE` | 给 `deepseek` 或自定义厂商登记中转 / 门控：它们本来就把兼容 Key 当作自己的账号 |
| `400` | `SOURCE_PAIR_NOT_OFFERED` | 门控设为 `KEYS` / `ON`，而这个版本没有提供这个（入口，厂商）对 |
| `409` | `SOURCE_PAIR_GATE_FORBIDDEN` | 门控对试用中的对设为 `ON`（只能 `KEYS`） |
| `400` | `SOURCE_TIER_CACHE_EVIDENCE_REQUIRED` | 缓存口径设为「不含」而没有缓存探针的证据 |
| `409` | `SOURCE_TIER_RUNTIME_UNSUPPORTED` | 保存了这个版本还不能运行的设置（`data` 列出） |
| `409` | `SOURCE_TIER_VERSION_CONFLICT` | `expectedVersion` 过期（`data.currentVersion`） |
| `404` | `SOURCE_TIER_MEMBER_NOT_FOUND` | 验证或拉取模型列表的账号不存在，或不是这个厂商的中转 |
| `429` | `SOURCE_VERIFY_RATE_LIMITED` | 同一个账号 30 秒内验证过（`data.retryAfterSeconds`），或 5 秒内拉取过模型列表 |
| `502` | `SOURCE_UPSTREAM_MODELS_FAILED` | 中转没有返回模型列表 |
| `400` | `SOURCE_EVIDENCE_WINDOW_INVALID` | 升级证据的 `days` 不在 1 到 90 |
| `503` | `TIER_CONFIG_UNAVAILABLE` | 配置从未加载成功（状态接口也一样） |

另见控制台「使用文档」里的「错误码速查」，它给最终用户讲了上面代理接口那几条的处理办法。

### 实验性的分级熔断

**账号池分级熔断策略**是实验性功能，**默认关闭**，用 `PATCH /admin/accounts/settings` 开关：

```json
{ "poolPolicy": { "enabled": true } }
```

关闭时，账号选择忽略 `poolTier` 的熔断路由。熔断策略、模型池监控和关闭开关都在控制台的「系统设置 → 账号池设置」页（「账号池分级熔断策略」卡片）里；账号页始终是普通的账号管理，不会因为启用而变成只读。启用需要确认「不稳定」提示，卡片里始终显示实验标签和不稳定警告，因为实验期间仍可能发生误熔断、路由抖动和监控口径变化。这个策略只作用于**没有配置[号源分级](#号源分级)**的厂商。

每个熔断器有一个有界的 `厂商:模型族` 键，派生自解析后的上游模型目录，如 `openai:gpt-5` 或 `deepseek:deepseek-v4`。未知的模型 ID 被归入厂商受控的 `厂商:other` 池，而不是创建无界的键。账号的 `poolTier` 取 `primary` 或 `secondary`；启用策略前通过账号创建 / 更新 API 配置。因为 `OPEN` 的池只路由给备用层，生产部署应当为每个启用的厂商至少准备一个可用的备用账号。

`GET /admin/accounts/pool-status` 返回权威的、按模型族的快照，放在 `modelPools` 里。旧版聚合字段为兼容保留；新客户端应使用 `modelPools` 获取熔断状态和层级指标：

```json
{
  "success": true,
  "data": {
    "enabled": true,
    "accountCount": 6,
    "availableAccountCount": 5,
    "activeTier": "mixed",
    "settings": {
      "enabled": true, "windowMinutes": 5, "minSampleSize": 10,
      "failoverThresholdPercent": 70.0, "recoveryThresholdPercent": 70.0,
      "retestIntervalMinutes": 5, "retestMaxConcurrency": 4,
      "circuitEpoch": "11111111-1111-1111-1111-111111111111",
      "activationStartedAt": "2026-08-10T03:00:00Z"
    },
    "modelPools": [
      {
        "key": "openai:gpt-5", "provider": "OpenAI", "modelFamily": "GPT-5",
        "representativeModel": "gpt-5", "state": "OPEN", "activeTier": "secondary",
        "primaryRequests": 20, "primaryFailures": 15, "primaryFailureRatePercent": 75.0,
        "secondaryRequests": 8, "secondaryFailures": 0,
        "primaryAccountCount": 2, "secondaryAccountCount": 1,
        "openedAt": "2026-08-10T03:04:00Z", "nextProbeAt": "2026-08-10T03:09:00Z",
        "halfOpenInFlight": 0, "halfOpenCompleted": 0, "halfOpenSucceeded": 0
      }
    ]
  }
}
```

状态含义：`CLOSED`（优先主层）、`OPEN`（严格冷却期内只走备用层）、`HALF_OPEN`（有界的真实流量探测主层，其余流量留在备用层）。`windowMinutes`、`minSampleSize`、`failoverThresholdPercent` 控制打开；`retestIntervalMinutes` 是半开探测前的冷却；`retestMaxConcurrency` 是半开的探测额度；`recoveryThresholdPercent` 控制恢复。旧版的 `retestThresholdPercent` 字段仍被接受，但不会独立触发状态变化。探测租约限制在 2–10 分钟。每次 `false -> true` 都创建一个新的 `circuitEpoch`，更早启用的请求和对账会被忽略。后台对账每 30 秒读取匹配的 PostgreSQL 纪元；策略设置通知触发立即对账；两者都不会发出合成测试或把请求扇出到所有账号。运维要点见 [运维手册：实验性分级熔断](OPERATIONS.md#实验性分级熔断)。

### 测试账号

`POST /admin/accounts/test` 测试：Kiro 账号走 Kiro 上游客户端；OpenAI 和 DeepSeek API Key 账号走 `/chat/completions`；Codex 账号走 `/responses`；Anthropic API Key 账号走 `/messages`。Google / Gemini 的 API Key 记录可以存储，但直连测试目前会为每个账号返回 `501`，因为没有接入 Google 上游客户端。

- `updateStatus` 默认 `true`；只有当测试**不该改变账号状态**时才设为 `false`。成功的测试会重新启用账号，并清除挂起 / 额度状态。
- 暂时性失败会让 `errorCount` 加一，但**不会**禁用账号。`402` 可能把额度标记为耗尽；普通的 `429` 仍然是暂时的限流信号，**不会**设置 `quotaExhaustedAt`、禁用或挂起账号。`401` / `403` 只有在错误里有明确的「Token 被吊销」或「组织 / 账号被禁用」证据时，才会永久禁用该凭据；有歧义的响应保持启用，交给生命周期的隔离机制和之后的复核。
- 在策略关闭的普通代理流量里，可恢复的上游失败会继续让账号的连续 `errorCount` 递增；连续两次失败会安排一次后台账号测试，这个测试不再因为暂时性结果禁用凭据。策略启用时，可重试的传输失败和上游声明可重试的 `408`、`409`、`5xx` 被限定在受影响的模型族熔断器里：主层的熔断失败不会增加账号全局的 `errorCount`，备用层的失败仍会增加。每次派发的尝试还会更新共享的 PostgreSQL 生命周期。因无效或无法解析的上游响应而产生的 `502` 被明确标为**不可重试**，不进入熔断窗口。

`GET /admin/accounts/test-models` 返回按厂商分组的测试模型：Kiro、OpenAI API Key、Google / Gemini、Anthropic、DeepSeek、Codex。OpenAI、Google / Gemini、Anthropic、DeepSeek 的选项来自内置的 `model-price-repo` 快照：只有带经验证的输入 / 输出 token 价格、chat 模式、且请求端点与当前代理兼容的条目才成为默认支持的 ID，仅 Realtime 的条目被排除。OpenAI、Google、Anthropic 的目录别名保留它们的路由前缀（如 `openai/gpt-5`、`google/gemini-2.5-pro`、`anthropic/claude-sonnet-4-5`）；账号测试请求会在上游 API 需要裸 ID 时去掉前缀。Codex OAuth 测试保留一个更小的、兼容 ChatGPT 账号的 GPT-5 子集。控制台用这个端点为每个账号行展示模型列表，但出现 Google 选项并不意味着直连 Google 账号测试已实现。

---

## API Key

```http
GET    /admin/api-keys
POST   /admin/api-keys
PATCH  /admin/api-keys/{keyId}
DELETE /admin/api-keys/{keyId}
```

创建：

```json
{ "name": "default", "creditsLimit": 1000, "modelProvider": "OpenAI" }
```

- 新建的 Key **必填 `modelProvider`**，并且要匹配至少一个已启用 `/v1/models` 条目的 `provider`（不区分大小写）。`*` **仅为迁移的旧 Key 保留**，创建或显式更新时会被拒绝。选择上游之前，请求的模型和任何映射目标，都必须同时在已启用的目录里并属于这个厂商。模型缺失返回 `400 MODEL_NOT_AVAILABLE` 或 `400 MAPPED_MODEL_NOT_AVAILABLE`；跨厂商访问返回 `403 API_KEY_MODEL_PROVIDER_FORBIDDEN`。
- API Key 归属于登录的本地用户。管理员可以列出和管理所有的 Key，并看到每把 Key 的 `userId`；普通用户只能列出、更新、删除和复制自己的。
- 新建的 Key 会把完整的 `sk-...` 值保存在所属用户的 Key 记录里，所以控制台可以直接复制 API Key 和生成的客户端配置。在这种行为之前创建的 Key 只有 `keyHash` 和 `keyPrefix`，完整密钥无法恢复，需要直接复制时请重建。
- 在 Key 的 PATCH 里，`clearCreditsLimit:true` 显式移除之前配置的限额；省略的字段保持原值。
- Key 创建时选择 `billingSource: "BALANCE" | "CODING_PLAN"`，没有未完成请求时可以修改。见下文 [Coding Plan 订阅](#coding-plan-订阅)。
- `tierPreference` 是这把 Key 的号源偏好（`auto`、号池标识、`L2` 或 `2`，可加 `+`，不超过 40 个字符），创建和 PATCH 都可以带，PATCH 换了 `modelProvider` 又没有带它时清回 `auto`，创建、PATCH 和列表的响应里都只在设过偏好时才有这个字段；有未完成请求时也能修改。`modelProvider` 为 `*` 的旧 Key 须在同一次 PATCH 里一起选好 `modelProvider` 才能设偏好，否则返回 `400 API_KEY_TIER_PREFERENCE_INVALID`。规则见 [号源分级：指定号池](#号源分级)，可选的号池用 `GET /admin/source-tiers/me?provider=` 查询。

---

## 模型目录管理

这些端点需要管理员会话：

```http
GET    /admin/models
PUT    /admin/models
POST   /admin/models/icon
DELETE /admin/models?modelId={modelId}
DELETE /admin/models/vendors?provider={provider}
PUT    /admin/models/order
GET    /admin/models/display
PATCH  /admin/models/display
GET    /system/model-catalog-display
```

创建或覆盖一个目录条目：

```json
{
  "id": "openai-compatible/meta-llama/llama-3.3-70b-instruct",
  "name": "Llama 3.3 70B",
  "provider": "OpenRouter",
  "family": "Llama 3",
  "targetModelId": "openai-compatible/meta-llama/llama-3.3-70b-instruct",
  "requestFormat": "OPENAI_CHAT_COMPLETIONS",
  "endpoint": "/v1/chat/completions",
  "contextWindow": 131072,
  "description": "OpenRouter-hosted Llama model",
  "enabled": true
}
```

- 目前只接受 `OPENAI_CHAT_COMPLETIONS` 作为 `requestFormat`。
- 更新一个内置 ID 会保存为覆盖；删除一个内置 ID 是停用它，而删除自定义 ID 则移除它的自定义。`DELETE /admin/models/vendors` 会删除自定义厂商及其下的所有自定义模型；内置厂商和仍含有内置模型的厂商会被拒绝。
- 图标上传接受 multipart 字段 `file`，从文件字节检测 PNG / JPEG / WebP，强制 `STARRY_MODEL_MAX_ICON_BYTES`，返回 `/assets/model-icons/` 下的 URL。目录自定义存在 PostgreSQL 里，但上传的文件在 `STARRY_MODEL_ICON_DIR` 里，**不包含在数据库导出中**。
- `PUT /admin/models/order` 为所有用户保存全局的模型 ID 显示顺序。传完整的 `modelIds` 列表，或 `{ "restoreDefault": true }` 回到目录顺序；未指定的现有 ID 按原来的相对顺序追加。`GET/PATCH /admin/models/display` 存储画廊布局（`cards` / `list` / `compact`）、密度、列数（`0` = 自动）、字段可见性、分组（`provider` / `none`）和卡片样式（`default` / `outline` / `flat`）。`GET /system/model-catalog-display` 是公开的，返回这些显示设置以及自定义的 `modelIds` 顺序，让控制台画廊对所有登录用户保持一致。`/v1/models` 也遵循保存的顺序。

---

## 模型价格

这些端点需要管理员会话：

```http
GET   /admin/model-pricing
POST  /admin/model-pricing
PUT   /admin/model-pricing
POST  /admin/model-pricing/preview
PATCH /admin/model-pricing/source
POST  /admin/model-pricing/refresh
```

`POST` 和 `PUT` **替换整个**覆盖规则列表。空列表恢复配置的全局倍率（默认 1.0）。规则支持模型模式和目录厂商 / 系列范围，每条都带一个相对厂商标价的直接倍率。倍率支持 6 位小数，范围 0.000001–1000；优先级 0–10000，默认 100。最多接受 200 条规则。重复的规范化精确模式、厂商 / 系列范围或 ID 返回 `MODEL_PRICE_RULE_DUPLICATE`。按上述顺序，第一条启用的规则替换全局倍率：

```json
{ "rules": [ { "modelPattern": "openai/gpt-5*", "priceMultiplier": 0.1, "priority": 10, "enabled": true } ] }
```

`POST /admin/model-pricing/preview` 接受 `{ "model": "starry/gpt-5.5", "rules": [...] }`。提供稳定的 `ruleId` 值，用来标识草稿匹配。它按保存时的约束校验草稿，使用当前的全局倍率，**从不**持久化任何改动。响应包含：

- `model`：请求的 ID；`pricingModel`：解析出的目录定价 ID；
- `source`：`official`、`normalized`、`legacy_total`、`legacy_itemized` 或 `unavailable`；`matchedPriceModel`：可用时的已验证来源键；
- `multiplier`：有效倍率，旧版绝对价格时省略；
- `matchedRuleIds`：按应用顺序列出的启用且匹配的规则，第一条胜出；
- `basePricing`：1.0 倍的厂商标价；`pricing`：提交的草稿下的有效价格，两者都用公开的价格结构。

请求可以另带 `factors`（号池价格系数的列表，每个大于 0、至多 1000，至多 9 个；越界返回 `400 MODEL_PRICE_FACTOR_INVALID`）。规则只命中**一条**，系数在它**之后**再乘：同一个预览里先找出生效的规则，再把它的结果乘上每个系数。响应此时多一个 `factorPricing`，与 `factors` 同序，每项是 `{ "factor": 0.6, "pricing": {…} }`，`pricing` 的结构同 `pricing`；不带 `factors` 时没有这个字段。

空草稿会预览默认值，即使已有保存的规则。预览使用标准的非快速定价；请求的快速模式仍然应用它原有的 2 倍附加费。缺失的价格在预览里表示为 `unavailable`，而不是让预览请求失败。旧版的原始 `*Multiplier` 字段仍被接受，并由界面保留；显式设置 `priceMultiplier` 会在保存时清除旧版价格。

规则的范围字段：

| 范围 | 必填字段 | 覆盖 |
| --- | --- | --- |
| `model`（默认） | `modelPattern` | 精确 ID，或显式的 `*` 通配符。裸的 `gpt` / `gpt5` **不是**前缀匹配 |
| `provider` | `provider`、`priceMultiplier` | 该厂商拥有的所有被请求的目录模型，包括以后新增的 |
| `family` | `provider`、`family`、`priceMultiplier` | 该厂商的目录系列，包括以后新增的 |

示例：`{"scope":"provider","provider":"OpenAI","priceMultiplier":0.2,"priority":100}` 设置 OpenAI 的默认值；`{"scope":"family","provider":"OpenAI","family":"GPT-5","priceMultiplier":0.1,"priority":100}` 为 GPT-5 覆盖它。四类 token 价格都使用选定的倍率，**倍率从不叠乘**。归属依据是被请求的目录条目，而不是上游目标的厂商。厂商 / 系列匹配不区分大小写；`Gemini` 规范化为 `Google`。范围规则忽略 `modelPattern`（为兼容返回 `*`），需要倍率，且不能创建旧版固定价。省略 `scope` 保留旧的模型模式行为。无效的范围或缺失的厂商 / 系列字段返回 `MODEL_PRICE_SCOPE_INVALID`。

---

## 模型映射

这些端点需要管理员会话。普通用户会话不能列出、创建、更新或删除映射规则。

```http
GET    /admin/model-mappings
POST   /admin/model-mappings
PATCH  /admin/model-mappings/{mappingId}
DELETE /admin/model-mappings/{mappingId}
```

```json
{
  "name": "openai-fast-to-haiku",
  "mappingType": "alias",
  "sourceModel": "gpt-4o-mini",
  "targetModels": ["claude-haiku-4.5"],
  "priority": 10,
  "apiKeyIds": null
}
```

`sourceModel` 支持精确匹配和 `*` 通配符，匹配长度限制在 256 字符以内。`apiKeyIds` 为 `null` 表示对所有 Key 生效。

---

## 支付与余额

下列路由对任何已认证的 `ADMIN` 或 `USER` 账号开放，订单始终限定在登录用户名下：

```http
GET  /payment/settings
GET  /payment/orders
POST /payment/orders
GET  /payment/orders/{orderId}
POST /payment/orders/{orderId}/cancel
POST /payment/redeem-code
```

创建充值订单，需要金额、`/payment/settings` 里启用的一种支付方式，以及可选的返回页元数据：

```json
{ "amount": 100, "paymentType": "alipay", "returnUrl": "https://console.example/#/payment", "mobile": false }
```

支付配置、所有用户的订单查看、兑换码管理和邀请返利历史需要 `ADMIN` 会话：

```http
GET   /admin/payment/settings
PATCH /admin/payment/settings
GET   /admin/payment/orders
GET   /admin/payment/orders/{orderId}
GET   /admin/payment/redemption-codes
POST  /admin/payment/redemption-codes
POST  /admin/payment/redemption-codes/{codeId}/revoke
GET   /admin/payment/invite-rebates
```

兑换码创建的请求体：`{ "balance": 10, "count": 3, "expiresAt": null }`（余额码），或 `{ "planId": "...", "count": 1 }`（套餐码）；`count` 取 1–100。

EasyPay 向下面任一形式的公开回调发送签名的异步通知。这些路由**不使用控制台会话**；支付服务会在给余额入账之前验证回调签名和订单状态：

```http
GET  /payment/webhook/easypay
POST /payment/webhook/easypay
```

---

## Coding Plan 订阅

API Key 在创建时选择 `billingSource: "BALANCE" | "CODING_PLAN"`，没有待处理请求时可以修改。已有的 Key 默认 `BALANCE`。**余额 Key 从不花订阅额度；Coding Plan Key 从不回退去扣钱包。**管理员名下的 Key 保留它们原有的「不扣钱包」行为。普通用户的请求在派发上游之前先预留资金，再结算实际用量并释放差额。

用户范围的路由需要控制台会话：

```http
GET  /payment/coding-plans
POST /payment/coding-plans/{planId}/purchase
GET  /payment/coding-plan-subscriptions
GET  /payment/coding-plan-subscriptions/page?page=1&pageSize=20&status=ACTIVE
GET  /payment/coding-plan-subscriptions/summary?provider=OpenAI
GET  /payment/coding-plan-subscriptions/{subscriptionId}/ledger?before=0
```

购买需要 `USER` 角色、JSON 请求体 `{"version": 1}`，以及稳定的 `Idempotency-Key` 请求头（1–100 字符）。路径标识套餐。服务端校验展示的版本，并原子地：扣钱包、追加一条购买账本记录、签发订阅。用同一个请求头安全地重试同一操作。把同一个键用于不同条款会返回 `409 IDEMPOTENCY_CONFLICT`；产品版本变了会返回 `409 CODING_PLAN_VERSION_CHANGED`。

每个订阅和签发的套餐兑换码都固定一个**不可变的产品版本**。编辑产品会发布新版本，不改变之前已签发的额度、厂商范围或时长。停用或调整产品顺序不会发布新的财务条款。同一产品的续订会**排队**：它们在前一期到期或被用完时才可用，而不是在额度只是被预留的时候。

管理员路由：

```http
GET   /admin/coding-plans
POST  /admin/coding-plans
PATCH /admin/coding-plans/{planId}
GET   /admin/coding-plan-subscriptions?userId={userId}
GET   /admin/coding-plan-subscriptions/page?page=1&pageSize=20&search=&status=
POST  /admin/coding-plan-subscriptions
PATCH /admin/coding-plan-subscriptions/{subscriptionId}
GET   /admin/coding-plan-subscriptions/{subscriptionId}/ledger?before=0
GET   /admin/billing/reviews
POST  /admin/billing/reviews/{requestHash}/resolve
```

产品示例：

```json
{
  "name": "Coding Pro",
  "description": "Monthly developer subscription",
  "priceCredits": "20",
  "durationDays": 30,
  "quotaCredits": "100",
  "allowedProviders": ["OpenAI", "Anthropic"],
  "sortOrder": 0
}
```

- 金额最多 6 位小数。产品价格和额度以**十进制字符串**序列化。创建时省略厂商列表（或显式给空列表）覆盖所有厂商；PATCH 时省略则保留当前范围。
- 手动发放用 `{"userId":"user-1","planId":"plan-1"}` 加一个 `Idempotency-Key` 请求头。状态变更必须给出原因，例如 `{"status":"REVOKED","reason":"Entitlement correction"}`。恢复一个已过期的订阅，或与已经推进的续订重叠的期限，会返回 409。
- 订阅响应包含 `planVersion`、`reservedCredits`、`availableCredits` 和有效状态（`ACTIVE`、`SCHEDULED`、`EXHAUSTED`、`EXPIRED`、`REVOKED`、`CANCELLED`）。`remainingCredits` 是额度减去已消耗的用量；`availableCredits` 还会额外扣除冻结额度。**在把一行当作可花费之前先检查状态。**汇总端点在服务端聚合所有匹配的订阅，包括已撤销 / 过期期限上未结清的冻结。分页响应含 `items,total,page,pageSize`（页大小最大 200）；账本分页最多 100 条，用最后一条的 ID 作 `before`。旧版的列表端点最多返回 200 行。

**余额不足与计费复核**：余额或匹配的套餐额度不足，会在派发之前返回 `402`。

- 请求在已经发出之后断开 / 读超时 / 响应不完整，其未知成本会保留冻结并需要复核；该用户（所有 Key）之后的调用会返回 `409 BILLING_REVIEW_REQUIRED`。
- 上游明确返回的错误响应，以及请求根本没发出去的失败，会直接释放冻结，不进入复核。
- 请求在跨协议中转手上时是例外，反过来：中转只被问一次，从派发到结算返回，无论读超时、响应不完整，还是客户端断开，冻结都**确定释放**（运营者承担，不向用户收费），不进复核。请求在原生账号手上时才按上面的规则保留冻结，此前已经问过原生账号、可能已计费的请求也仍然进复核。
- 管理员用 `{"action":"SETTLE","credits":"0.15","reason":"Verified upstream usage"}` 或 `{"action":"WAIVE","reason":"Confirmed no charge"}` 处理，每个操作都会被审计。
- `GET /admin/billing/reviews` 里，按号源分级服务的请求的预留按它**可能**被服务的号池里预留最多的那个定额，所以按预留金额结算会让用户为没有服务过他的号池付费。这类行因此另带 `tier_plan`（预留时冻结的号池计划）、`served`（实际服务的号池，含级别、系数、报价 `quote` 和该级的预留估算 `estimate`）和 `last_dispatched`（最后派发到的号池及时间，同样带报价）。核对时请按 `served`（没有则按 `last_dispatched`）的报价估算金额，而不是预留金额；没有分级的请求不含这些字段。
- 在计费未完成时修改 Key 的厂商 / 计费来源或删除它，会返回 `409 API_KEY_BILLING_BUSY`。

套餐兑换码用 `planId` 取代 `balance`（前缀 `PLN-`）；兑换时遵循固定的版本，即使该产品之后被更新或下架。目前的结账方式是钱包购买、管理员发放或兑换码；**套餐的网关直付尚未实现**。迁移和恢复的细节见 [Coding Plan 重建与验证](CODING_PLAN_REBUILD.md)。

---

## 工单

`/tickets` 需要控制台会话。普通用户只能访问自己的工单；管理员可以访问全部。未授权的归属以 404 表示。客户端的代理 API Key **不能**访问这些端点。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | `/tickets` | 分页列表；`page=1`、`pageSize=20`（最大 100），可选 `status` 和 `search`；返回 `items`、`total`、`page`、`pageSize`，以及按范围统计的状态 `counts` |
| POST | `/tickets` | multipart 创建：JSON 部分 `request` 含 `title`、`category`、`body`；可选重复的 `files` 部分 |
| GET | `/tickets/{id}` | 最新 30 条消息和工单元数据；`before` 加载更早的消息；返回 `ticket`、按时间顺序的 `messages`、`hasMore`、`nextBefore` |
| POST | `/tickets/{id}/replies` | multipart 的 JSON `request` 含 `body`、必需的 `version`、可选的管理员回复 `status`；可选重复的 `files` |
| PATCH | `/tickets/{id}/status` | JSON `{ "status": "CLOSED", "version": 1 }`；过期的版本返回 409 |
| GET | `/tickets/{id}/attachments/{attachmentId}` | 需要认证的二进制下载，no-store，attachment disposition |
| GET / PUT | `/admin/ticket-settings` | 管理员的通知设置，以及 SMTP / 收件人 / 失败状态 |
| POST | `/admin/ticket-settings/{id}/retry` | 管理员重试失败的、符合条件的通知；要求已启用通知 |

分类：`GENERAL`、`API`、`BILLING`、`ACCOUNT`、`BUG`。状态：`OPEN`、`IN_PROGRESS`、`WAITING_USER`、`RESOLVED`、`CLOSED`。用户回复会设为 `OPEN`；管理员回复默认 `WAITING_USER`。已结束的工单需要重新打开才能回复。

通知 PUT 的请求体：`{ "enabled": false, "mode": "SUMMARY", "recipients": [], "reminderMinutes": 240, "consoleUrl": "https://example.com" }`。`mode` 取 `SUMMARY` 或 `CONTENT`；收件人必须是已启用管理员的邮箱，空表示全部。限制、投递行为和验证见 [工单系统](SUPPORT_TICKETS.md)。

---

## 公开系统状态

```http
GET /system/status
```

这个端点是**公开**的，这样控制台和外部监控能区分「正常运行」与「故障关闭的维护状态」。它只暴露 `status`、`maintenance`、`code`、`message`、`checkedAt`，**不会**暴露密钥验证尝试、管理员身份或安全文件细节。

---

## PostgreSQL 数据传输

所有这些端点都要求初始应用 Root 的、数据库支撑的 `ADMIN` 会话，并作用于 PostgreSQL 17 的数据库 / schema `starry_api`。普通管理员会收到 `403 DATA_MANAGEMENT_ROOT_REQUIRED`（包括读取状态和备份列表），即使他们拥有安全密钥或 Root 签发的授权。旧版静态管理员令牌依然被禁止：

```http
GET    /admin/data-transfer/status
GET    /admin/data-transfer/security/status
POST   /admin/data-transfer/security/init
POST   /admin/data-transfer/security/unlock
GET    /admin/data-transfer/database.dump
GET    /admin/data-transfer/backup.zip
GET    /admin/data-transfer/backups
POST   /admin/data-transfer/backups
GET    /admin/data-transfer/backups/{fileName}
DELETE /admin/data-transfer/backups/{fileName}
POST   /admin/data-transfer/import?confirm=RESTORE_DATABASE
```

**怎么用**：

1. **初始化**：数据管理安全机制只能由应用 Root 用一个数据库支撑的 ADMIN 会话初始化。初始化返回形如 `dmsk-...` 的安全密钥，**只返回一次**。请直接复制进受控的密码管理器；控制台不提供明文下载。服务只在属主专有的安全状态文件里保存一个加盐的 PBKDF2 校验值。
2. **解锁换令牌**：受保护的操作之前，在 `/security/unlock` 用密钥换一个短期的 `dmt-...` 令牌，它绑定当前管理员会话、请求的动作和可选的备份文件名目标。
3. **带令牌调用**：把令牌作为 `X-Data-Management-Token` 发给受保护的端点。

支持的动作：`EXPORT_DATABASE`、`EXPORT_BACKUP`、`CREATE_BACKUP`、`DOWNLOAD_BACKUP`、`DELETE_BACKUP`、`RESTORE_DATABASE`，另有单独的页面授权 `VIEW_DATA_MANAGEMENT`（用于 `status` 和备份列表读取，不能用于任何破坏性 / 导出操作）。所有授权都只能签发给应用 Root 并由它使用；下载 / 删除类动作需要把确切的备份文件名作为 `target`。**删除和恢复的令牌只消耗一次。**

**锁定与恢复**：连续 5 次错误的安全密钥尝试会永久锁定数据管理，并让整个站点进入故障关闭的维护模式：只有静态控制台资源、`GET /system/status` 和健康端点保持可用；认证、管理、代理和支付 API 都返回 `503 SYSTEM_MAINTENANCE`。恢复是**服务启动期间的运维流程**，不是另一个公开端点。内置的 Docker 部署把可写的、仅属主的输入固定在 `/app/security/recovery.key`；通过镜像里交互式的 `/app/bin/recover-data-management --lang zh|en` 工具创建它，然后重启 `service`。完整流程见 [运维手册：恢复被锁定的数据管理](OPERATIONS.md#恢复被锁定的数据管理)。

**审计与脱敏**：每次失败的尝试会先原子地缓冲，再幂等地追加到 `starry_security.data_management_security_audit_events`，带有事件发生时管理员 / Root 邮箱的快照和已脱敏的请求元数据，即使当前没有配置 Root 邮箱也会进入持久发件箱。转发头只保留有效的 IP 字面量；User-Agent 和客户端请求 ID 以不可逆的 SHA-256 指纹保存。SMTP 投递优先使用事件快照，否则每次重试都通过不可变的 Root 标记重新解析当前邮箱。投递是至少一次，并带有稳定的 `X-Starry-Security-Event-Id`。第 5 次的邮件主题会明确说明后端很可能已被攻破。本地事件、审计 / 发件箱行、邮件和结构化日志里，**都不含**密码、密码哈希 / 盐、安全密钥、提交的错误值、会话令牌或数据管理令牌。`starry_security` schema 被排除在应用的备份 / 恢复之外，所以较旧的归档无法回滚之后的安全证据。

**文件格式**：`database.dump` 是 PostgreSQL 自定义格式的归档。`backup.zip` 和已存储的备份文件是 ZIP 包，含 `database.dump`、元数据和恢复说明。导入端点只接受 `.dump` 归档或 Starry API 的 `.zip` 包（multipart 字段 `file`）；它会校验归档、串行化维护操作，并在恢复前创建一份回滚备份。

---

## 用量、日志与登录账号

```http
GET  /admin/usage-summary/me
GET  /admin/usage-summary
GET  /admin/logs
GET  /admin/performance
GET  /admin/users
POST /admin/users
PATCH /admin/users/{userId}
POST /admin/users/{userId}/reset-password
GET  /admin/users/registration-settings
PATCH /admin/users/registration-settings
GET  /admin/users/invite-codes
POST /admin/users/invite-codes
POST /admin/users/invite-codes/batch
POST /admin/users/invite-codes/{codeId}/revoke
```

`GET /admin/usage-summary/me` 对任何已认证账号开放，只返回该用户自己的总计和按模型的用量。全局的 `/admin/usage-summary`、`/admin/logs`、`/admin/performance` 和 `/admin/users/*` 路由需要 `ADMIN` 会话；`USER` 账号会收到 `403 ADMIN_ROLE_REQUIRED`。全局用量响应还包含活跃用户汇总。

为了熔断对账，每条底层的 `request_logs` 行还保存 `circuit_epoch`、`circuit_key`、`circuit_tier`、`upstream_model` 和可空的 `upstream_success`。`success` 仍是端到端的代理结果，而 `upstream_success` 区分「上游调用成功」（`true`）和「会计入熔断的失败」（`false`）；上游成功但本地用量结算失败时它仍为 `true`；账号级或不可重试的失败为 `null`。带索引的熔断窗口只限于当前的启用纪元，所以只包含那次启用期间成功的上游调用、可重试的传输失败和上游声明可重试的 `408`、`409`、`5xx`，不会纳入账号级的 `401`、`402`、`403`、`429` 行。这些字段是内部的持久化元数据，目前**不**包含在 `GET /admin/logs` 的响应投影里。

`/admin/performance` 返回最近 120 个性能样本。后端每 30 秒记录一个：

```json
{
  "systemCpuLoadPercent": 12.5,
  "processCpuLoadPercent": 3.2,
  "heapUsedBytes": 134217728,
  "heapCommittedBytes": 268435456,
  "heapMaxBytes": 1073741824,
  "nonHeapUsedBytes": 98304000,
  "systemMemoryTotalBytes": 8589934592,
  "systemMemoryFreeBytes": 4294967296,
  "systemMemoryUsedPercent": 50.0,
  "networkRxBytes": 123456789,
  "networkTxBytes": 987654321,
  "networkRxBytesPerSecond": 2048.0,
  "networkTxBytesPerSecond": 1024.0,
  "sampledAt": "2026-06-07T12:00:00Z"
}
```

网络计数器读取自 Linux 的 `/proc/net/dev`；在非 Linux 宿主上这些网络字段可能为 `null`。

**创建或更新登录账号**，用 `role=ADMIN` 或 `role=USER`。`balance` 是普通用户非负的账户余额：

```json
{
  "username": "normal-user",
  "email": "user@example.com",
  "displayName": "Normal User",
  "role": "USER",
  "balance": 100,
  "password": "optional-initial-password"
}
```

公开的自助注册和邀请码注册会创建余额为零的 `USER` 账号。邀请码创建的请求体：`{ "count": 3, "expiresAt": null }`，`count` 取 1–100。
