# 号源分级实施任务清单

与 [设计文档](2026-10-24-tiered-source-pools-design.md) 配套。每个阶段可以单独发布；没有配置分级的部署，行为与现在完全相同。

## Phase 0 准备（零变化）

### P0-01 PoolCell 枚举作为账号池谓词唯一来源

- 文件：`service/src/main/java/tech/starryapi/account/PoolCell.java`, `service/src/main/java/tech/starryapi/account/AccountPoolService.java`, `service/src/test/java/tech/starryapi/account/PoolCellTest.java`
- 内容：八个单元（DEEPSEEK…XP_OPENAI_CHAT），谓词与 AccountPoolService.java:364-573 内联谓词逐一相同；另提供 predicate(Set<String> excluded) 重载（默认空集，枚举不持有注入状态）。select*ForPool 改用它，签名、错误消息、调用顺序不变；selectOpenAiAccountByIdForPool 不变。
- 测试：账号矩阵（官方/中转/旧兼容/自定义/DeepSeek/Codex/官方与第三方 Anthropic/OAuth/Kiro/Google）下新旧谓词结果一致。
- 验收：ProxyFacadeRoutingTest 不改通过。

### P0-02 ProxyFacade 分级运行时注入点

- 文件：`service/src/main/java/tech/starryapi/proxy/tier/TierRuntime.java`, `service/src/main/java/tech/starryapi/proxy/tier/TierRuntimeConfig.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`
- 内容：record TierRuntime（planner 恒返回 UNTIERED 的 disabled()），由 @Bean 工厂创建。新构造器接管 @Autowired（现有注解在 ProxyFacade.java:159-160，两个必选 @Autowired 构造器会启动失败）；其他构造器委托，前一个委托构造器仍设 admissionStore=permissive、responseOwnerStore=null（155-156）。invokeChat 增加 rawEntryBody 参数（messages/gemini 传原始体），本阶段不读。
- 测试：两个门面测试的构造与断言不改；Spring 上下文测试确认用新构造器。
- 验收：零行为变化。

### P0-03 PlatformCompletionResult 新组件与按非空渲染

- 文件：`service/src/main/java/tech/starryapi/platform/core/PlatformCompletionResult.java`, `service/src/main/java/tech/starryapi/platform/anthropic/AnthropicResponseFactory.java`, `service/src/main/java/tech/starryapi/platform/openai/OpenAiResponseFactory.java`, `service/src/main/java/tech/starryapi/platform/gemini/GeminiResponseFactory.java`
- 内容：追加可空 stopReason、usageSource、degraded，保留 2/3/4 参数构造器；工厂只在 stopReason 非空时按设计 7.10 渲染。
- 测试：null 时与现有输出逐字节相同；每个取值的渲染；ProxyWireFormatTest 不改。
- 验收：原生输出不变。

### P0-04 BillingQuote.scaled 与 JsonNodes.mediaStats

- 文件：`service/src/main/java/tech/starryapi/model/BillingQuote.java`, `service/src/main/java/tech/starryapi/common/JsonNodes.java`
- 内容：scaled(1) 返回 this，否则五项费率精确相乘，(0,1000] 之外抛异常；mediaStats 返回去 base64 的字符数/字节数、内联图片数、远程媒体数（Chat data URI、Anthropic source.data、Gemini inlineData；http image_url、file_id、fileUri 计远程）。
- 测试：精度与舍入同 BillingQuote.java:9-24；各种媒体形态。
- 验收：无调用方变化。

### P0-05 UpstreamException.generationStarted 与 Kiro 解析器标记

- 文件：`service/src/main/java/tech/starryapi/platform/upstream/UpstreamException.java`, `service/src/main/java/tech/starryapi/platform/kiro/AwsEventStreamParser.java`, `service/src/test/java/tech/starryapi/platform/kiro/AwsEventStreamParserTest.java`
- 内容：新增构造器参数 generationStarted（默认 false）。AwsEventStreamParser 在截断（50-54）和异常帧（88-96）时，若之前已解析出内容、工具或用量帧则置真。状态码与可恢复性不变，只有分级代码读取。
- 测试：先内容后截断/异常帧 → true；首帧即异常 → false；现有用例不变。
- 验收：未分级行为不变。

## Phase 1 数据与配置

### P1-01 V29 迁移

- 文件：`service/src/main/resources/db/migration/V29__source_tiers.sql`, `service/src/test/java/tech/starryapi/postgresql/SourceTiersMigrationPostgreSqlIntegrationTest.java`
- 内容：按设计 2.1-2.8：五张表（vendors 含 sticky、refusal_cap、cross_tier_reserved_slot；members 的 protocol 与 source_protocol CHECK 写宽为三个取值、两个缓存口径开关与 CHECK；models 的 max_context_tokens NOT NULL、image_token_cap）；api_keys.tier_preference；request_logs 10 列与重写的 ensure_request_log_partitions；billing_reservations 5 列；api_usage_events 2 列与触发器（NEW.request_hash::varchar，served 缺失时回退 last_dispatched 与 pricing 中的 factor）；settle_api_usage_served（p_level INTEGER）；种子与授权。
- 测试：PG（需 Colima + DOCKER_HOST）：全新库与 V28 数据库迁移；逐条 CHECK；级联；DEFERRED 交换级别；分区迁出保留 10 列（含并发插入）；包装函数首次、重复、冲突、REVIEW_REQUIRED；以 R2DBC 绑定 Java int 调用成功；触发器含核对回退；runtime 权限。
- 验收：PG 测试在 CI 实际运行并通过。

