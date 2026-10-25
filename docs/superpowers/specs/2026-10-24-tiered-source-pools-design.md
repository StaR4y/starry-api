# 号源分级（账号池分级）最终设计

> Starry API，分支 `dev/naming-and-hardening`（dbcc0c0）；Java 25 / Spring Boot 4 / WebFlux / Jackson 3 / R2DBC PostgreSQL / Flyway V1–V28 / Vue 3。
> 第 3 版（2026-10-25）：在含 D1–D4（第 11 节）的第 2 版上合入第二轮评审。每条意见先对照代码核实，采纳的写入正文（12 节汇总），驳回或部分驳回的见附录 A。只是设计，未改代码。`文件:行号` 已对照 dbcc0c0 核实；〔外部〕= 第三方行为，实施前核对。

## 0. 结论与不变量

把一个目录厂商（Anthropic、OpenAI、Google、DeepSeek、自定义）的账号分成有序**号池**，号池即**级别**（1 最好），有名称和价格系数。成员是这条路由本来能用的原生账号，或显式登记、映射已验证的 **OpenAI 格式中转**（跨协议号源，D1）。

- **自动默认**：新会话从 1 级开始；本请求内 1 级**全部**不可用（含繁忙，D2）才用 2 级。
- **降级后停留**（D4）：会话**真实降级**后停在该级直到空闲 10 分钟；按厂商可关，默认停留。只因本请求自身原因（HARD 特性、无映射、上下文、转换、门控、预算槽）跳过高级别不算降级。
- **按服务级计价**（D3）：单价 = 标准价（唯一命中规则价或全局倍率价，fast ×2）× 号池系数（默认 1.0）。
- 用户可用模型后缀、请求头或 Key 偏好固定号池。

骨架：两层模型（厂商 → 有序号池）；未分级厂商原样走今天的代码（`selectAccount`，ProxyFacade.java:808-858；4 参数 `settle_api_usage`）；结算外包 `settle_api_usage_served`；类别自动归级（按厂商限定）+ 逐账号覆盖 + 未命中落最低级；跨协议 = 显式成员行 + 每账号映射 + 真实验证 + 按（号源类型，入口，厂商）"对"门控。

**不变量**

- **I1 冻结计划**：`withBilling`（ProxyFacade.java:583）之前冻结 TierPlan：级别、单元、成员快照与跨协议资格、报价、holdLevels、会话起点、Responses owner 解析、响应头取值、配置版本。后续只用它。
- **I2 预留是上界**：预留 = holdLevels（5.2）上的最大估算；每级按其单元**实际发送的请求形态**估算输入。选号不离开该集合。`reserve_api_usage` 幂等且不可追加（V15:21-26）。跨协议结算另取 `min(cost, 服务级 estimate)`。
- **I3 按服务级结算**：金额 = 选中账号所在级别的冻结报价 × 用量。级别在**选中时**写入并校验（派发前），结算时不会缺失，不回退到最大报价。
- **I4 未配置即零变化**：路由、`select*ForPool` 调用序列、上游客户端与参数、结算函数、计费判定、价格、`request_logs.model`、线上协议、`sessionHint` 都不变；无跨协议成员行时跨协议代码不可达，专用排除是空操作。分级计划里的原生单元调用相同的方法和参数。
- **I5 失败关闭**：配置在 Web 服务器接收请求之前加载；从未加载成功时预留前返回 503，不按系数 1 静默计费。
- **I6 显式扩展**：分级只划分并排序本请求路由的原生链路能用的账号，不扩大范围；路由只在管理员打开可选的"全端点统一"（4.9）时改变；路由原本不用的账号只能经"跨协议成员行 + 已验证映射 + 已开放的对"加入。类别规则不产生跨协议成员。
- **I7 一次生成、从不进核对**：跨协议尝试一旦可能已生成（7.7.3）就不再派发到任何账号或级别；从派发到结算返回期间，跨协议尝试的任何失败或客户端断开都确定性释放预留，运营者承担并计指标。
- **I8 真实降级才绑定**：D4 级别绑定只在真实降级（4.7）后写入，只往低改（最后手段回升除外）。

## 1. 负责人的例子

| 厂商 | 级 | 号池 id | 名称 | 成员 | 系数 |
|---|---|---|---|---|---|
| anthropic | 1 | `relay` | openai源头 | 跨协议成员行：兼容 Key，映射 `claude-sonnet-4.5 → <中转名>`、`claude-haiku-4.5 → …` | 1.0 |
| anthropic | 2 | `kiro` | kiro反向代理 | 类别 KIRO、CLAUDE_OAUTH | 0.6 |

| 客户端 | 入口 | 走法 |
|---|---|---|
| Claude Code | `/v1/messages` | 路由 ANTHROPIC；1 级跨协议单元，2 级 `[ANTHROPIC_KEY, KIRO_COMPATIBLE]` |
| OpenCode（clientSetup.ts:305-336） | `/v1/chat/completions` | 路由 KIRO；1 级跨协议，2 级 `[KIRO_COMPATIBLE]`；不需要全端点统一 |
| Codex（claude-*） | `/v1/responses` | 同上，严格 Responses→Chat 转换（第 6 阶段） |
| Gemini 客户端 | `:generateContent` | 同上（第 7 阶段） |

门控：每个对先以 EXPERIMENTAL 上线（只能白名单 Key），达标后由升级任务改为 VERIFIED 才能设 ON（7.3）。原生例子：anthropic `official`（OFFICIAL_API_KEY）+ `kiro`；openai `official` + `codex`（需关闭 `isolateCodexPool`，默认 true，AccountDtos.java:313-315）。

- 类别由 `AccountAllocationCategory.classify`（account/allocation 包，AccountAllocationCategory.java:45-58）计算，是唯一区分官方与兼容 Key 的分类；`isAnthropicApiKey` 区分不了（AccountPlatforms.java:127-129）。
- **类别按厂商限定**：OFFICIAL_API_KEY 同时含官方 OpenAI 与 Anthropic Key，COMPATIBLE_API_KEY 含中转、DeepSeek、第三方 Anthropic（AccountAllocationCategory.java:13-16、49-54）。分级里类别只匹配本厂商的提供方族；否则 gpt id 走 /v1/messages（路由 ANTHROPIC，ProxyFacade.java:1266-1267）时官方 Anthropic Key 会被标成"openai源头"。族外账号按 2.4 规则 3 处理。
- 未归类的官方或第三方 Anthropic Key 落入最低级，排在 Kiro 之前（同 849-856），最贵的号源按最便宜的系数计费：最低级经规则 3 收进 OFFICIAL_API_KEY 账号时，保存接口与控制台警告。
- 中转须满足 `isOpenAiCompatibleApiKey`（AccountPlatforms.java:66-68）；类别表达不了"能服务 Claude"，只能用成员行。

## 2. 数据模型（单个迁移 `V29__source_tiers.sql`）

新迁移会让旧备份不能从控制台恢复（DataTransferService.java:67-97），所以所有阶段的表、列、CHECK 都在 V29；CHECK 按最终取值写宽，未实现的取值由 Java 拒绝。

**2.1 `source_tier_vendors`**：vendor VARCHAR(64) PK；enabled、unify_endpoints、public_listing BOOLEAN DEFAULT FALSE；sticky_sessions BOOLEAN DEFAULT TRUE（D4）；refusal_cap SMALLINT DEFAULT 16 CHECK 4..64（D2）；cross_tier_reserved_slot BOOLEAN DEFAULT TRUE（4.5，O1）；version BIGINT DEFAULT 1；时间戳。

**2.2 `source_tiers`**：(vendor FK CASCADE, tier_id) PK；tier_id CHECK `^[a-z0-9][a-z0-9-]{0,31}$` 且 `!~ '^(auto|l?[0-9]+)$'`；level SMALLINT 1..9；name VARCHAR(32) 非空；price_factor NUMERIC(12,6) DEFAULT 1 CHECK (0,1000]；member_categories TEXT[]（五类别子集）；user_selectable、enabled DEFAULT TRUE；description。`UNIQUE (vendor, level)`、`UNIQUE (vendor, name)` 均 DEFERRABLE INITIALLY IMMEDIATE。

**2.3 `source_tier_members`**：(vendor, account_id) PK，account_id FK → proxy_accounts(account_id)（V1:188 UNIQUE）CASCADE；(vendor, tier_id) FK CASCADE。跨协议列（protocol 为 NULL 的原生覆盖行忽略）：

- protocol VARCHAR(24) CHECK IN ('OPENAI_CHAT','OPENAI_RESPONSES','ANTHROPIC_MESSAGES')；本版只实现 OPENAI_CHAT，其余 400 `SOURCE_TIER_MEMBER_PROTOCOL_INVALID`，以后启用不需要新迁移。
- exclusive DEFAULT TRUE（7.5.3）；vision、json_schema、priority_tier、cache_control_passthrough DEFAULT FALSE；upstream_stream DEFAULT TRUE；reasoning_mode IN ('NONE','EFFORT')；max_duration_seconds SMALLINT DEFAULT 540 CHECK 30..900。
- usage_cache_read_in_prompt、usage_cache_write_in_prompt BOOLEAN NULL：中转 `prompt_tokens` 是否已含缓存读/写；`CHECK (protocol IS NULL OR 两者 NOT NULL)`。两个独立开关替代原来的 INCLUSIVE/EXCLUSIVE 二选一，因为有中转只含其中一种〔外部〕。

**2.4 `tierOf(vendor, account)`**：(1) 有成员行取其号池；(2) 否则取 member_categories 含该账号类别、且账号属本厂商提供方族的号池（每厂商每类别最多一个号池）；(3) 否则取已启用号池中 level 最大者（收进 OFFICIAL_API_KEY 时警告）。命中号池停用 → 不为本厂商分级请求服务。跨协议成员只经跨协议单元进入计划。一个账号可在不同厂商处于不同级别。

**2.5 `source_tier_member_models`**：(vendor, account_id) FK → members CASCADE；model_id VARCHAR(256)（上限同 ProxyFacade.java:68）；upstream_model VARCHAR(255) CHECK `^[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,254}$`，原样发送；**max_context_tokens INTEGER NOT NULL** CHECK > 0（控制台用 `/models` 的 context_length 预填，缺失时预填目录窗口）；**image_token_cap INTEGER NOT NULL DEFAULT 8192** CHECK 1..1000000；enabled；verified_at、verified_hash、verify_summary JSONB object。PK (vendor, account_id, model_id)，UNIQUE (vendor, account_id, lower(model_id))。目录 MODEL_ID 正则不允许 '@'（ModelCatalogManagementService.java:39）。

**2.6 `source_tier_pair_gates`**：(vendor FK CASCADE, source_protocol DEFAULT 'OPENAI_CHAT'（CHECK 同 2.3）, entry_endpoint IN ('CHAT','MESSAGES','RESPONSES','GEMINI')) PK；state IN ('OFF','KEYS','ON') DEFAULT 'OFF'；allow_key_ids TEXT[]（≤ 50）。无行即 OFF；与号池配置同一事务写入，OFF 经 NOTIFY 立即生效。

**2.7 其他**

- api_keys.tier_preference VARCHAR(40) DEFAULT 'auto'，CHECK `^(auto|[a-z0-9][a-z0-9-]{0,31}\+?|[Ll]?[1-9]\+?)$`；不进 `guard_api_key_billing_change`（V15:197-209）。
- request_logs 10 个可空列、不加 CHECK：source_tier_ref、source_tier_level、source_tier_name、price_factor、tier_selection、source_dispatch、wire_model、usage_source、stop_reason、degraded_features。`CREATE OR REPLACE ensure_request_log_partitions`，沿用 V5:19-156 并把 10 列追加到 INSERT/SELECT 列清单（V5:135-143），否则迁出默认分区的行静默丢列。
- billing_reservations：served_tier_ref、served_tier_level、served_price_factor、last_dispatched_tier_ref、last_dispatched_at。
- api_usage_events（V1:310-326）：source_tier_ref、price_factor；BEFORE INSERT 触发器 `fill_usage_event_tier()`：NEW.source_tier_ref 为 NULL 时按 `request_hash = NEW.request_hash::varchar` 查预留（事件表 CHAR(64)，V1:311；预留表 VARCHAR(64)，V14:8；显式转换以走主键索引），取 `COALESCE(served_tier_ref, last_dispatched_tier_ref)`，系数取 served_price_factor，缺失时取 `pricing->'tierPlan'->'levels'` 中同 tierRef 的 factor。管理员核对后结算或租约过期的请求因此归到最后派发的号池。
- system_settings 种子 `source_tier_config_version = '0'`。

**2.8 结算包装函数**

```sql
CREATE FUNCTION starry_api.settle_api_usage_served(p_hash VARCHAR, p_credits NUMERIC, p_input BIGINT,
    p_output BIGINT, p_tier_ref VARCHAR, p_level INTEGER, p_factor NUMERIC) RETURNS TEXT ...
  -- 与 V15 相同的加锁顺序：admin_users → api_keys → billing_reservations FOR UPDATE
  IF r.served_tier_ref IS NOT NULL AND ROW(r.served_tier_ref, r.served_tier_level::integer, r.served_price_factor)
     IS DISTINCT FROM ROW(p_tier_ref, p_level, p_factor) THEN RETURN 'IDEMPOTENCY_CONFLICT'; END IF;
  IF r.served_tier_ref IS NULL AND r.status NOT IN ('SETTLED','RELEASED') THEN UPDATE ... SET served_* ...; END IF;
  RETURN starry_api.settle_api_usage(p_hash, p_credits, p_input, p_output);   -- V15:81，一字不改
```

