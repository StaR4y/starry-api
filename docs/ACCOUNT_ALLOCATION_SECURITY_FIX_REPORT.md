# 账号调度策略漏洞修复与测试报告

- 报告日期：2026-09-04
- 修复状态：本轮定义范围内已完成
- 协调组件：PostgreSQL 17（未引入 Redis）
- 数据库迁移：Flyway V8-V10；本轮新增功能位于不可变迁移 `V10__account_scoped_cooldowns_and_retention.sql`

## 1. 修复结论

本轮将账号选择从「先在内存选中、调用结束后尽力更新」收敛为「PostgreSQL 原子准入租约 + 明确的终态回收」。官方 OpenAI API Key、Codex、DeepSeek 和 OpenAI-compatible 账号池已隔离；请求级错误及模型/端点级限流不再污染账号全局健康；客户端取消、计费失败和响应构建失败不再泄漏并发或 token 预留。

默认路由策略调整为安全模式：

| 设置 | 默认值 | 行为 |
| --- | --- | --- |
| `isolateCodexPool` | `true` | Codex 与官方 OpenAI API Key 隔离 |
| `gptUsesCodexAccounts` | `false` | 普通 GPT 请求默认使用官方 OpenAI API Key |
| `bindSessionOnAdmission` | `false` | 准入成功时不提前绑定会话 |
| `bindSessionByApiKey` | `false` | 不把整个客户端 API Key 当作一个会话 |
| Codex 最大尝试账号数 | `1` | 已派发请求默认不跨 Codex 账号重试 |
| OpenAI / DeepSeek 最大尝试账号数 | `3` | 仅在错误分类允许时使用不同账号重试 |
| Anthropic / Kiro 最大尝试账号数 | `2` | 保持较保守的跨账号重试上限 |

## 2. 已修复问题

### 2.1 准入租约在非标准终止路径泄漏

**原风险：** 客户端取消、计费异常、日志/响应构建异常或 reconcile 失败时，已预留的并发和 token 可能等待十分钟过期，连续触发后会造成可用账号被误判为满载。

**修复：** `ProxyFacade` 使用 `Mono.usingWhen` 管理每次准入租约。正常成功和上游失败执行 reconcile；派发前失败执行 release；取消或本地管线异常执行 abandon；reconcile 自身失败时回退 abandon。数据库用账号级 advisory transaction lock 串行化终态变更，重复终结保持幂等。

### 2.2 过期租约回收可能被后续拒绝回滚

**原风险：** 如果先回收过期租约、随后因 RPM 或其他容量限制拒绝新请求，回收计数可能随函数事务路径丢失，形成长期并发计数漂移。

**修复：** V9 在执行任何新准入判断前持久回收过期 `RESERVED`/`DISPATCHED` 租约，并修正 `active_concurrency` 与 token 预留。真实 PostgreSQL 测试覆盖「回收后仍拒绝」的路径。

### 2.3 排名快照版本导致无意义的准入拒绝

**原风险：** 候选排名使用短时缓存，携带旧 `state_version` 进入数据库后会把本来可用的账号拒绝为 stale，并在并发流量下放大无账号可用错误。

**修复：** 排名只负责候选顺序，准入始终以数据库锁内最新状态为准；代理传入 `expectedStateVersion=null`。版本校验能力仍保留给需要显式 CAS 的管理或诊断调用。

### 2.4 请求错误和局部限流污染账号全局健康

**原风险：** `400/404/422` 或单模型、单端点 `429` 被当作账号级故障后，会增加全局错误计数、降级甚至冷却整个凭据，导致无关模型一起失去容量。

**修复：** `REQUEST`、`CAPACITY_MODEL`、`CAPACITY_ENDPOINT` 三类失败只释放本次租约并写分配事件，不修改账号全局 lifecycle/EWMA/`error_count`。V10 增加 `account_scoped_cooldowns`，模型或端点限流只阻断该账号的对应 scope，其他模型和端点继续可用。

### 2.5 不同 OpenAI 形态账号池互相串用