### P1-02 备份契约与文档版本

- 依赖：P1-01
- 文件：`service/src/main/java/tech/starryapi/admin/DataTransferService.java`, `service/src/test/java/tech/starryapi/admin/DataTransferMigrationContractTest.java`, `docs/CONFIGURATION.md`, `docs/OPERATIONS.md`, `docs/ACCOUNT_EGRESS.md`
- 内容：REQUIRED_FLYWAY_VERSION=29、追加 V29、五张表加入 REQUIRED_RESTORE_TABLES；CONFIGURATION.md:178、OPERATIONS.md:572,579 的 V1–V28 改 V1–V29，ACCOUNT_EGRESS.md:177 的“V28 恢复要求”改为 V29；发布说明要求升级后立即备份。
- 测试：契约测试；恢复表断言。
- 验收：契约测试通过。

### P1-03 SourceTierRegistry

- 依赖：P1-01
- 文件：`service/src/main/java/tech/starryapi/sourcetier/SourceTierRegistry.java`, `service/src/main/java/tech/starryapi/sourcetier/SourceTierSnapshot.java`, `service/src/main/java/tech/starryapi/admin/RuntimeStateInvalidator.java`
- 内容：不可变快照；SmartInitializingSingleton 阻塞首载（早于 Web 服务器，不用 ApplicationRunner）；30 秒兜底、失败保留；invalidateSetting/invalidateAll 刷新；tierOf 三条规则且类别按厂商提供方族限定；exclusiveXpAccountIds；loaded()。
- 测试：首载失败 loaded=false；刷新失败保留；类别限定（gpt 走 Messages 时官方 Anthropic Key 不进 openai 的 official）；PG 经 NOTIFY 刷新。
- 验收：空表时快照为空。

### P1-04 管理 API、校验与能力位

- 依赖：P1-01, P1-03
- 文件：`service-api/src/main/java/tech/starryapi/sourcetier/SourceTierAdminApi.java`, `service-api/src/main/java/tech/starryapi/sourcetier/SourceTierDtos.java`, `service/src/main/java/tech/starryapi/sourcetier/SourceTierAdminService.java`, `service/src/main/java/tech/starryapi/sourcetier/SourceTierValidator.java`, `service/src/main/java/tech/starryapi/sourcetier/SourceTierRuntimeInfo.java`
- 内容：设计 2.9、7.4、8 的接口与加锁事务；校验 tier_id、level、名称、类别唯一、厂商、账号；能力位 nativeTiers/unifyEndpoints/xpMembers 及对状态（本阶段全假），enabled=true、unify=true、跨协议成员行、非 OFF 门控在能力位为假时 409 SOURCE_TIER_RUNTIME_UNSUPPORTED；最低级经规则 3 收进 OFFICIAL_API_KEY 时返回警告；映射指纹变化清空 verified_*。
- 测试：逐规则单元测试；本阶段 enabled=true 被拒；PG 并发 PUT 一个 409、版本递增。
- 验收：不存在先存后静默生效的配置。

### P1-05 控制台号池分级页

- 依赖：P1-04
- 文件：`webui/src/views/settings/SourceTiersSettingsView.vue`, `webui/src/views/settings/components/source-tiers/TierTable.vue`, `webui/src/api/sourceTiers.ts`, `webui/src/views/settings/SystemSettingsLayout.vue`, `webui/src/router/index.ts`, `webui/src/utils/http.ts`, `webui/src/i18n/en.json`
- 内容：设计 8：厂商开关（全端点统一默认不勾选）、号池表、原生覆盖、预设草稿；按能力位禁用开关并说明；409 提示刷新；http.ts:150-155 的 url.includes 分支加 '/admin/source-tiers/'。
- 测试：npm run build、lint、test:i18n。
- 验收：可保存配置，代理行为不变。

## Phase 2 原生分级

### P2-01 TierPlanner