`p_level` 必须是 **INTEGER**：R2DBC 把 Java int 绑定为 int4，函数解析没有 int4→int2 隐式转换，SMALLINT 参数会让每次分级结算报"function does not exist"，经 recordAccountingFailure（ProxyFacade.java:1509）变成生成后的错误和核对。现有计费函数也只用 INTEGER/BIGINT（V1:404、V10:320、V15:81）；列仍是 SMALLINT。调用 SQL 字符串有不依赖 Docker 的单元测试。效果：号池信息与扣款同事务；重复结算号池不同返回冲突；进 REVIEW_REQUIRED（V15:113-118）时号池已写入；`resolve_billing_review` 仍调 4 参数内层（V14:291）。权限：新函数 REVOKE PUBLIC + GRANT EXECUTE runtime（V14:303-307、V20）；触发器函数对 PUBLIC 与 runtime 都 REVOKE；五张新表 GRANT SELECT/INSERT/UPDATE/DELETE 给 runtime（默认只有 SELECT/INSERT，V1:5-8）。

**2.9 快照、加载时机、能力位**

- `SourceTierRegistry` 用 `SmartInitializingSingleton.afterSingletonsInstantiated` 阻塞首载五张表，早于 Web 服务器启动。不沿用 ApplicationRunner（AccountPoolPolicyService.java:68-71）：Netty 在 ApplicationRunner 之前已接收请求，那个窗口会让没有分级的部署也返回 503。从未加载成功 → 预留前 503 `TIER_CONFIG_UNAVAILABLE`。快照不可变，30 秒兜底刷新，失败保留旧快照；提供 `exclusiveXpAccountIds()`。
- 管理写操作一个事务：`acquireTransactionLock("starry_api:source_tiers")`（SystemSettingRepository.java:21）→ 比对 version（409）→ `SET CONSTRAINTS DEFERRED` → 整体替换 → version+1 → 更新 `source_tier_config_version`；现有 NOTIFY（V1:147,155）经 `RuntimeStateInvalidator.invalidateSetting`（83-98）新分支与 `invalidateAll`（59-81）刷新。
- **能力位**：`GET /admin/source-tiers` 的 runtime 给出 `nativeTiers`（P2-09）、`unifyEndpoints`（P3-03）、`xpMembers`（P4-10）和每个对的状态。校验器拒绝能力位为假的配置（enabled=true、unify=true、跨协议成员行、非 OFF 门控）→ 409 `SOURCE_TIER_RUNTIME_UNSUPPORTED`，没有"先存后静默生效"的配置。
- 账号类别、base URL 变化经账号缓存传播（30 秒，AccountPoolService.java:139）；指纹含 base URL。

## 3. 请求计划 TierPlan

位置：`invokeChat`（309-363）、`invokeResponses`（486-557）中，模型映射、`verify`、fast、`routeFor`、`poolContext` 之后（Responses 还在展开 521 和 `responseAffinity` 531-535 之后），`withBilling` 之前；`messages()`（272-283）、`geminiGenerateContent()`（286-299）传入原始入口体。

1. 解析选择器（第 6 节），后缀在最开头剥离。
2. 厂商 = `ModelPoolKeyResolver.resolve(requestedModel, upstreamModel, providerHint).provider()`，其中 `providerHint` 是服务这个请求的账号的厂商提示（6.1），不是路由给 `poolContext` 的那个；价格基准仍是 requestedModel（594）；**modelKey** = 7.4 解析后的规范目录 id（映射、绑定、模型冷却都用它）。
3. 厂商未启用或无启用号池 → `UNTIERED`，原样执行今天的代码。
4. **单元**：原生单元按 817-857 映射：DeepSeek `[DEEPSEEK]`；OPENAI 兼容模型 `[OPENAI_COMPATIBLE]`；偏好 Codex `[CODEX]`（隔离关为 `[CODEX, OFFICIAL_OR_CODEX]`）；OPENAI `[OFFICIAL_OPENAI]`（隔离关 `[OFFICIAL_OR_CODEX]`）；ANTHROPIC `[ANTHROPIC_KEY, KIRO_COMPATIBLE]`；KIRO `[KIRO_COMPATIBLE]`。某级有跨协议成员行且对允许、门控对本 Key 开放 → 追加 `XP_OPENAI_CHAT`。谓词定义在 `PoolCell`，`select*ForPool`（AccountPoolService.java:364-573）共用；单元同质，排序器 allMatch 策略不变（AccountCandidateRanker.java:48-54、216-222）。`openai-compatible/` 前缀（1269-1276）不产生跨协议单元。
   **Responses owner**（OPENAI 路由且 responseAffinity 给出 owner；AUTO、PIN 相同；链不可迁移且 owner 已解析时，计划、holdLevels 与低余额提示只含 owner 所在的那一级：只有它能服务，不另算预留，也没有更便宜的号池可改，不记为 D4 的降级）：在成员快照里查 owner；是某级原生单元成员 → 记 ownerLevel；否则（号池停用、成员移除、跨协议成员、不满足任何单元，如单元为 `[OFFICIAL_OPENAI]` 时的 Codex owner）链可迁移就忽略 owner，不可迁移 → 预留前 409 `RESPONSE_OWNER_TIER_MISMATCH`。链可迁移时（AUTO、PIN 相同），owner 只是偏好，只在它所在的级正是走查本来要开始的那一级时才保留并先试：AUTO 有绑定（4.7）就是绑定级（绑定级本请求没有可用账号则是不高于它的第一个有账号的级），没有绑定（新会话、从未降级的会话、绑定已空闲过期、厂商关了粘滞）就是第一个有账号的级；PIN(t) 只有 t 一级；PIN(t+) 不读绑定，从 t 起，是 t 及以下第一个有账号的级（和 AUTO 从起点开始一样）。在别处的 owner 是更低的号池留下的——一次固定到较低号池的请求、PIN(t+) 范围内因忙（D2）降下去的一轮，或已过期的降级——忽略它：不把之后的请求拉进那个较低的号池并一直按它的系数计价，也不让空闲后的会话停在低级，它们从起点开始，按起点预留和计价（所以 holdLevels 里的 owner 级不需要单独补入）；链不可迁移的请求不受此影响。
5. **级别**：已启用号池按 level 升序；AUTO 全保留，PIN(t) 只留 t，PIN(t+) 留 t 及以下。**5b 跨协议资格**（预留前）：在原始请求体上检测特性；成员 m 有资格当且仅当 (1) 注册表与门控允许；(2) 仍是 `isOpenAiCompatibleApiKey`；(3) modelKey 解析到已启用、已验证、指纹有效的映射；(4) 无 HARD 特性（7.9，以本路由原生单元种类为基线）；(5) 估算输入 ≤ 映射的 max_context_tokens 和本节点学到的上限（7.8）；(6) m 不在本会话的短期排除里（7.8）；(7) 线上请求体通过规范化与 lint（7.6，按配置签名只算一次）。跳过原因冻结并按（厂商，级别，原因）计数。
6. **剪枝**：结构剪枝（所有单元结构为空，含 5b 全部跳过）永不回补；可用性剪枝（缓存里无"启用 ∧ 谓词 ∧ 属于该级 ∧ isAvailable"的账号），若全剪光则保留结构剪枝后的全部级别（即缓存里有本路由单元账号的级别；没有账号的级别不留，免得它按最贵的一级撑大预留，也免得下级服务时被算成"已走过"）；结构剪枝后也一个不剩时（缓存里这条路由一个账号都没有）所有级别保留，因为计划至少要有一级，并标为"无成员"，缓存重载后无论哪一级找到账号，D4 都不把这些级别算作已走过；owner 所在级不做可用性剪枝。可用性剪枝只为给预留定额；被剪掉的级别若缓存里有本路由单元的账号（它们全部额度耗尽或被挂起，4.1 第 2 层），记入计划的 `unavailableTiers`，会话被它之下的级服务时按"已走过"算（4.7）。缓存里没有本路由单元账号的级别（包括账号全被停用的）是结构性的，不记。结构剪光：含 HARD → 400 `SOURCE_TIER_FEATURE_UNSUPPORTED {feature}`；门控/映射 → 今天的 `NO_AVAILABLE_ACCOUNT` 路径。
7. **报价**：base = 今天的 `billingQuote(requestedModel, fastMode, multiplier)` 只算一次；`quote = base.scaled(factor)`（1 返回原对象，否则五项费率精确相乘；estimate CEILING、cost HALF_UP，BillingQuote.java:9-24）。
8. **输入上界** = 单元最大值：原生用今天的公式（586-592）；例外是分级计划里的 KIRO_COMPATIBLE 单元只发文本（KiroPayloadFactory.java:609-627），界 = 去 base64 的 UTF-8 长度 + 1024，不因图片升到 1M（不可展开的 previous_response_id 仍升到 1M）。跨协议见 5.2。
9. 读 D4 绑定得到起点（4.7），算 holdLevels（5.2），预先算好响应头（6.4），冻结。

提前拒绝（预留前）：固定号池不含可用类型或门控关闭 → 400 `SOURCE_TIER_NOT_OFFERED`；固定号池只有跨协议成员且被 HARD 剪空 → 400 `SOURCE_TIER_FEATURE_UNSUPPORTED`。

## 4. 选号与降级

**4.1 账号不可用（沿用四层）**：(1) 不在已启用缓存（30 秒，本地写立即，AccountPoolService.java:139）；(2) `isAvailable` 假：暂停或额度、5 小时、7 天窗口耗尽；(3) 排序器不可调度：DISABLED 或 COOLDOWN/QUARANTINED 未到期（2 秒缓存，AccountCandidateRanker.java:26-29、43）；(4) PostgreSQL 准入拒绝：并发、RPM、突发、在途 token、会话数、按（账号, 模型）或（账号, 端点）冷却（V26:156-172；V10:145-182）。本请求已尝试或被拒的账号也算不可用；跨协议资格是第 0 层。

**4.2 级别全部不可用（D2）**：请求 R 的第 N 级不可用 ⇔ 每个单元的每个成员满足第 1–4 层之一、或在 R 内已尝试/被拒、或该级在 R 内拒绝数达 `refusal_cap`。

- 繁忙算不可用；失败率熔断不是条件（4.12）。
- "在 R 内"指整个请求：分级请求的被拒集合、每级计数、全局 64 次上限都跨派发尝试保留。未分级保持今天：每次派发后从空列表开始（ProxyFacade.java:999，64 次检查在 1021）。
- 每级上限只在其下还有级别时生效；PIN(t) 或最后一级只受全局 64 限制。

**4.3 走查**（新增 `selectTiered`，未分级不进入）

```
selectTiered(plan, walk, excluded, refusals, ctx):   # walk = {startIndex, narrowedTo, allowClimb}
  levels = walk.narrowedTo ? [walk.narrowedTo] : plan.levels[walk.startIndex..]
  if plan.owner 且未尝试: 先在 ownerLevel 选 owner；无账号且链可迁移 → 继续，否则同今天返回 owner 不可用
  for level in levels:                                       # 惰性 concatMap(...).next()
    if refusals.count(level) >= plan.refusalCap ∧ 其下还有级别: continue
    for cell in level.cells:                                 # 原生在前，跨协议在后
      selectInScope(hint, cell.predicate ∧ tierOf(acc)==level ∧ acc∉excluded ∧ (XP ⇒ acc∈level.xpEligible),
                    ctx, scope="tier:"+vendor+":"+tierId+"|"+modelKey, refreshOnEmpty=false)
      选中 → 写入 attempt.level/cell/dispatch 并校验 level∈plan → return
  整轮空 → refreshCacheIfStale() 一次 → 再走一轮 → 仍空
    → allowClimb ∧ narrowedTo==null ∧ startIndex>0 ? 最后手段回升（只进 holdLevels） : NO_AVAILABLE_ACCOUNT
```

`selectInScope` 与 `selectFromPool`（616-642）同为亲和 → isAvailable → 排序器；`refreshOnEmpty=false` 避免结构性空单元每 5 秒全量重载（593-597）。`AccountAttempt`（ProxyFacade.java:2148-2180）新增 level、cell、dispatch，选中时写入后不变；校验失败发生在派发前，预留仍是 RESERVED，释放时全额退还（V14:244-247 只把非 RESERVED 的非确定释放转核对）。

**4.4 准入**：`selectAccountWithAdmission`（1004-1107）结构不变（被拒加入排除集再走查，1087-1105）；分级版另按级计数、起点为本次 startIndex、被拒集合跨尝试保留。跨协议尝试的准入：模型冷却键用映射行的 model_id，不用客户端别名 `poolContext.upstreamModel()`（upstreamModelFor 1368-1371；1068），否则 `sonnet`、`claude-sonnet-4.5` 等别名各自再撞一次中转的 404；端点作用域 `xp:/chat/completions`（1069）。

**4.5 派发后的重试**

`shouldRetryWithAnotherAccount`（1815-1836）与按路由的不同账号数上限（AccountDtos.java:333-350，默认 Codex 1、Anthropic/Kiro 2、OpenAI/DeepSeek 3；TRANSPORT 等范围上限 2）不变。分级请求另用两个判定：

