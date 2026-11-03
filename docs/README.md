# 文档索引

这里是 Starry API 的全部文档。不知道从哪看起？先找最像你的情况：

| 你是…… | 先看 |
| --- | --- |
| 第一次接触，想知道它是什么 | [项目 README](../README.zh-CN.md) |
| 想最快跑通、发出第一个请求 | [快速开始指南](QUICKSTART.md) |
| 要把它部署到服务器 | [部署指南](DEPLOYMENT.md) |
| 管理员，想知道每个页面怎么用 | [控制台使用手册](CONSOLE_GUIDE.md) |
| 出了问题 | [故障排查](TROUBLESHOOTING.md) |

> 文档里的截图都取自真实的控制台界面，数据是演示用的虚构数据。截图集中放在 [`images/`](images/) 目录。

> **免责声明 / Disclaimer**
>
> Starry API 是独立的开源项目，与 Anthropic、OpenAI、Google、Amazon Web Services、DeepSeek 等任何厂商均无隶属、合作或背书关系。Claude、Claude Code、ChatGPT、Codex、OpenAI、Gemini、Kiro、Amazon Q、CodeWhisperer、DeepSeek 等名称和标识归各自权利人所有，本项目仅用于说明可连接的服务。你需要自行确保接入的每个厂商和账号都符合其条款，包括账号共享、转售、自动化访问和支持地区的规定。本软件能实现的某些配置可能被这些条款禁止。软件按现状提供，厂商可能限制或停用使用它的账号。
>
> Starry API is an independent open-source project. It is not affiliated with, endorsed by or sponsored by Anthropic, OpenAI, Google, Amazon Web Services, DeepSeek or any other provider. Claude, Claude Code, ChatGPT, Codex, OpenAI, Gemini, Kiro, Amazon Q, CodeWhisperer, DeepSeek and related names and logos belong to their owners and are used only to identify services this software can connect to. You are responsible for complying with the terms of every provider and account you connect, including rules on account sharing, resale, automated access and supported regions. Some configurations this software makes possible may be prohibited by those terms. The software is provided as is, and providers may suspend accounts used with it.
>
> 许可证：AGPL-3.0-only，见仓库根目录的 [LICENSE](../LICENSE) 与 [NOTICE](../NOTICE)。

---

## 一、使用指南（给管理员和用户）

| 文档 | 内容 |
| --- | --- |
| [快速开始指南](QUICKSTART.md) | 从登录、改密、添加上游账号、创建 API Key，到发出第一个请求，一共 6 步，带截图 |
| [控制台使用手册](CONSOLE_GUIDE.md) | 逐页讲解：仪表盘、账号管理、API Key、模型映射、模型管理、日志、用户、兑换码、数据管理、系统设置、余额充值、Coding Plan…… |
| [工单系统](SUPPORT_TICKETS.md) | 用户提交问题、管理员回复、邮件通知、限制与存储 |
| [用户文档系统](USER_DOCUMENTATION.md) | 管理员在线编写教程、图片 / 视频 / 附件、Apifox 入口、备份 |
| [中英文界面](INTERNATIONALIZATION.md) | 切换界面语言、双语文档、怎么维护翻译 |
| [账号出站代理与路由限制](ACCOUNT_EGRESS.md) | Clash 节点订阅、固定出口、Mihomo / TLS 部署、并发与会话窗口 |

## 二、部署与运维（给运维）

| 文档 | 内容 |
| --- | --- |
| [部署指南](DEPLOYMENT.md) | 一键安装、Docker Compose、从源码构建；反向代理、升级回滚、安装变量、发布新版本 |
| [配置参考](CONFIGURATION.md) | 所有环境变量和系统设置：数据库、JVM、备份、价格、支付、保留策略…… |
| [运维手册](OPERATIONS.md) | 导入账号、测试、账号分配与熔断、API Key 与客户端配置、**备份恢复**、日志保留、在线充值 |
| [号源分级与跨协议中转](SOURCE_TIERS.md) | 号池怎么选、怎么计费，中转的上线步骤、门控、失败处理和各客户端的效果 |
| [故障排查](TROUBLESHOOTING.md) | 按症状找原因：启动、登录、调用报错、维护锁定、邮件 |
| [性能与内存基线](PERFORMANCE.md) | 一次实测的内存与压测数据、推荐的 JVM 预算和监控阈值 |

## 三、开发与扩展（给开发者）

| 文档 | 内容 |
| --- | --- |
| [API 参考](API.md) | 代理接口（OpenAI / Anthropic / Gemini 协议）和全部管理接口 |
| [架构说明](ARCHITECTURE.md) | 请求流程、模块划分、模型目录、持久化、**账号池调度细节**、信任边界 |
| [包结构与扩展指南](PACKAGE_GUIDE.md) | 代码该放哪、包之间的规则、怎么新增协议或上游 |
| [插件开发](PLUGINS.md) | 开发者模式、安装 / 热重载、SDK、示例插件「灵动岛」 |

---

## 四、技术记录（历史文档，保持原样）

下面这些文档记录了特定时间点的修复、验证和规划，内容不随使用指南更新，供追溯背景时查阅。

| 文档 | 内容 |
| --- | --- |
| [账号调度策略漏洞修复与测试报告](ACCOUNT_ALLOCATION_SECURITY_FIX_REPORT.md) | 2026-09-04 的账号调度漏洞修复与测试 |
| [模型价格覆盖修复与验证](MODEL_PRICING_FIXES.md) | 2026-09-20 的价格覆盖修复 |
| [修复 OpenAI 503 错误状态码映射](FIX_503_ERROR_MAPPING.md) | 503 错误映射的修复方案与映射表 |
| [CI 故障修复报告](CI_FIX_REPORT_2026-09-22.md) | 2026-09-22 的 CI 故障根因与修复 |
| [Coding Plan 重建](CODING_PLAN_REBUILD.md) | 套餐计费的实现细节、迁移与恢复、验证 |
| [项目计划](PLAN.md) | 当前状态、设计规则、已知缺口与各工作流的路线图 |

---

## 写文档的约定

- 命令都写成可以直接复制运行的形式；需要你替换的值用「`<尖括号>`」或明显的占位文字标出。
- 截图里的红框和数字，对应正文里的编号。
- 凡是**有风险的操作**（恢复数据库、清空账号、关闭开发者模式……）都会用 ⚠️ 单独标出。
- 文档与代码一起维护：改动了界面或行为，请同步更新对应的文档和截图。