- 依赖：P0-01, P0-02, P0-04, P1-03
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/DefaultSourceTierPlanner.java`, `service/src/main/java/tech/starryapi/proxy/tier/TierPlan.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`
- 内容：设计第 3 节（AUTO、原生单元）：厂商与 modelKey、UNTIERED、单元映射、结构/可用性剪枝、报价、inputBound（分级 Kiro 单元去媒体）、Responses owner 解析与 409、响应头预计算、503 TIER_CONFIG_UNAVAILABLE、circuitEpoch=null。不置 nativeTiers。
- 测试：单元列表与 selectAccount 链路一致；剪枝；I5 未调用 reserveUsage；Kiro 单元 data URI 不升 1M；owner 不在计划内时可迁移忽略、不可迁移 409。
- 验收：未配置时 ProxyFacadeRoutingTest 不改通过。

### P2-02 selectTiered、selectInScope 与 D2 计数

- 依赖：P2-01
- 文件：`service/src/main/java/tech/starryapi/account/AccountPoolService.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/proxy/ProxyFacadeTieredSelectionTest.java`
- 内容：设计 4.2-4.4：selectInScope（refreshOnEmpty=false，作用域含 modelKey）；按级惰性走查；被拒集合与计数按请求保留；每级上限仅在其下还有级别时生效；owner 选号与回退经 selectTiered；选中时写 level/cell/dispatch 并校验（派发前失败）；原生派发参数同今天。
- 测试：16 次拒绝 → 2 级；64 次 → 耗尽；PIN 单级不受 16 限制；结构空单元不刷新；owner 在 L2 时 L1 不被选。
- 验收：未分级调用序列不变。

### P2-03 派发后重试规则

- 依赖：P2-02, P0-05
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/TierRetryPolicy.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/proxy/tier/TierRetryPolicyTest.java`
- 内容：设计 4.5：tierDefinitive（含 402，用于 recordFailure 与 withBilling 失败释放）、notStarted、跨级只限确定且未开始否则收窄、级内优先、跨级保留槽（cross_tier_reserved_slot）、准入耗尽返回原始错误、240 秒时间守卫、收窄后禁用回升/保留槽/crossTierOnly。预算规则本身不改。
- 测试：L1 402 → L2 429 → 预留 RELEASED；L1 Kiro 先内容后截断 → 不到 L2；L1 TimeoutException → 收窄；预算 2 + 两个 L1：第二个 L1 → 保留槽到 L2；保留槽关闭 → 原始错误；超过 240 秒不跨级。
- 验收：派发数至多比今天多 1（且仅在全部未生成时）。

### P2-04 按级预留、结算与续租

- 依赖：P2-02, P1-01
- 文件：`service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/main/java/tech/starryapi/proxy/tier/TierBilling.java`, `service/src/main/java/tech/starryapi/apikey/ApiKeyService.java`, `service/src/test/java/tech/starryapi/apikey/SettleServedSqlTest.java`
- 内容：设计 5.2-5.4、4.6：预留按 holdLevels 最大值（本任务先按全部级别，P2-05 接入起点）；pricing.tierPlan；上下文放 TierBilling；level 非空的分级尝试类型；settle_api_usage_served；markUsageDispatchedTiered 续租，派发运行期间由 renewDispatchLease 每 W/4 续期（不截断派发）；503 SOURCE_TIER_UNAVAILABLE 在外层转换。
- 测试：结算 0.0234 示例；调用 SQL 字符串单元测试（p_level 为 INTEGER）；未分级回归（BillingQuote.class、4 参数 settle、原 markUsageDispatched、pricing JSON 相同）；PG 续租不超 60 分钟。
- 验收：符合 I2、I3。

### P2-05 D4 级别绑定与级内亲和