- `tierDefinitive(e)` = `definitiveBillingFailure(e)`（623-639）∨ 上游 402。402 是付费/额度拒绝、不生成；今天不在状态集合（626、631、636）里，`recordFailure` 置粘滞的 BILLING_UNCERTAINTY（1786-1792），之后任何失败都非确定释放（611-612）→ REVIEW_REQUIRED（V14:244-247）→ 用户被封（V15:29-30）。分级请求在 `recordFailure` 与 `withBilling` 失败释放中都用 `tierDefinitive`；未分级保持今天（I4），全局修正另立任务（O7）。
- `notStarted(e)`：`UpstreamException` 新增 `generationStarted`（默认 false）。Kiro 解析器在截断（AwsEventStreamParser.java:50-54，可恢复 502）或异常帧（88-96，可恢复 500/503/429）之前已解析出内容、工具或用量帧时置真；`RelayAbortedAfterDispatchException` 恒为真。`definitiveBillingFailure` 把这些可恢复状态当"不计费"，但内容其实已生成。

规则：

1. **跨级只限"确定且未开始"**：此前每次失败都满足 `tierDefinitive ∧ notStarted` 才能进入其他级别；否则计划收窄为失败账号所在级，用尽后返回原始上游错误（474-476、791-793）。Kiro 超时、发送后 IOException（KiroUpstreamClient.java:62-70；UpstreamFailureClassifier.java:248-273）、截断的 Kiro 流都不会在另一级再生成一次。
2. **级内优先 + 跨级保留槽**（"本级还有可选账号"与走查用同一组层：缓存、isAvailable、排序器可调度，再除去本请求已试或被拒的账号；Responses owner 在走查之前先试，但可迁移的链只在 owner 所在的级正是走查开始的那一级时才保留 owner（第 3 节步骤 4），所以 owner 的失败就是这一级任一账号的失败：预算内留在本级，预算用尽时保留槽用来跨到下一级，不会花在同级的别的账号上，派发数也不会超过路由的账号预算；不可迁移的链从不重试）：失败级别仍有可选账号（启用 ∧ isAvailable ∧ 可调度 ∧ 未尝试 ∧ 未被拒）且预算未用尽 → 留在本级；本级无可选账号或预算用尽 → 规则 1 允许且有更低级时进入下一级。`cross_tier_reserved_slot=true`（默认）时，另有一个只能用于跨级的保留槽，每请求一次：默认预算 2、两个 1 级中转都确定失败时仍能到 Kiro，派发数最多比今天多 1 且之前全部未生成；关闭则上限同今天（O1）。因预算或保留槽跨级（本级还有未试账号）不是真实降级，不写 D4 绑定；保留槽跨级时本级已没有可选的未试账号（负责人的例子：两个 1 级源头都确定失败）则是本级用尽，算真实降级。
3. **准入耗尽按无账号处理**：重试中遇到 `ACCOUNT_ADMISSION_EXHAUSTED` 同 `NO_AVAILABLE_ACCOUNT` 返回原始错误，否则非确定的 503 会把已派发预留送进核对。
4. 跨协议覆盖规则见 7.8。
5. **时间守卫**：距请求开始超过 240 秒不再跨级，返回原始错误（240 = Claude Code 约 600 秒默认超时〔外部〕− Kiro 300 秒 − 60 秒）。否则客户端会在原生尝试中断开，原生断开非确定释放（ProxyFacade.java:613）。
6. **收窄的后果**：收窄后本请求不再做最后手段回升、保留槽跨级、crossTierOnly 和上下文超长跨级；同级与同账号重试（含 Kiro `retryWhen`）保持今天（风险 4）。

**4.6 租约**：现状租期 15 分钟（V15:52），`markUsageDispatched` 不续租（ApiKeyService.java:204-212），清扫器每 60 秒把过期 DISPATCHED 转核对（BillingMaintenanceService.java:15-19）。分级用 `markUsageDispatchedTiered`：同一 UPDATE 写 last_dispatched_tier_ref/at，`expires_at = LEAST(created_at+60min, GREATEST(expires_at, now()+W))`，`W = max(15min, requestTimeout×(maxRetries+1)+60s, 跨协议时限+60s)`，默认 960 秒；`now+W > created_at+60min` 时不再跨账号重试。每次派发还会在运行期间续租：Kiro 自己对每次重试用 `.timeout(requestTimeout)` 限时，已在 W 之内；Anthropic、OpenAI、Codex 只受 reactor-netty 的 responseTimeout（两次读取之间）限制，流式响应可以一直有数据而超出 W。不能靠网关截断它们：截断后答案被丢弃，预留以非确定释放转核对且没有人再结算；而不处理时清扫器只是把预留暂转核对，流结束时的 `settle_api_usage` 接受 REVIEW_REQUIRED 行并结算它。所以改为续租：派发运行期间每隔 W/4（默认 240 秒）执行一次 `renewDispatchLease`，`UPDATE billing_reservations SET expires_at = LEAST(created_at+60min, GREATEST(expires_at, now()+W)) WHERE request_hash = :hash AND status = 'DISPATCHED'`，返回是否续上（行不再是 DISPATCHED——已被清扫器转核对、已结算或已释放——则不再续）；次数为 `untilCap / (W/4)`，最后一次把租约续到 60 分钟的上限；派发结束（完成、失败、取消）即停止；一次续期失败只记警告，下个节拍再试，连续失败两次租约仍有 W/4 的余量（周期取 W/3 时第三次续期恰落在到期时刻，所以取 W/4）；连续失败三次则租约可能到期，清扫器把预留转核对，流结束时的结算清掉。清扫器先选出过期预留、再另行调用 `release_api_reservation`，后者只检查状态与实际额度而不再检查 expires_at，所以刚好在这个间隙里被续上的预留仍可能被转核对，结果同样由结算清掉；续期对已过期但清扫器尚未选中的租约有效，是尽力而为。不再对派发设总时限：超过 60 分钟的派发无法再续，清扫器把预留转核对，流结束时的结算清掉核对并按服务级结算（未分级请求超过 15 分钟租约时同样如此）。多节点：续期由处理请求的节点发出、写在共享库里，该节点中途停止则无人续期，租约在 W 内到期，由任一节点的清扫器转核对。未分级与匿名（无预留）不受此影响，Kiro 不续租。跨协议单次时限 = `min(max_duration_seconds, created_at+60min−now−60s)`，不足 30 秒不发起（真正的约束是 60 分钟绝对上限，W 本身总覆盖时限）。

**4.7 会话粘滞（D4）**

今天：每节点 Caffeine 亲和表（expireAfterAccess 10 分钟，application.yml:172），`putIfAbsent`（AccountPoolService.java:1282），键 `keyId:hint`（ProxyFacade.java:2023-2050）。putIfAbsent 保留首次绑定：先绑主链路账号的会话在它恢复后回到它；先绑回退账号（如 Kiro）的会话在主链路池里找不到绑定，每次都重新从主链路选号。所以表达不了"降级后停留"。

两层：(1) **级内账号亲和**：键 `tier:<vendor>:<tierId>|<modelKey>|` + sessionHint，在可靠性检查（1276-1278）之后加前缀；准入会话哈希仍用原 hint（1063）。(2) **级别绑定** `TierSessionBindings`（独立 Caffeine，TTL、容量同亲和表）：键 `tier:<vendor>|<modelKey>|<tierSessionKey>`，值 `{tierId, level, accountId}`。带 modelKey 是因为资格依赖模型，Haiku 或 opus 请求不会移动 sonnet 的绑定。

**tierSessionKey**（只用于分级；sessionHint 与未分级亲和不变）：(1) 可靠的 sessionHint（2132-2134），但不是由 previous_response_id 得来的；(2) Responses：绑定同时登记在本请求产生的 response id 下（`keyId:resp:<id>`），下一轮按 previous_response_id 查到后转写到新 id——sessionHint 把 previous_response_id 当最后回退（2046），它每轮都变；(3) 都没有：内容指纹 `sha256(keyId|modelKey|system 前 2 KB|首条 user 前 2 KB)`，覆盖不带会话头的 OpenCode（生成的 `starry` provider 无 headers，clientSetup.ts:305-336；OpenCode 可能只对自家 provider 发 `x-opencode-session`〔外部〕）和以 store:false 发全量历史的 Codex〔外部〕；开头相同的会话共用绑定（O6）；不用于账号亲和。Codex 真实会话头或 `prompt_cache_key`〔外部〕在第 3 阶段核对，可靠则作为来源 (1)。

**写入（I8）**：在 X 级成功后，以下全部成立才 put 为 X：计划里有更高级别且走查确实访问过（未被起点、收窄、预算跨级跳过）；每个被访问的更高级都按 4.2 不可用；离开更高级不是因为结构剪枝（HARD、NO_MAPPING、CONTEXT 含学到的上限、CONVERSION、门控、白名单、短期排除）、预算/保留槽跨级、crossTierOnly 或上下文超长跨级；更高级的拒绝不全是本会话自己的并发（节点按（tierSessionKey, 级别）记录在途数，原因为并发/在途 token/会话数且本会话在该级有在途请求的拒绝不计入；请求照样按 D2 降级服务，只是不改绑定，Claude Code 并行子代理不会把会话推下去）。写入单调：只往低改，例外只有最后手段回升；晚完成的 1 级并发请求不满足条件、不写，不会改回去。

**读取**（sticky=true，AUTO）：按 tierId 在当前计划找绑定级（重排后仍跟随）；找到就从它开始、不查更高级；该级被本请求结构剪掉（例如带 HARD 特性）则从第一个不高于原 level 的级别开始，绑定不变；都没有从头开始。级内优先绑定账号，它不可用就换同级再往低，不回高。最后手段回升：起点及以下整轮不可用且未收窄，才进入 holdLevels 中更高的级别，成功后改写绑定；起点及以下的拒绝全是本会话自己的并发时，请求照样由更高级服务，但不改写绑定（同真实降级的判定）。写入"只往低改"按写入时计划里各级的当前顺序（按 tierId 查），不按绑定里存的 level。读取刷新访问时间，空闲 10 分钟过期；新会话从 1 级开始；派发协议随成员冻结，会话不在中转与 Kiro（注入身份提示，KiroPayloadFactory.java:161-179）间来回切换。

**其他**：sticky=false 或 PIN 不读不写级别绑定，PIN 的请求仍是会话的一员：派发期间计入该会话在途数（它造成的拒绝不算别人的负载），成功后算作会话在使用（刷新绑定的访问时间，所有入口一致，Responses 链的转写本来就算一次访问；一直发 PIN 请求的会话不算空闲，停留的级别不会在它工作的中途过期），Responses 链的绑定转写到它产生的响应 id（它的账号成为链的 owner 也不影响之后的自动请求，见第 3 节步骤 4）；`session-affinity-enabled=false`（application.yml:171）时级别绑定与级内亲和都不生效；账号删除或停用时级内账号亲和像 `invalidateAffinityForAccount`（AccountPoolService.java:1218-1220）那样清掉指向它的条目；级别绑定不记账号（记了就得在每次账号失效时重写全部绑定，Caffeine 把每次重写算作访问，空闲会话就永不过期）；绑定每节点独立：多节点部署时，负载均衡不把同一个会话固定到同一个节点，换了节点的请求就从 1 级重新开始、按 1 级计费，「停留」只部分成立，控制台开关旁和运维手册都写明。低级比 1 级贵时，降级会话在 1 级恢复后仍按低级价付费直到空闲，控制台开关旁写明。

**4.8 Responses 链**：只有 OPENAI 路由固定 owner（1950-1966）；owner 在计划阶段解析（3 步骤 4），在计划内时那一级不做可用性剪枝、按它计费。owner 选号和 owner 不可用后的回退都经 `selectTiered`，不再走无级别过滤的 `selectOpenAiAccountByIdForPool`（AccountPoolService.java:473-484，谓词只有 isOpenAi∨isDeepSeek + id）和 `selectAccountWithAutoRetest`（ProxyFacade.java:1030-1042）。跨协议尝试不记录 owner：今天 `attemptResponses` 对每个服务账号调用 `rememberResponseOwner`（739），状态存储也记 ownerAccountId（1920、1936），中转成为 owner 后未分级的 OPENAI 链会对它调 `/responses`。本节点无法展开的 previous_response_id（OpenAiResponseStateStore.java:37 的 6 小时等）对跨协议是 HARD，原生 Kiro 照旧；剪完只剩跨协议级 → 预留前 409 `RESPONSE_CHAIN_NOT_PORTABLE`。

**4.9 全端点统一（第 3 阶段，可选范围，O2）**：负责人没有要求，且会提高成本，向导**默认不勾选**。打开后（仅 anthropic、真实 Claude 目录模型）Chat/Responses 入口按 ANTHROPIC 路由：单元 `[ANTHROPIC_KEY, KIRO_COMPATIBLE]`，派发与预算按 ANTHROPIC（1218-1253）；Gemini id 与路由预设不扩展（routeFor 1261-1279）。这是唯一改变路由的开关。

**4.10 自动复测**：每请求最多一次，整轮为空后（904-968）；过滤器是计划内"单元谓词 ∧ 级别成员"的并集，再加上计划略去的级别（可用性剪枝与结构剪枝，含账号全被停用的级别）里这条路由用得上的账号（否则那一级永远不会经这条路径恢复；本请求计划已冻结，不会改用它们，下一个请求才会；被收窄的请求不含这部分），取代 `accountFilterFor`（1190-1216）；跨协议成员用第一条映射的 wire 名和跨协议客户端（7.4）；跨协议中断不触发复测与回升。

**4.11 恢复与多实例**：无共享"当前级别"；恢复时间取决于挡住它的层（准入/额度/冷却实时，生命周期 2 秒，启停 30 秒，配置 NOTIFY + 30 秒兜底）；各节点按自己的快照计价，请求内一致，快照记 configVersion。