**原风险：** OpenAI-compatible、DeepSeek、Codex 与官方 OpenAI API Key 的候选谓词过宽时，请求可能落到错误主机或认证方式，既产生失败，也可能把第三方请求发往非预期提供方。

**修复：** 四类账号使用独立选择入口和 provider hint。重试始终留在初始 provider/subtype 池内，DeepSeek 或第三方池耗尽时直接返回无可用账号，不跨池兜底。

### 2.6 会话亲和键不一致及并发振荡

**原风险：** 读写使用不同的模型池前缀会造成「写入了但读不到」；并发请求直接覆盖同一亲和键会让后续请求在账号间振荡。把裸 API Key 默认当会话还会把互不相关的用户请求粘到同一账号。

**修复：** 亲和读写统一通过同一个模型池 key；写入使用 `putIfAbsent`，首个成功结果稳定拥有绑定。默认只在上游成功后绑定显式会话，裸 API Key 绑定与准入时提前绑定均改为显式 opt-in。

### 2.7 重试重复选择失败账号

**原风险：** 上游失败后的自动复测或递归选择若丢失已尝试集合，可能再次派发同一凭据，浪费重试额度并放大限流。

**修复：** 已派发失败账号和仅准入被拒账号分别跟踪，再合并为排除集合；自动复测后沿用原集合。跨账号重试同时受错误 scope 上限和路由设置上限约束。

### 2.8 历史表无界增长及部署配置未透传

**原风险：** 租约、分配事件、过期 session/cooldown/response owner 长期增长会增加索引与维护成本；新增保留参数若只存在于 Spring 配置，Compose 用户修改 `.env` 也不会生效。

**修复：** V10 增加有界批量清理函数和每日维护服务。默认保留终态租约 7 天、分配事件 90 天；过期 session、scope cooldown 和 response owner 立即具备清理资格。源 Compose、预构建 Compose、安装脚本和 `.env.example` 已全部透传 `STARRY_ACCOUNT_ALLOCATION_*`。

## 3. 关键实现位置

| 范围 | 文件 |
| --- | --- |
| 代理生命周期与重试 | `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java` |
| 错误分类 | `service/src/main/java/tech/starryapi/proxy/UpstreamFailureClassifier.java` |
| 账号池隔离与亲和 | `service/src/main/java/tech/starryapi/account/AccountPoolService.java` |
| 路由安全默认值 | `service-api/src/main/java/tech/starryapi/account/AccountDtos.java` |
| PostgreSQL 准入适配 | `service/src/main/java/tech/starryapi/account/allocation/PostgresAccountAdmissionStore.java` |
| 保留维护任务 | `service/src/main/java/tech/starryapi/account/allocation/AccountAllocationMaintenanceService.java` |
| 数据库一致性与局部冷却 | `service/src/main/resources/db/migration/V9__account_allocation_consistency_fixes.sql`、`V10__account_scoped_cooldowns_and_retention.sql` |

## 4. 自动化测试结果

### 4.1 后端全量回归

命令：

```bash
./gradlew test -PskipWebuiBuild=true --rerun-tasks
```

结果：`BUILD SUCCESSFUL`。JUnit 共发现 614 项测试，556 项实际执行，0 失败，0 错误；58 项 Testcontainers PostgreSQL 测试因当前机器没有 Docker 而跳过。

本轮直接相关覆盖包括：

- 准入携带正确的模型/端点 scope，且不使用陈旧 lifecycle version。
- 客户端取消执行 abandon。
- 派发标记失败执行 release，且不会调用上游。
- 上游成功后计费失败仍完成 reconcile。
- 响应构建失败不会重新打开已 reconcile 的租约。
- reconcile 失败回退 abandon，且不错误地失败已完成响应。
- `400/404/422` 不更新账号全局健康。
- 四类 OpenAI 形态账号池互相隔离。
- 亲和读写 key 一致，且并发提交不覆盖首个绑定。
- 维护任务按批循环、受维护锁约束、可配置关闭。
- R2DBC 存储层完整绑定 scoped acquire、typed null、failure scope、cooldown 和 JSON evidence。

### 4.2 真实 PostgreSQL 17 验证