- 依赖：P2-03, P2-04
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/TierSessionBindings.java`, `service/src/main/java/tech/starryapi/proxy/tier/TierSessionKeys.java`, `service/src/main/java/tech/starryapi/account/AccountPoolService.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/proxy/ProxyFacadeTieredStickyTest.java`
- 内容：设计 4.7 与 5.2：键含 modelKey；tierSessionKey（可靠 hint、Responses response id 转写、内容指纹）；只在真实降级时写（排除结构剪枝、预算跨级、crossTierOnly、本会话自身并发）；单调；起点与 holdLevels；最后手段回升只进 holdLevels 且未收窄；遵守 session-affinity-enabled；账号移除时清理。
- 测试：真实降级后停留、10 分钟后回 L1；HARD 特性请求与无映射模型在 L2 服务后下一个普通请求仍从 L1 开始；本会话并发拒绝不改绑定；晚完成的 L1 请求不改回；收窄后不回升；无会话头时内容指纹保持；3 轮 Responses 链降级后保持；sticky=false/PIN 不读写。
- 验收：符合 I8。

### P2-06 日志列、响应头与 DTO

- 依赖：P2-04
- 文件：`service/src/main/java/tech/starryapi/stats/StatsService.java`, `service-api/src/main/java/tech/starryapi/stats/StatsDtos.java`, `service/src/main/java/tech/starryapi/config/WebFluxConfig.java`, `service/src/main/java/tech/starryapi/apikey/BillingReviewController.java`, `service/src/test/java/tech/starryapi/config/WireFormatTest.java`
- 内容：RequestLog 追加 10 字段并保留 18 参数构造器；/logs/me 隐藏管理员字段；每次尝试写号源列；预计算的响应头（含合成 SSE）与 exposedHeaders；核对接口返回 tierPlan、served、last dispatched 及其报价。
- 测试：分级/未分级的日志与头；SSE 带头；WireFormatTest 有意更新。
- 验收：未分级无新列值与新头。

### P2-07 状态接口、/v1/models 与控制台页面

- 依赖：P2-06, P1-05
- 文件：`service/src/main/java/tech/starryapi/sourcetier/SourceTierStatusService.java`, `service/src/main/java/tech/starryapi/model/ModelCatalogController.java`, `webui/src/views/accounts/AccountListView.vue`, `webui/src/views/logs/LogListView.vue`, `webui/src/views/settings/CodingPlanSettingsView.vue`, `webui/src/views/models/components/ModelCatalogPricingPanel.vue`, `docs/OPERATIONS.md`, `docs/API.md`
- 内容：设计 5.7、8：状态面板；定价预览 factors；/v1/models 缩放与 sourceTiers；账号列表级别列与批量；日志号源列；计费核对显示服务级报价而非最大预留；旧熔断说明与文档修正。
- 测试：状态服务单元；未分级 /v1/models 不变；前端 build、lint、i18n。
- 验收：管理员可见实时状态。

### P2-08 分级自动复测与旧熔断隔离

- 依赖：P2-02
- 文件：`service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`
- 内容：复测过滤器为计划内单元谓词与级别成员的并集（取代 accountFilterFor 1190-1216），每请求一次；circuitEpoch=null 隔离。
- 测试：复测只覆盖计划成员；旧策略开启时分级请求 circuit 列为 null。
- 验收：旧熔断只作用于未分级。

### P2-09 原生分级端到端与 nativeTiers 能力位

- 依赖：P2-03, P2-04, P2-05, P2-06, P2-07, P2-08
- 文件：`service/src/test/java/tech/starryapi/proxy/ProxyFacadeTieredNativeTest.java`, `service/src/test/java/tech/starryapi/proxy/SelectForPoolCallSequenceSnapshotTest.java`, `service/src/test/java/tech/starryapi/postgresql/SourceTierEndToEndPostgreSqlIntegrationTest.java`, `service/src/main/java/tech/starryapi/sourcetier/SourceTierRuntimeInfo.java`
- 内容：门面：官方 Key L1 + Kiro L2；繁忙、确定失败、不确定失败、402、D4。快照：未分级调用序列。PG：reserve→dispatch→settle_api_usage_served→事件触发器→按号池汇总。最后置 nativeTiers=true。
- 测试：如上；PG 在 CI 实际运行。
- 验收：I4 成立；能力位只在全部原生任务完成后为真。

## Phase 3 手动选级

### P3-01 选择器、固定模式与错误

- 依赖：P2-09
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/TierSelectorParser.java`, `service/src/main/java/tech/starryapi/proxy/tier/DefaultSourceTierPlanner.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/proxy/ProxyFacadeTierPinTest.java`
- 内容：设计第 6 节：后缀（含 Gemini 路径）与请求头解析、优先级、PIN 级别过滤与预留、6.3 全部错误（请求头固定到不可选号池忽略）、6.4 的 ignored 规则、低余额提示更便宜号池。
- 测试：6.3 每行；未配置厂商带 '@' 的 id 同今天；被拒请求无预留。
- 验收：所有错误在预留前。

### P3-02 API Key 号源偏好

- 依赖：P3-01
- 文件：`service-api/src/main/java/tech/starryapi/apikey/ApiKeyDtos.java`, `service-api/src/main/java/tech/starryapi/apikey/ApiKeyPrincipal.java`, `service/src/main/java/tech/starryapi/apikey/ApiKeyService.java`, `webui/src/views/api-keys/ApiKeyListView.vue`
- 内容：DTO 追加 tierPreference 并保留委托构造器；GET /admin/source-tiers/me；不适用时忽略；匿名与 * Key 只能自动。
- 测试：线上格式；偏好生效与忽略。
- 验收：用户可设置偏好。

### P3-03 全端点统一（可选范围）与能力位

- 依赖：P2-09
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/DefaultSourceTierPlanner.java`, `webui/src/views/settings/SourceTiersSettingsView.vue`, `service/src/test/java/tech/starryapi/proxy/ProxyFacadeUnifyEndpointsTest.java`
- 内容：设计 4.9；向导默认不勾选；完成后置 unifyEndpoints=true。负责人决定不要时整项删去（O2）。
- 测试：开/关时 Chat 上 claude 的走向；Gemini id 不受影响。
- 验收：符合 4.9。

### P3-04 客户端片段、会话头核对与文档

- 依赖：P3-02
- 文件：`webui/src/platforms/clientSetup.ts`, `webui/src/views/models/ModelListView.vue`, `docs/API.md`
- 内容：「号池」片段；模型页价格与复制后缀；对照真实 Claude Code、Codex、OpenCode 核对后缀、请求头和会话头〔外部〕，可靠的 Codex 会话头加入 tierSessionKey 来源 1（不改 sessionHint）；文档写明 D4 依赖内容指纹的客户端。
- 测试：片段快照与语言无关；build、lint、i18n。
- 验收：核对结果记录在文档。

## Phase 4 跨协议基础

### P4-01 PairRegistry 与夹具框架

- 依赖：P0-03
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/PairRegistry.java`, `service/src/test/java/tech/starryapi/platform/xproto/CrossProtocolContractTest.java`, `service/src/test/java/tech/starryapi/platform/xproto/PairRegistryCompletenessTest.java`
- 内容：注册表（本阶段全部 NOT_OFFERED）；夹具目录与逐字节比较；完整性测试要求 EXPERIMENTAL 与 VERIFIED 都具备 7.14 全部用例。
- 测试：框架自检。
- 验收：无可用的对。