**4.12 与旧熔断隔离**：分级请求的上下文 `circuitEpoch = null`。旧策略关闭时 `enabled()` 检查的是**设置**里的 epoch（AccountPoolPolicyService.java:772-776）而为假；打开时设置里的 epoch 非 null，`Objects.equals(null, settingsEpoch)` 为假（371）。两种情况 `matchesCurrentEpoch` 都返回 false，于是 `applyTierPolicy` 原样返回（253-255），`affinityKey` 返回原始键（232-238），不出现 `ACCOUNT_POOL_CIRCUIT_OPEN`（AccountPoolService.java:598-606）。旧熔断只作用于未分级厂商，`pool_tier`（V1:204、227）不变。

## 5. 计费

**5.1 价格（D3）**：最终单价 = 现有解析结果（唯一命中规则价；否则官方价 × `MODEL_PRICE_BILLING_MULTIPLIER`，PaymentSettingsService.java:33、48；或旧版绝对价，ModelPricingService.java:219-251）× fast ×2（287-295）× 号池系数。fast 只按**计费的** fastMode 收，fastMode 只在 OPENAI 路由成立（ProxyFacade.java:330）；Sanitizer 也只在它为真时发 `service_tier: priority`，anthropic 厂商的 "fast" 按 ×1 计、不发 priority。fastMode 为真而成员无 priority_tier → HARD。价格键永远是 requestedModel。

**5.2 预留**：`withBilling` 新增 TierPlan 参数，base 算一次。

- **holdLevels**：AUTO 无绑定 → 全部；AUTO 起点 s > 0 → s 及以下，加上更高级中估算不超过前者最大值的级别；PIN(t) → {t}；PIN(t+) → t 及以下。预留 = holdLevels 上 `estimate(inputBound, outputBound)` 的最大值；回升只进 holdLevels，停在便宜级的低余额用户不会因昂贵的 1 级被拒。
- 输出上界沿用 586-592 与强制 max_tokens（563-578），跨协议体原样转发。
- 原生输入上界今天的公式；`containsRemoteBillingInput` 对任何 image_url 含 data URI 都触发（656-664）；分级 Kiro 单元例外（3 步骤 8）。
- `xpInputBound(wireBody)` = 去 base64 的 UTF-8 长度 + 1024 + 每张内联图 `image_token_cap`；远程图片、文件 id、不可展开的 previous_response_id 才升到 1M。
- 被剪掉的级别不参与（不会误报 INSUFFICIENT_BALANCE 等，V15:32-45）。
- pricing 快照新增 `tierPlan:{vendor, configVersion, mode, holdLevels, startTierRef, levels:[{tierRef, level, name, factor, estimate, dispatchKinds}]}`；上下文不放 `BillingQuote.class`（唯一读取 1489-1493），改放 TierBilling。

**5.3 结算**：`recordSuccess`（1455）分级金额 = `attempt.level().quote().cost(usage)`，跨协议再取 `min(cost, level.estimate)`；报价冻结，重复结算相同（V15:95-101）；分级尝试的 level 在类型上非空，没有"缺 level"分支；调用 `settle_api_usage_served`。高级别失败共用同一笔预留；BILLING_UNCERTAINTY 按 `tierDefinitive`。

**5.4 错误转换在外层**：固定号池用尽内部仍抛 `NO_AVAILABLE_ACCOUNT`，在 `usingWhen` 外层转 503 `SOURCE_TIER_UNAVAILABLE`（Retry-After），释放判断（606-613、623-639）与 474-476 看到原始错误。

**5.5 示例（claude-sonnet-4.5：$3/M 入、$15/M 出、$0.30/M 缓存读）**：
- 2 级（×0.6）服务 8,000 入 + 1,000 出：0.039 × 0.6 = **0.0234**，多余预留退回。
- 1 级中转报 prompt 8000（含缓存读）、cached 6000、completion 1000：2000×3e-6 + 6000×0.3e-6 + 1000×15e-6 = **0.0228**。若中转 prompt 不含缓存读（报 2000 + `cache_read_input_tokens` 6000），今天的解析器只读 cached_tokens/DeepSeek 计数（OpenAiUpstreamClient.java:1331-1333）收 0.021，归一后 0.0228。
- 只在首块报用量的中转：100k 提示其中 90k 缓存，completion 为 0。正确输入费用 10k×3e-6 + 90k×0.3e-6 = **0.057**；旧"输入输出都估算"规则把全部输入当未缓存（BillingQuote.java:19-24）约 0.30，多收 5 倍。5.8 改为只估缺的那项。

**5.6 Coding Plan、ADMIN、匿名**：套餐按服务级 credits 扣（V15:158-175），资格只看 model_provider（V15:41-47）；只向 USER 扣费（V15:35）；匿名不计费（585）但按分级走查。分级 AUTO 的 INSUFFICIENT_BALANCE 消息列出更便宜的可选号池与后缀（第 3 阶段）；请求的模型 ID 是 Key 映射里围着 '@' 写的规则的源时（6.1），`模型@标识` 整串仍是映射源，消息不再写后缀，按标识列出号池。

**5.7 日志与可见性**：每次尝试写号源列；`model`、汇总主键（V1:734、825）、`upstream_model`（1556、1615、1778）不变；跨协议另写 wire_model、source_dispatch、usage_source、stop_reason、degraded_features，只给管理员（/admin/logs/me，AdminStatsApi.java:17，只给名称、级别、系数、credits）。收入：`SELECT source_tier_ref, sum(credits) FROM api_usage_events GROUP BY 1`。/v1/models（ModelCatalogController.java:26-32）：分级厂商标题价按第一个有成员的级别缩放（照顾只走 Codex 的模型，1329-1340），`public_listing` 时才追加 `sourceTiers[]`，不含协议或中转信息。

**5.8 跨协议用量**（今天 cacheWrite 写死 0、缺 usage 返回 0，OpenAiUpstreamClient.java:1325-1342，零用量对 OpenAI 路径计 0，BillingQuote.java:14-17）。`RelayUsageNormalizer` 产出平台约定（input 含缓存，缓存是子集；AnthropicUpstreamClient.java:483-499）：

- **合并**：SSE 各块 usage 逐字段取最大值。
- **数字**：JSON 数字之外，文本形式的计数只在 32 个字符以内按小数解析；更长的只认纯数字（可带符号），有效数字超过 18 位饱和，其余按无计数，从不把中转的长文本交给 BigDecimal（耗时随位数平方增长，一个兆字节的数字会占住读取应答的线程数分钟）。
- **字段**：prompt = prompt_tokens|input_tokens；cacheRead = 第一个存在的 `prompt_tokens_details.cached_tokens`、`prompt_cache_hit_tokens`、`cache_read_input_tokens`；cacheWrite = `prompt_tokens_details.cache_write_tokens`、`…cache_creation_tokens`、`cache_creation_input_tokens`；DeepSeek hit+miss（1335-1337）；output = completion_tokens|output_tokens；reasoning_tokens。
- **口径**：input = prompt + (读不含 ? cacheRead : 0) + (写不含 ? cacheWrite : 0)。"不含"只有缓存探针（7.4）观察到证据才能保存；配成"含"的错配只会少收。
- 不变量 cacheRead + cacheWrite ≤ input，否则按比例缩小记 CLAMPED。output 超过转发上限钳到**恰好**上限，input 超过该级上界钳到上界，记 CLAMPED；加上 `min(cost, estimate)`，I2 按构造成立。
- **只估缺的那项**：prompt > 0 时保留报告的 input 与缓存；prompt 完全缺失才 input = 去 base64 字符数 / 4（同 ProxyFacade.java:1169-1174）+ 每图 1,600〔外部〕；completion 缺失或为 0 而内容、工具或推理非空时 output = UTF-8 字节 / 3.5（同 AwsEventStreamParser.java:300-302），≥ 1 且 ≤ 上限。任一项估算即 ESTIMATED。
- 空的成功见 7.7。报告 input 低于估算 25% 照报告计费并计"疑似少报"。状态页按成员显示 15 分钟 ESTIMATED/CLAMPED 占比，ESTIMATED > 5% 标红。

## 6. 手动选级（第 3 阶段）

**6.1 通道**：模型后缀 > 请求头 > Key 偏好 > 自动。(1) 后缀 `模型ID@<引用>`（`claude-sonnet-4.5@kiro`、`gpt-5.5@L2`、`claude-opus-4.5@1+`），入口剥离一次，Gemini 路径变量同样剥离（ProxyApi.java:26），之后看到的都是干净 id；只有 `@` 确实是选择器才剥离，由**服务这个模型的厂商**判断（Key 的模型映射、路由与**服务它的账号**的厂商提示之后与规划器取同一个厂商：Kiro 路由上以 `claude` 开头的目录外模型由 Kiro 账号服务，算 anthropic；Responses 上只有 Codex 账号能服务的模型，如不以 `gpt` 开头的 `codex-mini-latest`，算 openai；`poolContext` 的提示仍是路由的，其池键冻结）：base 是目录模型且该厂商有分级配置（有厂商行**并且至少一个号池**；号池被删光只剩厂商一行的厂商算没有配置。目录正则不含 '@'，ModelCatalogManagementService.java:39），或 base 不在目录（比目录新的带日期 Claude id、目录里没有的 Codex 模型）但引用是该厂商的一个号池、级别或 `auto`（Vertex 风格的日期不是号池，留在 id 里）；Key 的映射有一条源模型自己带 '@' 而且接得住完整字符串的规则（整串，或围着 '@' 写的通配，如 `claude-sonnet-4@*`、`*@cheap`；`*`、`claude-*` 这样自己不带 '@' 的不算，它们照常映射 '@' 前面的模型）时不剥离，它就是一个映射源；映射**只问一次**（入口处算出 base 映到的模型，剥离时它就是请求的 mappedModel，不剥离时用完整串的映射），否则加权随机的映射会让判断后缀的厂商与服务的厂商各抽一次而不同；回显目录 upstreamModel（349、423）；`@auto` 清除请求头固定。(2) `X-Starry-Tier`，在 sessionHint 旁读取（2023-2050），CORS 已放开（WebFluxConfig.java:80）。(3) `api_keys.tier_preference`；匿名与 `*` Key 只能自动：匿名调用的后缀照样取走（上游不会收到带后缀的模型名），后缀和请求头都按不适用处理（`Status: ignored`）；`*` Key 存不了偏好（400 `API_KEY_TIER_PREFERENCE_INVALID`，须同一次 PATCH 选好模型分类）。请求头与 Key 偏好都是**提示**，不适用就忽略（6.3）：客户端配置、Key 会在不同端点、模型和响应链上使用同一个选择，后缀才是对一个请求的要求。

**6.2 语法**：`auto | <tierId> | L<N> | <N>`，可加 `+`；不带 `+` 严格固定（只用、只按这一级预留计费），带 `+` 为该级及以下；可固定到更贵的 user_selectable 级别；不能绕过 `verify`（322/498）、Codex 隔离和门控。

**6.3 错误（全部在预留前）**

| 情况 | 结果 |
|---|---|
| 后缀引用不存在的号池 | 400 `SOURCE_TIER_UNKNOWN` |
| 后缀固定的号池在本端点/模型无可用类型，或门控未开放（号池的账号全被停用或挂起也是：它们不在账号缓存里，号池没有这条路由的成员） | 400 `SOURCE_TIER_NOT_OFFERED` |
| 只有跨协议成员且含 HARD 特性 | 400 `SOURCE_TIER_FEATURE_UNSUPPORTED` |
| 后缀固定到不可选号池 | 403 `SOURCE_TIER_NOT_SELECTABLE` |
| 请求头/Key 偏好引用不存在、不可选或格式错误，或本路由在该号池里没有账号类型，或不可迁移的链的 owner 不在其中 | 忽略按自动，`X-Starry-Tier-Status: ignored` |
| 请求头/Key 偏好固定的号池在本路由有账号，但都被停用或挂起（不在账号缓存里，由账号表确认） | 仍固定，503 `SOURCE_TIER_UNAVAILABLE` + Retry-After，预留退还；不改由别的号池按另一个价格服务 |
| 后缀：不可迁移的链与固定号池冲突或 owner 不在计划内 | 409 `RESPONSE_OWNER_TIER_MISMATCH` |
| 不可展开的链且固定号池只有跨协议成员 | 409 `RESPONSE_CHAIN_NOT_PORTABLE` |
| 固定号池暂无可用账号 | 503 `SOURCE_TIER_UNAVAILABLE` + Retry-After，预留退还 |
| 从未配置过的厂商（含号池被删光、只剩厂商一行的） | 后缀按普通 id（I4），请求头忽略，不加任何号源响应头 |
| 配置过、已停用的厂商 | 剥离后缀并忽略；仅当请求带了选择器时返回 `Status: ignored` |

**6.4 响应头**：分级请求返回 `X-Starry-Tier`、`-Level`、`-Name`（UTF-8 百分号编码）、`-Mode: auto|pinned`、`-Status: served`，加入 exposedHeaders（WebFluxConfig.java:81）；合成 SSE 也带。未分级不加，唯一例外是 6.3 的 ignored。响应在结算后构造（422-424），所以头取值在计划冻结时就算好，结算后只放入现成字符串，不做可能抛异常的处理，避免"扣了费没有响应"。不含协议、wire 名、中转主机。

**6.5 客户端写法**〔外部，第 3 阶段核对〕：Claude Code `ANTHROPIC_CUSTOM_HEADERS="X-Starry-Tier: kiro"` 或 `/model claude-opus-4.5@official`；Codex `[model_providers.starry]` 的 `http_headers` 或 `model = "gpt-5.5@codex"`；OpenCode `provider.starry.options.headers` 或模型 `claude-sonnet-4.5@kiro`；SDK `default_headers`/`defaultHeaders`；CC Switch 用后缀或 Key 偏好。`clientSetup.ts` 新增「号池」选择，片段与界面语言无关（tests/i18n.test.mjs）。文档写明 OpenCode/Codex 的 D4 依赖内容指纹。

