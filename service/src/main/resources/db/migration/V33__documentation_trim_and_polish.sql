-- The default tutorials (the in-console 使用文档) are trimmed. Of the 开始使用 pages, four are deleted (getting-started,
-- console-tour, coding-plan, models-and-pricing) and two are rewritten as short pages that keep only what a reader acts on:
-- billing (top-ups, charges and Coding Plan) and api-keys (keys, models and prices, tiers). The others get plainer Chinese,
-- which adds and removes nothing; their English changes only in links to the pages deleted or retitled here. Categories
-- and sort order stay as they were.
-- Written like V24 and V30: a tutorial is replaced or deleted only while it is still exactly the version V23-V30 left
-- (identical draft and publication, matching content hash). Anything an administrator edited, unpublished or deleted is
-- left alone. The hashes are md5(draft::text) of each page in a database migrated to V32.
WITH tutorials(id, previous_hash, content) AS (VALUES
    ('billing', '8da984c48234523d42cefd649367aa0c', jsonb_build_object(
        'title', '充值与计费', 'category', '开始使用',
        'summary', '余额和 Coding Plan 的区别、充值和兑换码、没到账怎么办、怎么扣费，以及订阅怎么开通和使用。', 'sortOrder', 20,
        'body', $doc$## 两种计费途径

每把密钥只用一种途径，两者**互不串用**：

- **余额计费**：扣钱包余额，按实际用量计费。余额不足时请求被拒绝（`402`）。
- **Coding Plan**：用余额买订阅，在有效期内消耗订阅里的调用额度。额度用完、过期或不适用时请求被拒绝（`402`），**不会**改扣余额。

## 余额和用量在哪看

页面顶部显示当前余额。[数据统计](#/welcome) 页还显示已启用的密钥数量、**累计调用**、**已用 Token / 累计消耗** 和各模型的调用次数，点 **刷新** 获取最新数据。

## 充值

打开 [余额充值](#/payment)：

- **在线充值**：选择 **充值金额** 和 **支付方式**，点 **创建订单并支付**，按提示付款。订单显示 **已入账** 时余额就到了。单笔金额范围和每日限额见页面上的交易规则。
- **兑换码**：在 **兑换码充值** 里输入兑换码，点 **兑换余额**，立即到账。每个兑换码只能用一次；有的兑换码会直接开通 Coding Plan 订阅。

页面提示“当前未开放在线充值”时，用兑换码，或 [提交工单](#/documentation/tickets)。

## 付了款余额没变

**不要重复付款。** 到 **充值历史记录** 看订单状态：只有 **已入账** 才算到账，其他状态稍等再刷新。几分钟后仍未入账，[提交工单](#/documentation/tickets) 并附上订单号和付款时间，**不要**提供支付密码或银行卡信息。

## 怎么扣费

- 请求前先**预留**一部分余额，结束后按实际用量结算，多预留的部分退回；
- 余额不足时，请求在发往模型服务之前就被拒绝（`402`），不产生费用；
- 费用取决于模型、输入和输出的 token 量、是否命中缓存和本站的计费规则。各模型的价格见 [模型 ID](#/models)，实际扣费以请求结束后的结算为准。

## Coding Plan 订阅

1. 在 [Coding Plan](#/coding-plans) 选套餐，点 **开通套餐**，确认 **应付余额** 后点 **确认开通**。开通用的是钱包余额。
2. 订阅不会自动生效：在 [API Key](#/api-keys) 新建一把密钥，**计费途径** 选 **Coding Plan**，**模型分类** 选套餐适用的分类。只有这把密钥发出的请求才消耗订阅额度。
3. **我的订阅** 显示状态、额度使用进度和有效期，**额度明细** 列出每一笔消耗。

规则：

- 每款套餐只适用于指定的模型分类；
- 同一套餐重复购买会排队，当前期到期或额度用尽后才开始新的一期；
- 订阅固定为购买时的套餐版本，套餐以后调整不影响已有的订阅。

## 额度用完了怎么办

Coding Plan 的密钥不会改扣余额。到 [Coding Plan](#/coding-plans) 再开通一期（同一套餐排在当前期之后），或者先 [余额充值](#/payment)，再改用余额计费的密钥。
$doc$,
        'english', jsonb_build_object(
            'title', 'Top-ups and billing', 'category', 'Getting started',
            'summary', 'Balance versus Coding Plan, top-ups and redemption codes, what to do if a payment does not arrive, how charges work, and how to activate and use a plan.',
            'body', $doc$## Two billing sources

Each key uses one source, and the two are **never mixed**:

- **Balance billing**: charged from your wallet balance by actual usage. When the balance runs out, requests are rejected (`402`).
- **Coding Plan**: buy a subscription with your balance, then consume its request credits during its validity. When the credits run out, expire, or do not apply, requests are rejected (`402`); your balance is **not** charged instead.

## Where to see balance and usage

The top bar shows your current balance. The [Statistics](#/welcome) page also shows how many keys are enabled, **Total requests**, **Tokens / total credits used**, and how many times each model was called. Select **Refresh** to load the latest figures.

## Top up

Open [Top up](#/payment):

- **Pay online**: choose a **top-up amount** and a **payment method**, select **Create order & pay**, and pay as prompted. When the order shows **Credited**, the balance has arrived. The transaction limits on the page state the per-order range and the daily limit.
- **Redemption code**: enter the code in **Redeem a code** and select **Redeem credits**; the credits arrive immediately. Each code can be used once, and some codes activate a Coding Plan subscription directly.

If the page says "Online top-ups are unavailable", use a redemption code, or [submit a ticket](#/documentation/tickets).

## Paid, but the balance did not change

**Do not pay again.** Check the order's status in **Top-up history**: only **Credited** means it has arrived; for other statuses, wait a moment and refresh. If it still has not arrived after a few minutes, [submit a ticket](#/documentation/tickets) with the order number and payment time. **Never** include payment passwords or card details.

## How charges work

- Before each request, part of your balance is **reserved**. When the request finishes, the actual usage is settled and anything over-reserved is returned;
- If your balance is too low, the request is rejected (`402`) before it reaches the model service, so nothing is charged;
- The cost depends on the model, input and output tokens, cache hits, and this site's billing rules. See [Model IDs](#/models) for each model's price; the actual charge is the settlement after each request.

## Coding Plan subscriptions

1. On [Coding Plan](#/coding-plans), pick a plan, select **Activate plan**, confirm the **Balance to pay**, and select **Confirm activation**. Activation is paid from your wallet balance.
2. A plan does not take effect automatically: in [API Key](#/api-keys), create a new key, set **Billing source** to **Coding Plan**, and choose a **Model category** the plan covers. Only requests made with that key consume plan credits.
3. **My subscriptions** shows the status, credit usage, and validity; **Quota history** lists each deduction.

Rules:

- Each plan covers specific model categories;
- Buying the same plan again queues: the new term starts only after the current one expires or its credits run out;
- A subscription is pinned to the plan version you bought; later changes to the plan do not affect it.

## What if the credits run out?

A Coding Plan key never charges your balance instead. Activate another term on [Coding Plan](#/coding-plans) (the same plan queues after the current term), or top up on [Top up](#/payment) and switch to a balance-billing key.
$doc$))),
    ('api-keys', '418e60c1fec5e30db688c244d2640966', jsonb_build_object(
        'title', 'API Key 与模型', 'category', '开始使用',
        'summary', '创建密钥、模型分类、列表里的操作和保管习惯，在哪查模型 ID 和价格，以及号池和 `模型@标识` 的写法。', 'sortOrder', 30,
        'body', $doc$## 创建密钥

API Key 以 `sk-` 开头，谁拿到它谁就能花你的额度，请像密码一样保管。

1. 打开 [API Key](#/api-keys)，点 **新建 API Key**。
2. 填写 **名称**，选 **计费途径**（**余额计费** 或 **Coding Plan**，区别见 [充值与计费](#/documentation/billing)）和 **模型分类**（必选）。可选的有 **号源偏好**（见下文“号池”）和 **累计额度上限**（留空表示不限制，发给脚本或同事时可以防止用超）。
3. 点 **创建密钥**，在 **保存你的新密钥** 里复制完整密钥、Base URL 和客户端配置，再点 **已妥善保存**。

之后随时可以在列表里点 **复制密钥** 找回完整密钥。很早以前创建的密钥只保存了前缀，找不回完整值，请重新创建。

## 一把密钥一个模型分类

密钥只能调用所选分类下的模型，比如用 OpenAI 分类的密钥请求 `claude-*` 会返回 `403`（`API_KEY_MODEL_PROVIDER_FORBIDDEN`）。要用几家就建几把：Claude Code 通常配 **Anthropic** 分类，Codex CLI 通常配 **OpenAI** 分类，OpenCode 看你要用的模型。

## 列表里的操作

| 操作 | 作用 |
| --- | --- |
| 复制 API 地址 | 复制 Base URL（以 `/v1` 结尾），填到 OpenAI 兼容的客户端 |
| 复制密钥 | 复制完整密钥 |
| 客户端配置 | 连接信息和各客户端的现成配置，包含 **CC Switch 一键连接** |
| 编辑 | 修改这把密钥的设置 |
| 更多操作 → 停用 / 启用 | 暂停或恢复密钥，不会删除 |
| 更多操作 → 删除 | 永久删除，使用该密钥的客户端立刻失效 |

每台设备、每个应用用一把密钥；不用的停用或删除；不要把密钥提交到 Git、发到群聊，或贴进截图和工单。怀疑泄露就马上停用，再新建一把替换，并更新所有用过旧密钥的客户端。

## 模型和价格

[模型 ID](#/models) 页列出本站所有可用的模型，可以按 **模型厂商**、**模型系列** 筛选或搜索。每个模型显示 ID、**上下文窗口** 和 **价格**（每百万 token，输入和输出分别计价）。点 **复制模型 ID** 填到客户端的“模型”一栏：请求里的 `model` 必须和它一致，模型的厂商也要和密钥的模型分类一致。

页面上的价格用于估算，实际扣费以请求结束后的结算为准。标记为 **待定价** 的模型暂时没有价格，调用会被拒绝（`503`，`MODEL_PRICE_UNAVAILABLE`），不会扣费，请换一个模型。

## 号池

站点可以把一个厂商的账号分成几个**号池**（1 级最好），每个号池有名称和价格系数。站点没有为你的模型分类公开号池时，表单和模型卡片上都不会出现号池，这一节可以跳过。

- **价格 = 模型的标准价 × 实际服务这个请求的号池的系数**，模型卡片上列出每个号池的系数和参考价；
- 默认自动：先由 1 级服务，本次请求里 1 级的账号全部暂时不可用时才由下一级服务。会话因此改由较低的号池服务后，会留在那个号池，直到空闲约 10 分钟（站点可以关闭这个行为），这期间按那个号池的价格计费；
- 密钥的 **号源偏好** 可选“自动”、某个号池，或“某个号池 及以下”，含义和下表的后缀写法相同。它是默认选择，用不上时会被忽略，响应头 `X-Starry-Tier-Status` 为 `ignored`。在编辑里换了模型分类，偏好会回到“自动”；不限模型分类的旧密钥不能设置偏好（`400`，`API_KEY_TIER_PREFERENCE_INVALID`）；
- 想让某个请求只用某个号池，就在模型名后面加后缀，例如 `claude-sonnet-4.5@economy`。后缀优先于请求头 `X-Starry-Tier`，请求头优先于号源偏好：

| 写法 | 含义 |
| --- | --- |
| `模型@economy` | 只由标识为 `economy` 的号池服务，只按它的价格计费 |
| `模型@economy+` | 由它和它以下的号池服务，按实际服务的号池计费 |
| `模型@L2` 或 `模型@2` | 按级别：第 2 级的号池 |
| `模型@auto` | 回到自动，并盖过请求头和密钥里的号源偏好 |

- 后缀用不上就拒绝，不会扣费：号池不存在（`400`，`SOURCE_TIER_UNKNOWN`），这个模型或入口用不上它（`400`，`SOURCE_TIER_NOT_OFFERED`），站点不允许选择它（`403`，`SOURCE_TIER_NOT_SELECTABLE`）；
- 只用一个号池时，如果它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池；想允许降级就加 `+`；
- 响应头 `X-Starry-Tier` 告诉你这次由哪个号池服务。
$doc$,
        'english', jsonb_build_object(
            'title', 'API keys and models', 'category', 'Getting started',
            'summary', 'Creating keys, model categories, list actions and safe habits, where to find model IDs and prices, and tiers with the `model@tier` form.',
            'body', $doc$## Create a key

An API key starts with `sk-`. Whoever holds it can spend your credits, so treat it like a password.

1. Open [API Key](#/api-keys) and select **Create API key**.
2. Enter a **Name**, and choose a **Billing source** (**Balance billing** or **Coding Plan**; see [Top-ups and billing](#/documentation/billing) for the difference) and a **Model category** (required). Optional: a **Tier preference** (see "Tiers" below) and a **Total spending limit** (leave blank for no limit; useful for scripts or colleagues, to prevent overspending).
3. Select **Create key**. In **Save your new key**, copy the full key, the Base URL, and the client configurations, then select **Saved securely**.

You can retrieve the full key at any time with **Copy key** in the list. Keys created long ago stored only a prefix and their full value cannot be recovered; create a new key instead.

## One model category per key

A key can call only models in its category. For example, a key in the OpenAI category that requests `claude-*` gets `403` (`API_KEY_MODEL_PROVIDER_FORBIDDEN`). Create one key per provider you use: Claude Code usually needs the **Anthropic** category, Codex CLI the **OpenAI** category, and OpenCode depends on the models you want.

## Actions in the list

| Action | What it does |
| --- | --- |
| Copy API URL | Copies the Base URL (ending in `/v1`) for OpenAI-compatible clients |
| Copy key | Copies the full key |
| Client configuration | Connection details and ready-made client configurations, including **Connect with CC Switch** |
| Edit | Changes the key's settings |
| More actions → Disable / Enable | Pauses or resumes a key without deleting it |
| More actions → Delete | Deletes it permanently; clients using it stop working immediately |

Use one key per device or app; disable or delete keys you no longer use; never commit keys to Git, post them in chats, or include them in screenshots or tickets. If you suspect a leak, disable the key immediately, create a replacement, and update every client that used the old one.

## Models and prices

The [Model IDs](#/models) page lists every model available on this site; filter by **Model provider** and **Model series**, or search. Each model shows its ID, **Context window**, and **price** (per million tokens, input and output priced separately). Select **Copy model ID** and use it for the "model" field of your client: the `model` in your request must match it, and the model's provider must match your key's model category.

Prices on the page are for estimating; the actual charge is the settlement after each request. An **Unpriced** model has no price yet, so calls are rejected (`503`, `MODEL_PRICE_UNAVAILABLE`) and nothing is charged. Choose another model.

## Tiers

A site can split one provider's accounts into several **tiers** (tier 1 is the best), each with a name and a price factor. When the site shows no tiers for your model category, neither the form nor the model cards show any, and you can skip this section.

- **Price = the model's standard price × the factor of the tier that served the request.** Model cards list each tier's factor and reference price;
- Automatic by default: tier 1 serves a request first, and the next tier serves it only when every account in tier 1 is temporarily unavailable for it. Once your session is served by a lower tier for that reason, it stays there until it has been idle for about 10 minutes (the site can turn this off), and is billed at that tier's price meanwhile;
- A key's **Tier preference** can be "Automatic", a tier, or "a tier and below", with the same meanings as the suffixes in the table. It is a default: when it cannot be used it is ignored, and the `X-Starry-Tier-Status` response header is `ignored`. Changing the model category while editing resets it to "Automatic"; older keys that are not tied to a model category cannot have one (`400`, `API_KEY_TIER_PREFERENCE_INVALID`);
- To make one request use one tier, add a suffix to the model name, for example `claude-sonnet-4.5@economy`. The suffix beats the `X-Starry-Tier` request header, and the header beats the tier preference:

| Form | Meaning |
| --- | --- |
| `model@economy` | Served only by the tier whose identifier is `economy`, and billed at its price |
| `model@economy+` | Served by that tier or any tier below it, billed at the tier that served the request |
| `model@L2` or `model@2` | By level: the tier at level 2 |
| `model@auto` | Back to automatic, overriding the request header and the key's tier preference |

- A suffix that cannot be followed is rejected, with nothing charged: the tier does not exist (`400`, `SOURCE_TIER_UNKNOWN`), this model or endpoint cannot use it (`400`, `SOURCE_TIER_NOT_OFFERED`), or the site does not let clients choose it (`403`, `SOURCE_TIER_NOT_SELECTABLE`);
- With a single tier, if all its accounts are temporarily unavailable the request fails (`503`, `SOURCE_TIER_UNAVAILABLE`) and is not moved to another tier; add `+` if you want it to fall back;
- The `X-Starry-Tier` response header tells you which tier served the request.
$doc$))),
    ('cc-switch', '2b9dfb09b461646de89b16a39624deb8', jsonb_build_object(
        'title', 'CC Switch 一键接入', 'category', '连接客户端',
        'summary', '推荐方式：在本站一键把 Claude Code、Codex CLI 或 OpenCode 的配置导入 CC Switch。', 'sortOrder', 40,
        'body', $doc$## CC Switch 是什么

[CC Switch](https://ccswitch.io) 是一个管理 AI 编程客户端配置的桌面工具，支持 Windows、macOS 和 Linux。本站能生成一个导入链接，点一下就把供应商配置写进 CC Switch，省去手改配置文件。

> CC Switch 只负责**管理配置**。Claude Code、Codex CLI、OpenCode 本身仍需要你另外安装。

## 准备

1. 从 [CC Switch 官方网站](https://ccswitch.io) 下载并安装对应你系统的版本，也可以使用 [官方 GitHub 下载页](https://github.com/farion1231/cc-switch/releases/latest)。安装后**启动一次**。
2. 安装好你要用的客户端：Claude Code、Codex CLI 或 OpenCode。
3. 在 [API Key](#/api-keys) 里准备好一把密钥，且它的 **模型分类** 要和你要用的客户端匹配（见 [API Key 与模型](#/documentation/api-keys)）。

## 一键导入

1. 打开 [API Key](#/api-keys)，找到要使用的密钥。
2. 点该密钥的 **客户端配置**。
3. 在 **CC Switch 一键连接** 里，选择和密钥的模型分类匹配的客户端。
4. 点 **打开**，在浏览器的提示里允许启动 CC Switch。
5. CC Switch 会弹出导入确认。核对站点地址和模型，确认导入，并在 CC Switch 里**启用**这份配置。
6. **重新打开**本地客户端，发送一句话测试。

## 浏览器没有打开 CC Switch

- 先确认 CC Switch 已经安装并启动过一次，再回来重新点 **打开**；
- 浏览器如果拦截了，请在提示里选择“始终允许”或“打开”；
- 也可以点 **复制链接**，再粘贴到 CC Switch 的链接导入入口。若你的版本没有这个入口，请升级，或改用 [手动配置](#/documentation/manual-api)。

> 导入链接里**包含完整密钥**，只在自己的设备上使用，不要分享给别人。

## 换了密钥或模型之后

重新导入一次即可。旧的配置不会自动更新。

## 仍然连不上

- 你的电脑能否正常打开本站？导入的站点地址应该是你平时访问本站用的域名；
- 密钥是否有效、没有被停用？计费途径是否有可用额度？
- 客户端的模型是否属于该密钥的模型分类？

对照 [错误码速查](#/documentation/errors) 逐项排查，或按客户端查看 [Claude Code](#/documentation/claude-code)、[Codex CLI](#/documentation/codex-cli)、[OpenCode](#/documentation/opencode)。$doc$,
        'english', jsonb_build_object(
            'title', 'CC Switch setup', 'category', 'Connect a client',
            'summary', 'Recommended: import a Claude Code, Codex CLI, or OpenCode configuration into CC Switch with one click from this site.',
            'body', $doc$## What CC Switch is

[CC Switch](https://ccswitch.io) is a desktop tool for managing AI coding client configurations on Windows, macOS, and Linux. This site generates an import link that writes the provider configuration into CC Switch with one click, so you do not edit configuration files by hand.

> CC Switch only **manages configuration**. You still need to install Claude Code, Codex CLI, or OpenCode separately.

## Before you start

1. Download and install the version for your system from the [CC Switch website](https://ccswitch.io), or from the [official GitHub releases](https://github.com/farion1231/cc-switch/releases/latest). **Start it once** after installing.
2. Install the client you want: Claude Code, Codex CLI, or OpenCode.
3. Prepare an API key in [API Key](#/api-keys) whose **Model category** matches that client (see [API keys and models](#/documentation/api-keys)).

## Import with one click

1. Open [API Key](#/api-keys) and find the key you want.
2. Select **Client configuration** for that key.
3. Under **Connect with CC Switch**, choose the client that matches the key's model category.
4. Select **Open** and allow your browser to launch CC Switch.
5. CC Switch shows an import confirmation. Check the site address and model, confirm, and **activate** the configuration in CC Switch.
6. **Restart** your local client and send a short message to test.

## The browser did not open CC Switch

- Make sure CC Switch has been installed and started once, then select **Open** again;
- If the browser blocked it, choose "Always allow" or "Open" in its prompt;
- You can also select **Copy link** and paste it into CC Switch's link-import option. If your version lacks one, upgrade or use [manual setup](#/documentation/manual-api).

> The import link **contains your full key**. Use it only on your own device and never share it.

## After changing a key or model

Import again. Existing configurations are not updated automatically.

## Still cannot connect

- Can your computer open this site normally? The imported site address should be the domain you normally use to reach it;
- Is the key valid and not disabled? Does its billing source have available credits?
- Does the client's model belong to the key's model category?

Work through the [Error reference](#/documentation/errors), or see the client guides for [Claude Code](#/documentation/claude-code), [Codex CLI](#/documentation/codex-cli), and [OpenCode](#/documentation/opencode).$doc$))),
    ('claude-code', 'c03b05b969cad8f879e462b06a5da22f', jsonb_build_object(
        'title', '手动配置 Claude Code', 'category', '连接客户端',
        'summary', '用环境变量把 Claude Code 连到本站：怎么复制现成配置、每个变量的含义、怎么指定号池，以及常见错误。', 'sortOrder', 45,
        'body', $doc$## 开始之前

- 已安装 Claude Code；
- 有一把 **Anthropic** 模型分类的 API Key（见 [API Key 与模型](#/documentation/api-keys)）；
- 想省事的话，用 [CC Switch 一键接入](#/documentation/cc-switch)。本页讲手动配置。

## 最省事：复制生成的配置

1. 在 [API Key](#/api-keys) 里点密钥的 **客户端配置**。
2. **客户端** 选 **Claude Code**，**平台** 选你的系统（macOS / Linux、Windows CMD 或 PowerShell）。
3. 点 **复制当前配置**，粘贴到终端运行。里面已经带上了你的密钥。

## 手动设置环境变量

Claude Code 通过环境变量连接本站。**注意 Base URL 不带 `/v1`。**

| 变量 | 值 |
| --- | --- |
| `ANTHROPIC_BASE_URL` | `{{site_url}}` |
| `ANTHROPIC_API_KEY` 和 `ANTHROPIC_AUTH_TOKEN` | 你的完整 API Key |
| `ANTHROPIC_MODEL` | 主模型，例如 `claude-opus-4.8` |
| `ANTHROPIC_SMALL_FAST_MODEL` | 用于轻量任务的小模型，例如 `claude-haiku-4.5` |
| `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` | `1`，关闭非必要的网络请求 |
| `CLAUDE_CODE_ATTRIBUTION_HEADER` | `0` |

> 模型 ID 请以 [模型 ID](#/models) 页为准，上面只是示例。

**macOS / Linux**（bash、zsh）：

```bash
export ANTHROPIC_BASE_URL='{{site_url}}'
export ANTHROPIC_API_KEY='YOUR_API_KEY'
export ANTHROPIC_AUTH_TOKEN='YOUR_API_KEY'
export ANTHROPIC_MODEL='claude-opus-4.8'
export ANTHROPIC_SMALL_FAST_MODEL='claude-haiku-4.5'
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1'
export CLAUDE_CODE_ATTRIBUTION_HEADER='0'
```

**Windows PowerShell**：

```powershell
$env:ANTHROPIC_BASE_URL = '{{site_url}}'
$env:ANTHROPIC_API_KEY = 'YOUR_API_KEY'
$env:ANTHROPIC_AUTH_TOKEN = 'YOUR_API_KEY'
$env:ANTHROPIC_MODEL = 'claude-opus-4.8'
$env:ANTHROPIC_SMALL_FAST_MODEL = 'claude-haiku-4.5'
$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = '1'
$env:CLAUDE_CODE_ATTRIBUTION_HEADER = '0'
```

**Windows CMD**：

```text
set "ANTHROPIC_BASE_URL={{site_url}}"
set "ANTHROPIC_API_KEY=YOUR_API_KEY"
set "ANTHROPIC_AUTH_TOKEN=YOUR_API_KEY"
set "ANTHROPIC_MODEL=claude-opus-4.8"
set "ANTHROPIC_SMALL_FAST_MODEL=claude-haiku-4.5"
set "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"
set "CLAUDE_CODE_ATTRIBUTION_HEADER=0"
```

> 这样设置的环境变量**只在当前终端窗口有效**。想长期生效：macOS / Linux 把 `export` 行加到 `~/.zshrc` 或 `~/.bashrc`；Windows 在系统的环境变量设置里添加。

## 验证

在**同一个终端**里运行 `claude`，问一句简单的话。能得到回复，就说明连通了。回到 [数据统计](#/welcome)，**累计调用** 会增加。

## 指定号池（可选）

站点设置了号池并公开了号池名称时，在 [API Key](#/api-keys) 的 **客户端配置** 里，**客户端** 选 **Claude Code** 后会多一项 **号池**：选一个号池（或“…及以下”），复制出来的配置就带上它。没有这一项，说明站点没有为这把密钥的模型分类公开号池，可以跳过这一节。

手动写有两种方式：

| 方式 | 写法 | 特点 |
| --- | --- | --- |
| 请求头 | 再加一个环境变量：`ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: economy'` | 是默认选择：所选号池这个请求用不上时，不会报错，照常按自动服务 |
| 模型后缀 | `ANTHROPIC_MODEL='claude-opus-4.8@economy'`，或启动时 `claude --model claude-opus-4.8@premium` | 是对每个请求的要求：号池用不了就被拒绝（`400` 或 `403`） |

```bash
export ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: economy'
```

PowerShell 写 `$env:ANTHROPIC_CUSTOM_HEADERS = 'X-Starry-Tier: economy'`，CMD 写 `set "ANTHROPIC_CUSTOM_HEADERS=X-Starry-Tier: economy"`。

- `economy`、`premium` 只是例子，换成你要用的号池标识；在 [模型 ID](#/models) 页的号池旁可以复制。标识后面加 `+`（如 `economy+`）表示它和它以下的号池都可以；
- 只选一个号池时，如果它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费；
- 价格 = 模型的标准价 × 实际服务这个请求的号池的系数，各模型的标准价见 [模型 ID](#/models)；
- 用 [CC Switch 一键接入](#/documentation/cc-switch) 时，号池写在模型名后面（后缀方式）；
- 不写号池，请求就按密钥里的 **号源偏好**，没有设置就自动，见 [API Key 与模型](#/documentation/api-keys)。

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| `401` | 密钥复制不完整、带了多余空格，或密钥被停用。重新复制完整密钥 |
| `403` | 密钥的模型分类不是 Anthropic，或模型 ID 不属于该分类。换成 Anthropic 分类的密钥。信息里是 `SOURCE_TIER_NOT_SELECTABLE` 时，说明模型名后面的号池不允许选择，去掉 `@标识` |
| `400`，信息里有 `SOURCE_TIER_…` | 模型名后面的 `@标识` 不对，或这个模型用不上它。去掉它，或到 [模型 ID](#/models) 页复制正确的写法 |
| `503`，`SOURCE_TIER_UNAVAILABLE` | 你固定的号池暂时没有可用账号。按 `Retry-After` 稍后重试，或在标识后面加 `+` 允许降级 |
| 路径不对、`404` | `ANTHROPIC_BASE_URL` 多写了 `/v1`，请去掉 |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |
| 想换模型 | 把 `ANTHROPIC_MODEL` 改成 [模型 ID](#/models) 页里的其他 Claude 模型 ID |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up Claude Code', 'category', 'Connect a client',
            'summary', 'Connect Claude Code to this site with environment variables: copy a ready-made configuration, what each variable means, how to choose a tier, and common errors.',
            'body', $doc$## Before you start

- Claude Code is installed;
- You have an API key in the **Anthropic** model category (see [API keys and models](#/documentation/api-keys));
- For the easiest route, use [CC Switch setup](#/documentation/cc-switch). This page covers manual setup.

## Easiest: copy the generated configuration

1. In [API Key](#/api-keys), select **Client configuration** for your key.
2. Set **Client** to **Claude Code** and **Platform** to your system (macOS / Linux, Windows CMD, or PowerShell).
3. Select **Copy configuration** and paste it into your terminal. Your key is already included.

## Set environment variables manually

Claude Code connects through environment variables. **The Base URL has no `/v1`.**

| Variable | Value |
| --- | --- |
| `ANTHROPIC_BASE_URL` | `{{site_url}}` |
| `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` | Your full API key |
| `ANTHROPIC_MODEL` | The main model, for example `claude-opus-4.8` |
| `ANTHROPIC_SMALL_FAST_MODEL` | A small model for lightweight tasks, for example `claude-haiku-4.5` |
| `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` | `1`, disables non-essential network traffic |
| `CLAUDE_CODE_ATTRIBUTION_HEADER` | `0` |

> Use the IDs on the [Model IDs](#/models) page; the ones above are examples.

**macOS / Linux** (bash, zsh):

```bash
export ANTHROPIC_BASE_URL='{{site_url}}'
export ANTHROPIC_API_KEY='YOUR_API_KEY'
export ANTHROPIC_AUTH_TOKEN='YOUR_API_KEY'
export ANTHROPIC_MODEL='claude-opus-4.8'
export ANTHROPIC_SMALL_FAST_MODEL='claude-haiku-4.5'
export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1'
export CLAUDE_CODE_ATTRIBUTION_HEADER='0'
```

**Windows PowerShell**:

```powershell
$env:ANTHROPIC_BASE_URL = '{{site_url}}'
$env:ANTHROPIC_API_KEY = 'YOUR_API_KEY'
$env:ANTHROPIC_AUTH_TOKEN = 'YOUR_API_KEY'
$env:ANTHROPIC_MODEL = 'claude-opus-4.8'
$env:ANTHROPIC_SMALL_FAST_MODEL = 'claude-haiku-4.5'
$env:CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC = '1'
$env:CLAUDE_CODE_ATTRIBUTION_HEADER = '0'
```

**Windows CMD**:

```text
set "ANTHROPIC_BASE_URL={{site_url}}"
set "ANTHROPIC_API_KEY=YOUR_API_KEY"
set "ANTHROPIC_AUTH_TOKEN=YOUR_API_KEY"
set "ANTHROPIC_MODEL=claude-opus-4.8"
set "ANTHROPIC_SMALL_FAST_MODEL=claude-haiku-4.5"
set "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"
set "CLAUDE_CODE_ATTRIBUTION_HEADER=0"
```

> Variables set this way **last only for the current terminal window**. To make them permanent, add the `export` lines to `~/.zshrc` or `~/.bashrc` on macOS / Linux, or add them in your system's environment-variable settings on Windows.

## Verify

In the **same terminal**, run `claude` and ask a simple question. A reply means it works; **Total requests** on [Statistics](#/welcome) will increase.

## Choose a tier (optional)

When the site has set up tiers and shows their names, **Client configuration** in [API Key](#/api-keys) has an extra **Tier** field after you choose **Claude Code**: pick a tier (or "… and below") and the copied configuration includes it. Without that field, the site shows no tiers for this key's model category, and you can skip this section.

There are two ways to write it by hand:

| Way | What to write | Behavior |
| --- | --- | --- |
| Request header | Add one more variable: `ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: economy'` | A default: when the chosen tier cannot be used for a request, there is no error and the request is served automatically |
| Model suffix | `ANTHROPIC_MODEL='claude-opus-4.8@economy'`, or start with `claude --model claude-opus-4.8@premium` | A demand on every request: if the tier cannot be used, the request is rejected (`400` or `403`) |

```bash
export ANTHROPIC_CUSTOM_HEADERS='X-Starry-Tier: economy'
```

In PowerShell write `$env:ANTHROPIC_CUSTOM_HEADERS = 'X-Starry-Tier: economy'`, and in CMD `set "ANTHROPIC_CUSTOM_HEADERS=X-Starry-Tier: economy"`.

- `economy` and `premium` are only examples; use the identifier of the tier you want, which you can copy next to the tier on the [Model IDs](#/models) page. Add `+` after the identifier (for example `economy+`) to allow that tier and the tiers below it;
- With a single tier, if all its accounts are temporarily unavailable the request fails (`503`, `SOURCE_TIER_UNAVAILABLE`); it is not moved to another tier and nothing is charged;
- Price = the model's standard price × the factor of the tier that served the request; see [Model IDs](#/models) for each model's standard price;
- With [CC Switch setup](#/documentation/cc-switch) the tier is written after the model name (the suffix way);
- Without a tier, requests follow the key's **Tier preference**, or are automatic when none is set; see [API keys and models](#/documentation/api-keys).

## Common problems

| Symptom | Cause and fix |
| --- | --- |
| `401` | The key was copied incompletely, has extra spaces, or is disabled. Copy the full key again |
| `403` | The key's model category is not Anthropic, or the model ID is outside that category. Use a key in the Anthropic category. If the message says `SOURCE_TIER_NOT_SELECTABLE`, the tier written after the model name cannot be chosen; remove the `@tier` |
| `400` with `SOURCE_TIER_…` in the message | The `@tier` after the model name is wrong, or this model cannot use it. Remove it, or copy the correct form from [Model IDs](#/models) |
| `503`, `SOURCE_TIER_UNAVAILABLE` | The tier you pinned has no available account for now. Retry after `Retry-After`, or add `+` after the identifier to allow falling back |
| Wrong path, `404` | `ANTHROPIC_BASE_URL` includes `/v1`; remove it |
| `402` | Not enough balance or plan credits; see [Top-ups and billing](#/documentation/billing) |
| Want another model | Change `ANTHROPIC_MODEL` to another Claude model ID from [Model IDs](#/models) |

See the [Error reference](#/documentation/errors) for more.$doc$))),
    ('codex-cli', '96667d73abaf19e69f6922a424a78de9', jsonb_build_object(
        'title', '手动配置 Codex CLI', 'category', '连接客户端',
        'summary', '编辑 config.toml 和 auth.json 把 Codex CLI 连到本站，怎么指定号池，以及怎么处理 refresh token 报错。', 'sortOrder', 50,
        'body', $doc$## 开始之前

- 已安装 Codex CLI；
- 有一把 **OpenAI** 模型分类的 API Key（见 [API Key 与模型](#/documentation/api-keys)）；
- 想省事的话，用 [CC Switch 一键接入](#/documentation/cc-switch)。本页讲手动配置。

## 最省事：复制生成的配置

在 [API Key](#/api-keys) 里点密钥的 **客户端配置**，**客户端** 选 **Codex CLI**，选好平台，点 **复制当前配置**。内容里已经带上了你的密钥，对应下面两个文件。

## 手动配置

Codex CLI 需要两个文件，都放在**用户目录**下的 `.codex` 文件夹里：

| 系统 | 文件夹 |
| --- | --- |
| macOS / Linux | `~/.codex/` |
| Windows CMD | `%USERPROFILE%\.codex\` |
| Windows PowerShell | `$HOME\.codex\` |

> ⚠️ 必须放在**用户级**配置里，不要放进项目里的 `.codex/config.toml`。

### 1. config.toml

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
base_url = "{{api_base_url}}"
wire_api = "responses"
experimental_bearer_token = "YOUR_API_KEY"
supports_websockets = false
```

要点：

- `base_url` 要带 `/v1`（上面的 `{{api_base_url}}` 已经带了）；
- `wire_api = "responses"`：使用 Responses 接口；
- `model` 换成 [模型 ID](#/models) 页里你想用的 OpenAI 模型。

### 2. auth.json

```json
{
  "OPENAI_API_KEY": "YOUR_API_KEY"
}
```

这会**覆盖**之前登录 ChatGPT 留下的授权。如果想保留原来的文件，请先备份。

## 验证

运行 `codex`，问一句简单的话。能得到回复，说明连通了。

## 指定号池（可选）

站点设置了号池并公开了号池名称时，在 [API Key](#/api-keys) 的 **客户端配置** 里，**客户端** 选 **Codex CLI** 后会多一项 **号池**，选好后复制出来的 `config.toml` 就带上它。没有这一项，说明站点没有为这把密钥的模型分类公开号池，可以跳过这一节。

手动写有两种方式：

| 方式 | 写法 | 特点 |
| --- | --- | --- |
| 请求头 | 在 `[model_providers.starry]` 下加一行 `http_headers = { "X-Starry-Tier" = "economy" }` | 是默认选择：所选号池这个请求用不上时，不会报错，照常按自动服务 |
| 模型后缀 | `model = "gpt-5.5@economy"` | 是对每个请求的要求：号池用不了就被拒绝（`400` 或 `403`）。Codex 会在本地提示找不到这个模型的信息，不影响请求 |

```toml
[model_providers.starry]
name = "Starry API"
base_url = "{{api_base_url}}"
wire_api = "responses"
experimental_bearer_token = "YOUR_API_KEY"
supports_websockets = false
http_headers = { "X-Starry-Tier" = "economy" }
```

- `economy` 只是例子，换成你要用的号池标识；在 [模型 ID](#/models) 页的号池旁可以复制。标识后面加 `+`（如 `economy+`）表示它和它以下的号池都可以；
- 只选一个号池时，如果它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费；
- 续写上一轮的响应时，如果它所在的号池不是你用后缀固定的那个，会返回 `409`（`RESPONSE_OWNER_TIER_MISMATCH`）；请求头和密钥的号源偏好则会被忽略，不报错；
- 价格见 [模型 ID](#/models)；不写号池时，请求按密钥里的 **号源偏好**，没有设置就自动。

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| `refresh token was already used` | 本机 `auth.json` 里还留着旧的 ChatGPT 授权。备份后换成上面的最简内容，或先运行 `codex logout` |
| `401` | 密钥复制不完整，或被停用 |
| `403` | 密钥的模型分类不是 OpenAI，或模型不属于该分类。信息里是 `SOURCE_TIER_NOT_SELECTABLE` 时，说明 `model` 后面的号池不允许选择，去掉 `@标识` |
| `400`，信息里有 `SOURCE_TIER_…` | `model` 后面的 `@标识` 不对，或这个模型用不上它。去掉它，或到 [模型 ID](#/models) 页复制正确的写法 |
| `503`，`SOURCE_TIER_UNAVAILABLE` | 你固定的号池暂时没有可用账号。按 `Retry-After` 稍后重试，或在标识后面加 `+` 允许降级 |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |
| 改了配置没生效 | 重新打开终端和 Codex CLI；确认修改的是用户目录下的文件 |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up Codex CLI', 'category', 'Connect a client',
            'summary', 'Edit config.toml and auth.json to connect Codex CLI to this site, how to choose a tier, and how to fix refresh-token errors.',
            'body', $doc$## Before you start

- Codex CLI is installed;
- You have an API key in the **OpenAI** model category (see [API keys and models](#/documentation/api-keys));
- For the easiest route, use [CC Switch setup](#/documentation/cc-switch). This page covers manual setup.

## Easiest: copy the generated configuration

In [API Key](#/api-keys), select **Client configuration** for your key, set **Client** to **Codex CLI**, choose your platform, and select **Copy configuration**. Your key is already included, and the content maps to the two files below.

## Manual setup

Codex CLI needs two files, both in the `.codex` folder in your **user directory**:

| System | Folder |
| --- | --- |
| macOS / Linux | `~/.codex/` |
| Windows CMD | `%USERPROFILE%\.codex\` |
| Windows PowerShell | `$HOME\.codex\` |

> ⚠️ Use the **user-level** configuration. Do not put it in a project's `.codex/config.toml`.

### 1. config.toml

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
base_url = "{{api_base_url}}"
wire_api = "responses"
experimental_bearer_token = "YOUR_API_KEY"
supports_websockets = false
```

Notes:

- `base_url` must include `/v1` (`{{api_base_url}}` above already does);
- `wire_api = "responses"` uses the Responses API;
- Replace `model` with the OpenAI model you want from [Model IDs](#/models).

### 2. auth.json

```json
{
  "OPENAI_API_KEY": "YOUR_API_KEY"
}
```

This **overwrites** any earlier ChatGPT sign-in. Back up the existing file first if you want to keep it.

## Verify

Run `codex` and ask a simple question. A reply means it works.

## Choose a tier (optional)

When the site has set up tiers and shows their names, **Client configuration** in [API Key](#/api-keys) has an extra **Tier** field after you choose **Codex CLI**, and the copied `config.toml` includes your choice. Without that field, the site shows no tiers for this key's model category, and you can skip this section.

There are two ways to write it by hand:

| Way | What to write | Behavior |
| --- | --- | --- |
| Request header | Add a line `http_headers = { "X-Starry-Tier" = "economy" }` under `[model_providers.starry]` | A default: when the chosen tier cannot be used for a request, there is no error and the request is served automatically |
| Model suffix | `model = "gpt-5.5@economy"` | A demand on every request: if the tier cannot be used, the request is rejected (`400` or `403`). Codex may warn locally that it has no information about the model; the request is not affected |

```toml
[model_providers.starry]
name = "Starry API"
base_url = "{{api_base_url}}"
wire_api = "responses"
experimental_bearer_token = "YOUR_API_KEY"
supports_websockets = false
http_headers = { "X-Starry-Tier" = "economy" }
```

- `economy` is only an example; use the identifier of the tier you want, which you can copy next to the tier on the [Model IDs](#/models) page. Add `+` after the identifier (for example `economy+`) to allow that tier and the tiers below it;
- With a single tier, if all its accounts are temporarily unavailable the request fails (`503`, `SOURCE_TIER_UNAVAILABLE`); it is not moved to another tier and nothing is charged;
- When continuing an earlier response, if it belongs to a tier other than the one you pinned with a suffix, you get `409` (`RESPONSE_OWNER_TIER_MISMATCH`); a request header or the key's tier preference is simply ignored instead, with no error;
- For prices see [Model IDs](#/models). Without a tier, requests follow the key's **Tier preference**, or are automatic when none is set.

## Common problems

| Symptom | Cause and fix |
| --- | --- |
| `refresh token was already used` | An old ChatGPT sign-in remains in `auth.json`. Back it up and replace it with the minimal content above, or run `codex logout` first |
| `401` | The key was copied incompletely or is disabled |
| `403` | The key's model category is not OpenAI, or the model is outside that category. If the message says `SOURCE_TIER_NOT_SELECTABLE`, the tier written after `model` cannot be chosen; remove the `@tier` |
| `400` with `SOURCE_TIER_…` in the message | The `@tier` after `model` is wrong, or this model cannot use it. Remove it, or copy the correct form from [Model IDs](#/models) |
| `503`, `SOURCE_TIER_UNAVAILABLE` | The tier you pinned has no available account for now. Retry after `Retry-After`, or add `+` after the identifier to allow falling back |
| `402` | Not enough balance or plan credits; see [Top-ups and billing](#/documentation/billing) |
| Changes have no effect | Restart your terminal and Codex CLI; confirm you edited the files in your user directory |

See the [Error reference](#/documentation/errors) for more.$doc$))),
    ('opencode', 'ecba105c971db68ce15dba4ede7e17ac', jsonb_build_object(
        'title', '手动配置 OpenCode', 'category', '连接客户端',
        'summary', '在项目根目录放一个 opencode.json，用 OpenAI 兼容方式把 OpenCode 连到本站，以及怎么指定号池。', 'sortOrder', 55,
        'body', $doc$## 开始之前

- 已安装 OpenCode；
- 有一把 API Key，且它的 **模型分类** 包含你要用的模型（见 [API Key 与模型](#/documentation/api-keys)）；
- 想省事的话，用 [CC Switch 一键接入](#/documentation/cc-switch)。本页讲手动配置。

## 最省事：复制生成的配置

在 [API Key](#/api-keys) 里点密钥的 **客户端配置**，**客户端** 选 **OpenCode**，点 **复制当前配置**。内容已经带上了你的密钥。

## 手动配置

在**项目根目录**创建 `opencode.json`：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "model": "starry/gpt-5.5",
  "small_model": "starry/gpt-5.4-mini",
  "provider": {
    "starry": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Starry API",
      "options": {
        "baseURL": "{{api_base_url}}",
        "apiKey": "YOUR_API_KEY"
      },
      "models": {
        "gpt-5.5": {
          "name": "GPT-5.5",
          "limit": { "context": 200000, "output": 64000 }
        },
        "gpt-5.4-mini": {
          "name": "GPT-5.4 Mini",
          "limit": { "context": 200000, "output": 32000 }
        }
      }
    }
  }
}
```

要点：

- `baseURL` 要带 `/v1`（上面的 `{{api_base_url}}` 已经带了）；
- `model` 的写法是 `供应商名/模型 ID`，这里的供应商名是 `starry`；
- `models` 里要列出你想用的模型，名称请使用 [模型 ID](#/models) 页里的 ID；
- `small_model` 用于生成标题等轻量任务。OpenCode 可能在一次对话里再发一次请求给它，这是正常的，会在同一把密钥上记账。

> ⚠️ 这个文件里**包含你的密钥**。不要把它提交到 Git，请把 `opencode.json` 加进 `.gitignore`。

## 验证

在该项目里启动 OpenCode，选择 `starry` 下的模型，问一句简单的话。

## 指定号池（可选）

站点设置了号池并公开了号池名称时，在 [API Key](#/api-keys) 的 **客户端配置** 里，**客户端** 选 **OpenCode** 后会多一项 **号池**，选好后复制出来的 `opencode.json` 就带上它。没有这一项，说明站点没有为这把密钥的模型分类公开号池，可以跳过这一节。

手动写有两种方式：

| 方式 | 写法 | 特点 |
| --- | --- | --- |
| 请求头 | 在 `provider.starry.options` 里加 `headers`：`"headers": { "X-Starry-Tier": "economy" }` | 是默认选择：所选号池这个请求用不上时，不会报错，照常按自动服务 |
| 模型后缀 | 先把带后缀的 ID 写进 `models`（如 `"gpt-5.5@economy"`），再把 `model` 写成 `starry/gpt-5.5@economy` | 是对每个请求的要求：号池用不了就被拒绝（`400` 或 `403`）。OpenCode 自己会拒绝没写进 `models` 的模型，请求根本发不出去 |

```json
"options": {
  "baseURL": "{{api_base_url}}",
  "apiKey": "YOUR_API_KEY",
  "headers": { "X-Starry-Tier": "economy" }
}
```

- `economy` 只是例子，换成你要用的号池标识；在 [模型 ID](#/models) 页的号池旁可以复制。标识后面加 `+`（如 `economy+`）表示它和它以下的号池都可以；
- 只选一个号池时，如果它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费；
- [CC Switch 一键接入](#/documentation/cc-switch) 的 OpenCode 链接不写号池：请用上面的 `opencode.json`，或在密钥里设置 **号源偏好**，见 [API Key 与模型](#/documentation/api-keys)；
- 价格见 [模型 ID](#/models)。

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| 列表里没有模型 | `models` 里没有列出该模型，或 `provider` 的名字和 `model` 里写的不一致 |
| `401` | 密钥复制不完整，或被停用 |
| `403` | 密钥的模型分类不包含该模型。例如密钥是 OpenAI 分类，却选了 `claude-*`。信息里是 `SOURCE_TIER_NOT_SELECTABLE` 时，说明模型 ID 后面的号池不允许选择，去掉 `@标识` |
| `400`，信息里有 `SOURCE_TIER_…` | 模型 ID 后面的 `@标识` 不对，或这个模型用不上它。去掉它，或到 [模型 ID](#/models) 页复制正确的写法 |
| `503`，`SOURCE_TIER_UNAVAILABLE` | 你固定的号池暂时没有可用账号。按 `Retry-After` 稍后重试，或在标识后面加 `+` 允许降级 |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up OpenCode', 'category', 'Connect a client',
            'summary', 'Put an opencode.json in your project root to connect OpenCode to this site as an OpenAI-compatible provider, and how to choose a tier.',
            'body', $doc$## Before you start

- OpenCode is installed;
- You have an API key whose **Model category** covers the models you want (see [API keys and models](#/documentation/api-keys));
- For the easiest route, use [CC Switch setup](#/documentation/cc-switch). This page covers manual setup.

## Easiest: copy the generated configuration

In [API Key](#/api-keys), select **Client configuration** for your key, set **Client** to **OpenCode**, and select **Copy configuration**. Your key is already included.

## Manual setup

Create `opencode.json` in your **project root**:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "model": "starry/gpt-5.5",
  "small_model": "starry/gpt-5.4-mini",
  "provider": {
    "starry": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Starry API",
      "options": {
        "baseURL": "{{api_base_url}}",
        "apiKey": "YOUR_API_KEY"
      },
      "models": {
        "gpt-5.5": {
          "name": "GPT-5.5",
          "limit": { "context": 200000, "output": 64000 }
        },
        "gpt-5.4-mini": {
          "name": "GPT-5.4 Mini",
          "limit": { "context": 200000, "output": 32000 }
        }
      }
    }
  }
}
```

Notes:

- `baseURL` must include `/v1` (`{{api_base_url}}` above already does);
- `model` is written as `provider-name/model-ID`; here the provider name is `starry`;
- List the models you want under `models`, using the IDs from [Model IDs](#/models);
- `small_model` is used for lightweight tasks such as generating titles. OpenCode may send a second request to it during a conversation; that is normal and is billed to the same key.

> ⚠️ This file **contains your key**. Do not commit it to Git; add `opencode.json` to `.gitignore`.

## Verify

Start OpenCode in that project, choose a model under `starry`, and ask a simple question.

## Choose a tier (optional)

When the site has set up tiers and shows their names, **Client configuration** in [API Key](#/api-keys) has an extra **Tier** field after you choose **OpenCode**, and the copied `opencode.json` includes your choice. Without that field, the site shows no tiers for this key's model category, and you can skip this section.

There are two ways to write it by hand:

| Way | What to write | Behavior |
| --- | --- | --- |
| Request header | Add `headers` to `provider.starry.options`: `"headers": { "X-Starry-Tier": "economy" }` | A default: when the chosen tier cannot be used for a request, there is no error and the request is served automatically |
| Model suffix | Declare the ID with the suffix under `models` first (such as `"gpt-5.5@economy"`), then write `model` as `starry/gpt-5.5@economy` | A demand on every request: if the tier cannot be used, the request is rejected (`400` or `403`). OpenCode itself refuses a model that is not declared under `models`, so no request is sent |

```json
"options": {
  "baseURL": "{{api_base_url}}",
  "apiKey": "YOUR_API_KEY",
  "headers": { "X-Starry-Tier": "economy" }
}
```

- `economy` is only an example; use the identifier of the tier you want, which you can copy next to the tier on the [Model IDs](#/models) page. Add `+` after the identifier (for example `economy+`) to allow that tier and the tiers below it;
- With a single tier, if all its accounts are temporarily unavailable the request fails (`503`, `SOURCE_TIER_UNAVAILABLE`); it is not moved to another tier and nothing is charged;
- The OpenCode link of [CC Switch setup](#/documentation/cc-switch) does not write a tier: use the `opencode.json` above, or set a **Tier preference** on the key; see [API keys and models](#/documentation/api-keys);
- For prices see [Model IDs](#/models).

## Common problems

| Symptom | Cause and fix |
| --- | --- |
| No models in the list | The model is not listed under `models`, or the `provider` name does not match the one used in `model` |
| `401` | The key was copied incompletely or is disabled |
| `403` | The key's model category does not include the model, for example an OpenAI-category key with a `claude-*` model. If the message says `SOURCE_TIER_NOT_SELECTABLE`, the tier written after the model ID cannot be chosen; remove the `@tier` |
| `400` with `SOURCE_TIER_…` in the message | The `@tier` after the model ID is wrong, or this model cannot use it. Remove it, or copy the correct form from [Model IDs](#/models) |
| `503`, `SOURCE_TIER_UNAVAILABLE` | The tier you pinned has no available account for now. Retry after `Retry-After`, or add `+` after the identifier to allow falling back |
| `402` | Not enough balance or plan credits; see [Top-ups and billing](#/documentation/billing) |

See the [Error reference](#/documentation/errors) for more.$doc$))),
    ('errors', '8bfbe9001c80c9c8ed192ccce6dd55ed', jsonb_build_object(
        'title', '错误码速查', 'category', '遇到问题',
        'summary', '按状态码查看每种报错的含义和处理办法，包括号池相关的错误，以及求助时该准备哪些信息。', 'sortOrder', 70,
        'body', $doc$## 先看状态码

调用出错时，先看返回的**状态码**。本站会按你所用协议的格式返回错误：OpenAI 兼容的客户端看到 OpenAI 风格的错误，Claude Code 看到 Anthropic 风格的错误。错误信息里通常带有状态码和说明。

| 状态码 | 含义 | 怎么办 |
| --- | --- | --- |
| `400` | 请求内容无效：缺少 `model` 或消息，或这个模型不支持你使用的接口；模型名后面的 `@标识` 不对时也是 `400` | 检查请求；换成模型支持的协议，或用 [客户端配置](#/api-keys) 重新生成。和号池有关的见下文“号池相关的错误” |
| `401` | 密钥缺失或无效 | 确认用的是本站的 API Key（不是登录密码），完整复制，没被停用 |
| `402` | 余额或订阅额度不足，请求在发往模型之前就被拒绝，**不会扣费** | 去 [余额充值](#/payment) 或 [Coding Plan](#/coding-plans) |
| `403` | 看信息里的错误码。`API_KEY_MODEL_PROVIDER_FORBIDDEN`：密钥的模型分类与所请求的模型不匹配。`SOURCE_TIER_NOT_SELECTABLE`：模型名后面写的号池，站点不允许选择 | 前者：换成对应分类的密钥，或换成该密钥分类下的模型。后者：去掉模型名后面的 `@标识`，或改选可以选择的号池，见下文 |
| `409` | 续写上一轮的响应（Responses 接口的 `previous_response_id`）时，它所在的号池不是你固定的那个（`RESPONSE_OWNER_TIER_MISMATCH`） | 去掉模型名后面的 `@标识`，或开始一段新的对话，见下文 |
| `413` | 请求内容太大 | 减少一次发送的上下文，例如文件或对话历史 |
| `429` | 请求过于频繁，达到密钥的额度上限，或模型服务暂时繁忙 | 稍等片刻再试，减少并发；持续出现就看密钥的用量和上限 |
| `502` | 模型服务返回了错误 | 稍后重试；反复出现就提交工单 |
| `503` | 模型服务暂时繁忙、该模型暂不能使用，或站点正在维护 | 见下文 |

## 503 的几种情况

- **模型服务暂时繁忙**：稍等一会儿再试。持续出现就提交工单。
- **所选号池暂时没有可用账号**（`SOURCE_TIER_UNAVAILABLE`）：你固定了一个号池，它的账号暂时都用不了。响应带 `Retry-After`，表示建议等多少秒再试。这次请求不会扣费，也不会换到别的号池；如果想允许降级，在 `@标识` 后面加 `+`，或改回自动。
- **模型待定价**（`MODEL_PRICE_UNAVAILABLE`）：该模型还没有价格，不能调用。换一个模型即可。
- **站点维护**（`SYSTEM_MAINTENANCE`）：站点正在维护，请稍后再试。

## 号池相关的错误

站点设置了号池，而你在模型名后面写了 `@标识`、用了请求头，或给密钥设了号源偏好时，可能遇到下面这些错误。它们都在扣费之前返回，**不会扣费**。写法和说明见 [API Key 与模型](#/documentation/api-keys)。

| 错误码 | 状态码 | 原因 | 怎么办 |
| --- | --- | --- | --- |
| `SOURCE_TIER_UNKNOWN` | `400` | `@` 后面不是这个厂商的号池，或写法不对。带日期的模型 ID（如 `claude-sonnet-4@20250514`）也会被当成号池标识 | 到 [模型 ID](#/models) 页复制号池的写法，或去掉 `@` 和后面的内容 |
| `SOURCE_TIER_NOT_OFFERED` | `400` | 号池存在，但这个模型或这个接口用不上它，或它暂时停用 | 换一个号池，或去掉后缀改用自动 |
| `SOURCE_TIER_NOT_SELECTABLE` | `403` | 站点不允许客户端选择这个号池 | 去掉后缀，或改选模型 ID 页上可以复制的号池 |
| `SOURCE_TIER_FEATURE_UNSUPPORTED` | `400` | 请求用到了某个功能（信息里写明是哪个，如 `web_search`），而这次请求能用的号池里没有任何号源支持它（固定了号池时尤其常见） | 去掉这个功能，或不要固定号池 |
| `API_KEY_TIER_PREFERENCE_INVALID` | `400` | 保存密钥时，号源偏好写法不对，或给不限模型分类的旧密钥设了偏好 | 用表单里的选项；旧密钥先选好模型分类 |
| `RESPONSE_OWNER_TIER_MISMATCH` | `409` | 续写的上一轮响应不能换账号，而它所在的号池不是你固定的那个 | 去掉后缀，或从一段新的对话开始 |
| `SOURCE_TIER_UNAVAILABLE` | `503` | 你固定的号池，账号暂时都不可用。响应带 `Retry-After` | 等 `Retry-After` 给的秒数再试；加 `+` 允许降级，或改回自动 |

请求头 `X-Starry-Tier` 和密钥的号源偏好只是默认选择，用不上时**不会报错**，请求照常按自动服务，响应头 `X-Starry-Tier-Status` 是 `ignored`。

## 配置类问题

这几类错误通常不是状态码，而是连不上或路径不对：

- **连不上**：检查本站能否正常打开，以及 Base URL 是不是你平时访问本站用的域名。
- **404 或路径错误**：Base URL 多写或少写了 `/v1`。OpenAI 兼容的客户端要带 `/v1`；Claude Code 不要带。详见 [常见问题](#/documentation/faq)。

## 求助前先准备

提交工单时，请提供下面这些信息，可以更快定位问题：

1. 出错的**时间**（精确到分钟）；
2. 使用的**客户端**和版本；
3. **模型 ID**；
4. **状态码**和完整的错误信息；
5. 你已经尝试过的步骤。

> 提供错误信息前，**先隐藏完整的 API Key**（比如只保留前几位）。

准备好后，按 [如何提交工单](#/documentation/tickets) 联系管理员。$doc$,
        'english', jsonb_build_object(
            'title', 'Error reference', 'category', 'Troubleshooting',
            'summary', 'What each status code means and what to do, including tier errors, plus what to prepare when asking for help.',
            'body', $doc$## Start with the status code

When a call fails, look at the returned **status code**. This site returns errors in the format of the protocol you use: OpenAI-compatible clients see OpenAI-style errors, and Claude Code sees Anthropic-style errors. The message usually includes a status code and a description.

| Status | Meaning | What to do |
| --- | --- | --- |
| `400` | Invalid request: `model` or messages are missing, or the model does not support the endpoint you use; a wrong `@tier` after the model name is also `400` | Check the request; use a protocol the model supports, or regenerate with [Client configuration](#/api-keys). For tier problems see "Tier errors" below |
| `401` | The key is missing or invalid | Make sure you use this site's API key (not your sign-in password), copied in full and not disabled |
| `402` | Insufficient balance or plan credits; rejected before reaching the model, so **nothing is charged** | Go to [Top up](#/payment) or [Coding Plan](#/coding-plans) |
| `403` | Look at the error code in the message. `API_KEY_MODEL_PROVIDER_FORBIDDEN`: the key's model category does not match the requested model. `SOURCE_TIER_NOT_SELECTABLE`: the site does not let clients choose the tier written after the model name | For the first, use a key in the right category, or a model in this key's category. For the second, remove the `@tier` after the model name or choose a tier you may choose; see below |
| `409` | When continuing an earlier response (`previous_response_id` of the Responses endpoint), it belongs to a tier other than the one you pinned (`RESPONSE_OWNER_TIER_MISMATCH`) | Remove the `@tier` after the model name, or start a new conversation; see below |
| `413` | The request is too large | Send less context at once, such as fewer files or less history |
| `429` | Too many requests, the key's spending limit was reached, or the model service is briefly busy | Wait a moment and retry with less concurrency; if it persists, check the key's usage and limit |
| `502` | The model service returned an error | Retry later; submit a ticket if it keeps happening |
| `503` | The model service is busy, the model cannot be used right now, or the site is under maintenance | See below |

## The kinds of 503

- **Model service busy**: wait a moment and retry. Submit a ticket if it persists.
- **The tier you chose has no available account** (`SOURCE_TIER_UNAVAILABLE`): you pinned a tier and all its accounts are temporarily unusable. The response carries `Retry-After`, the number of seconds to wait before retrying. The request is not charged and is not moved to another tier; add `+` after the `@tier` if you want it to fall back, or go back to automatic.
- **Unpriced model** (`MODEL_PRICE_UNAVAILABLE`): the model has no price yet and cannot be called. Choose another model.
- **Site maintenance** (`SYSTEM_MAINTENANCE`): the site is under maintenance. Please try again later.

## Tier errors

When the site has set up tiers and you write `@tier` after the model name, send a request header, or set a key's tier preference, you may meet the following. They are all returned before anything is charged, so **nothing is charged**. See [API keys and models](#/documentation/api-keys) for the forms and details.

| Code | Status | Cause | What to do |
| --- | --- | --- | --- |
| `SOURCE_TIER_UNKNOWN` | `400` | What follows `@` is not a tier of this provider, or is malformed. A dated model ID such as `claude-sonnet-4@20250514` is also read as a tier identifier | Copy the tier form from [Model IDs](#/models), or remove the `@` and what follows |
| `SOURCE_TIER_NOT_OFFERED` | `400` | The tier exists, but this model or endpoint cannot use it, or it is switched off for now | Choose another tier, or remove the suffix to use automatic |
| `SOURCE_TIER_NOT_SELECTABLE` | `403` | The site does not let clients choose this tier | Remove the suffix, or choose a tier you can copy from Model IDs |
| `SOURCE_TIER_FEATURE_UNSUPPORTED` | `400` | The request uses a feature (the message names it, such as `web_search`) that nothing in the tiers this request may use supports (most often when a tier is pinned) | Remove the feature, or do not pin a tier |
| `API_KEY_TIER_PREFERENCE_INVALID` | `400` | When saving a key, the tier preference is malformed, or an older key without a model category was given one | Use the choices in the form; give an older key a model category first |
| `RESPONSE_OWNER_TIER_MISMATCH` | `409` | The earlier response being continued cannot move to another account, and its tier is not the one you pinned | Remove the suffix, or start a new conversation |
| `SOURCE_TIER_UNAVAILABLE` | `503` | The tier you pinned has no account available for now. The response carries `Retry-After` | Wait the number of seconds in `Retry-After`; add `+` to allow falling back, or go back to automatic |

The `X-Starry-Tier` request header and a key's tier preference are only defaults: when they cannot be used they raise **no error**, the request is served automatically, and the `X-Starry-Tier-Status` response header is `ignored`.

## Configuration problems

These usually show up as a failure to connect or a wrong path rather than a status code:

- **Cannot connect**: check that this site opens normally and that the Base URL is the domain you normally use to open it.
- **404 or wrong path**: the Base URL has an extra or missing `/v1`. OpenAI-compatible clients need `/v1`; Claude Code must not have it. See the [FAQ](#/documentation/faq).

## Prepare before asking for help

When you submit a ticket, include the following so the problem can be located faster:

1. The **time** of the error, to the minute;
2. The **client** and its version;
3. The **model ID**;
4. The **status code** and the full error message;
5. The steps you have already tried.

> **Hide your full API key** before sharing error messages, for example by keeping only the first few characters.

Once ready, follow [Submit a support ticket](#/documentation/tickets) to contact an administrator.$doc$))),
    ('faq', '376090c60fa838e67152ee1c1fab9aad', jsonb_build_object(
        'title', '常见问题与求助', 'category', '遇到问题',
        'summary', '密钥、额度、模型、地址、Codex 授权等常见问题的快速解答。', 'sortOrder', 80,
        'body', $doc$## 密钥与登录

### 提示 401，或者密钥无效

确认用的是本站的 **API Key**，不是登录密码，也不是其他平台的密钥。检查复制时有没有带进空格，是不是误用了只显示前缀的密钥，以及密钥有没有被停用。失效的密钥需要重新创建。

### 我忘了密钥，找不回来

在 [API Key](#/api-keys) 列表里点 **复制密钥**，就能复制完整密钥。很早以前（旧版本）创建的密钥只保存了前缀，找不回完整值，请新建一把并更新本地配置。

### 怎么修改密码

点右上角头像，选择 **修改密码**。管理员创建的账号第一次登录必须先改密码，新密码为 12–128 个字符。

### 怀疑密钥泄露了

立即在 [API Key](#/api-keys) 里把它**停用**，再新建一把替换，更新所有用到旧密钥的客户端。以后不要把密钥提交到 Git、发到群聊或贴在工单里。

## 额度与计费

### 提示余额不足，或订阅用不了

先看这把密钥的**计费途径**。余额计费的密钥需要钱包里有余额；Coding Plan 计费的密钥需要有效的订阅、剩余额度和适用的模型分类。两者**不会**互相兜底。见 [充值与计费](#/documentation/billing)。

### 付款了，余额没变

不要重复付款。到 [余额充值](#/payment) 的 **充值历史记录** 看订单状态，等几分钟。仍然没到账就提交工单，附上订单号和付款时间。

### 为什么实际扣费和我估算的不一样

实际费用取决于模型价格、输入和输出的 token 量、缓存命中情况以及本站的计费规则。页面价格用于估算，实际扣费以请求结束后的结算为准。

## 模型

### 模型不存在，或者不支持

从 [模型 ID](#/models) 页复制完整的 ID，确认模型的厂商和密钥的 **模型分类** 一致。也可能是客户端使用的协议不适合该模型，这时请用 **客户端配置** 重新生成。

### 提示 403

通常是密钥的模型分类和所请求的模型不匹配。例如用 OpenAI 分类的密钥去请求 `claude-*`。为需要的每个厂商各建一把密钥。

### 模型标着“待定价”，调用提示 503

待定价的模型暂时没有价格，不能调用。换一个模型即可。

## 地址与连接

### Base URL 到底要不要带 /v1

| 客户端 | Base URL |
| --- | --- |
| OpenAI 兼容的应用、Codex CLI、OpenCode | 带 `/v1`：`{{api_base_url}}` |
| Claude Code（Anthropic 协议） | **不带** `/v1`：`{{site_url}}` |

写错的话会出现 404 或路径错误。

### 连接失败，或 5xx

先确认本站能正常打开，再检查 Base URL 是不是你平时访问本站用的域名。短暂的服务异常可以稍后重试；不要持续快速重试，以免产生额外调用。

### 429，请求太频繁

稍等一会儿再试，减少同时发出的请求。持续出现时，查看密钥的额度上限，并提交工单。

## Codex CLI

### 提示 refresh token was already used

本机 `auth.json` 里还留着旧的 ChatGPT 授权。备份后换成只含 `OPENAI_API_KEY` 的最简内容，或先运行 `codex logout`。详见 [手动配置 Codex CLI](#/documentation/codex-cli)。

## 还是解决不了

按 [如何提交工单](#/documentation/tickets) 在 [我的工单](#/tickets) 里联系管理员，说明客户端、发生时间、模型 ID、错误信息和已经尝试的步骤。可以附上截图。

提交前**隐藏完整的 API Key**、导入链接里的密钥和支付凭证中的敏感信息。$doc$,
        'english', jsonb_build_object(
            'title', 'FAQ and support', 'category', 'Troubleshooting',
            'summary', 'Quick answers about keys, credits, models, addresses, and Codex sign-in.',
            'body', $doc$## Keys and sign-in

### I get 401, or the key is invalid

Make sure you use this site's **API key**, not your sign-in password or another platform's key. Check for stray spaces when copying, that you did not use a key shown only as a prefix, and that the key is not disabled. An invalid key must be recreated.

### I forgot my key and cannot find it

In the [API Key](#/api-keys) list, select **Copy key** to copy the full key. Keys created long ago, in earlier versions, stored only a prefix and cannot be recovered; create a new key and update your local configuration.

### How do I change my password?

Click your avatar in the top right and choose **Change password**. An account created by an administrator must change its password on first sign-in; the new password is 12–128 characters.

### I suspect my key leaked

Immediately **disable** it in [API Key](#/api-keys), create a replacement, and update every client that used the old one. Never commit keys to Git, post them in chats, or paste them into tickets.

## Credits and billing

### Insufficient balance, or the plan cannot be used

Check the key's **billing source** first. A balance-billing key needs wallet balance; a Coding Plan key needs an active subscription with remaining credits and a matching model category. The two **never** cover for each other. See [Top-ups and billing](#/documentation/billing).

### I paid, but my balance did not change

Do not pay again. Check the order status in **Top-up history** on [Top up](#/payment) and wait a few minutes. If it still has not arrived, submit a ticket with the order number and payment time.

### Why does the actual charge differ from my estimate?

The actual cost depends on the model price, input and output tokens, cache hits, and this site's billing rules. Prices on the page are for estimating; the actual charge is the settlement after each request.

## Models

### The model does not exist, or is not supported

Copy the full ID from [Model IDs](#/models) and make sure the model's provider matches your key's **Model category**. The client may also be using a protocol that does not suit the model; regenerate with **Client configuration**.

### I get 403

Usually the key's model category does not match the requested model, for example an OpenAI-category key requesting `claude-*`. Create one key for each provider you need.

### A model is marked "Unpriced" and calls return 503

An unpriced model has no price yet and cannot be called. Choose another model.

## Addresses and connections

### Should the Base URL include /v1?

| Client | Base URL |
| --- | --- |
| OpenAI-compatible apps, Codex CLI, OpenCode | With `/v1`: `{{api_base_url}}` |
| Claude Code (Anthropic protocol) | **Without** `/v1`: `{{site_url}}` |

A wrong value causes 404 or path errors.

### Connection failure, or 5xx

First confirm this site opens normally, then check that the Base URL is the domain you normally use to open it. Retry temporary service errors later; avoid rapid repeated requests that could create extra usage.

### 429, too many requests

Wait a moment and retry with fewer concurrent requests. If it persists, check the key's spending limit and submit a ticket.

## Codex CLI

### refresh token was already used

An old ChatGPT sign-in remains in `auth.json`. Back it up and replace it with the minimal content containing only `OPENAI_API_KEY`, or run `codex logout` first. See [Set up Codex CLI](#/documentation/codex-cli).

## Still stuck

Follow [Submit a support ticket](#/documentation/tickets) and contact an administrator from [My tickets](#/tickets), with your client, the time, the model ID, the error message, and the steps already tried. Screenshots can help.

Before submitting, **hide your full API key**, the key inside import links, and sensitive payment details.$doc$)))
)
UPDATE documentation_pages p
SET draft = tutorials.content,
    published = tutorials.content,
    published_at = clock_timestamp(),
    version = p.version + 1,
    updated_at = clock_timestamp()
FROM tutorials
WHERE p.id = tutorials.id
  AND p.deleted = FALSE AND p.published IS NOT NULL
  AND p.draft = p.published
  AND md5(p.draft::text) = tutorials.previous_hash;

-- Deleted as the console deletes a page: unpublished and marked deleted, so it leaves the catalog and the assistant.
WITH removed(id, previous_hash) AS (VALUES
    ('getting-started', '338abd1a536881538501515ec9c48428'),
    ('console-tour', 'bd07cb0b4db7be1f507dde1bcbd8e981'),
    ('coding-plan', '19fadb512d0e92662b0adbf39a5c9bed'),
    ('models-and-pricing', '68f145d00dd1a82250c0990a9a0acd7b')
)
UPDATE documentation_pages p
SET published = NULL,
    published_at = NULL,
    deleted = TRUE,
    version = p.version + 1,
    updated_at = clock_timestamp()
FROM removed
WHERE p.id = removed.id
  AND p.deleted = FALSE AND p.published IS NOT NULL
  AND p.draft = p.published
  AND md5(p.draft::text) = removed.previous_hash;