### P4-02 ChatCompletionAccumulator

- 依赖：P0-03, P0-05
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/ChatCompletionAccumulator.java`, `service/src/main/java/tech/starryapi/platform/xproto/IncrementalSseDecoder.java`, `service/src/main/java/tech/starryapi/platform/xproto/RelayAbortedAfterDispatchException.java`
- 内容：设计 7.7：JSON/SSE 同一累加器；usage 逐字段取最大；started 与 possiblyGenerated；7.7 带内错误表（含空的成功、role 块后 EOF、解析失败）；工具参数校验；停止原因；16 MB 上限。中断异常带 generationStarted=true。
- 测试：每种带内错误在 possiblyGenerated 前后；首块 usage + 末块 completion 合并；空的成功 → 502。
- 验收：不产出 _rawArguments。

### P4-03 RelayErrorMapper

- 文件：`service/src/main/java/tech/starryapi/platform/xproto/RelayErrorMapper.java`
- 内容：设计 7.11 的状态映射（insufficient_quota → 402、上下文标记）、脱敏消息、原文只进 WARN 日志。
- 测试：每种类型；消息不含主机、URL、原文。
- 验收：客户端与用户日志看不到中转原文。

### P4-04 RelayUsageNormalizer

- 依赖：P0-04
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/RelayUsageNormalizer.java`
- 内容：设计 5.8：两个口径开关、字段列表、DeepSeek 补算、cache ≤ input、输出钳到恰好上限、只估缺项、ESTIMATED/CLAMPED。
- 测试：5.5 三个示例（0.0228、0.0228、0.057）；属性测试 cache ≤ input 且非空结果不为 0。
- 验收：BillingQuote.cost 结果符合示例。

### P4-05 OpenAiChatRelayClient

- 依赖：P4-02, P4-03, P4-04
- 文件：`service/src/main/java/tech/starryapi/platform/openai/OpenAiChatRelayClient.java`, `service/src/test/java/tech/starryapi/platform/openai/OpenAiChatRelayClientTest.java`
- 内容：设计 7.7：一次 POST、wire 名原样、流式增量解码、JSON 模式收到 SSE 也累积、总时限中断；不修改 OpenAiUpstreamClient。
- 测试：本地 HttpServer：请求字节、大小写、404 只一次请求、跨块拆分、总时限。
- 验收：测试通过。

### P4-06 Sanitizer、Canonicalizer 与 Lint

- 依赖：P0-04
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/ChatWireSanitizer.java`, `service/src/main/java/tech/starryapi/platform/xproto/ChatCanonicalizer.java`, `service/src/main/java/tech/starryapi/platform/xproto/StrictChatLint.java`
- 内容：设计 7.6：顶层、消息、部件三级白名单；priority 只看计费 fastMode；reasoning_effort 来源含 adaptive + output_config.effort（取原始体）；占位替代删除 user 轮；空 assistant 删除并合并；按位置消解重复工具 id；lint 只查结构。不修改输入树。
- 测试：messagesToChat 真实输出（只有图片的 tool_result、无 content 的 tool_result、空输出、只有图片的 user 轮、重复 call_0）处理后 lint 通过；无泄漏字段；属性测试。
- 验收：正常 Claude Code/OpenCode 历史不会因 lint 剪掉中转。

### P4-07 RequestFeatureDetector 与 FeaturePolicy

- 依赖：P4-01
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/RequestFeatureDetector.java`, `service/src/main/java/tech/starryapi/platform/xproto/FeaturePolicy.java`
- 内容：设计 7.9 的 MESSAGES、CHAT 表；策略按（入口，路由原生单元种类，成员配置）判定，透传基线下图片、json_schema、音频/文件为 HARD；tool_search_tool_* 与 context_management 为 SOFT。
- 测试：Claude Code 主循环（cache_control、thinking、tool_search）无 HARD；web_search 为 HARD；openai 厂商 gpt 请求的图片与 json_schema 为 HARD，anthropic 厂商为 SOFT。
- 验收：与 7.9 逐行一致。

### P4-08 跨协议分类与 XpDispatcher

- 依赖：P4-05
- 文件：`service/src/main/java/tech/starryapi/proxy/UpstreamFailureClassifier.java`, `service/src/main/java/tech/starryapi/proxy/XpDispatcher.java`, `service/src/main/java/tech/starryapi/proxy/tier/Dispatch.java`
- 内容：设计 7.5、7.8：三参数 classify（中断、模型未服务、上下文超长与转换被拒的 crossTierOnly(native)、402）；XpDispatcher 全捕获（按 possiblyGenerated 分为可恢复 502 或中断）、铸造网关唯一工具 id；模型冷却键为映射 model_id。
- 测试：每类分类；200 text/html 与畸形 SSE 不产生非确定失败；铸造 id 唯一；未发出的连接失败仍可重试。
- 验收：跨协议失败不会让 definitiveBillingFailure 为假。

### P4-10 专用排除与 xpMembers 能力位