## 7. 跨协议号源（D1）

**7.1 基线**：入口与上游已经解耦：/v1/messages 在鉴权前转 Chat（273 → AnthropicRequestAdapter.messagesToChat 22-56），Gemini 转 Chat（287），非 OPENAI 路由的 Responses 经 `responsesToChat`（1246）；上游返回缓冲的 `PlatformCompletionResult`（5-10），客户端响应由工厂按 ResponseMode 合成（1898-1906、1968-1991）。缺口是**派发由路由决定**（1218-1253；/v1/messages 固定 ANTHROPIC，1266-1267）。直接复用 `OpenAiUpstreamClient.complete`（52-60、79-96）不行：浅拷贝把 top_k、stop_sequences、metadata（含 `metadata.user_id`）、thinking、previous_response_id 发给中转（801-803；AnthropicRequestAdapter.java:28-31），严格上游 400 = REQUEST 不重试（UpstreamFailureClassifier.java:77-79）；`openAiModel` 转小写并加 `openai/` 前缀，靠错误文本再 POST 一次（810-882）；强制非流式且只解析 JSON（805-808、211-220），中转回 SSE 得到不可恢复 502（205-206）→ 核对；带 error 的 200 被当成 0 计费的成功；cacheWrite 写死 0（1341）；兼容 Key 服务所有兼容模型（AccountPoolService.java:447-471）；缺 id 用 `"call_"+index`（235）；非法参数变 `{_rawArguments}`（1225-1234）；停止原因未建模。网关代理路径**不**注入 cache_control（只在测试请求里，AnthropicUpstreamClient.java:91-114），system 和内容被压平成文本（142-145；AnthropicRequestAdapter.java:34-38）。

**7.2 原则**：P1 按对门控（代码注册表 + 数据门控）；P2 原生路径与参数不变；P3 特性在原始请求体上检测，**以本请求路由的原生单元种类为基线**：原生同样会丢的是 SOFT（丢弃并记录，不剪枝），原生能承载而该中转不能的是 HARD（剪掉成员），跨协议号源不比原生差；P4 先复用（入口 → Chat → 中转 /chat/completions → PlatformCompletionResult → 入口工厂）；P5 计费不信任空、零或不合理结果；P6 一次生成（I7）。

**7.3 号源类型、对与升级**

原生类型与今天谓词一致（ANTHROPIC_KEY、KIRO 含 Claude OAuth、OPENAI_OFFICIAL、CODEX、DEEPSEEK、OPENAI_COMPATIBLE）；**OPENAI_CHAT_RELAY** = `isOpenAiCompatibleApiKey` ∧ 有 `protocol='OPENAI_CHAT'` 成员行。Google API Key 没有上游客户端（AccountTestService.java:300-306）。跨协议成员只用于 anthropic、openai、google（deepseek、自定义厂商本来就把兼容 Key 当原生，返回 400 `SOURCE_PROFILE_NOT_APPLICABLE`）。

`PairRegistry`：代码内 `(SourceKind, Entry, Vendor) → {status, converter, featurePolicy}`，未列出即 NOT_OFFERED（旧节点不认新对，滚动升级安全）。**统一规则**：对在转换器和**全部**必备夹具（7.14）就绪时以 EXPERIMENTAL 进入（门控只能 OFF/KEYS）；升 VERIFIED（可 ON）是单独的升级任务：KEYS 下累计 ≥ 200 请求、lint 失败 0、转换被拒 < 1%、ESTIMATED < 5%、UNBILLED < 2%，以状态页导出为证据；负责人可在升级任务里书面放宽请求数。

| 入口 \ 厂商 | anthropic | openai | google |
|---|---|---|---|
| CHAT | 第 5 阶段 → 升级 P5-09 | 第 5 阶段 → P5-09 | 第 7 阶段 → P7-04 |
| MESSAGES | 第 5 阶段 → P5-09 | 第 5 阶段 → P5-09 | NOT_OFFERED |
| RESPONSES | 第 6 阶段 → P6-05 | 第 6 阶段 → P6-05 | NOT_OFFERED |
| GEMINI | 第 7 阶段 → P7-04 | NOT_OFFERED | 第 7 阶段 → P7-04 |

负责人的 Claude 例子在四个入口上都有升级路径。三个 NOT_OFFERED 没有现实客户端（例如 Gemini 格式请求 gpt），需要时按同一流程新增。

**7.4 映射**

- 存储：`source_tier_member_models`，按（厂商，账号）分开；不放 proxy_accounts（会动 AccountResponse、导入导出、插件 DTO 和 30 秒账号缓存），不复用按 Key 生效的 model_mappings（ModelMappingService.java:98），不复用只给 Kiro 用的 targetModelId（KiroPayloadFactory.java:146）。
- 查找（modelKey）：映射后去掉 `starry/`、`-fast`（1368-1371）、`anthropic/` 的目录 id；先精确（大小写不敏感），否则 `modelCatalog.targetFor`（ModelCatalogProvider.java:20、30-50）且 target 同厂商时用 target 的行（`sonnet` 等别名共用 `claude-sonnet-4.5`，AnthropicModelCatalog.java:31-45；Gemini 别名的 target 是 Claude，GeminiModelCatalog.java:24-31，不被 google 映射误用）；都没有 → NO_MAPPING。
- 校验：账号 `isOpenAiCompatibleApiKey`；键是同厂商已启用目录模型（`ModelPoolKeyResolver.resolve(id,id,null).provider()`），排除 Routing Preset 与 CodeWhisperer Internal；wire 名合法、无通配；至少一条映射；max_context_tokens 必填；"缓存不含在 prompt 内"需要探针证据；门控对照注册表（NOT_OFFERED → 400 `SOURCE_PAIR_NOT_OFFERED`；EXPERIMENTAL 只能 KEYS，否则 409 `SOURCE_PAIR_GATE_FORBIDDEN`）。
- **验证** `POST /admin/source-tiers/{vendor}/members/{accountId}/verify`，用真实跨协议客户端与解析器：(1) 短提示 max_tokens 16，要求 2xx、finish_reason、用量字段；(2) 工具调用：`tool_choice: auto` 加明确指令和一个简单工具，要求合法 tool_calls JSON（不用 `required`：中转会转成 Anthropic 的 `any`，开启思考的新 Claude 模型对它返回 400〔外部〕）；(3) **两轮工具循环**：assistant tool_calls → tool 结果 → 最终回答（Claude Code/OpenCode 最常见的形状，也是 OpenAI→Anthropic 中转最常出错处）；(4) upstream_stream 时带 include_usage 的 SSE；(5) vision 时 1×1 PNG；(6) 可选缓存探针：两次相同约 1,100 token 提示，比较 prompt_tokens 与缓存字段，分别给出"读/写是否含在 prompt 内"的建议与证据。通过后写 verified_at、verify_summary、`verified_hash = sha256(配置列 + upstream_model + 账号 provider + base URL)`；指纹不符视为未验证。运营者承担费用，按账号 30 秒限流。`GET …/upstream-models` 经账号出口拉 `{base}/models`，预填 wire 名与 context_length。
- 账号测试与复测（904-968）对跨协议成员改用第一条映射的 wire 名，不用 `AccountTestService.openAiModel` 把 claude-* 换成默认模型的逻辑（462-500）。

**7.5 派发**

- `Dispatch{kind: NATIVE|OPENAI_CHAT, pair, wireModel, member, wireBody, outgoingEndpoint}` 与 level、cell 一起在选中时写入；原生尝试调用 `callWithAccount`/`callResponsesWithAccount`，参数一字不差；未分级永不创建 OPENAI_CHAT。
- `XpDispatcher`（`tech.starryapi.proxy`，可用包内 `neverReachedUpstream`）：浅拷贝预生成体写入 wire 名 → `OpenAiChatRelayClient`；**捕获全部异常**：请求写出后的任何失败（读超时、连接错误、200 text/html、JSON/SSE 解析失败、解码错误、累加器内部错误）按 7.7 的 possiblyGenerated 变成可恢复的未生成 `UpstreamException`（502）或 `RelayAbortedAfterDispatchException`；能证明未发出的保持原分类：这一证明由中转客户端给出（它知道请求有没有交给连接；没有就把传输错误包进 `RelayNotSentException`），调度器按被包的原错误分类，不再只凭 `neverReachedUpstream` 的错误类清单判断（对端复位或关闭的握手只是普通 SocketException，清单认不出），清单仍作为没有经过客户端的错误的第二道检查。为每个工具调用**铸造网关唯一 id**（Messages `toolu_` + 24 位 base62，Chat/Responses `call_` + 24 位），中转的原 id 和它的 Chat 形状的原始项（rawOutputItem）都不往下传：Responses 渲染器会把原始项抄进客户端的项里，每轮都是 `call_0` 的中转 id 就成了客户端看到的 id。
- 准入元数据 route 记 `OPENAI_CHAT`（1484）；CAPACITY_MODEL 证据（1752）与准入（1068）的模型键都用映射行 model_id；端点作用域 `xp:/chat/completions`（1069、1753）；`request_logs.path` 仍是客户端路径。
- **专用排除**（`exclusive=true`，第 4 阶段与成员保存同时上线）：不进入未分级兼容池（447-471）、计划内 OPENAI_COMPATIBLE 单元、兼容复测过滤（ProxyFacade.java:1199-1203）和 owner 选号（`selectOpenAiAccountByIdForPool`）；成员行存在即生效；registry 为空时是空操作。`exclusive=false` 时继续服务 GLM 等未分级流量，只服务 Claude 的中转会返回 404（REQUEST，不重试），控制台警告。跨协议单元只含 OpenAI 类账号，排序器用 OpenAI 策略（AccountCandidateRanker.java:51-52、216-218）。

**7.6 请求转换**（计划阶段按成员配置签名生成，签名相同只算一次）

- **CHAT**：有界的客户端请求体（331-333）。**MESSAGES**（第 5 阶段）：今天 `messagesToChat` 的输出原样（工具 192-203，tool_choice 48-54/175-190，tool_use 67-83，tool_result 85-101），HARD 特性在原始体上检测。**RESPONSES**（第 6 阶段）：`ResponsesChatConverter`，输入为本地展开后的体（521）：连续 function_call 合并为一条 assistant（修正 OpenAiRequestAdapter.java:53-65）；tool_choice 形状修正（31）；跳过 reasoning、item_reference、未知项（修正 73-78，网关自己会把 reasoning 写进链历史，OpenAiResponseStateStore.java:90-97）；不复制 previous_response_id；instructions/developer → system；apply_patch 复用 Codex 仿真（需把 OpenAiUpstreamClient.java:463-485、1142-1172 的私有方法抽成包内辅助并提供 Chat 形状 `{type, function:{…}}`，原输出逐字节不变）。**GEMINI**（第 7 阶段）：`GeminiChatConverter`：functionCall 唯一 id、functionResponse 按函数名先进先出配对（修正 GeminiRequestAdapter.java:71、84）；inlineData → image_url（仅 vision）；toolConfig → tool_choice；stopSequences、responseSchema、thinkingConfig 映射；thoughtSignature 经 `extra_content` 往返〔外部〕。
- **`ChatWireSanitizer`**（白名单新建，不是浅拷贝）：顶层保留 model（wire 名）、messages、token 字段（按目录 upstreamModel 的 `usesMaxCompletionTokens` 选 `max_completion_tokens`/`max_tokens`）、temperature、top_p、stop（≤ 4，来自 stop 或 stop_sequences）、tools、tool_choice、parallel_tool_calls、penalties、seed、response_format（json_schema 仅 json_schema 开）、reasoning_effort（见下）、`service_tier: priority`（仅计费 fastMode 且 priority_tier）、stream/stream_options（按 upstream_stream）；其他一律丢弃（top_k、metadata、thinking、user、previous_response_id、store、include、n、logprobs、fast、会话 id 等）。**消息与部件也走白名单**：消息只留 role、content、tool_calls（id、type、function.name、function.arguments）、tool_call_id；部件只留 `{type:text,text}`、`{type:image_url,image_url:{url,detail}}`（仅 vision）、`cache_control`（仅 cache_control_passthrough）；丢弃 name、reasoning_content、refusal、audio 等（OpenCode 会给 claude id 加 cache_control〔外部〕，严格中转会 400）。developer → system；无 vision 的 image_url、audio、file 换成占位并记 SOFT。**reasoning_effort**：只在 EFFORT、客户端请求了思考（Anthropic `thinking.budget_tokens`，或 `thinking.type: adaptive` + `output_config.effort`〔外部：Claude Code 新模型的写法〕，取自原始入口体，因为 messagesToChat 不复制 output_config，22-56；或 Gemini thinkingConfig）、且（模型的厂商需要上一轮的 thinking 块，即 anthropic：`Settings.continuationDropsReasoning`，计划按 `dropsReasoningOnContinuation(vendor)` 设置，净化器与特性策略读同一个标志）本轮不是工具续接轮时发送；其他厂商（openai 的推理模型每个请求都接受 reasoning_effort）每轮发送，否则智能体循环里几乎每一轮都落到模型的默认级别，用户为不是他们要求的推理量按号池价付费；预算 < 4k → low、< 16k → medium、否则 high；effort 原样映射（max → high）。客户端请求头永不转发。
- **`ChatCanonicalizer`**（净化后）：工具调用组合并、tool 消息紧随；只有 tool_calls 的 assistant content=null；**不删除 user 轮**：空 tool 内容 → `"[no output]"`，空 user 文本（例如只有图片，messagesToChat 产生 ""，AnthropicRequestAdapter.java:103-105、139-146、155-164）→ `"[non-text content omitted]"`，都记 SOFT；空 assistant 文本消息删除并合并相邻同角色消息；孤立 tool 结果转 user 文本；非末轮未回答的调用补 `Tool result missing`（Kiro 也修复，KiroPayloadFactory.java:272-279、317-330）；工具 id 规范为 `^[A-Za-z0-9_-]{1,64}$`，**重复 id 按位置消解**（每条 tool 消息配最近的前一个 assistant 调用，整对一起改名）。**请求体没有 `tools` 时**（客户端为总结/压缩重发整段对话并去掉了工具，或只剩被摘掉的 tool_search），**且成员的上游拒绝这样的历史**（`Settings.needsToolsForToolHistory`，按厂商：只有 anthropic；与 `continuationDropsReasoning` 同为模型厂商的属性），历史里的工具调用转成文本（附在所在助手消息文本之后，形如 `Tool use: <名>\nInput: <参数>`；每个答案成为 `Tool result:` 用户消息），记 `tool_history_flattened`。转换在配对与排序**之后**做：答案已按调用顺序紧随其调用，所以客户端把并行调用的结果写成别的顺序时，每个结果仍对着自己的调用（文本里没有 id），非末轮没有答案的调用也有 `Tool result missing`。理由：转发给 Anthropic 或 Bedrock 的中转会拒绝没有 tools 却含 tool_use/tool_result 的请求〔外部〕，而 Kiro 同样转成文本（KiroPayloadFactory.java:323-379），否则 Kiro 能服务的请求中转会回 400。转发给 OpenAI 的中转与原生 OpenAI 账号一样收到结构化历史（外部行为未在本地验证），不转换也不记名：这个名字是对历史的修补而不是请求的特性，只在原生路径对同一模型也这样做的地方记录，所以策略不需要为它设 Feature（一致性测试 `FeaturePolicySanitizerTest` 核对修补名与特性名互不相同，且 `tool_history_flattened` 只在成员设置要求时出现）；Google 厂商随它的对与夹具再定。重命名后缀从 id 的前 57 个字符之后加起（更长的 id 先截到 57），共享同一个计数：开头相同的 id 各自计数会让耗时随个数平方增长，请求体上限内即可被客户端拖住请求线程。id 按调用顺序分配，每个取第一个空闲名，所以早先的调用占着的名字（包括给前面被改名的调用起的名字）会让后面客户端自己写的同名 id 也被改名（`c, c, c_2` → `c, c_2, c_2_2`）；没有更早的调用占用的合法 id 不动。原生 Kiro 接受同样的历史（空内容变 "Continue"，145；空工具文本原样，224-231），所以这些规范化让中转不比原生差。
- **`StrictChatLint`**：只查结构：角色合法、每条 tool 回答前一 assistant 的某个 id、除末轮外无未回答调用、id 合法且唯一（规范化之后）、函数名非空、stop ≤ 4、无未知键。失败以 CONVERSION 剪掉成员。

