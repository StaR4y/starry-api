# 包结构与扩展指南

Starry API 已经从「以 Kiro 为中心的代理」演进成**多平台路由服务**。包名描述的是它所拥有的**平台边界**，而不是碰巧第一个执行调用的上游适配器。

这份指南讲三件事：**代码该放在哪个包**、**包与包之间的硬性规则**、以及**怎么新增一个客户端协议或上游适配器**。整体设计见 [架构说明](ARCHITECTURE.md)。

---

## 后端规则

后端源码根目录：

```text
service/src/main/java/tech/starryapi
```

### 各包的职责

| 包 | 负责什么 | 可以依赖 |
| --- | --- | --- |
| `proxy` | 请求编排、鉴权、限流、模型映射、账号选择、计费、日志 | `platform/*`、account / API key / model / stat 服务 |
| `service-api/model` | 对外的模型目录 API 契约、模型 DTO，以及面向后端的 `ModelCatalogProvider` 抽象 | JDK、Jackson 注解、Spring Web 注解 |
| `platform/core` | 与厂商无关的补全结果、用量、工具调用记录 | 仅 Jackson 和 JDK |
| `platform/catalog` | 服务内部的实现细节：为 `ModelCatalogProvider` 聚合各厂商的目录类 | 各厂商的目录类 |
| `platform/openai` | OpenAI 兼容的请求适配器、响应工厂、模型别名、OpenAI / Codex / DeepSeek 的 HTTP 传输，以及 OpenAI 账号测试 | `platform/core`、`platform/catalog`、`platform/upstream`、账号类型和 Token 刷新 |
| `platform/anthropic` | Anthropic 兼容的请求适配器、响应工厂、模型别名，以及原生 Anthropic API Key 传输 | `platform/core`、`platform/catalog`、`platform/upstream`、账号类型 |
| `platform/gemini` | Gemini 兼容的请求适配器、响应工厂、Gemini 模型别名 | `platform/core`、`platform/catalog` |
| `platform/kiro` | Kiro / AWS 的 payload 构造、端点派发、按 Token 的请求头、模型 ID 转换、AWS 事件流解析 | `platform/core`、`platform/catalog`、`platform/upstream`、账号 / 配置服务 |
| `platform/upstream` | 共享的上游异常契约；尚未引入通用的执行适配器接口 | 尽量只依赖 JDK |

### 硬性规则

1. **OpenAI、Anthropic、Gemini 三个模块不能互相 import。**
2. 客户端协议模块**不能** import `platform/kiro`——它们只格式化 `PlatformCompletionResult`。
3. 带 Kiro 的名字只能出现在：`platform/kiro` 内部、Kiro 账号的导入 / 刷新代码、部署兼容变量，或明确讨论迁移的文档里。
4. 共享的模型能力、默认目标 ID 和模型列表 DTO 属于 `service-api/model`；服务代码要依赖 `ModelCatalogProvider`，而不是直接读各厂商的目录。
5. 新增入站协议：新建一个 `platform/<provider>` 包，包含请求适配器、响应工厂、模型目录和与包对齐的测试。
6. 新增上游执行能力：新建一个 `platform/<upstream>` 包；在引入更丰富的上游适配器接口之前，它应当返回 `PlatformCompletionResult`。
7. `account` 包可以为多个厂商存放凭据，但**请求路由必须只选择与当前上游适配器兼容的账号**。

---

## 前端规则

前端源码根目录：

```text
webui/src
```

| 文件 / 包 | 负责什么 |
| --- | --- |
| `platforms/types.ts` | 共享的平台和安装脚本类型 |
| `platforms/registry.ts` | 统一的厂商顺序、模型厂商 / 系列推断、CSS 类查找 |
| `platforms/<provider>/index.ts` | 厂商的展示元数据和模型 ID 推断 |
| `platforms/<provider>/setup.ts` | 厂商专属的客户端环境变量或配置片段 |
| `platforms/clientSetup.ts` | 把各片段汇总成 macOS / Linux / Windows 脚本 |
| `platforms/models.ts` | 给页面 import 用的兼容性再导出 |

前端的硬性规则：

- `views` 可以从 `@/platforms/models` 或 `@/platforms/clientSetup` 导入，但**不应硬编码**厂商顺序、系列顺序或客户端环境变量块。
- 新增厂商 = 新增 `platforms/<provider>/index.ts`、可选的 `platforms/<provider>/setup.ts`，并在 `registry.ts` 或 `clientSetup.ts` 里注册。
- 页面组件应当渲染注册表里的元数据；**不要**在页面里判断某个模型是不是 OpenAI、Anthropic、Gemini 或别的厂商。

---

## 怎么新增一个客户端协议（入站）

比如要让 Starry API 多支持一种客户端协议：

1. 新增 `service/.../platform/<provider>/<Provider>RequestAdapter.java`。
2. 新增 `service/.../platform/<provider>/<Provider>ResponseFactory.java`。
3. 新增 `service/.../platform/<provider>/<Provider>ModelCatalog.java`。
4. 把目录注册到 `platform/catalog/ProxyModelCatalog`。
5. 只为公开端点的形状，在 facade / controller 里加路由。
6. 在 `service/src/test/java/.../platform/<provider>` 下加测试。
7. 新增 `webui/src/platforms/<provider>/index.ts`，并在 `registry.ts` 里注册。
8. 只有当客户端需要厂商专属的环境变量时，才添加配置片段（`setup.ts`）。

## 怎么新增一个上游适配器（出站）

比如要接入一个新的上游厂商：

1. 新增 `service/.../platform/<upstream>`。
2. 在这个包里，把与厂商无关的对话结构转换成该上游的 payload。
3. 上游调用路径返回 `PlatformCompletionResult`。
4. 凭据、请求头、端点选择和事件解析**都留在上游包内部**。
5. 只有适配器确实需要时，才添加账号元数据和配置字段。
6. 在 `service/src/test/java/.../platform/<upstream>` 下加测试。

> 新增上游后别忘了：账号选择必须按厂商 / 账号子类型隔离（见 [架构说明：账号池行为](ARCHITECTURE.md#账号池行为)），不要让新的上游和现有厂商互相串用账号。