- 依赖：P1-04, P0-01
- 文件：`service/src/main/java/tech/starryapi/account/AccountPoolService.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/account/AccountPoolServiceExclusiveXpTest.java`
- 内容：设计 7.5：排除集经 Supplier 注入（保留旧构造器重载），作用于兼容池两个重载、PoolCell.OPENAI_COMPATIBLE、兼容复测过滤和 selectOpenAiAccountByIdForPool；完成后置 xpMembers=true，管理 API 才接受跨协议成员行。
- 测试：空注册表时选择逐一相同；专用成员不接 GLM；exclusive=false 时接 GLM 且 404 不重试。
- 验收：空配置为空操作。

### P4-09 映射验证、模型预取与映射感知复测

- 依赖：P4-05, P4-10
- 文件：`service/src/main/java/tech/starryapi/sourcetier/XpMappingVerifier.java`, `service/src/main/java/tech/starryapi/account/AccountTestService.java`, `service/src/test/java/tech/starryapi/sourcetier/XpMappingVerifierTest.java`
- 内容：设计 7.4：探针（auto + 指令的工具调用、两轮工具循环、流式、vision、缓存双开关证据）；指纹；30 秒限流；/models 预填 wire 名与 context_length；校验器对“不含”口径要求证据；账号测试与复测用映射 wire 名。
- 测试：模拟中转的各探针结论；指纹失效。
- 验收：未验证的映射不进计划。

## Phase 5 跨协议 CHAT + MESSAGES

### P5-01 计划中的跨协议单元与资格

- 依赖：P2-09, P3-01, P4-01, P4-06, P4-07, P4-09
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/DefaultSourceTierPlanner.java`, `service/src/main/java/tech/starryapi/proxy/tier/XpModelResolver.java`, `service/src/test/java/tech/starryapi/proxy/tier/TierPlannerXpTest.java`
- 内容：设计 3 步骤 4、5b：XP 单元、映射查找、七个资格条件、线上体按签名生成、跳过原因、HARD/门控错误（含 PIN）。
- 测试：负责人配置下两个入口的计划；门控三态；别名与精确行；PIN 到 relay 且含 HARD → 400。
- 验收：无跨协议行时计划同第 2 阶段。

### P5-02 Chat 入口分级路径的跨协议派发

- 依赖：P5-01, P4-08, P2-05
- 文件：`service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/proxy/ProxyFacadeTieredXpChatTest.java`
- 内容：OPENAI_CHAT 时调用 XpDispatcher，否则 callWithAccount；三参数分类；crossTierOnly 每请求一次且只去原生首单元级；准入与证据的键；日志字段；中断不复测不回升。
- 测试：成功渲染；404 → Kiro；200 带 error → Kiro；转换被拒一次跨级；上下文超长 → Kiro；中断不重试。
- 验收：未分级测试中 verifyNoInteractions(xpDispatcher)。

### P5-03 学到的上下文上限与中断后短期排除

- 依赖：P5-02
- 文件：`service/src/main/java/tech/starryapi/proxy/tier/XpLearnedLimits.java`, `service/src/main/java/tech/starryapi/proxy/tier/TierSessionBindings.java`, `service/src/main/java/tech/starryapi/proxy/tier/DefaultSourceTierPlanner.java`
- 内容：设计 7.8：（成员, 映射）上下文上限 6 小时 TTL；（tierSessionKey, 成员）中断后排除 2 分钟；计划以结构原因剪枝，不写 D4 绑定。
- 测试：压缩请求超窗后同会话后续超长请求直接 Kiro、短请求回中转；中断后客户端重试去其他成员。
- 验收：不影响 D4 绑定。

### P5-04 跨协议输入上界与 XP_IN_FLIGHT

- 依赖：P5-02
- 文件：`service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/postgresql/XpBillingPostgreSqlIntegrationTest.java`
- 内容：设计 5.2、7.8：xpInputBound（image_token_cap）；min(cost, estimate)；XP_IN_FLIGHT 从派发到结算返回，取消时确定释放。
- 测试：data URI 不升 1M；PG：跨协议中取消（含成功后结算前）→ RELEASED；原生中取消 → REVIEW_REQUIRED（同今天）；中断 → RELEASED；L1 relay 402 → L2 400 → RELEASED。
- 验收：跨协议尝试从不进入核对（I7）。

### P5-05 四个对的夹具与 EXPERIMENTAL 上线

- 依赖：P5-02, P5-04
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/PairRegistry.java`, `service/src/test/resources/xproto/OPENAI_CHAT_RELAY`
- 内容：采集真实 Claude Code 与 OpenCode 请求，为 (CHAT|MESSAGES) × (anthropic|openai) 补齐 7.14 全部用例，注册为 EXPERIMENTAL（只能 KEYS）。
- 测试：契约与完整性测试。
- 验收：负责人可用白名单 Key 试用。

### P5-06 控制台跨协议成员、门控与指标