**7.7 中转客户端与解析**

- `OpenAiChatRelayClient`（platform.openai，复用 `OpenAiEndpoints.openAiApi`，15-38，同一 WebClient 与出口属性 92）：每次尝试恰好一次 POST，不调 openAiModel；upstream_stream 时 `bodyToFlux(DataBuffer)` 增量 SSE，累积上限 16 MB（HttpClientConfig.java:59），持续到达的数据不会触发 300 秒读间隔超时（HttpClientConfig.java:38）〔外部〕；JSON 模式下中转仍回 SSE 就按 SSE 累积；读到 `[DONE]` 即结束（`takeUntil` 并关闭连接）：此后中转不带最后一块就关闭连接、或一直不关，都不能把已完整、已付费的应答变成中断；`[DONE]` 之后的任何字节（不是 UTF-8 的、没写完的多字节字符的头几个字节）都在应答之外：无论它与 `[DONE]` 在同一次网络读取里还是在之后的读取里（之后的根本不读），应答都照常返回——TCP 怎么分段不能决定一份完整、已付费的应答是否成为中断；总时限见 4.6。
- `ChatCompletionAccumulator`：content 字符串或部件数组；`reasoning_content`/`reasoning`；tool_calls 按 index（缺失按 id）组装；finish_reason；usage 逐字段取最大。`started` = 收到非空 content/reasoning/tool_calls 增量，或 completion_tokens > 0，或 finish_reason 为 length / max_tokens / content_filter（模型用完了预算或被过滤，小预算全花在中转不显示的推理上时内容和用量都可能是空的，这样的应答不能当作没生成去别处再试一次）。`possiblyGenerated` = started ∨ 收到任何 SSE data 块（含只有 role 的块）∨ 收到保活注释 ∨ 无法解析但表明自己是应答的 JSON 体（整体返回的体只在中转做完之后才到，管道转发上游体的中转在上游断开处干净地结束它：含 `"choices"`、`"finish_reason"`、`"completion_tokens"`、`"object":"chat.…"` 或以 `chatcmpl` 开头的 `"id"` 之一；错误体常有的 `message`、`usage` 不算，被截断在这些字样之前的体仍是未生成）∨ 请求写出后已过 30 秒。JSON 体只读第一个值，其后的内容（空白、`data: [DONE]`、杂字节）不看：完整的应答不因它被丢弃（Jackson 3 默认的尾部令牌检查会拒绝它）。同一次网络读取里坏字节（非 UTF-8）之前的事件照常先读，所以结果不取决于 TCP 怎么分段。
- **带内错误与截断**：

| 情况 | 未 possiblyGenerated | 已 possiblyGenerated |
|---|---|---|
| 2xx 带顶层 `error`（JSON 或任一 SSE 块；同一块里还有 `finish_reason: "error"` 时也是它，先于该标志） | 按 7.11 映射状态；映射结果是 5xx 且请求写出已过 30 秒时与错误状态码相同，按 7.8 的「30 秒之后的 5xx」为中断（先发 200 再等自己上游的流式中转、总回 200 的中转只能这样报；400、402、404、429 等拒绝照映射，任何时候都是拒绝） | started 时为中断 |
| 无 choices 且无 usage；`finish_reason: "error"`；空的成功（有 choices、内容/工具/推理全空、completion 为 0） | 502 可恢复（确定、可跨级） | 中断 |
| 无 choices 但 completion_tokens > 0；空内容但 completion > 0 | — | 中断 |
| SSE 无 finish_reason 也无 `[DONE]` 即结束；任何解析或解码失败；200 非 JSON/SSE（如 text/html：首个非空白行不是 SSE 行——`data:`、`event:`、`id:`、`retry:` 或冒号注释——就不按流读，网页里以冒号开头的样式行不能让它变成保活注释） | 502 可恢复 | 中断 |
| 总时限、读超时、写出后的连接错误 | 中断 | 中断 |
| 能证明未发出（`neverReachedUpstream`、出口未发送） | 现有分类 | — |

  空的成功不再渲染成空文本 + end_turn（AnthropicResponseFactory.java:59-64、89-93），否则 Claude Code 的循环会静默停止而用户被计费。
- **停止原因与工具**：`PlatformCompletionResult` 追加可空 stopReason、usageSource、degraded（原生为 null，ProxyWireFormatTest :24、:103 不变）；stop → END_TURN（有工具 TOOL_USE）、length → MAX_TOKENS、tool_calls → TOOL_USE、content_filter → CONTENT_FILTER，中转不产生 STOP_SEQUENCE。工具参数须解析为 JSON 对象（空串视为 {}）：不合法且 finish_reason = length → 丢弃该调用、MAX_TOKENS；否则按中断。推理永不输出为 Anthropic thinking 块（不可能有合法签名），所以只有推理、没有文本和工具、以 stop 结束的答案在 MESSAGES 入口上按空的成功处理（中断）；只有空白的文本在所有入口上都算空。一个答案最多 256 个工具调用（同一个 index、id 都没有区分的调用按函数名和已完整的参数区分），超过按中断。已有调用的 id 再次出现的块永远是这个调用的一部分，不开新调用，除非块里点名了另一个函数（重发的块总会重复函数名；每个调用都叫 `call_0` 的中转因此仍按函数名区分，得到两个调用）（重发的完整参数与已有的是同一个 JSON 值就只留一份，文本不同也一样；是别的值则该调用不合法，按中断，而不是多出一个客户端会照做的调用）。拼装对每个片段是 O(片段)：参数是否已是完整对象随追加增量记录，不对已收到的部分重新复制或解析（否则一个大的 Write 调用或敌意中转会占住事件循环线程）。

**7.8 失败分类与跨级**（新增 `classify(error, account, dispatch)`，在通用 switch（UpstreamFailureClassifier.java:72-111）之前；两参数版不变）

| 失败 | 分类 | 重试 | 计费 |
|---|---|---|---|
| 中断 `RelayAbortedAfterDispatchException`（502、recoverable、generationStarted） | TRANSPORT，1 分钟冷却；本会话对该成员短期排除 2 分钟 | 不重试，不复测，不回升 | 确定释放，UNBILLED，计运营者损失 |
| 模型未服务：404，或 400/403/422/503 且命中 model-not-found 模式（OpenAiUpstreamClient.java:865-882；UpstreamFailureClassifier.java:169-181） | CAPACITY_MODEL，10 分钟，按（账号, 映射 model_id） | 跨账号、跨级 | 确定 |
| 上下文超长（未开始生成） | REQUEST + crossTierOnly(native) | 一次跨级，只去首单元为原生的级别；没有则返回 `prompt is too long …` | 确定 |
| 其他 400/413/422（转换被拒） | REQUEST + crossTierOnly(native)，计"转换被拒" | 同上 | 确定 |
| 402 | CAPACITY_ACCOUNT（同今天） | 今天的规则 | 分级请求按 `tierDefinitive` 确定 |
| 408、504、524（超时：中转或它前面的网关等不及了，中转还在生成） | 同第一行：中断（`RelayErrorMapper` 保住这三个状态，不论正文怎么称呼；`error.code` 或类型是 `timeout` 的带内错误也是 504） | 不重试 | 确定释放，UNBILLED |
| 请求写出 30 秒之后到达的其他 5xx（可能已经生成；映射成 400/404 等拒绝的除外） | 同第一行：中断 | 不重试 | 确定释放，UNBILLED |
| 401/403/409/429、30 秒内的 5xx | 今天的规则 | 今天的规则 | 今天的规则 |

- **学到的上下文上限**：上下文超长时，本节点记录（成员, 映射）的上限 = 失败时的估算输入（6 小时 TTL），之后计划以 CONTEXT 结构剪掉超过它的请求，不再每次撞一次。Claude Code 的压缩请求（含整个历史）因此直接去 Kiro 或窗口更大的原生级别。
- **中断后的短期排除**：中断返回 502（mapUpstreamStatusCode，1874-1892），SDK 会自动重试 5xx（Claude Code 多次〔外部〕）；对（tierSessionKey, 成员）排除 2 分钟，使重试去别的成员或级别，而不是让运营者为多次部分生成付费。短期排除是结构原因，不写 D4 绑定。
- **I7 的边界**：`withBilling` 在 Reactor 上下文里放 `XP_IN_FLIGHT`：跨协议派发时置真，**直到结算结果返回**才清除（失败转入下一次原生尝试时也清除）；取消处理（ProxyFacade.java:613）在它为真时以 definitive=true 释放（原因 `Client disconnected; cross-protocol attempt released`）；结算已提交时释放是空操作。原生与未分级的断开行为不变（风险 16）。

**7.9 特性策略**（原始体上检测，只作用于跨协议成员；CARRY 承载，CARRY_IF(x)，SOFT 丢弃记录，HARD 剪掉）