由于 Docker/Testcontainers 不可用，另行启动本机临时 PostgreSQL 17，完整执行 V1-V10 后做 SQL 冒烟断言。以下结果全部通过：

- 模型冷却只拒绝匹配模型，其他模型仍可使用同一账号。
- scoped failure 不改变账号全局 lifecycle、EWMA 或兼容 `error_count`。
- abandon 立即释放并发/token 预留并记录 `ABANDONED`。
- 过期租约回收后，即使下一次准入被拒，计数仍保持正确。
- `REQUEST` failure 不改变全局健康。
- stale 非空版本被拒绝，空版本以锁内最新状态正常准入。
- 租约、事件、session、cooldown、response owner 五类清理各删除一条目标记录。

临时数据库已正常停止并删除。

### 4.3 部署与前端验证

| 验证 | 结果 |
| --- | --- |
| `bash scripts/test-install-server.sh` | 通过，含新增保留参数生成/透传断言 |
| `bash -n scripts/install-server.sh scripts/publish-artifact.sh scripts/test-install-server.sh` | 通过 |
| `npm run build`（`webui`） | 通过，Vue 类型检查与 Vite 生产构建完成 |
| `RUN_TESTS=false BUILD_WITH_DOCKER=false ./scripts/publish-artifact.sh` | 通过，使用已验证源码重建 JAR、WebUI 包及 bundle |
| artifact 与 bundle SHA-256 清单 | 全部匹配 |
| 源码/预构建 Compose YAML 解析 | 通过 |
| 发布 JAR 内容检查 | 包含静态 WebUI 与 V10 迁移 |
| `git diff --check` | 通过，无空白错误 |

前端首次构建发现 `MaintenanceView.vue` 有一个未使用的 appearance store 声明；已做无行为变化的最小删除，随后构建通过。

## 5. 部署注意事项

1. 已应用的 Flyway 迁移禁止原地修改。后续调整使用 V11 及更高版本，避免 V10 checksum 不一致。
2. 服务启动时自动应用 V10。生产迁移前仍应完成数据库备份，并验证 migration role 与 runtime role 权限。
3. 当前备份恢复契约要求精确的 V1-V10 历史和 checksum；旧版本备份不能绕过该校验直接覆盖新库。
4. 多实例无需 Redis 即可保证并发/RPM/token 准入正确性，因为权威状态与锁都在 PostgreSQL。应确保所有实例连接同一主库，并按总连接预算配置 R2DBC 池。
5. 上线后重点监控 `ACCOUNT_ADMISSION_EXHAUSTED`、`MODEL_COOLDOWN`、`ENDPOINT_COOLDOWN`、租约终态分布和维护任务告警。

## 6. 剩余风险

以下事项不影响本轮准入一致性结论，但仍建议后续处理：

- **代理出口与 TLS 身份未强制校验。** 当前没有账号到固定出口 IP、代理身份、TLS profile 的基线约束；对于严格风控的上游，这是最高优先级的剩余风险。
- **错误 scope 仍部分依赖响应文本。** 模型/端点限流和永久凭据撤销的识别使用状态码与已知文本证据；应逐步补充各提供方结构化错误字段及 header 解析。
- **亲和状态是实例本地缓存。** 多实例间的准入正确性不受影响，但显式会话可能在负载均衡切换实例后重新选择账号。需要跨实例强亲和时，可优先使用负载均衡层 cookie/一致性哈希，之后再评估独立共享 KV。
- **熔断状态为最终一致。** 实例每 30 秒从 PostgreSQL 请求日志窗口收敛，短窗口内不同实例可能作出不同的 primary/secondary 决策；原子准入仍会阻止容量超发。
- **延迟指标不是 TTFT。** 当前健康反馈记录完整上游 attempt latency，流式请求的首 token 体验尚未单独建模。

综合判断：当前不需要为了账号准入引入 Redis。引入 Redis 只能改善跨实例亲和或更低延迟的共享短状态，不能替代现有 PostgreSQL 事务、审计、恢复和计费一致性；在没有明确多实例亲和指标前，会增加双写与故障恢复复杂度。