- 依赖：P5-05, P2-07
- 文件：`webui/src/views/settings/components/source-tiers/XpMemberDialog.vue`, `webui/src/views/settings/components/source-tiers/ModelMapEditor.vue`, `webui/src/views/settings/components/source-tiers/PairGatePanel.vue`, `service/src/main/java/tech/starryapi/sourcetier/SourceTierStatusService.java`
- 内容：设计 8：成员对话框（口径证据、上下文必填、图片上限、缓存成本警告、上限提示）、映射编辑与验证、门控面板、指标与升级证据导出。
- 测试：build、lint、i18n；状态服务单元。
- 验收：不写 SQL 即可完成负责人例子。

### P5-07 负责人例子端到端

- 依赖：P5-03, P5-04, P5-05, P4-10, P3-01
- 文件：`service/src/test/java/tech/starryapi/proxy/OwnerExampleTieredXpTest.java`, `service/src/test/java/tech/starryapi/postgresql/OwnerExampleBillingPostgreSqlIntegrationTest.java`
- 内容：relay L1 ×1.0 + kiro L2 ×0.6，两个入口各一遍：成功；404、200 带 error、繁忙 → Kiro；预算 2 时一个 L1 失败先试第二个 L1，两个都失败经保留槽到 Kiro；开始前/后截断；web_search 请求剪掉 L1；D4：真实降级后停留，而 WebSearch 子请求和无映射 Haiku 与主循环交错时主循环留在 L1；call_0 中转回退到 ANTHROPIC_KEY；空工具输出历史仍走中转；压缩请求超窗到 Kiro；L1 402 → L2 失败 → RELEASED；专用中转不接 GLM。
- 测试：门面 + HttpServer 中转；PG 金额与释放（CI 实际运行）。
- 验收：两个入口端到端通过；ProxyFacadeRoutingTest、ProxyWireFormatTest 不改。

### P5-08 文档与发布说明

- 依赖：P5-07
- 文件：`docs/API.md`, `docs/OPERATIONS.md`, `docs/CONFIGURATION.md`
- 内容：运营步骤（成员、口径、验证、上限、KEYS→ON、kill switch、指标、UNBILLED、隐私、多节点、Claude Code 缓存成本）；新错误码；全部节点升级后再开门控。
- 测试：test:documentation。
- 验收：覆盖第 7、8 节运营操作。

### P5-09 第 5 阶段四个对升级为 VERIFIED

- 依赖：P5-07, P5-06
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/PairRegistry.java`
- 内容：按 7.3 统一标准（KEYS 下 ≥200 请求、lint 失败 0、转换被拒 <1%、ESTIMATED <5%、UNBILLED <2%，附状态页导出）逐个升级；未达标的保持 EXPERIMENTAL，负责人可书面放宽请求数。
- 测试：完整性测试；状态页证据。
- 验收：负责人例子可对普通 Key 设为 ON。

## Phase 6 Responses

### P6-01 ResponsesChatConverter

- 依赖：P5-07
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/ResponsesChatConverter.java`, `service/src/main/java/tech/starryapi/platform/openai/OpenAiRequestAdapter.java`, `service/src/main/java/tech/starryapi/platform/openai/ApplyPatchEmulation.java`, `service/src/main/java/tech/starryapi/platform/openai/OpenAiUpstreamClient.java`
- 内容：设计 7.6；把 OpenAiUpstreamClient.java:463-485、1142-1172 的私有方法抽到 ApplyPatchEmulation，增加 Chat 形状工具，原输出逐字节不变（P4-05 的“不改 OpenAiUpstreamClient”在此有意放开）。
- 测试：并行调用合并、tool_choice、reasoning 跳过、apply_patch 往返；OpenAiRequestAdapterTest 与 OpenAiUpstreamClientTest 不改。
- 验收：输出通过 lint；原生不变。

### P6-02 Responses 特性与链剪枝

- 依赖：P6-01
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/RequestFeatureDetector.java`, `service/src/main/java/tech/starryapi/proxy/tier/DefaultSourceTierPlanner.java`
- 内容：7.9 RESPONSES 表（透传基线）；不可展开链 HARD 与 409；owner 为跨协议成员时忽略。
- 测试：Codex 请求无 HARD；内置工具 HARD；只剩跨协议时 409。
- 验收：与设计一致。

### P6-03 Responses 入口派发、不记 owner、incomplete

- 依赖：P6-02
- 文件：`service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/java/tech/starryapi/proxy/ProxyFacadeTieredXpResponsesTest.java`
- 内容：OPENAI_CHAT 时 XpDispatcher，否则 callResponsesWithAccount；跨协议成功时 owner 记 null（不调 rememberResponseOwner，状态存储 accountId 为 null）；绑定按 response id 转写。
- 测试：Codex 多轮；截断 incomplete；3 轮链降级后保持 L2；跨协议服务后未分级 OPENAI 链不选中中转。
- 验收：原生 Responses 不变。

### P6-04 Responses 夹具与 EXPERIMENTAL

- 依赖：P6-03
- 文件：`service/src/test/resources/xproto/OPENAI_CHAT_RELAY/RESPONSES`, `service/src/main/java/tech/starryapi/platform/xproto/PairRegistry.java`
- 内容：真实 Codex 请求；(RESPONSES, anthropic|openai) 全部用例，EXPERIMENTAL。
- 测试：契约与完整性。
- 验收：白名单 Key 可用。

### P6-05 Responses 对升级

- 依赖：P6-04
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/PairRegistry.java`
- 内容：同 P5-09 标准。
- 测试：状态页证据。
- 验收：Codex 的 claude 请求对普通 Key 可走中转。