- **MESSAGES**：文本、system、多轮、自定义工具、tool_use/tool_result、tool_choice、disable_parallel_tool_use、temperature、top_p、max_tokens CARRY；stop_sequences ≤ 4 CARRY、> 4 HARD；服务端工具与 Anthropic 定义工具（`tools[].type` 非 custom：web_search_*、web_fetch_*、code_execution_*、bash_*、text_editor_*、computer_*、memory_*）、mcp_servers、container HARD（原生把它们变成假函数，192-203）；**tool_search_tool_* SOFT**：去掉该工具，defer_loading 的工具作为普通函数发送（与原生一致），否则带它的每个主循环请求都会跳过中转〔外部〕；图片、文档 SOFT（同原生，140-146；第 8 阶段 CARRY_IF(vision)）；thinking 参数 CARRY_IF(EFFORT) 且（anthropic 厂商的模型）非工具续接轮，否则 SOFT；output_config.effort 同 thinking；历史 thinking 块、cache_control（第 8 阶段 CARRY_IF）、tool_result.is_error（第 8 阶段 CARRY）、top_k、metadata、service_tier、其他 beta、**context_management** SOFT（messagesToChat 本来就丢，22-56）；估算输入超过上限 HARD。
- **CHAT**：文本、多轮、tools、tool_choice、parallel_tool_calls、temperature、top_p、penalties、seed、max_tokens CARRY；stop > 4 HARD；n > 1 HARD（只返回 choices[0]，OpenAiResponseFactory.java:31-35）；web_search_options、旧版 functions、非 function 工具 HARD；fast（openai 厂商计费 fastMode）CARRY_IF(priority_tier) 否则 HARD；developer CARRY；reasoning_effort CARRY_IF(EFFORT) 否则 SOFT（anthropic 厂商模型的工具续接轮除外，理由同 MESSAGES：下一轮需要上一轮的 thinking 块；openai 厂商每轮携带）；`tool_choice` 为 `allowed_tools` 等中转格式没有的类型（只有字符串 auto/none/required 与 `{type:function}` 能给中转）：特性 `tool_choice`，基线同图片（透传型 HARD，Kiro 类与 MESSAGES SOFT；只在请求有 tools 时才算，与净化器同一个判断）；**verbosity（`low`、`high`）、logit_bias 按基线**（它们改变应答，透传型 HARD，Kiro 类 SOFT；`verbosity: "medium"` 是默认值，写出来不要求任何不同的应答，不算特性，不会让 gpt 请求跳过所有中转成员）；prompt_cache_key、prompt_cache_retention、safety_identifier（永不转发，7.12）、logprobs、top_logprobs、user、metadata、store、prediction、modalities SOFT；消息/部件级 cache_control CARRY_IF(cache_control_passthrough) 否则 SOFT。**图片、json_schema、audio/file 按基线**：路由原生单元是 Kiro 类（anthropic、google 厂商）→ image_url CARRY_IF(vision) 否则 SOFT（Kiro 只取文本，KiroPayloadFactory.java:609-627）、json_schema CARRY_IF 否则 SOFT（降为 json_object）、audio/file SOFT；路由原生单元是透传型（OFFICIAL_OPENAI、CODEX、OFFICIAL_OR_CODEX、OPENAI_COMPATIBLE、DEEPSEEK，透传整个请求，OpenAiUpstreamClient.java:84-87、801-803）→ 这三项不满足条件时 **HARD**，gpt 请求落到中转级别不会悄悄丢图或 schema。
- **RESPONSES**：function 工具、function_call/output、input_text/output_text、instructions、max_output_tokens、temperature、top_p CARRY；apply_patch CARRY（仿真）；其他自定义工具与内置工具（web_search*、file_search、local_shell、code_interpreter、image_generation、computer_use*、mcp）HARD；reasoning 项（含 encrypted_content）SOFT（原生 Kiro 同样丢失，且网关无法跨号源携带）；reasoning.effort CARRY_IF(EFFORT)（工具续接轮的规则同 CHAT：只对 anthropic 厂商的模型去掉）；input_image、text.format json_schema 按 CHAT 的基线规则；include、store、truncation、prompt_cache_key、metadata、service_tier SOFT；`text.verbosity` 与 CHAT 同一规则（`medium` 不算特性；`low`、`high` 是否像 CHAT 的透传基线那样 HARD，待 O9 决定，代码在 RESPONSES 入口上暂按 SOFT，因为这个入口的转换在任何路由之前已把请求压平）；不可展开的 previous_response_id HARD。
- **GEMINI**：文本、systemInstruction、多轮、functionDeclarations、functionCall/Response（唯一 id）、toolConfig、thoughtSignature CARRY；inlineData/fileData CARRY_IF(vision) 否则 SOFT；stopSequences > 4、candidateCount > 1、googleSearch/codeExecution HARD；responseSchema、thinkingConfig CARRY_IF 否则 SOFT；safetySettings SOFT。

**7.10 渲染**：工厂按 ResponseMode 渲染，跨协议结果额外渲染非空 stopReason：MAX_TOKENS → Anthropic `max_tokens` / Chat `length` / Responses `incomplete` + `max_output_tokens` / Gemini `MAX_TOKENS`；CONTENT_FILTER → `refusal`〔外部〕/ `content_filter` / `incomplete` / `SAFETY`。回显目录 upstreamModel（349、423）。错误在任何 SSE 字节之前以 HTTP 返回（422-424）。第 7 阶段 Chat 工厂在工具调用上重新输出 extra_content，Gemini 工厂输出签名与用量明细。

**7.11 错误与脱敏**：`RelayErrorMapper`：rate_limit → 429；insufficient_quota/billing → 402（分级请求确定，4.5；文本里的 insufficient balance、余额不足、额度不足同样算）；HTTP 402/429 状态优先于正文里泛泛的 invalid_request 类型（DeepSeek 对余额耗尽回 402，类型却是 invalid_request_error，按 400 会成了不冷却、不换中转的转换被拒），2xx 里的带内错误以数字 `error.code` 402/429（数字或数字字符串）说同一件事时也一样（总回 200 的中转只能在正文里说）；invalid_request → 400；context_length_exceeded 或"maximum context length/prompt is too long/input is too long" → 400 + contextExceeded；model_not_found/no available channel → 404；认证 → 401；permission → 403；overloaded/server_error → 503 可恢复；`error.code` 为 4xx/5xx 数字时采用；其他 → 502 可恢复。消息 `Source error <status> (<匹配 ^[a-z_]{1,40}$ 的 type/code>)`；中转原文（≤ 2 KB）只进服务端 WARN 日志（凭证遮蔽只扫文本开头 8 KB，在空白处截断且不切开账号自己的密钥，之后再截到 2 KB，因为 2xx 里的带内错误只受 16 MB 应答上限约束；账号密钥是必传参数，没有不带它的重载），不进客户端消息和用户可读的 `request_logs.error_message`（safeFailureMessage，ProxyFacade.java:1694、1865-1872）。所有号源保持 Starry 信封（GlobalExceptionHandler.java:18-23，O4）；`prompt is too long` 改写只对会解析 Starry 信封的客户端有用〔外部〕，协议原生信封在第 9 阶段。

**7.12 安全与隐私**：中转只看到白名单请求体、Bearer Key 和出口路由，永远看不到客户端请求头、`metadata.user_id`、会话 id、`user`；`exclusive` 默认开；添加成员时提示提示词、代码和工具输出会发给第三方；模型预取与验证是管理员调用，经出口、限流。

**7.13 灰度**：两把锁（代码状态 + 数据门控）；门控默认 OFF，启用厂商分级不打开任何对；KEYS 模式供负责人先用自己的 Key；OFF 是 kill switch，进行中的请求按冻结计划完成。

**7.14 必备夹具**（`PairRegistryCompletenessTest` 要求 EXPERIMENTAL 与 VERIFIED 都齐全；取自真实 Claude Code、OpenCode、Codex、Gemini SDK 请求，id 用占位符）：纯文本；system 字符串/数组；多轮；单工具；并行工具；两轮工具循环；tool_result 错误；tool_choice 四种；停止序列 ≤4/>4；max_tokens 截断含不完整工具 JSON；vision 开/关；cache_control（顶层与部件级）；thinking 在 NONE/EFFORT 与工具续接轮；adaptive thinking + output_config.effort；服务端工具剪枝；tool_search_tool；两个口径开关的四种组合；缺失用量 → 估算；**只在首块报用量**；多报 → 钳制；200 带 error；**200 text/html**；**畸形 SSE data 行**；无 choices（有/无 completion）；空的成功；stream:false 回 SSE；有/无 usage 块；开始前/后截断；只收到 role 块后 EOF；流中 error 块；400；404 model_not_found；上下文超长（含 Claude Code 压缩请求回退到 Kiro）；429 Retry-After；5xx；402；**只有图片的 tool_result、无 content 的 tool_result、空 Bash 输出、只有图片的 user 轮，各接 3 轮**；**每轮都返回 call_0 的中转，再回退到 ANTHROPIC_KEY**；中转原文不出现在客户端消息；Unicode；白名单成立（顶层、消息、部件三级）。状态页按（厂商，级别，成员，对）显示成功率、降级率、转换被拒、带内错误、ESTIMATED/CLAMPED/UNBILLED、p95、跳过原因。

**7.15 各客户端效果**

- **Claude Code**：文本、工具、并行工具同原生；截断如实报告；不输出 thinking 块（同原生）；WebSearch 子请求（web_search_*）、无映射的 Haiku、超长请求只让**那个请求**去原生级别，主循环的级别不变（I8）；图片在第 8 阶段前同原生丢弃；**中转级别没有提示缓存**（system 与内容压平成字符串，cache_control 第 8 阶段前是 SOFT），每轮按全价付输入，而 Kiro 计缓存读（AwsEventStreamParser.java:166-176）——同系数下 1 级可能每轮贵数倍，控制台警告，第 8 阶段是 Claude Code 成本对齐的前提；count_tokens 在所有号源都是 404（ProxyApi.java:17-31），Claude Code 会回退为估算〔外部〕。
- **OpenCode**：接近透传；没有会话头时 D4 依赖内容指纹；合成流没有 usage 块（OpenAiResponseFactory.java:481-506），OpenCode 无法提前压缩〔外部〕，中转窗口小于目录窗口时由上下文超长跨级和学到的上限兜底，第 9 阶段 P9-03 补 usage 块。
- **Codex**：推理照旧以 reasoning 项返回；内置工具请求跳过中转；apply_patch 仿真；截断给出 incomplete；D4 经 response id 转写或内容指纹。
- **Gemini 客户端**：generateContent 可用；streamGenerateContent/countTokens 在第 9 阶段。

## 8. 管理端与用户端

- **号池分级页**（`settings-source-tiers`，加入 SystemSettingsLayout.vue:47、186，路由在 router/index.ts:254-266 旁）：厂商标签；开关「启用」「全端点统一」（默认不勾选，附成本提示）「公开号池名称」「会话保持在降级号池」（默认开，写明空闲 10 分钟回到 1 级、低级更贵时继续按低级价、绑定只记在各节点上）「每级拒绝上限」「跨级保留槽」；号池表（拖拽排序、名称、标识与"复制 `模型@标识`"、系数与"标准价 × 系数"示例、成员统计、可选、启用；最低级标注"未归类账号归入此级"，收进 OFFICIAL_API_KEY 时警告）；能力位为假时显示"本版本尚不支持"并禁用对应开关。
- **跨协议成员对话框**：只列兼容 Key；配置项逐项说明（vision、两个缓存口径开关——"不含"需探针证据、上游流式、思考映射、json_schema、priority、单次时限）；专用开关（关闭时警告 404 风险）；映射表（目录模型、wire 名、覆盖的别名、上下文上限必填、图片上限、启用、验证状态），「从上游拉取」预填；验证按钮与探针结果；隐私提示；未设并发/RPM 上限时提示（兼容 Key 默认不限，AccountAllocationCategory.java:16，D2 的繁忙几乎不会触发）；Claude Code 无提示缓存的成本警告（7.15）。
- **门控面板**：每入口 OFF/白名单 Key/ON，注册表徽标，降级摘要。**状态**：每级在各层的账号数、本节点会服务的级别、15 分钟服务分布与降级率、跨协议指标。保存整体替换带 version，409 提示刷新。
- **其他页面**：账号列表新增「号源级别」列、按级筛选、批量「设为号池…」（`AccountResponse` 与 formModel.ts:110-137 不改）；旧熔断卡片（SettingsView.vue:186-254）说明只作用于未分级厂商，并修正 OPERATIONS.md:308-309、API.md:545；日志新增号源列（管理员另见派发方式、wire 名、用量来源、停止原因、降级特性）；**计费核对**（CodingPlanSettingsView.vue 的核对列表）显示 tierPlan、served 与最后派发的级别及**该级报价估算**，不只显示最大预留，避免管理员按最大预留结算；定价面板写明"规则只命中一条，号池系数在其后再乘"。
- **管理 API**（verifyAdmin）：`GET /admin/source-tiers`（含 runtime 能力位）、`PUT /admin/source-tiers/{vendor}`（整体替换，expectedVersion）、`POST …/members`、`POST …/members/{id}/verify`、`GET …/members/{id}/upstream-models`、`GET …/status`、`POST …/preset`（只返回草稿）、定价预览可选 `factors`。
- **用户端**：Key 表单「号源偏好」（`GET /admin/source-tiers/me?provider=`，只返回名称、级别、系数、价格）；模型页号池价格与复制后缀；用量助手与我的日志显示号源名称、级别、系数、credits；docs/API.md 与文档模板。
- **i18n**：新增中文文案在 en.json 有对应条目且占位符一致（tests/i18n.test.mjs）；号池名称、标识、wire 名是数据，不经 t()。`utils/http.ts` 的 SILENT_PATHS 用 `endsWith` 匹配（http.ts:91、150），动态路径要在 150-155 的 `url.includes` 分支加 `'/admin/source-tiers/'`。

## 9. 迁移与兼容

