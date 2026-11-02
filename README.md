<p align="center">
  <img src="docs/images/brand/logo.svg" width="96" height="96" alt="Starry API">
</p>

# Starry API

[![CI](https://github.com/StaR4y/starry-api/actions/workflows/ci.yml/badge.svg)](https://github.com/StaR4y/starry-api/actions/workflows/ci.yml)

**一个自托管的大模型 API 网关与管理控制台。** 把你手里的各种上游模型账号（OpenAI、Anthropic、DeepSeek、Kiro 等）汇总成一个统一入口，对外提供 OpenAI、Anthropic、Gemini 三种兼容协议，并配有完整的账号池调度、API Key、计费、日志和后台管理界面。

![仪表盘](docs/images/dashboard.jpg)

## 它解决什么问题

| 你的场景 | Starry API 怎么帮你 |
| --- | --- |
| 手里有多个上游账号 / Key，想统一管理 | 账号池：自动轮询、限流保护、失败切换、健康检查 |
| 想让团队或朋友共用，又不想泄露上游 Key | 给每个人发独立的 **API Key**，设置额度上限，随时停用 |
| 客户端各不相同（Cursor、Claude Code、Codex CLI、OpenCode……） | 同时兼容 OpenAI / Anthropic / Gemini 协议，并能一键生成各客户端的配置 |
| 想按用量收费或自己对账 | 厂商标价计费、余额与充值、兑换码、Coding Plan 订阅、完整请求日志 |
| 想知道钱花在哪、哪个账号出了问题 | 仪表盘、请求日志、账号状态与分配策略一目了然 |

## 工作原理

```text
你的客户端 (Cursor / Claude Code / Codex CLI / 自己的程序)
        │   使用 Starry 发的 API Key，调用 /v1/chat/completions 等接口
        ▼
┌───────────────────────── Starry API ─────────────────────────┐
│  鉴权 → 限流 → 模型映射 → 账号池选择 → 转发 → 计费与日志        │
└───────────────────────────────────────────────────────────────┘
        │   自动挑选一个健康的上游账号
        ▼
OpenAI · Anthropic · DeepSeek · Kiro(AWS) · 其他 OpenAI 兼容站点
```

## 主要功能

- **多协议入口**：`/v1/chat/completions`、`/v1/responses`（OpenAI），`/v1/messages`（Anthropic），`/v1beta/models/{model}:generateContent`（Gemini）。
- **多种上游账号**：Kiro、OpenAI API Key、ChatGPT 浏览器授权（Codex）、手动 Codex Token、DeepSeek、Anthropic API Key，以及任意 OpenAI 兼容站点。
- **智能账号池**：按厂商隔离、预热分级、并发与 RPM 保护、失败重试与冷却，可选实验性的分级熔断。
- **号源分级**：把一个厂商的账号分成有序的号池（官方 Key 一级、Kiro 二级……），每级一个价格系数，按实际服务请求的号池计价；客户端可以用 `模型@号池` 或请求头指定；可选地把 OpenAI 格式的中转登记为别的厂商的一级。见 [运维手册](docs/OPERATIONS.md#号源分级)。
- **API Key 与计费**：按用户发放、绑定模型厂商、额度上限、余额计费或 Coding Plan 订阅计费；内置厂商标价目录（来自社区维护的 `model-price-repo`，不是厂商的接口）并自动校验更新。
- **完整的管理控制台**：仪表盘、请求日志、用户、兑换码、在线充值、工单、使用文档、模型目录与价格倍率、外观定制，中英文界面。
- **数据安全**：Root 专属的「数据管理」（备份 / 导出 / 恢复），所有高危操作要二次验证。
- **可扩展**：全信任的插件机制，示例见 [插件开发](docs/PLUGINS.md)。
- **技术栈**：Java 25、Spring Boot 4（WebFlux、Jackson 3）、R2DBC、PostgreSQL 17、Flyway、Vue 3，控制台内嵌在同一个 jar 里。

## 5 分钟快速开始

选一种方式，把服务跑起来：

| 方式 | 适合谁 | 文档 |
| --- | --- | --- |
| **服务器一键安装** | 有一台 Linux 服务器，想要最省事的生产部署 | [服务器一键安装](docs/INSTALL_SERVER.md) |
| **1Panel / Docker Compose** | 习惯用 1Panel 面板，或想从源码构建镜像 | [1Panel 部署](docs/1PANEL_DEPLOY.md) |
| **本机源码运行** | 想在自己电脑上试用或开发 | 往下看 ↓ |

### 本机源码运行

需要：Java 25、PostgreSQL 17、Node.js（构建控制台）。

**① 准备数据库**（只需一次，`POSTGRES_USER` 填本机 PostgreSQL 的超级用户）：

```bash
export POSTGRES_USER=postgres
export POSTGRES_DB=starry_api
export STARRY_POSTGRES_MIGRATION_PASSWORD='换成一个强密码-迁移用'
export STARRY_POSTGRES_APP_PASSWORD='换成另一个强密码-应用用'

createdb --username "$POSTGRES_USER" --encoding=UTF8 "$POSTGRES_DB"
./docker/postgresql/init/001-starry-api-roles.sh
```

**② 启动服务**：

```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DATABASE=starry_api
export POSTGRES_USERNAME=starry_api
export POSTGRES_PASSWORD="$STARRY_POSTGRES_APP_PASSWORD"
export POSTGRES_MIGRATION_USERNAME=starry_api_migration
export POSTGRES_MIGRATION_PASSWORD="$STARRY_POSTGRES_MIGRATION_PASSWORD"

./scripts/start-proxy.sh
```

**③ 确认服务正常**：

```bash
curl http://127.0.0.1:8080/health
```

**④ 登录控制台**：打开 <http://127.0.0.1:8080/#/login>，用户名 `admin`，密码是**首次启动时日志里打印的那一次**随机密码。第一次登录会强制你改密码。（Docker 部署的服务器上，用 `sudo starry-api info` 查看登录信息，忘了密码用 `sudo starry-api reset-password` 重置，见 [运维手册](docs/OPERATIONS.md#服务器上的-starry-api-命令)。）

![登录页](docs/images/login.jpg)

接下来按 [快速开始指南](docs/QUICKSTART.md) 一步步添加上游账号、创建 API Key，并发出第一个请求。

## 文档导航

| 我想…… | 看这里 |
| --- | --- |
| 从零到发出第一个请求 | [快速开始指南](docs/QUICKSTART.md) |
| 了解控制台每个页面怎么用 | [控制台使用手册](docs/CONSOLE_GUIDE.md) |
| 部署到服务器 | [一键安装](docs/INSTALL_SERVER.md) · [1Panel](docs/1PANEL_DEPLOY.md) · [预构建产物](docs/PREBUILT_ARTIFACTS.md) |
| 查某个配置项 / 环境变量 | [配置参考](docs/CONFIGURATION.md) |
| 日常运维：导入账号、备份恢复、日志保留 | [运维手册](docs/OPERATIONS.md) |
| 调用接口 / 写集成 | [API 参考](docs/API.md) |
| 遇到报错 | [故障排查](docs/TROUBLESHOOTING.md) |
| 了解内部设计 | [架构说明](docs/ARCHITECTURE.md) · [包结构与扩展](docs/PACKAGE_GUIDE.md) |
| 开发插件 | [插件开发](docs/PLUGINS.md) |
| 完整目录 | [文档索引](docs/README.md) |

## 项目结构

```text
service-api/   接口契约与共享 DTO
plugin-api/    插件 SDK
service/       Spring Boot 服务：管理接口、账号池、代理转发、计费、统计
  └─ platform/ 各厂商协议适配（openai / anthropic / gemini / deepseek / kiro）
webui/         Vue 3 管理控制台（构建后嵌入 service 的 jar）
docker/        PostgreSQL 初始化脚本
scripts/       启动、部署、发布、恢复脚本
docs/          文档（含 docs/images 截图）
```

## 开发与验证

```bash
./gradlew test                     # 后端测试
npm --prefix webui run build       # 构建控制台
./gradlew :service:bootJar         # 打包完整 jar（含控制台）
```

发布可部署产物的流程见 [预构建产物](docs/PREBUILT_ARTIFACTS.md)。

## 当前能力边界

使用前请了解这些限制，免得踩坑：

- 普通 OpenAI / GPT 请求默认只走**官方 OpenAI API Key** 账号（Base URL 为 `api.openai.com`）；Codex 账号默认与之隔离，需要管理员显式开启才会混用。
- DeepSeek 请求只会走 DeepSeek 账号，不会回退到别的厂商。
- Anthropic Messages 请求优先使用原生 Anthropic API Key 账号，不可用时回退到兼容的 Kiro 账号。
- Gemini 目前是**入站兼容协议**：内置 Gemini 别名通过 Kiro 目标模型执行；还不支持直连 Google API Key 和 `streamGenerateContent`。
- `stream=true` 目前在上游完成后再以 SSE 输出，还不是真正的逐块透传。
- 图片 / 文档 / 文件转换仅部分支持；尚未实现分布式限流。

## 免责声明 / Disclaimer

Starry API 是独立的开源项目，与 Anthropic、OpenAI、Google、Amazon Web Services、DeepSeek 等任何厂商均无隶属、合作或背书关系。Claude、Claude Code、ChatGPT、Codex、OpenAI、Gemini、Kiro、Amazon Q、CodeWhisperer、DeepSeek 等名称和标识归各自权利人所有，本项目仅用于说明可连接的服务。你需要自行确保接入的每个厂商和账号都符合其条款，包括账号共享、转售、自动化访问和支持地区的规定。本软件能实现的某些配置可能被这些条款禁止。软件按现状提供，厂商可能限制或停用使用它的账号。

Starry API is an independent open-source project. It is not affiliated with, endorsed by or sponsored by Anthropic, OpenAI, Google, Amazon Web Services, DeepSeek or any other provider. Claude, Claude Code, ChatGPT, Codex, OpenAI, Gemini, Kiro, Amazon Q, CodeWhisperer, DeepSeek and related names and logos belong to their owners and are used only to identify services this software can connect to. You are responsible for complying with the terms of every provider and account you connect, including rules on account sharing, resale, automated access and supported regions. Some configurations this software makes possible may be prohibited by those terms. The software is provided as is, and providers may suspend accounts used with it.

## 安全

发现安全漏洞时，请**不要**开公开的 issue，而是用 GitHub 的私密漏洞报告（仓库页面 **Security → Report a vulnerability**）。支持的版本、受理范围和插件的信任模型见 [SECURITY.md](SECURITY.md)。

## 参与贡献

欢迎提 issue 和 Pull Request。动手之前请先读 [CONTRIBUTING.md](CONTRIBUTING.md)（环境要求、测试方式、提交规范）和 [行为准则](CODE_OF_CONDUCT.md)。你提交的代码按 AGPL-3.0-only 授权，并附带 [NOTICE](NOTICE) 第 2 节的插件附加许可（见下面的「许可证」一节；`plugin-api/` 的改动按 Apache-2.0）。

## 许可证

Starry API 以 **GNU Affero 通用公共许可证第 3 版（仅此版本，SPDX：`AGPL-3.0-only`）** 发布，全文见 [LICENSE](LICENSE)。依赖库的许可证选择、源码和许可证文本在哪里、数据来源、商标声明和可选组件 Mihomo 的说明见 [NOTICE](NOTICE)；控制台构建时还会生成 `THIRD-PARTY-NOTICES.txt`（npm 依赖，末尾附 logback 的版权声明和 LGPL-2.1-only 说明，随控制台一起提供，页脚的「第三方许可」链接指向它），Java 依赖的清单是构建生成的 `JAVA-THIRD-PARTY-NOTICES.txt`（在 jar 的 `META-INF/` 和部署包里）。

- **向网络用户提供源码（AGPL 第 13 条）**：控制台页脚显示「源代码」链接，地址由环境变量 `STARRY_SOURCE_URL` 设置，默认 `https://github.com/StaR4y/starry-api`。**如果你修改了代码并让别人通过网络使用它，必须把它指向你修改后的源码。**见 [配置参考](docs/CONFIGURATION.md#源代码与许可证)。
- **插件 SDK**：[`plugin-api/`](plugin-api/) 使用 [Apache License 2.0](plugin-api/LICENSE)。同时，在 AGPL-3.0 第 7 条下附加如下许可（原文为英文，以 [NOTICE](NOTICE) 第 2 节为准）：只通过 `plugin-api` 与 Starry API 交互、且不含 Starry API 其他部分的插件，是独立作品，可以按你选择的条款（包括闭源）授权和使用。这项许可不覆盖前端挂件插件的浏览器脚本（`widget.json` 声明、由控制台从 `/plugin-assets` 加载的脚本）：它运行在控制台页面里，用的是控制台自己的钩子，不是 `plugin-api` 的接口。

  > Additional permission under section 7 of the GNU Affero General Public License, version 3: a plugin that interacts with Starry API only through the interfaces of the plugin-api module (including the HTTP calls it makes through PluginContext.request), and that contains no other part of Starry API, is an independent work and is not a covered work under the license of Starry API. You may license, convey and use such a plugin under terms of your choice, including proprietary terms, together with an unmodified or modified Starry API. This permission does not extend to a plugin that copies, links against or otherwise builds on any part of Starry API other than plugin-api, and it does not extend to the browser script of a web widget plugin (the script that widget.json declares and the console loads from /plugin-assets), which runs inside the console page and uses the console's own hooks, not the interfaces of plugin-api. If you modify Starry API, you may extend this permission to your version, but you are not obliged to; if you do not wish to, delete this paragraph from your version.

- **名称与标识**：「Starry」「Starry API」和 Starry 标识不在软件许可的授权范围内（AGPL-3.0 第 7(e) 条）；第三方的名称和标识归各自权利人所有，见上面的免责声明。
- **Mihomo**：可选的出站代理组件 Mihomo 使用 GPL-3.0，是单独的容器镜像，部署时从镜像仓库拉取，**不随本项目分发**。
