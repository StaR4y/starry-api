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

> 想看**界面操作**而不是接口？请看 [控制台使用手册](CONSOLE_GUIDE.md)。想快速发出第一个请求？请看 [快速开始](QUICKSTART.md)。

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
| `400` | 请求体无效，或缺少模型 / 消息 |
| `401` | 缺少或无效的 API Key / 管理员令牌 |
| `402` | 余额或订阅额度不足（在转发到上游之前就拒绝） |
| `403` | 需要先改首次登录密码；管理员权限不足；API Key 的模型厂商限制 |
| `409` | 并发冲突（版本号过期）、幂等键冲突、计费待复核 |
| `413` | 请求体超过配置的上限 |
| `429` | API Key 限流、额度上限，或上游容量 / 限流。上游的 `429` 是**暂时**的，本身不会让账号被标记为额度耗尽 |
| `502` | 上游返回了不可恢复的错误 |
| `503` | 没有可用的上游账号；或数据管理安全锁定后整站的 `SYSTEM_MAINTENANCE` |

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

- `models` 是 `data` 的兼容别名。被管理员停用的条目会同时从两个数组里省略。顺序遵循管理员保存的全局显示顺序。
- 每个条目既给出「每 100 余额能买多少 token」，也给出精确的**每 100 万 token 有效价格**。默认情况下，服务从 [`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) 读取输入 / 输出 / 缓存写 / 缓存读的美元成本，再乘以管理员的「模型官方价格计费倍率」，默认 `1.0`——所以余额的数字扣费等于官方美元成本。
- 逐项计费的模型会填充四个详细的 `*PricePerMillionTokens` 字段，同时把 `totalPricePerMillionTokens` 作为面向用户的「50% 输入 + 50% 输出」参考价。**实际计费仍按真实的 token 类别**。旧版固定总价的规则则暴露其真实的固定总价，包括通配规则。
- **价格解析是确定的**：启用的规则按优先级升序，同优先级时 精确模型 → 目录系列 → 目录厂商 → 通配符；通配符按字面长度降序。请求别名的精确匹配先于目标的精确匹配；其余并列用模式和规则 ID。**只有胜出的规则生效**，它的倍率直接替换全局倍率，所以 `0.1` 永远表示官方价的 10%。已知前缀（`starry/`、`openai-compatible/`、`openai/`、`anthropic/`、`google/`、`gemini/`、`deepseek/`）在精确规则和价格查找时都会规范化。优先使用模型自己的精确 / 规范化官方价格，否则沿着配置的目录目标走；**不会**使用系列 / 厂商中位数。
- 既没有经验证的价格、也没有显式旧版固定价的条目，会暴露 `billingMode: "unavailable"` 且不含数值价格；计费请求会在预留和派发之前返回 `503 MODEL_PRICE_UNAVAILABLE`。

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

Anthropic Messages 请求和 `anthropic/` 模型 ID 会**先**选择原生的 `provider=Anthropic`、`authMethod=api-key` 账号，并使用 `AnthropicUpstreamClient`。该子类池不可用时，路由回退到兼容的 Kiro / AWS 账号。OpenAI 兼容端点上的裸 Claude 模型 ID 继续走 Kiro 路由，除非某条模型映射选择了 `anthropic/` 目标。

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

> **Gemini 目前只是入站兼容层**：内置的 Gemini 目录别名在构造 Kiro payload 时解析成 Kiro 兼容的目标模型。直连 Google / Gemini API Key 的上游派发、直连账号测试和 `streamGenerateContent` **都没有实现**。

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

`GET /auth/permissions` 返回 `data: {"root": true|false}`，带 `Cache-Control: no-store`。Root 由不可变的 `application_root_user_id` 和账号当前的「已启用 / 管理员 / 密码已修改」状态解析而来，**从不**依据用户名或浏览器存储。普通管理员、用户和旧版静态管理员令牌得到 `root: false`。控制台用这个响应来隐藏和保护「数据管理」与「插件开发」；服务端会在每个请求上独立地强制它们的权限。

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

管理员通过 `GET/PATCH /admin/users/registration-settings` 配置注册和 SMTP。SMTP 字段沿用 sub2api 风格的设置键：`email_verify_enabled`、`smtp_host`、`smtp_port`、`smtp_username`、`smtp_password`、`smtp_from_email`、`smtp_from_name`、`smtp_use_tls`、`smtp_security`。API 的 JSON 使用 camelCase 字段名，响应里只暴露 `smtpPasswordConfigured`，**绝不返回**已保存的密码。`smtpSecurity` 取 `auto`、`ssl`、`starttls`、`none`；腾讯企业邮用 `smtp.exmail.qq.com:465` + `ssl`，或 587 端口 + `starttls`。

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
- 通用的 OpenAI 兼容账号，`baseUrl` 可以是站点根（如 `https://joystar.cc`）或带版本的 API 根（如 `https://openrouter.ai/api/v1`）。路径不是以 API 版本段结尾时，默认调用 `/v1/chat/completions` 和 `/v1/responses`。
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
  "maxInflightTokens": 2000000
}
```

校验范围：优先级和并发 `1..10000`、RPM `1..1000000`、突发 `1..100000`、会话数 `0..100000`、会话空闲 `60..86400` 秒、在途 token `1..1000000000`。`allocationPriority` **越小越优先**。OpenAI、Codex、DeepSeek、OpenAI 兼容账号对这个调度器是无状态的，所以服务端总是为它们存 `maxSessions=0`。Anthropic 和 Kiro 兼容账号可以用会话限额。

响应包含可编辑字段，外加 `lifecycleState`、`stateVersion`、`warmupStage`、`cooldownUntil`、`recentErrorRate`（`0..1` 的 EWMA）、`recentLatencyMs`（整次尝试的延迟，**不是** TTFT）、`activeConcurrency`、输入 / 输出 token 预留和 `updatedAt`。新的 Anthropic 账号默认并发 / RPM / 突发 / 会话限额是 `2/20/2/4`；OpenAI 家族是 `4/60/4/0`；Kiro / 其他是 `2/30/2/4`。所有类型默认优先级 `100`、会话空闲 600 秒、在途 token 1,000,000。账号状态的含义见 [架构说明：账号池行为](ARCHITECTURE.md#账号池行为)。

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

### 实验性的分级熔断

**账号池分级熔断策略**是实验性功能，**默认关闭**，用 `PATCH /admin/accounts/settings` 开关：

```json
{ "poolPolicy": { "enabled": true } }
```

关闭时，账号选择忽略 `poolTier` 的熔断路由，控制台保持普通的账号管理模式：隐藏熔断指标、分层控件、权重和策略横幅，页头只暴露一个「启用实验功能」入口。启用需要确认「不稳定」提示，然后控制台切换成只读的熔断监控布局，并隐藏账号变更操作。启用后的布局始终显示实验标签、不稳定警告和关闭开关，因为实验期间仍可能发生误熔断、路由抖动和监控口径变化。

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

`POST` 和 `PUT` **替换整个**覆盖规则列表。空列表恢复配置的全局倍率（默认 1.0）。规则支持模型模式和目录厂商 / 系列范围，每条都带一个相对官方价格的直接倍率。倍率支持 6 位小数，范围 0.000001–1000；优先级 0–10000，默认 100。最多接受 200 条规则。重复的规范化精确模式、厂商 / 系列范围或 ID 返回 `MODEL_PRICE_RULE_DUPLICATE`。按上述顺序，第一条启用的规则替换全局倍率：

```json
{ "rules": [ { "modelPattern": "openai/gpt-5*", "priceMultiplier": 0.1, "priority": 10, "enabled": true } ] }
```

`POST /admin/model-pricing/preview` 接受 `{ "model": "starry/gpt-5.5", "rules": [...] }`。提供稳定的 `ruleId` 值，用来标识草稿匹配。它按保存时的约束校验草稿，使用当前的全局倍率，**从不**持久化任何改动。响应包含：

- `model`：请求的 ID；`pricingModel`：解析出的目录定价 ID；
- `source`：`official`、`normalized`、`legacy_total`、`legacy_itemized` 或 `unavailable`；`matchedPriceModel`：可用时的已验证来源键；
- `multiplier`：有效倍率，旧版绝对价格时省略；
- `matchedRuleIds`：按应用顺序列出的启用且匹配的规则，第一条胜出；
- `basePricing`：1.0 倍的官方价格；`pricing`：提交的草稿下的有效价格，两者都用公开的价格结构。

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

**余额不足与计费复核**：余额或匹配的套餐额度不足，会在派发之前返回 `402`。请求在已经发出之后断开 / 读超时 / 响应不完整，其未知成本会保留冻结并需要复核；该用户（所有 Key）之后的调用会返回 `409 BILLING_REVIEW_REQUIRED`。上游明确返回的错误响应，以及请求根本没发出去的失败，会直接释放冻结，不进入复核。管理员用 `{"action":"SETTLE","credits":"0.15","reason":"Verified upstream usage"}` 或 `{"action":"WAIVE","reason":"Confirmed no charge"}` 处理。每个操作都会被审计。在计费未完成时修改 Key 的厂商 / 计费来源或删除它，会返回 `409 API_KEY_BILLING_BUSY`。

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

所有这些端点都要求初始应用 Root 的、数据库支撑的 `ADMIN` 会话，并作用于 PostgreSQL 17 的数据库 / schema `starry_api`。普通管理员会收到 `403 DATA_MANAGEMENT_ROOT_REQUIRED`——包括对状态和备份列表的读取——即使他们拥有安全密钥或 Root 签发的授权。旧版静态管理员令牌依然被禁止：

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