- **I4 清单**：`source_tier_vendors` 为空时全部 UNTIERED，`selectAccount` 原样；4 参数 settle、上下文 `BillingQuote.class`、原 `markUsageDispatched`、pricing JSON 逐字节不变；新列 NULL；/v1/models 不变；`pool_tier`、熔断、AccountResponse 不变；无跨协议行时排除集为空，XP 代码不被调用，工厂输出不变；402 的计费判定不变。
- **构造器**：现有构造器已标 `@Autowired`（ProxyFacade.java:159-160），注解移到新的带 TierRuntime 的构造器，否则两个必选的 @Autowired 构造器会让 Spring 启动失败；其他构造器委托，前一个委托构造器仍设 `admissionStore = permissive`、`responseOwnerStore = null`（155-156）。`TierRuntime` 是 record，由 `@Bean` 工厂创建。ProxyFacadeRoutingTest（:2739）与 ProxyFacadeAdmissionLifecycleTest（:310）的构造和断言不改。`PoolCell` 枚举不持有注入状态，排除集通过 `predicate(Set<String> excluded)` 传入；AccountPoolService 保留旧构造器重载。
- **备份**：`REQUIRED_FLYWAY_VERSION = "29"`，追加 V29（67-97），五张新表加入 REQUIRED_RESTORE_TABLES（104-117）；更新 DataTransferServiceTest、DataTransferMigrationContractTest、CONFIGURATION.md:178、OPERATIONS.md:572、579 的"V1–V28"，以及 ACCOUNT_EGRESS.md:177 的"V28 恢复要求…"（那里不是 V1–V28 的写法）。V28 备份升级后不能从控制台恢复，发布说明要求升级后立即备份。
- **滚动升级**：旧节点调用 4 参数 settle，不受影响；必须所有节点升级后再启用分级或打开门控（控制台提示）；旧节点不认识的对是 NOT_OFFERED。
- **DTO**：只在记录末尾追加并保留旧参数个数的委托构造器（WireFormatTest 约定）：ApiKeyDtos 与 ApiKeyPrincipal 加 tierPreference；`StatsService.RequestLog`（49-68）加 10 个字段；RequestLogEntity、RequestLogResponse 同步，/logs/me 把管理员字段置 null；ModelListItem 加 sourceTiers（有意更新 WireFormatTest）；PlatformCompletionResult 加三个组件；新增 SourceTierDtos；插件 SDK 与 AccountResponse 不变。
- **测试**：ProxyFacadeRoutingTest（86 处 `*ForPool` 打桩）与 ProxyWireFormatTest 断言不改；新增 select*ForPool 调用序列快照、跨协议契约、分级门面；未分级回归（上下文仍是 BillingQuote.class、4 参数 settle、原 markUsageDispatched、pricing JSON 相同）。PostgreSQL 测试基于 `@Testcontainers(disabledWithoutDocker = true)`（PostgreSqlIntegrationTestSupport.java:14），本地须启动 Colima 并设 DOCKER_HOST，否则静默跳过，以 CI 为准；关键 SQL（结算调用字符串）另有不依赖 Docker 的单元测试。

## 10. 分阶段

每阶段可单独发布；能力位保证配置不会早于运行时生效。

| 阶段 | 内容 | 对外效果 |
|---|---|---|
| 0 | PoolCell；构造器与 UNTIERED 规划器；PlatformCompletionResult 新组件；BillingQuote.scaled 与去媒体统计；UpstreamException.generationStarted 与 Kiro 标记 | 零变化 |
| 1 | V29；备份契约；Registry（早于 Web 服务器加载）；管理 API、校验与能力位；配置页 | 只多配置页 |
| 2 | 计划、selectTiered、D2 计数、派发后规则（tierDefinitive、未开始、级内优先、保留槽、时间守卫）、按级预留与结算、续租、D4（真实降级、会话键）、日志、响应头、状态与页面；最后置 nativeTiers | 原生分级可用 |
| 3 | 后缀、请求头、Key 偏好；错误与低余额提示；`/source-tiers/me`；全端点统一（可选）；客户端片段与会话头核对 | 用户可固定号池 |
| 4 | 中转客户端、累加器、用量归一、错误脱敏、白名单与规范化、特性检测、分类与 XpDispatcher、映射验证、专用排除与 xpMembers 能力位、夹具框架 | 管理员可登记并验证中转 |
| 5 | 跨协议单元与资格、Chat 入口派发、学到的上限与短期排除、输入上界与 XP_IN_FLIGHT、四个对以 EXPERIMENTAL 上线、控制台、端到端；P5-09 升级 | **负责人例子可用**（先 KEYS，升级后 ON） |
| 6 | Responses 对；P6-05 升级 | Codex 走中转 |
| 7 | Gemini 对；P7-04 升级 | Gemini 入口与 google 厂商 |
| 8 | 原始体 Anthropic 转换（图片、is_error、cache_control 透传） | Claude Code 成本与保真对齐（建议必做） |
| 9 | 可选：count_tokens、Gemini 流、Chat usage 块、Responses 透传、原生 Anthropic 透传与 thinking 安全规则、原生错误信封与 SSE 保活、按预算降级、「超时后不跨账号重试」 | 按需 |

## 11. 决策

| 编号 | 决策 | 落实 |
|---|---|---|
| D1 | 跨协议号源必需：OpenAI 格式中转可作为别的厂商的某一级；Anthropic、Chat、Responses、Gemini 格式请求都能派发给它；每账号上游模型名；请求、流式响应、工具、错误的转换；计费用量 | 第 7 节；2.3、2.5、2.6；7.3 的升级任务 |
| D2 | 繁忙（并发、RPM、在途 token、按模型/端点冷却）算不可用并降级；每级上限 16，位于全局 64 内 | 4.2、4.4 |
| D3 | 号池价 = 标准价 × 系数（默认 1.0），fast ×2 | 5.1 |
| D4 | 降级会话停留到空闲（10 分钟），新会话从 1 级开始，按厂商可切换，默认停留 | 4.7 |

**派生选择（已采用，可调整）**：按对门控、不做自动熔断器（指标 + kill switch）；原生同样丢失的特性 SOFT、原生能承载的 HARD，以路由原生单元为基线；可能已生成后绝不重试；所有号源保持 Starry 信封；最后手段回升；映射"精确优先、同厂商 target 回退"；真实降级才绑定（I8）；分级请求 402 确定。

**待负责人确认**：

- **O1** 跨级保留槽（默认开）：派发数最多比今天多 1，换来"两个 1 级都失败仍能到 2 级"。关闭则预算用尽即失败。
- **O2** 全端点统一是额外范围，默认关，可整体删去。
- **O3** I7：中断后运营者承担，即使中转在错误前报告了用量也不向用户收费。
- **O4** 错误保持 Starry 信封，协议原生信封放第 9 阶段。
- **O5** 中转 SSE 先缓冲、客户端 SSE 由网关合成（长生成期间客户端收不到字节，风险 16）。
- **O6** 内容指纹会话键：开头相同的会话共享 D4 级别。
- **O7** 未分级请求上 402 的全局修正作为独立缺陷修复（今天单个 402 就会把用户锁进核对）。
- **O8** 原生 ANTHROPIC_KEY 的 thinking 安全规则（风险 22）留在第 9 阶段，不改分级计划里的原生单元。
- **O9** RESPONSES 入口上 `text.verbosity` 为 `low`/`high` 时，gpt 请求（透传基线，原生 OpenAI、Codex 账号会带上它）对中转成员是 HARD（与 CHAT 一致，请求不会悄悄变短或变长）还是 SOFT（Codex 控制台生成的配置每次都发 `model_verbosity`，默认 `medium` 已不算特性，但用户设成 `low`/`high` 的会跳过所有中转成员）。第 6 阶段 P6-02 之前决定；目前代码与 7.9 都按 SOFT。

## 12. 评审修正汇总

第一轮的修正已在第 2 版合入（包装函数与触发器、last_dispatched、续租、确定未生成才跨级、准入耗尽、上下文不放最大报价、冻结前剪枝、两层模型、三个选级通道、epoch=null、PoolCell、每级 16 次、按对门控、增量分类、显式口径、更正 cache_control 注入）。第二轮采纳的修正分布在：2.3/2.5（缓存双开关、上下文必填、图片上限、CHECK 写宽）、2.7（触发器转换与核对归属）、2.8（INTEGER）、2.9（加载时机、能力位）、3（owner 解析、Kiro 上界、基线）、4.2–4.8（拒绝按请求、tierDefinitive、generationStarted、级内优先与保留槽、时间守卫、收窄后果、I8 绑定、会话键、owner 经 selectTiered）、4.9/4.12、5.1–5.8（priority、holdLevels、min、只估缺项）、6.3/6.4、7.3（统一升级规则）、7.4（探针）、7.5（全捕获、铸造 id、模型冷却键、专用排除前移）、7.6（消息/部件白名单、不删 user 轮、按位置去重、effort）、7.7（possiblyGenerated、空的成功）、7.8（上下文跨级、学到的上限、短期排除、XP_IN_FLIGHT）、7.9（tool_search、context_management、透传基线）、8、9 与任务依赖。驳回与部分驳回见附录 A。

## 13. 风险

1. 全端点统一会让 Chat 上的 Claude 先打官方 Key，成本上升（默认关）。
2. D2 价格波动：首次降级前后计价不同；需要稳定的用户固定号池。
3. 低价级在高价级之后时低余额用户可能被拒（holdLevels 只缓解有绑定的会话）。
4. 同级双重生成：原生同级内 Kiro 超时与发送后 IOException 的重试仍会再生成（今天已有）；可选的「超时后不跨账号重试」在第 9 阶段，代价是转核对。
5. 提示缓存：中转级别没有缓存，Claude Code 成本高于 Kiro（7.15）；Kiro 在首条消息放当前时间（KiroPayloadFactory.java:162），前缀每次都变。
6. 每级 16 次是启发式，可按厂商调 4–64。
7. 多节点：账号状态与配置最多 30 秒不一致；绑定、学到的上限、短期排除、Responses 状态都是每节点的。
8. 配置失误：停用级别让成员退出；未归类账号按最低级计价；缓存口径配成"不含"会多收（因此需要探针证据）；`exclusive=false` 的 Claude 专用中转收到 GLM 流量。
9. 备份断层；V5 分区函数拷贝必须列全 10 列；ALTER 父表短暂持锁。
10. 〔外部〕客户端假设：后缀与请求头写法、会话头、WebSearch 子请求、"prompt is too long" 触发压缩、约 600 秒超时、SDK 自动重试、tool_search；第 5 阶段升级前对照真实客户端验证。
11. 计量精度：Kiro 部分按字节估算（AwsEventStreamParser.java:30、65、300）；中转用量是被信任的；估算被系数放大。
12. 名称与错误泄露号源：public_listing 默认关；原生号源仍把上游消息带进用户日志。
13. 两套"分级"并存，控制台需要说明。
14. PostgreSQL 测试可能静默跳过；夹具固定的是转换器，不是真实中转。
15. 会话换级时身份提示（KiroPayloadFactory.java:161-179）、工具错误信号（Kiro 永远 success，231）、图片处理不同；I8 让主循环只在真实降级时换一次。
16. 长生成对客户端静默（合成 SSE）；原生尝试的断开仍以非确定方式释放（ProxyFacade.java:613；V14:244-247）进入核对，第 9 阶段 SSE 保活改善；时间守卫减少跨级引起的超时。
17. 运营者承担：中断、断开、验证调用的成本，状态页显示 UNBILLED。
18. 繁忙很少触发：兼容 Key 默认不限并发，降级多依赖派发后的 429。
19. 同 id 不同模型：google 厂商 2 级 Kiro 用 Claude 回答 gemini-*（GeminiModelCatalog.java:24-31）仍按 Gemini 价；有些中转把未知 id 静默映射到默认模型〔外部〕。
20. 隐私：中转收到完整提示与工具输出。
21. 维护成本随对增长；共享辅助的改动必须保持 `messagesToChat` 与 OpenAiUpstreamClient 输出逐字节不变。
22. 既有隐患更易触达：2 级里排在 Kiro 前的 Anthropic Key 会转发 `thinking`（AnthropicUpstreamClient.java:135），而历史里没有 thinking 块（工厂不输出，AnthropicResponseFactory.java:88-101；messagesToChat 丢弃，67-83），开启思考的工具续接轮可能 400〔外部〕，属 REQUEST、不在同级换 Kiro。刚从中转降下来的会话会遇到；规则见 P9-05（O8）。
23. 内容指纹碰撞：开头相同的不同会话共享级别。

## 附录 A：第二轮评审中驳回或部分驳回的意见

| 意见 | 处理 | 理由 |
|---|---|---|
| MODEL_ID 正则在 ModelCatalogManagementService.java:38 | 驳回 | 第 39 行才是 `MODEL_ID = Pattern.compile(...)`，原引用正确 |
| stripAnthropicPrefix 在 1230/1249 | 驳回 | 1231、1250 是调用它的两行，方法定义在 1288；原文指调用处，正确 |
| fastMode 在 ProxyFacade.java:325 | 部分采纳 | 结论正确，行号是 330（325 是 sessionHint） |
| 分级亲和丢失 ARCHITECTURE.md:307 承诺的按模型族隔离 | 部分驳回 | 今天只有旧熔断策略打开时 affinityKey 才加模型池键（AccountPoolPolicyService.java:232-238 要求 matchesCurrentEpoch），默认部署本来就不隔离，不是分级造成的回归；仍在分级键里加 modelKey |
| 全局把 402 判为确定 | 部分采纳 | 分级请求用 tierDefinitive；未分级改动违反 I4，另立独立修复（O7） |
| 配置先存为 armed、首个可用版本重新确认 | 驳回 | 校验器在能力位为假时拒绝保存即可；配置只能由管理 API 写入。由有能力的版本保存后恢复到旧版本再升级而生效，是管理员在有能力版本上的决定 |
| D4 增加"仅当不更贵才停留"选项 | 部分驳回 | 只在控制台写明；新选项超出 D4，关闭粘滞即可 |
| 中断后发送 `x-should-retry: false` | 驳回 | 会让客户端直接显示错误；改为会话-成员短期排除，让自动重试去别的成员 |
| 以"计划里存在透传原生单元"为特性基线 | 部分采纳 | 改为以本请求路由的原生单元种类为基线：计划里暂时没有原生账号时，跨协议也不应比原生更差，且结果与账号可用性无关 |
| 透传基线下把 reasoning 项也设为 HARD | 未采纳（意见只列图片、schema、音频文件） | reasoning 项原生 Kiro 同样丢失，网关也无法跨号源携带；设 HARD 会让 Codex 第二轮起永远用不上中转 |
| 重复条目（D4 绑定三条、402 两条） | 合并 | 按同一修正处理 |
| 〔外部〕未验证项（OpenCode/Codex 会话头、tool_choice 与思考冲突、600 秒超时、SDK 重试） | 采纳并标注 | 设计改为在假设不成立时也安全（内容指纹、auto 探针、时间守卫、短期排除），实施前核对 |