## Phase 7 Gemini

### P7-01 GeminiChatConverter 与思维签名

- 依赖：P5-07
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/GeminiChatConverter.java`, `service/src/main/java/tech/starryapi/platform/gemini/GeminiResponseFactory.java`, `service/src/main/java/tech/starryapi/platform/openai/OpenAiResponseFactory.java`
- 内容：设计 7.6、7.10：唯一 id 与先进先出配对、图片、toolConfig、HARD 判定、extra_content 往返与渲染；原生输出不变。
- 测试：同名函数配对；签名往返；现有工厂测试不改。
- 验收：输出通过 lint。

### P7-03 Gemini 入口派发、夹具与 EXPERIMENTAL

- 依赖：P7-01
- 文件：`service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`, `service/src/test/resources/xproto/OPENAI_CHAT_RELAY/GEMINI`, `service/src/test/resources/xproto/OPENAI_CHAT_RELAY/CHAT/google`
- 内容：(GEMINI, google|anthropic)、(CHAT, google) 全部用例，EXPERIMENTAL；inlineData 上界。
- 测试：google L1 中转 + L2 Kiro；404 → Kiro；契约。
- 验收：白名单 Key 可用。

### P7-04 Gemini 相关对升级

- 依赖：P7-03
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/PairRegistry.java`
- 内容：同 P5-09 标准，三个对逐个升级。
- 测试：状态页证据。
- 验收：D1 的四种入口格式对普通 Key 可用。

## Phase 8 保真（建议必做）

### P8-01 原始体 Anthropic 转换器与特性承载

- 依赖：P5-07
- 文件：`service/src/main/java/tech/starryapi/platform/xproto/AnthropicChatWireConverter.java`, `service/src/main/java/tech/starryapi/platform/anthropic/AnthropicRequestAdapter.java`, `service/src/main/java/tech/starryapi/platform/xproto/FeaturePolicy.java`
- 内容：共享辅助（messagesToChat 字节不变）；图片 CARRY_IF(vision)、is_error、tool_result 图片、cache_control 透传、精确占位；每个特性夹具通过前保持 SOFT。
- 测试：新特性夹具；无新特性时线上体与第 5 阶段一致；低余额截图不因 1M 被拒（含 OpenCode 入口，Kiro 单元已按去媒体上界）。
- 验收：Claude Code 在中转级别获得缓存与图片。

## Phase 9 可选

### P9-01 count_tokens 与 Gemini 流/countTokens

- 依赖：P5-07
- 文件：`service-api/src/main/java/tech/starryapi/proxy/ProxyApi.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`
- 内容：本地估算；不预留、不写级别绑定、不写账号亲和；Gemini :streamGenerateContent 合成 SSE。
- 测试：形状；无预留；无绑定。
- 验收：新端点写入发布说明。

### P9-03 Chat 流 usage 块、稳定 id 与 Anthropic 缓存字段

- 依赖：P0-03
- 文件：`service/src/main/java/tech/starryapi/platform/openai/OpenAiResponseFactory.java`, `service/src/main/java/tech/starryapi/platform/anthropic/AnthropicResponseFactory.java`
- 内容：独立开关（默认关）；OpenCode 依赖 usage 块提前压缩〔外部〕。
- 测试：关时固定值不变。
- 验收：OpenCode 显示用量并能提前压缩。

### P9-04 Responses 中转透传

- 依赖：P6-05
- 文件：`service/src/main/java/tech/starryapi/platform/openai/OpenAiResponsesRelayClient.java`
- 内容：V29 的 CHECK 已含 OPENAI_RESPONSES，无需新迁移；Java 放开该取值。
- 测试：透传与解析；对为 EXPERIMENTAL。
- 验收：可透传 Responses。

### P9-05 原生 Anthropic 透传与 thinking 安全规则

- 依赖：P5-07
- 文件：`service/src/main/java/tech/starryapi/platform/anthropic/AnthropicUpstreamClient.java`
- 内容：独立开关；历史中最后的工具轮没有真实签名 thinking 时去掉 thinking 参数（风险 22，O8）。
- 测试：混合历史。
- 验收：负责人决策后启用。

### P9-06 原生错误信封、SSE 保活、按预算降级、超时后不跨账号重试

- 依赖：P5-07
- 文件：`service/src/main/java/tech/starryapi/common/GlobalExceptionHandler.java`, `service-api/src/main/java/tech/starryapi/account/AccountDtos.java`, `service/src/main/java/tech/starryapi/proxy/ProxyFacade.java`
- 内容：各自独立开关（默认关）：协议原生错误信封；SSE 保活；余额不足时去掉超预算级别；RoutingPolicySettings.strictTimeoutFailover（非连接类 TimeoutException 视同读超时，对所有流量生效，WireFormatTest 有意更新）。
- 测试：关时行为不变。
- 验收：负责人决策后启用。
