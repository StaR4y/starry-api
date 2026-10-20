-- Keep the default tutorials focused on what regular users do: remove operations, hosting and
-- administrator-only remarks that V23 had included.
-- A tutorial is replaced only while it is still exactly the version V23 installed (identical draft and
-- publication, matching content hash). Anything an administrator edited, unpublished or deleted is left alone.
WITH tutorials(id, previous_hash, content) AS (VALUES
    ('console-tour', '3cb01a88e7c8b8640cc3103648bb7ccd', jsonb_build_object(
        'title', '认识控制台', 'category', '开始使用',
        'summary', '左侧菜单每一项是做什么的，以及数据统计页怎么读。', 'sortOrder', 15,
        'body', $doc$## 界面布局

- **左侧**是菜单；
- **顶部**有面包屑、语言选择、你的当前余额和头像菜单；
- **中间**是当前页面的内容。

## 菜单一览

| 菜单 | 用来做什么 |
| --- | --- |
| [数据统计](#/welcome) | 你的首页：余额、密钥数量、累计调用和各模型的使用情况 |
| [API Key](#/api-keys) | 创建和管理密钥，获取各客户端的配置 |
| [余额充值](#/payment) | 在线充值、使用兑换码、查看充值记录 |
| [Coding Plan](#/coding-plans) | 购买和查看订阅额度 |
| [模型天梯](#/model-ranking) | 主流模型的公开基准榜单 |
| [模型 ID](#/models) | 本站可用的模型、ID 和价格 |
| [新闻](#/news) | 按日期回看每天的要闻 |
| [使用文档](#/documentation) | 你正在看的这里 |
| [我的工单](#/tickets) | 提交问题、跟进处理进度 |

## 顶部栏

- **语言选择**：在简体中文和 English 之间切换。使用文档里有英文版的文章会随之切换。
- **余额**：你当前的钱包余额。
- **头像菜单**：**修改密码**、**退出登录**。

## 数据统计怎么读

登录后的首页显示：

- **当前余额**，以及最近一次使用的时间；
- **API Key 密钥**：已启用的密钥数量；
- **累计调用**：一共发出了多少次请求；
- **已用 Token / 累计消耗**：用掉的 token 总量和累计消耗的额度；
- **模型使用情况**：每个模型被调用的次数和最近使用时间。

点页面上的 **刷新** 可以获取最新数据。$doc$,
        'english', jsonb_build_object(
            'title', 'Tour of the console', 'category', 'Getting started',
            'summary', 'What each menu item does and how to read the Statistics page.',
            'body', $doc$## Layout

- The **left side** holds the menu.
- The **top bar** has the breadcrumb, language selector, your balance, and the avatar menu.
- The **middle** shows the current page.

## Menu overview

| Menu | What it is for |
| --- | --- |
| [Statistics](#/welcome) | Your home page: balance, key count, total requests, and model usage |
| [API Key](#/api-keys) | Create and manage keys and get client configurations |
| [Top up](#/payment) | Pay online, redeem codes, and review top-up history |
| [Coding Plan](#/coding-plans) | Buy and review subscription credits |
| [Model rankings](#/model-ranking) | Public benchmark rankings for leading models |
| [Model IDs](#/models) | Models available here, their IDs, and prices |
| [News](#/news) | Daily news you can browse by date |
| [Documentation](#/documentation) | The page you are reading |
| [My tickets](#/tickets) | Submit questions and follow progress |

## Top bar

- **Language selector**: switch between 简体中文 and English. Articles that have an English version switch with it.
- **Balance**: your current wallet balance.
- **Avatar menu**: **Change password** and **Sign out**.

## Reading the Statistics page

Your home page shows:

- **Current balance** and the time of your latest usage;
- **API Key**: how many keys are enabled;
- **Total requests**: how many requests you have made;
- **Tokens / total credits used**: the total tokens and credits consumed;
- **Model usage**: how many times each model was called and when it was last used.

Select **Refresh** to load the latest figures.$doc$))),
    ('models-and-pricing', '69b288c5d1352b0a844e658468aa5ef8', jsonb_build_object(
        'title', '选择模型与查看价格', 'category', '开始使用',
        'summary', '在哪里查模型 ID 和价格、怎么选模型、费用怎么算，以及“待定价”的模型为什么不能调用。', 'sortOrder', 35,
        'body', $doc$## 去哪里看模型

[模型 ID](#/models) 页列出了本站所有可用的模型：

- 页面上方显示模型总数和厂商数；
- 可以用 **模型厂商**、**模型系列** 筛选，也可以按名称、ID、系列搜索；
- 每个模型显示名称、ID、系列、**上下文窗口**、**价格**（每百万 token）和来源（内置或自定义）；
- 点 **复制模型 ID** 复制准确的 ID，填到客户端的“模型”一栏。

> 请求里的 `model` 必须和这里的 ID 一致。

## 怎么选模型

1. **先看厂商**：模型的厂商必须和你密钥的 **模型分类** 一致，否则会收到 `403`。
2. **再看能力和价格**：简单任务、生成标题这类轻量工作，用 mini、haiku 之类的小模型更省；复杂任务再选更强的模型。
3. **看上下文窗口**：处理很长的代码或文档时，选上下文窗口更大的模型。
4. 客户端默认的模型不在列表里，或者你没有权限时，换成列表里的 ID。

## 价格怎么算

- 页面显示的是每百万 token 的价格，输入和输出分别计价；
- 实际费用取决于模型、实际的输入和输出 token 量、是否命中缓存，以及本站的计费规则；
- 页面上的价格用于估算，**实际扣费以请求结束后的结算为准**。

余额和订阅的区别见 [充值与计费](#/documentation/billing)。

## 标记为“待定价”的模型

**待定价** 的模型暂时没有价格，**调用会被拒绝**（`503`，`MODEL_PRICE_UNAVAILABLE`），并且不会扣费。请换一个模型。

## 模型天梯

[模型天梯](#/model-ranking) 展示 ProgramBench、FrontierBench 等公开基准榜单，每日更新，可以帮助你了解主流模型的相对水平。榜单上的模型不一定都在本站提供，具体请以 [模型 ID](#/models) 为准。$doc$,
        'english', jsonb_build_object(
            'title', 'Models and pricing', 'category', 'Getting started',
            'summary', 'Where to find model IDs and prices, how to choose a model, how costs are calculated, and why unpriced models cannot be called.',
            'body', $doc$## Where to see models

The [Model IDs](#/models) page lists every model available on this site:

- The top of the page shows the number of models and providers;
- Filter by **Model provider** and **Model series**, or search by name, ID, or series;
- Each model shows its name, ID, series, **Context window**, **price** (per million tokens), and source (built-in or custom);
- Select **Copy model ID** to copy the exact ID for the "model" field of your client.

> The `model` in your request must match the ID shown here.

## How to choose a model

1. **Check the provider first**: the model's provider must match your key's **Model category**, or you will get `403`.
2. **Then capability and price**: for simple or lightweight work such as generating titles, a small model like a mini or haiku is cheaper; use a stronger model for complex tasks.
3. **Check the context window**: for very long code or documents, choose a model with a larger window.
4. If your client's default model is not in the list, or you lack access, switch to an ID from the list.

## How pricing works

- The page shows prices per million tokens, with input and output priced separately;
- The actual cost depends on the model, the actual input and output tokens, cache hits, and this site's billing rules;
- Prices on the page are for estimating; **the actual charge is the settlement after each request**.

For balance versus subscription, see [Top-ups and billing](#/documentation/billing).

## Models marked "Unpriced"

An **Unpriced** model has no price yet, so **calls are rejected** (`503`, `MODEL_PRICE_UNAVAILABLE`) and nothing is charged. Choose another model.

## Model rankings

[Model rankings](#/model-ranking) shows public benchmarks such as ProgramBench and FrontierBench, updated daily, to help you gauge how leading models compare. Not every ranked model is offered here; rely on [Model IDs](#/models).$doc$))),
    ('cc-switch', '284c5ca7bfdf95f83df39a2d7fd26eba', jsonb_build_object(
        'title', 'CC Switch 一键接入', 'category', '连接客户端',
        'summary', '推荐方式：在本站点一下，就把 Claude Code、Codex CLI 或 OpenCode 的配置导入 CC Switch。', 'sortOrder', 40,
        'body', $doc$## CC Switch 是什么

[CC Switch](https://ccswitch.io) 是一个管理 AI 编程客户端配置的桌面工具，支持 Windows、macOS 和 Linux。本站能生成一个导入链接，点一下就把供应商配置写进 CC Switch，省去手改配置文件。

> CC Switch 只负责**管理配置**。Claude Code、Codex CLI、OpenCode 本身仍需要你另外安装。

## 准备

1. 从 [CC Switch 官方网站](https://ccswitch.io) 下载并安装对应你系统的版本，也可以使用 [官方 GitHub 下载页](https://github.com/farion1231/cc-switch/releases/latest)。安装后**启动一次**。
2. 安装好你要用的客户端：Claude Code、Codex CLI 或 OpenCode。
3. 在 [API Key](#/api-keys) 里准备好一把密钥，且它的 **模型分类** 要和你要用的客户端匹配（见 [创建与管理 API Key](#/documentation/api-keys)）。

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
- 也可以点 **复制链接**，使用 CC Switch 支持的链接导入入口粘贴。若你的版本没有这个入口，请升级，或改用 [手动配置](#/documentation/manual-api)。

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
3. Prepare an API key in [API Key](#/api-keys) whose **Model category** matches that client (see [Create and manage API keys](#/documentation/api-keys)).

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
    ('billing', '5ae732ddcdbb4c93a93ab93abd6b1dd8', jsonb_build_object(
        'title', '充值与计费', 'category', '开始使用',
        'summary', '余额和 Coding Plan 的区别、怎么充值和兑换、付款后没到账怎么办、余额是怎么扣的。', 'sortOrder', 20,
        'body', $doc$## 两种计费途径，先选对

| | 余额计费 | Coding Plan |
| --- | --- | --- |
| 额度从哪来 | 钱包余额（充值或兑换码） | 订阅里的调用额度 |
| 怎么付费 | 按实际用量扣费 | 先用余额购买订阅，之后消耗订阅额度 |
| 额度不够时 | 请求被拒绝（`402`） | 请求被拒绝（`402`），**不会**改扣余额 |
| 适合 | 偶尔使用、用量不固定 | 每天稳定使用 |

> 两种途径**互不串用**：余额密钥永远不会消耗订阅额度，Coding Plan 密钥额度用完也不会去扣余额。创建密钥时就选好用途，把日常开发和临时测试分开，更好管理。

## 给余额充值

打开 [余额充值](#/payment)。

### 在线充值

1. 在左侧选择 **充值金额**（单位：元）和 **支付方式**（支付宝或微信支付，以页面显示为准）。
2. 页面会实时显示 **预计到账余额**、**实际应付金额** 和 **手续费率**，确认无误后点 **创建订单并支付**。
3. 按提示完成支付（扫码，或跳转到支付页面）。
4. 付款后回到本站。订单显示 **已入账** 时，顶部的 **当前账户余额** 就已经增加了。

页面里的“交易规则”写明了单笔金额范围、每日限额，以及你最多可以同时保留几个未支付订单。

> 页面提示“当前未开放在线充值”时，暂时无法在线付款。可以使用兑换码，或 [提交工单](#/documentation/tickets) 咨询。

### 使用兑换码

在 **兑换码充值** 面板输入管理员发放的兑换码，点 **兑换余额**，余额立即到账。**每个兑换码只能使用一次。**

有的兑换码直接开通一个 Coding Plan 订阅，兑换成功时页面会提示“Coding 套餐订阅已开通”。

### 付了款但余额没变？

1. **不要重复付款。**
2. 在 **充值历史记录** 里查看这笔订单的状态。**已入账** 才表示余额已经增加；其他状态请先稍等片刻再刷新。
3. 超时未支付的订单会自动过期；不想要的待支付订单可以点 **取消订单**。
4. 等几分钟仍未入账，请 [提交工单](#/documentation/tickets)，附上订单号和付款时间。**不要**提供支付密码或银行卡信息。

## 余额是怎么扣的

- 每次请求前，系统会先**预留**一部分余额，请求结束后按实际用量结算，多预留的部分会退回。
- 余额不足时，请求在发往模型服务**之前**就会被拒绝（`402`），不会产生费用。
- 实际费用由模型、输入和输出的 token 用量、是否命中缓存以及本站的计费规则共同决定。价格见 [选择模型与查看价格](#/documentation/models-and-pricing)。

## 下一步

想用订阅？看 [使用 Coding Plan](#/documentation/coding-plan)。准备好额度后，去 [创建 API Key](#/documentation/api-keys)。$doc$,
        'english', jsonb_build_object(
            'title', 'Top-ups and billing', 'category', 'Getting started',
            'summary', 'Balance versus Coding Plan, how to top up and redeem codes, what to do if a payment does not arrive, and how charges work.',
            'body', $doc$## Two billing sources: choose the right one

| | Balance billing | Coding Plan |
| --- | --- | --- |
| Where credits come from | Wallet balance (top-ups or redemption codes) | The request credits in a subscription |
| How you pay | Charged per actual usage | Buy a subscription with your balance, then consume its credits |
| When credits run out | Requests are rejected (`402`) | Requests are rejected (`402`); your balance is **not** charged instead |
| Best for | Occasional or unpredictable use | Steady daily use |

> The two sources are **never mixed**: a balance key never consumes subscription credits, and a Coding Plan key never charges your balance when its credits run out. Choose the purpose when you create the key, and keep daily work and temporary tests on separate keys.

## Top up your balance

Open [Top up](#/payment).

### Pay online

1. On the left, choose a **top-up amount** (in CNY) and a **payment method** (Alipay or WeChat Pay, as shown on the page).
2. The page shows **Expected credits**, **Amount due**, and **Fee rate** in real time. When they look right, select **Create order & pay**.
3. Complete payment as prompted (scan a code, or open the payment page).
4. Return to this site. When the order shows **Credited**, your **Account balance** has increased.

The transaction limits on the page state the per-order range, the daily limit, and how many unpaid orders you can keep at once.

> If the page says "Online top-ups are unavailable", you cannot pay online right now. Use a redemption code, or [submit a ticket](#/documentation/tickets).

### Redeem a code

In the **Redeem a code** panel, enter the code from an administrator and select **Redeem credits**. The credits arrive immediately. **Each code can be used once.**

Some codes activate a Coding Plan subscription directly. In that case you will see "Code redeemed. Coding Plan subscription activated."

### Paid, but your balance did not change?

1. **Do not pay again.**
2. Check the order's status in **Top-up history**. Only **Credited** means the balance has increased; for other statuses, wait a moment and refresh.
3. Unpaid orders expire automatically; you can select **Cancel order** on one you no longer want.
4. If it still has not arrived after a few minutes, [submit a ticket](#/documentation/tickets) with the order number and payment time. **Never** include payment passwords or card details.

## How balance is charged

- Before each request, the system **reserves** part of your balance. When the request finishes, it settles the actual usage and returns anything over-reserved.
- If your balance is too low, the request is rejected (`402`) **before** it reaches the model service, so nothing is charged.
- The actual cost depends on the model, input and output token usage, cache hits, and this site's billing rules. See [Models and pricing](#/documentation/models-and-pricing) for prices.

## Next steps

Want a subscription? Read [Coding Plan subscriptions](#/documentation/coding-plan). Once your credits are ready, [create an API key](#/documentation/api-keys).$doc$))),
    ('coding-plan', '945057708d3023b64f831f91a6d24b44', jsonb_build_object(
        'title', '使用 Coding Plan 订阅', 'category', '开始使用',
        'summary', '怎么开通订阅、怎么让密钥使用订阅额度，以及额度用完、续购和套餐变更的规则。', 'sortOrder', 25,
        'body', $doc$## Coding Plan 是什么

Coding Plan 是一种订阅：在**有效期**内，你可以消耗一份固定的**调用额度**，而不是每次请求都扣余额。例如“30 天，100 credits”。

> 套餐的价格、额度、有效期和适用的模型分类，请以 [Coding Plan](#/coding-plans) 页面显示的为准。

## 开通订阅

1. 打开 [Coding Plan](#/coding-plans)，在 **选择套餐** 里查看每款套餐的有效期、价格、调用额度和适用分类。
2. 点 **开通套餐**，在弹窗里确认 **应付余额**，点 **确认开通**。
3. 开通使用的是**钱包余额**。余额不足时弹窗会提示“钱包余额不足”，点 **充值余额** 先去 [余额充值](#/payment)。

开通后，**我的订阅** 会列出这份订阅的状态、额度使用进度和有效期。点 **额度明细** 可以查看每一笔额度的消耗记录；用 **订阅状态** 可以筛选。

## 让密钥使用订阅额度

订阅不会自动生效。你需要在 [API Key](#/api-keys) **新建一把密钥**，把 **计费途径** 选为 **Coding Plan**，并选择套餐适用的 **模型分类**。只有用这把密钥发出的请求，才会消耗订阅额度。

创建对话框会显示当前所选途径的可用额度；显示“暂不可用”，说明这个途径目前没有可用额度。

## 需要知道的规则

- **不会回退到余额**：订阅额度用完、过期或不适用时，这把密钥的请求会被拒绝（`402`），不会自动扣钱包。想用余额，请使用余额计费的密钥。
- **适用范围**：每款套餐只适用于指定的模型分类，其他分类的密钥不会消耗它。
- **续购会排队**：同一套餐重复购买，新的一期要等当前期到期或额度用尽后才开始。
- **版本固定**：订阅固定为你购买当时的套餐版本。套餐以后如有调整，只影响新购买的订阅，不会改变你已有订阅的额度、范围和有效期。
- **预占额度**：请求进行中会先预占一部分额度，结束后按实际用量结算。所以进行中的订阅可能短暂显示“预占”。

## 额度用完了怎么办

1. 到 [Coding Plan](#/coding-plans) 再开通一期（同一套餐会排在当前期之后）；
2. 或者改用余额计费的密钥，先去 [余额充值](#/payment)。

更多报错的含义见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Coding Plan subscriptions', 'category', 'Getting started',
            'summary', 'How to activate a plan, make a key use its credits, and the rules for exhausted credits, renewals, and plan changes.',
            'body', $doc$## What is a Coding Plan?

A Coding Plan is a subscription: during its **validity period** you can consume a fixed amount of **request credits** instead of being charged from your balance on each request. For example, "30 days, 100 credits".

> A plan's price, credits, validity, and eligible model categories are as shown on the [Coding Plan](#/coding-plans) page.

## Activate a plan

1. Open [Coding Plan](#/coding-plans) and review each plan's validity, price, request credits, and eligible categories under **Choose a plan**.
2. Select **Activate plan**, confirm the **Balance to pay** in the dialog, and select **Confirm activation**.
3. Activation is paid from your **wallet balance**. If the balance is too low, the dialog says so; select **Top-up credits** to go to [Top up](#/payment) first.

Afterwards, **My subscriptions** lists the subscription's status, credit usage, and validity. Select **Quota history** to see each credit deduction, and use **Subscription status** to filter.

## Make a key use the plan's credits

A plan does not take effect automatically. In [API Key](#/api-keys), **create a new key**, set **Billing source** to **Coding Plan**, and choose a **Model category** the plan covers. Only requests made with that key consume plan credits.

The create dialog shows the available credits for the selected source. "Unavailable" means that source currently has no available credits.

## Rules to know

- **No fallback to balance**: when plan credits run out, expire, or do not apply, requests with that key are rejected (`402`) and your wallet is not charged. To use your balance, use a balance-billing key.
- **Scope**: each plan covers specific model categories. Keys for other categories do not consume it.
- **Renewals queue**: buying the same plan again starts the new term only after the current one expires or its credits run out.
- **Pinned version**: a subscription is pinned to the plan version you bought. If the plan changes later, only new purchases are affected; your credits, scope, and validity stay the same.
- **Reserved credits**: while a request runs, part of the credits is reserved and then settled by actual usage, so an active subscription may briefly show **Reserved** credits.

## What if the credits run out?

1. Activate another term on [Coding Plan](#/coding-plans) (the same plan queues after the current term);
2. or switch to a balance-billing key after topping up on [Top up](#/payment).

See the [Error reference](#/documentation/errors) for what each error means.$doc$))),
    ('errors', 'd635d6314c6c2805422c1c8dc2249cb6', jsonb_build_object(
        'title', '错误码速查', 'category', '遇到问题',
        'summary', '按状态码查看每种报错的含义和处理办法，以及求助时该准备哪些信息。', 'sortOrder', 70,
        'body', $doc$## 先看状态码

调用出错时，先看返回的**状态码**。本站会按你所用协议的格式返回错误：OpenAI 兼容的客户端看到 OpenAI 风格的错误，Claude Code 看到 Anthropic 风格的错误，信息里通常带有状态码和说明。

| 状态码 | 含义 | 怎么办 |
| --- | --- | --- |
| `400` | 请求内容无效：缺少 `model` 或消息，或这个模型不支持你使用的接口 | 检查请求；换成模型支持的协议，或用 [客户端配置](#/api-keys) 重新生成 |
| `401` | 密钥缺失或无效 | 确认用的是本站的 API Key（不是登录密码），完整复制，没被停用 |
| `402` | 余额或订阅额度不足，请求在发往模型之前就被拒绝，**不会扣费** | 去 [余额充值](#/payment) 或 [Coding Plan](#/coding-plans) |
| `403` | 密钥的模型分类与所请求的模型不匹配（`API_KEY_MODEL_PROVIDER_FORBIDDEN`） | 换成对应分类的密钥，或换成该密钥分类下的模型 |
| `413` | 请求内容太大 | 减少一次发送的上下文，例如文件或对话历史 |
| `429` | 请求过于频繁，达到密钥的额度上限，或模型服务暂时繁忙 | 稍等片刻再试，减少并发；持续出现就看密钥的用量和上限 |
| `502` | 模型服务返回了错误 | 稍后重试；反复出现就提交工单 |
| `503` | 模型服务暂时繁忙、该模型暂不能使用，或站点正在维护 | 见下文 |

## 503 的几种情况

- **模型服务暂时繁忙**：稍等一会儿再试。持续出现就提交工单。
- **模型待定价**（`MODEL_PRICE_UNAVAILABLE`）：该模型还没有价格，不能调用。换一个模型即可。
- **站点维护**（`SYSTEM_MAINTENANCE`）：站点正在维护，请稍后再试。

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
            'summary', 'What each status code means and what to do, plus what to prepare when asking for help.',
            'body', $doc$## Start with the status code

When a call fails, look at the returned **status code**. This site returns errors in the format of the protocol you use: OpenAI-compatible clients see OpenAI-style errors, and Claude Code sees Anthropic-style errors. The message usually includes a status code and a description.

| Status | Meaning | What to do |
| --- | --- | --- |
| `400` | Invalid request: `model` or messages are missing, or the model does not support the endpoint you use | Check the request; use a protocol the model supports, or regenerate with [Client configuration](#/api-keys) |
| `401` | The key is missing or invalid | Make sure you use this site's API key (not your sign-in password), copied in full and not disabled |
| `402` | Insufficient balance or plan credits; rejected before reaching the model, so **nothing is charged** | Go to [Top up](#/payment) or [Coding Plan](#/coding-plans) |
| `403` | The key's model category does not match the requested model (`API_KEY_MODEL_PROVIDER_FORBIDDEN`) | Use a key in the right category, or a model in this key's category |
| `413` | The request is too large | Send less context at once, such as fewer files or less history |
| `429` | Too many requests, the key's spending limit was reached, or the model service is briefly busy | Wait a moment and retry with less concurrency; if it persists, check the key's usage and limit |
| `502` | The model service returned an error | Retry later; submit a ticket if it keeps happening |
| `503` | The model service is busy, the model cannot be used right now, or the site is under maintenance | See below |

## The kinds of 503

- **Model service busy**: wait a moment and retry. Submit a ticket if it persists.
- **Unpriced model** (`MODEL_PRICE_UNAVAILABLE`): the model has no price yet and cannot be called. Choose another model.
- **Site maintenance** (`SYSTEM_MAINTENANCE`): the site is under maintenance. Please try again later.

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
    ('faq', 'de2f04ff4a0c72111c94f41c0709b1ab', jsonb_build_object(
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

先看这把密钥的**计费途径**。余额计费的密钥需要钱包里有余额；Coding Plan 计费的密钥需要有效的订阅、剩余额度和适用的模型分类。两者**不会**互相兜底。见 [充值与计费](#/documentation/billing) 和 [使用 Coding Plan](#/documentation/coding-plan)。

### 付款了，余额没变

不要重复付款。到 [余额充值](#/payment) 的 **充值历史记录** 看订单状态，等几分钟。仍然没到账就提交工单，附上订单号和付款时间。

### 为什么实际扣费和我估算的不一样

实际费用取决于模型价格、输入和输出的 token 量、缓存命中情况以及本站的计费规则。页面价格用于估算，实际扣费以请求结束后的结算为准。

## 模型

### 模型不存在，或者不支持

从 [模型 ID](#/models) 页复制完整的 ID，确认模型的厂商和密钥的 **模型分类** 一致。也可能是客户端使用的协议不适合该模型，这时请用 **客户端配置** 重新生成。

### 提示 403

通常是密钥的模型分类和所请求的模型不匹配。例如用 OpenAI 分类的密钥去请求 `claude-*`。为需要的每一家各建一把密钥。

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

Check the key's **billing source** first. A balance-billing key needs wallet balance; a Coding Plan key needs an active subscription with remaining credits and a matching model category. The two **never** cover for each other. See [Top-ups and billing](#/documentation/billing) and [Coding Plan subscriptions](#/documentation/coding-plan).

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

Before submitting, **hide your full API key**, the key inside import links, and sensitive payment details.$doc$))),
    ('tickets', '2f1ae73b044d575c20b142ddf8d3aaf6', jsonb_build_object(
        'title', '如何提交工单', 'category', '遇到问题',
        'summary', '新建工单的步骤和限制、状态的含义、怎么补充回复与关闭，以及怎样写出好懂的工单。', 'sortOrder', 90,
        'body', $doc$## 什么时候该提交工单

文档和 [常见问题](#/documentation/faq) 解决不了的问题，都可以提交工单，例如付款后没到账、模型调用一直失败、需要管理员开通或调整某些内容。

## 提交一张工单

1. 打开 [我的工单](#/tickets)，点 **新建工单**。
2. 填写：

| 字段 | 说明 |
| --- | --- |
| 标题 | 最多 160 字，用一句话说清问题 |
| 问题分类 | 一般咨询、API 调用、计费与支付、账号问题、问题反馈，选最接近的 |
| 问题描述 | 最多 20000 字，按**纯文本**显示。写清发生时间、相关请求和错误信息 |
| 附件 | 最多 3 个，每个不超过 2 MB，不能是空文件。适合放截图和日志 |

3. 点 **提交工单**。

> 提交失败时，你填的内容和待上传的附件都会保留，不用重写。

## 写出好懂的工单

| 要写的 | 举例 |
| --- | --- |
| 发生了什么 | 调用返回 `402`，但我刚充值过 |
| 什么时候 | 今天 14:20 左右 |
| 用的什么 | Claude Code，模型 `claude-opus-4.8` |
| 错误信息 | 完整的状态码和报错文字 |
| 已经试过 | 重新复制了密钥，重启了客户端 |

**不要**在工单里写完整的 API Key、登录密码或支付密码。截图里也要先遮住。

## 查看回复、补充问题

在 [我的工单](#/tickets) 打开工单，**沟通记录** 按时间显示你和管理员的每次回复，附件可以下载。要继续追问，在 **补充回复** 里写内容，点 **发送回复**。

## 状态的含义

| 状态 | 说明 |
| --- | --- |
| 待处理 | 工单已提交，或者你刚刚补充了回复，等管理员处理 |
| 处理中 | 管理员正在处理 |
| 等待用户 | 管理员已回复，**轮到你**回复了 |
| 已解决 | 管理员确认问题已经解决 |
| 已关闭 | 工单已结束 |

问题解决后，可以点 **关闭工单**。已解决或已关闭的工单要继续沟通，需要先点 **重新打开工单**。

## 注意

- 工单只有提交人和管理员能看到；
- 编辑回复时离开页面，浏览器会提醒你确认。**没发送的内容不会被保存**，刷新页面后会丢失。$doc$,
        'english', jsonb_build_object(
            'title', 'Submit a support ticket', 'category', 'Troubleshooting',
            'summary', 'How to create a ticket and its limits, what each status means, how to reply and close, and how to write a clear ticket.',
            'body', $doc$## When to open a ticket

Open a ticket for anything the documentation and the [FAQ](#/documentation/faq) cannot solve, such as a payment that did not arrive, model calls that keep failing, or something an administrator must enable or adjust.

## Submit a ticket

1. Open [My tickets](#/tickets) and select **New ticket**.
2. Fill in:

| Field | Notes |
| --- | --- |
| Title | Up to 160 characters; state the problem in one sentence |
| Category | General inquiry, API requests, Billing & payments, Account issues, or Bug report; pick the closest |
| Description | Up to 20,000 characters, shown as **plain text**. Include when it happened, the related requests, and error details |
| Attachments | Up to 3, each no larger than 2 MB and not empty. Good for screenshots and logs |

3. Select **Submit ticket**.

> If submission fails, your text and pending attachments are kept, so you do not need to retype them.

## Write a clear ticket

| Include | Example |
| --- | --- |
| What happened | A call returned `402` right after I topped up |
| When | Around 14:20 today |
| What you used | Claude Code with model `claude-opus-4.8` |
| The error | The full status code and error text |
| What you tried | Copied the key again and restarted the client |

**Never** write a full API key, sign-in password, or payment password in a ticket. Cover them in screenshots too.

## View replies and add details

Open the ticket in [My tickets](#/tickets). The **Conversation** shows each reply from you and the administrator in order, and attachments can be downloaded. To follow up, write in **Add reply** and select **Send reply**.

## What the statuses mean

| Status | Meaning |
| --- | --- |
| Pending | The ticket was submitted, or you just added a reply, and it awaits an administrator |
| In progress | An administrator is working on it |
| Awaiting reply | An administrator replied, and it is **your turn** |
| Resolved | An administrator confirmed it is solved |
| Closed | The ticket has ended |

When your problem is solved, select **Close ticket**. To keep talking on a resolved or closed ticket, select **Reopen ticket** first.

## Good to know

- Only you and administrators can see a ticket;
- If you leave while editing a reply, the browser asks you to confirm. **Unsent text is not saved** and is lost when you refresh.$doc$)))
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
