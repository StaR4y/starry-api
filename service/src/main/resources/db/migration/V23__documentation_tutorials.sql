-- Expand the default tutorials (Chinese + English).
-- The six original tutorials are upgraded only while they are still the untouched seeds from V21/V22
-- (version 2, draft identical to the publication, English present). Edited, unpublished or deleted
-- tutorials are never replaced. The remaining tutorials are new and are inserted once.
WITH tutorials(id, content) AS (VALUES
    ('getting-started', jsonb_build_object(
        'title', '第一次使用，从这里开始', 'category', '开始使用',
        'summary', '5 步跑通：登录改密、准备额度、创建密钥、接入客户端、发出第一个请求。', 'sortOrder', 10,
        'body', $doc$## 先看全貌

从拿到账号到发出第一个请求，一般 10 分钟左右。一共 5 步：

| 步骤 | 做什么 | 去哪里 |
| --- | --- | --- |
| 1 | 登录；管理员帮你创建的账号要先修改初始密码 | 登录页 |
| 2 | 准备额度：充值余额，或购买 Coding Plan | [余额充值](#/payment)、[Coding Plan](#/coding-plans) |
| 3 | 创建一把 API Key | [API Key](#/api-keys) |
| 4 | 把密钥接入你的客户端 | [CC Switch 一键接入](#/documentation/cc-switch) |
| 5 | 发出第一个请求，并确认用量 | 你的客户端、[数据统计](#/welcome) |

## 1. 登录并修改密码

用管理员提供的用户名和密码登录。管理员创建的账号**第一次登录会被要求先修改密码**，新密码长度为 **12–128 个字符**。改完后重新进入控制台即可。

以后想再改密码：点右上角的头像，选择 **修改密码**。

> 站点开放了注册的话，也可以在登录页自行注册。

## 2. 准备额度

调用模型需要有额度。两种方式任选其一，也可以都用：

- **余额**：在 [余额充值](#/payment) 在线支付，或输入管理员发的兑换码。适合按量付费、偶尔使用。
- **Coding Plan**：在 [Coding Plan](#/coding-plans) 用余额购买一个带额度、有有效期的订阅。适合每天稳定使用。

两者的区别见 [充值与计费](#/documentation/billing)。

## 3. 创建 API Key

打开 [API Key](#/api-keys)，点 **新建 API Key**，填写：

- **名称**：给自己看的，例如“笔记本 Claude Code”；
- **计费途径**：**余额计费** 或 **Coding Plan**，对应上一步准备的额度；
- **模型分类**：你要使用哪一家的模型，例如 OpenAI、Anthropic。**一把密钥只能调用一个分类下的模型**。

创建后会出现 **保存你的新密钥**，里面是完整的 `sk-…` 密钥。复制保存好再关闭；之后也可以随时在列表里点 **复制密钥**。详见 [创建与管理 API Key](#/documentation/api-keys)。

## 4. 接入客户端

推荐用 [CC Switch](#/documentation/cc-switch) 一键导入：在 API Key 列表里点该密钥的 **客户端配置**，在 **CC Switch 一键连接** 里点 **打开**。

想手动配置，按你使用的工具选择：

- [Claude Code](#/documentation/claude-code)
- [Codex CLI](#/documentation/codex-cli)
- [OpenCode](#/documentation/opencode)
- [其他应用或命令行](#/documentation/manual-api)

## 5. 发出第一个请求

重新打开客户端，选一个本站提供的模型（在 [模型 ID](#/models) 查看，并且要和密钥的模型分类一致），发一句“你好”。

成功后回到 [数据统计](#/welcome)，**累计调用** 会增加。

## 出问题了？

- 先对照 [错误码速查](#/documentation/errors) 和 [常见问题](#/documentation/faq)；
- 仍然解决不了，按 [如何提交工单](#/documentation/tickets) 联系管理员。$doc$,
        'english', jsonb_build_object(
            'title', 'Start here', 'category', 'Getting started',
            'summary', 'Five steps: sign in, add credits, create a key, connect a client, and make your first request.',
            'body', $doc$## The big picture

From receiving your account to making your first request usually takes about 10 minutes. There are five steps:

| Step | What to do | Where |
| --- | --- | --- |
| 1 | Sign in. If an administrator created your account, change the initial password first | Sign-in page |
| 2 | Add credits: top up your balance or buy a Coding Plan | [Top up](#/payment), [Coding Plan](#/coding-plans) |
| 3 | Create an API key | [API Key](#/api-keys) |
| 4 | Connect the key to your client | [CC Switch setup](#/documentation/cc-switch) |
| 5 | Make your first request and check usage | Your client, [Statistics](#/welcome) |

## 1. Sign in and change your password

Sign in with the username and password from your administrator. An account created by an administrator **must change its password on first sign-in**; the new password must be **12–128 characters**. Afterwards, open the console again.

To change your password later, click your avatar in the top right and choose **Change password**.

> If the site allows registration, you can also register on the sign-in page.

## 2. Add credits

You need credits to call models. Choose either method, or use both:

- **Balance**: pay online on [Top up](#/payment), or enter a redemption code from an administrator. Good for pay-as-you-go use.
- **Coding Plan**: buy a subscription with a credit quota and validity period on [Coding Plan](#/coding-plans). Good for steady daily use.

See [Top-ups and billing](#/documentation/billing) for the difference.

## 3. Create an API key

Open [API Key](#/api-keys), select **Create API key**, and fill in:

- **Name**: for your own reference, such as "Laptop Claude Code";
- **Billing source**: **Balance billing** or **Coding Plan**, matching the credits you prepared;
- **Model category**: which provider's models you want, such as OpenAI or Anthropic. **One key can call models from only one category.**

After creation, **Save your new key** shows the full `sk-…` key. Copy it somewhere safe before closing; you can also select **Copy key** in the list later. See [Create and manage API keys](#/documentation/api-keys).

## 4. Connect a client

We recommend importing with [CC Switch](#/documentation/cc-switch): in the API Key list, select **Client configuration** for the key, then select **Open** under **Connect with CC Switch**.

To configure manually, pick your tool:

- [Claude Code](#/documentation/claude-code)
- [Codex CLI](#/documentation/codex-cli)
- [OpenCode](#/documentation/opencode)
- [Other apps or the command line](#/documentation/manual-api)

## 5. Make your first request

Restart your client, choose a model offered by this site (see [Model IDs](#/models); it must match your key's model category), and send "Hello".

Then return to [Statistics](#/welcome). **Total requests** should increase.

## Something went wrong?

- Check the [Error reference](#/documentation/errors) and the [FAQ](#/documentation/faq);
- If you are still stuck, follow [Submit a support ticket](#/documentation/tickets) to contact an administrator.$doc$))),
    ('console-tour', jsonb_build_object(
        'title', '认识控制台', 'category', '开始使用',
        'summary', '左侧菜单每一项是做什么的、数据统计页怎么读，以及哪些页面是管理员专属。', 'sortOrder', 15,
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
| [我的工单](#/tickets) | 向管理员提问、跟进处理进度 |

## 顶部栏

- **语言选择**：在简体中文和 English 之间切换。使用文档也有对应的英文版（管理员提供了英文版的文章才会切换）。
- **余额**：你当前的钱包余额。
- **头像菜单**：**修改密码**、**退出登录**。

## 数据统计怎么读

登录后的首页显示：

- **当前余额**，以及最近一次使用的时间；
- **API Key 密钥**：已启用的密钥数量；
- **累计调用**：一共发出了多少次请求；
- **已用 Token / 累计消耗**：用掉的 token 总量和累计消耗的额度；
- **模型使用情况**：每个模型被调用的次数和最近使用时间。

点页面上的 **刷新** 可以获取最新数据。

## 看不到某些页面？

账号管理、请求日志、用户管理、系统设置等页面是**管理员专属**，普通用户的菜单里没有它们，这是正常的。

如果你需要某次请求的详细记录，可以在 [我的工单](#/tickets) 里说明时间、模型和现象，由管理员协助查询。$doc$,
        'english', jsonb_build_object(
            'title', 'Tour of the console', 'category', 'Getting started',
            'summary', 'What each menu item does, how to read the Statistics page, and which pages are for administrators only.',
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
| [My tickets](#/tickets) | Ask administrators questions and follow progress |

## Top bar

- **Language selector**: switch between 简体中文 and English. Articles switch only when an administrator has provided an English version.
- **Balance**: your current wallet balance.
- **Avatar menu**: **Change password** and **Sign out**.

## Reading the Statistics page

Your home page shows:

- **Current balance** and the time of your latest usage;
- **API Key**: how many keys are enabled;
- **Total requests**: how many requests you have made;
- **Tokens / total credits used**: the total tokens and credits consumed;
- **Model usage**: how many times each model was called and when it was last used.

Select **Refresh** to load the latest figures.

## Missing pages?

Account management, request logs, user management, and system settings are **administrator-only**. They do not appear in a regular user's menu, and that is expected.

If you need details about a specific request, open a ticket in [My tickets](#/tickets) with the time, model, and symptoms, and an administrator can help look it up.$doc$))),
    ('billing', jsonb_build_object(
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

> 在线充值需要管理员先开通。页面提示“当前未开放在线充值”时，请联系管理员，或使用兑换码。

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

> An administrator must enable online payment first. If the page says "Online top-ups are unavailable", contact an administrator or use a redemption code.

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
    ('coding-plan', jsonb_build_object(
        'title', '使用 Coding Plan 订阅', 'category', '开始使用',
        'summary', '怎么开通订阅、怎么让密钥使用订阅额度，以及额度用完、续购和套餐变更的规则。', 'sortOrder', 25,
        'body', $doc$## Coding Plan 是什么

Coding Plan 是一种订阅：在**有效期**内，你可以消耗一份固定的**调用额度**，而不是每次请求都扣余额。例如“30 天，100 credits”。

> 套餐的价格、额度、有效期和适用的模型分类由管理员设定，请以 [Coding Plan](#/coding-plans) 页面显示的为准。

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
- **版本固定**：订阅固定为你购买当时的套餐版本。管理员以后调整套餐，只影响新购买的订阅，不会改变你已有订阅的额度、范围和有效期。
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

> A plan's price, credits, validity, and eligible model categories are set by administrators. The [Coding Plan](#/coding-plans) page is authoritative.

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
- **Pinned version**: a subscription is pinned to the plan version you bought. If administrators change the plan later, only new purchases are affected; your credits, scope, and validity stay the same.
- **Reserved credits**: while a request runs, part of the credits is reserved and then settled by actual usage, so an active subscription may briefly show **Reserved** credits.

## What if the credits run out?

1. Activate another term on [Coding Plan](#/coding-plans) (the same plan queues after the current term);
2. or switch to a balance-billing key after topping up on [Top up](#/payment).

See the [Error reference](#/documentation/errors) for what each error means.$doc$))),
    ('api-keys', jsonb_build_object(
        'title', '创建与管理 API Key', 'category', '开始使用',
        'summary', '创建密钥时每个选项的含义、模型分类的限制、列表里的常用操作，以及保管密钥的习惯。', 'sortOrder', 30,
        'body', $doc$## 密钥是什么

API Key 是客户端调用本站时出示的凭据，以 `sk-` 开头。谁拿到密钥，谁就能花你的额度，所以请像对待密码一样保管它。

## 创建一把密钥

1. 打开 [API Key](#/api-keys)，点 **新建 API Key**。
2. 填写：

| 字段 | 怎么填 |
| --- | --- |
| 名称 | 给自己看的，如“笔记本 Claude Code” |
| 计费途径 | **余额计费**：只扣钱包余额，余额不足时停止调用。**Coding Plan**：只扣订阅额度，额度不足时停止调用。对话框会显示当前所选途径的可用额度，显示“暂不可用”表示这个途径目前没有可用额度 |
| 模型分类 | 这把密钥能调用哪一家的模型，**必选** |
| 累计额度上限（可选） | 限制这把密钥累计最多能用多少额度，留空表示不限制。发给脚本或同事时很有用，可以防止用超 |

3. 点 **创建密钥**。随后会出现 **保存你的新密钥**，包含完整的 `sk-…` 密钥、Base URL 和各客户端的配置。复制保存好，再点 **已妥善保存**。

之后你随时可以在列表里点 **复制密钥** 找回完整密钥。

> 很早以前（旧版本）创建的密钥只保存了前缀，找不回完整值。这样的密钥请重新创建。

## 模型分类：一把密钥一个厂商

密钥绑定一个模型分类，只能调用该分类下的模型。比如绑定“OpenAI”的密钥去请求 `claude-*` 模型，会返回 `403`（`API_KEY_MODEL_PROVIDER_FORBIDDEN`）。想同时用多家，就为每一家各建一把密钥。

怎么选：

- Claude Code 默认用 Claude 模型，通常配 **Anthropic** 分类的密钥；
- Codex CLI 默认用 GPT 模型，通常配 **OpenAI** 分类的密钥；
- OpenCode 取决于你要用的模型。

## 列表里的操作

列表显示密钥前缀、计费途径、模型分类、状态、**用量 / 上限** 和最近使用时间。

| 操作 | 作用 |
| --- | --- |
| 复制 API 地址 | 复制 Base URL（以 `/v1` 结尾），填到 OpenAI 兼容的客户端 |
| 复制密钥 | 复制完整密钥 |
| 客户端配置 | 打开连接信息和各客户端的现成配置，包含 **CC Switch 一键连接** |
| 编辑 | 修改这把密钥的设置 |
| 更多操作 → 停用 / 启用 | 暂停或恢复密钥，不会删除 |
| 更多操作 → 删除 | 永久删除，使用该密钥的客户端立刻失效 |

## 好习惯

- 每台设备、每个应用一把密钥，如“笔记本开发”“CI 脚本”；
- 不再使用就**停用**或删除；
- 不要把密钥提交到 Git、发到群聊，或贴进截图和工单；
- 怀疑泄露：马上停用，再新建一把替换，并更新所有用过旧密钥的客户端。

## 下一步

[用 CC Switch 一键接入客户端](#/documentation/cc-switch)，或者 [手动配置](#/documentation/manual-api)。$doc$,
        'english', jsonb_build_object(
            'title', 'Create and manage API keys', 'category', 'Getting started',
            'summary', 'What each option means when creating a key, the model-category limit, common list actions, and habits for keeping keys safe.',
            'body', $doc$## What a key is

An API key is the credential your client presents when calling this site. It starts with `sk-`. Whoever holds the key can spend your credits, so treat it like a password.

## Create a key

1. Open [API Key](#/api-keys) and select **Create API key**.
2. Fill in:

| Field | What to enter |
| --- | --- |
| Name | For your own reference, such as "Laptop Claude Code" |
| Billing source | **Balance billing** uses only your wallet balance and stops when it runs out. **Coding Plan** uses only plan credits and stops when they run out. The dialog shows the available credits for the selected source; "Unavailable" means that source currently has none |
| Model category | Which provider's models the key can call. **Required** |
| Total spending limit (optional) | Caps the total credits the key can use. Leave blank for no limit. Useful for scripts or colleagues, to prevent overspending |

3. Select **Create key**. **Save your new key** then shows the full `sk-…` key, the Base URL, and client configurations. Copy and store them, then select **Saved securely**.

You can retrieve the full key at any time with **Copy key** in the list.

> Keys created long ago, in earlier versions, stored only a prefix and their full value cannot be recovered. Create a new key instead.

## Model category: one provider per key

A key is bound to one model category and can call only models in it. For example, a key bound to OpenAI that requests a `claude-*` model gets `403` (`API_KEY_MODEL_PROVIDER_FORBIDDEN`). To use several providers, create one key per provider.

How to choose:

- Claude Code uses Claude models by default, so it usually needs a key in the **Anthropic** category;
- Codex CLI uses GPT models by default, so it usually needs a key in the **OpenAI** category;
- OpenCode depends on the models you want.

## Actions in the list

The list shows the key prefix, billing source, model category, status, **Usage / Limit**, and last used time.

| Action | What it does |
| --- | --- |
| Copy API URL | Copies the Base URL (ending in `/v1`) for OpenAI-compatible clients |
| Copy key | Copies the full key |
| Client configuration | Opens connection details and ready-made client configurations, including **Connect with CC Switch** |
| Edit | Changes the key's settings |
| More actions → Disable / Enable | Pauses or resumes a key without deleting it |
| More actions → Delete | Deletes it permanently; clients using it stop working immediately |

## Good habits

- One key per device or app, such as "Laptop development" or "CI script";
- **Disable** or delete keys you no longer use;
- Never commit keys to Git, post them in chats, or include them in screenshots or tickets;
- If you suspect a leak, disable the key immediately, create a replacement, and update every client that used the old one.

## Next steps

[Connect a client with CC Switch](#/documentation/cc-switch), or [configure it manually](#/documentation/manual-api).$doc$))),
    ('models-and-pricing', jsonb_build_object(
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
- 实际费用取决于模型、实际的输入和输出 token 量、是否命中缓存，以及本站的计费规则（管理员可能对某个厂商、系列或具体模型设置了不同的倍率）；
- 页面上的价格用于估算，**实际扣费以请求结束后的结算为准**。

余额和订阅的区别见 [充值与计费](#/documentation/billing)。

## 标记为“待定价”的模型

**待定价** 的模型暂时没有价格，**调用会被拒绝**（`503`，`MODEL_PRICE_UNAVAILABLE`），并且不会扣费。请换一个模型，或者联系管理员为它定价。

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
- The actual cost depends on the model, the actual input and output tokens, cache hits, and this site's billing rules (administrators may set different multipliers for a provider, series, or specific model);
- Prices on the page are for estimating; **the actual charge is the settlement after each request**.

For balance versus subscription, see [Top-ups and billing](#/documentation/billing).

## Models marked "Unpriced"

An **Unpriced** model has no price yet, so **calls are rejected** (`503`, `MODEL_PRICE_UNAVAILABLE`) and nothing is charged. Choose another model, or ask an administrator to price it.

## Model rankings

[Model rankings](#/model-ranking) shows public benchmarks such as ProgramBench and FrontierBench, updated daily, to help you gauge how leading models compare. Not every ranked model is offered here; rely on [Model IDs](#/models).$doc$))),
    ('cc-switch', jsonb_build_object(
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

- 本站地址能否从你的电脑访问？如果服务部署在远程，不能使用服务器上的 `127.0.0.1`，要用你访问本站的域名；
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

- Can your computer reach this site? If the service is hosted remotely, you cannot use the server's `127.0.0.1`; use the domain you use to open this site;
- Is the key valid and not disabled? Does its billing source have available credits?
- Does the client's model belong to the key's model category?

Work through the [Error reference](#/documentation/errors), or see the client guides for [Claude Code](#/documentation/claude-code), [Codex CLI](#/documentation/codex-cli), and [OpenCode](#/documentation/opencode).$doc$))),
    ('claude-code', jsonb_build_object(
        'title', '手动配置 Claude Code', 'category', '连接客户端',
        'summary', '用环境变量把 Claude Code 连到本站：怎么复制现成配置、每个变量的含义，以及常见错误。', 'sortOrder', 45,
        'body', $doc$## 开始之前

- 已安装 Claude Code；
- 有一把 **Anthropic** 模型分类的 API Key（见 [创建与管理 API Key](#/documentation/api-keys)）；
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

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| `401` | 密钥复制不完整、带了多余空格，或密钥被停用。重新复制完整密钥 |
| `403` | 密钥的模型分类不是 Anthropic，或模型 ID 不属于该分类。换成 Anthropic 分类的密钥 |
| 路径不对、`404` | `ANTHROPIC_BASE_URL` 多写了 `/v1`，请去掉 |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |
| 想换模型 | 把 `ANTHROPIC_MODEL` 改成 [模型 ID](#/models) 页里的其他 Claude 模型 ID |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up Claude Code', 'category', 'Connect a client',
            'summary', 'Connect Claude Code to this site with environment variables: copy a ready-made configuration, what each variable means, and common errors.',
            'body', $doc$## Before you start

- Claude Code is installed;
- You have an API key in the **Anthropic** model category (see [Create and manage API keys](#/documentation/api-keys));
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

## Common problems

| Symptom | Cause and fix |
| --- | --- |
| `401` | The key was copied incompletely, has extra spaces, or is disabled. Copy the full key again |
| `403` | The key's model category is not Anthropic, or the model ID is outside that category. Use a key in the Anthropic category |
| Wrong path, `404` | `ANTHROPIC_BASE_URL` includes `/v1`; remove it |
| `402` | Not enough balance or plan credits; see [Top-ups and billing](#/documentation/billing) |
| Want another model | Change `ANTHROPIC_MODEL` to another Claude model ID from [Model IDs](#/models) |

See the [Error reference](#/documentation/errors) for more.$doc$))),
    ('codex-cli', jsonb_build_object(
        'title', '手动配置 Codex CLI', 'category', '连接客户端',
        'summary', '编辑 config.toml 和 auth.json 把 Codex CLI 连到本站，以及 refresh token 报错的处理。', 'sortOrder', 50,
        'body', $doc$## 开始之前

- 已安装 Codex CLI；
- 有一把 **OpenAI** 模型分类的 API Key（见 [创建与管理 API Key](#/documentation/api-keys)）；
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

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| `refresh token was already used` | 本机 `auth.json` 里还留着旧的 ChatGPT 授权。备份后换成上面的最简内容，或先运行 `codex logout` |
| `401` | 密钥复制不完整，或被停用 |
| `403` | 密钥的模型分类不是 OpenAI，或模型不属于该分类 |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |
| 改了配置没生效 | 重新打开终端和 Codex CLI；确认修改的是用户目录下的文件 |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up Codex CLI', 'category', 'Connect a client',
            'summary', 'Edit config.toml and auth.json to connect Codex CLI to this site, and how to fix refresh-token errors.',
            'body', $doc$## Before you start

- Codex CLI is installed;
- You have an API key in the **OpenAI** model category (see [Create and manage API keys](#/documentation/api-keys));
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

## Common problems

| Symptom | Cause and fix |
| --- | --- |
| `refresh token was already used` | An old ChatGPT sign-in remains in `auth.json`. Back it up and replace it with the minimal content above, or run `codex logout` first |
| `401` | The key was copied incompletely or is disabled |
| `403` | The key's model category is not OpenAI, or the model is outside that category |
| `402` | Not enough balance or plan credits; see [Top-ups and billing](#/documentation/billing) |
| Changes have no effect | Restart your terminal and Codex CLI; confirm you edited the files in your user directory |

See the [Error reference](#/documentation/errors) for more.$doc$))),
    ('opencode', jsonb_build_object(
        'title', '手动配置 OpenCode', 'category', '连接客户端',
        'summary', '在项目根目录放一个 opencode.json，用 OpenAI 兼容方式把 OpenCode 连到本站。', 'sortOrder', 55,
        'body', $doc$## 开始之前

- 已安装 OpenCode；
- 有一把 API Key，且它的 **模型分类** 包含你要用的模型（见 [创建与管理 API Key](#/documentation/api-keys)）；
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

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| 列表里没有模型 | `models` 里没有列出该模型，或 `provider` 的名字和 `model` 里写的不一致 |
| `401` | 密钥复制不完整，或被停用 |
| `403` | 密钥的模型分类不包含该模型。例如密钥是 OpenAI 分类，却选了 `claude-*` |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up OpenCode', 'category', 'Connect a client',
            'summary', 'Put an opencode.json in your project root to connect OpenCode to this site as an OpenAI-compatible provider.',
            'body', $doc$## Before you start

- OpenCode is installed;
- You have an API key whose **Model category** covers the models you want (see [Create and manage API keys](#/documentation/api-keys));
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

## Common problems

| Symptom | Cause and fix |
| --- | --- |
| No models in the list | The model is not listed under `models`, or the `provider` name does not match the one used in `model` |
| `401` | The key was copied incompletely or is disabled |
| `403` | The key's model category does not include the model, for example an OpenAI-category key with a `claude-*` model |
| `402` | Not enough balance or plan credits; see [Top-ups and billing](#/documentation/billing) |

See the [Error reference](#/documentation/errors) for more.$doc$))),
    ('manual-api', jsonb_build_object(
        'title', '手动接入 API', 'category', '连接客户端',
        'summary', '为其他应用配置地址和密钥，用命令行或 Python 直接调用，并了解各接口的路径和认证方式。', 'sortOrder', 60,
        'body', $doc$## 准备地址与密钥

本站地址：

```text
{{site_url}}
```

OpenAI 兼容接口的 Base URL（带 `/v1`）：

```text
{{api_base_url}}
```

在应用的 API Key 一栏填写本站生成的**完整密钥**，模型名称从 [模型 ID](#/models) 复制。

> 有的应用会自动在地址后追加 `/v1`。如果出现 404，检查是不是写成了 `/v1/v1`。

## 各接口的地址

| 协议 | 路径 | 适用 |
| --- | --- | --- |
| OpenAI Chat Completions | `{{api_base_url}}/chat/completions` | 大多数 OpenAI 兼容应用 |
| OpenAI Responses | `{{api_base_url}}/responses` | Codex CLI 等 |
| Anthropic Messages | `{{site_url}}/v1/messages` | Claude Code 等 Anthropic 客户端 |
| 模型列表 | `{{api_base_url}}/models` | 查看可用模型 |

Claude Code、Codex CLI 和 OpenCode 建议直接使用 [API Key](#/api-keys) 页的 **客户端配置**，那里会按客户端区分协议和地址。

## 认证方式

任选一种请求头：

```http
Authorization: Bearer YOUR_API_KEY
X-Api-Key: YOUR_API_KEY
```

## 用命令行查看模型

以下示例适用于 macOS、Linux 或其他 Bash 终端。Windows 可以在 WSL 或 Git Bash 中执行。

```bash
export STARRY_API_KEY='YOUR_API_KEY'
curl '{{api_base_url}}/models' \
  -H "Authorization: Bearer $STARRY_API_KEY"
```

## 发起一个对话请求

把 `MODEL_ID` 换成你的密钥可以使用的模型 ID。下面演示 OpenAI Chat Completions：

```bash
curl '{{api_base_url}}/chat/completions' \
  -H "Authorization: Bearer $STARRY_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "MODEL_ID",
    "messages": [{"role": "user", "content": "你好"}],
    "stream": false
  }'
```

只支持 Anthropic Messages 的模型和客户端，用下面的写法：

```bash
curl '{{site_url}}/v1/messages' \
  -H "x-api-key: $STARRY_API_KEY" \
  -H 'anthropic-version: 2023-06-01' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "MODEL_ID",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": [{"type": "text", "text": "你好"}]}]
  }'
```

## 在 Python 中调用

先安装官方 OpenAI Python SDK：

```bash
python -m pip install openai
```

设置好上面的 `STARRY_API_KEY` 环境变量后运行：

```python
import os
from openai import OpenAI

client = OpenAI(
    api_key=os.environ["STARRY_API_KEY"],
    base_url="{{api_base_url}}",
)
result = client.chat.completions.create(
    model="MODEL_ID",
    messages=[{"role": "user", "content": "你好"}],
)
print(result.choices[0].message.content)
```

> 请不要把真实密钥直接写进项目代码。测试请求也会产生实际用量。

## 出错了？

对照 [错误码速查](#/documentation/errors)。最常见的是密钥无效（`401`）、额度不足（`402`）和模型分类不匹配（`403`）。$doc$,
        'english', jsonb_build_object(
            'title', 'Manual API setup', 'category', 'Connect a client',
            'summary', 'Configure the address and key in other apps, call the API from the command line or Python, and learn each endpoint and its authentication.',
            'body', $doc$## Prepare the address and key

This site's address:

```text
{{site_url}}
```

The Base URL for OpenAI-compatible APIs (with `/v1`):

```text
{{api_base_url}}
```

Enter the **full key** generated on this site in the app's API Key field, and copy model names from [Model IDs](#/models).

> Some apps append `/v1` automatically. If you see 404, check that you did not end up with `/v1/v1`.

## Endpoint addresses

| Protocol | Path | Used by |
| --- | --- | --- |
| OpenAI Chat Completions | `{{api_base_url}}/chat/completions` | Most OpenAI-compatible apps |
| OpenAI Responses | `{{api_base_url}}/responses` | Codex CLI and others |
| Anthropic Messages | `{{site_url}}/v1/messages` | Claude Code and other Anthropic clients |
| Model list | `{{api_base_url}}/models` | See available models |

For Claude Code, Codex CLI, and OpenCode, we recommend **Client configuration** on the [API Key](#/api-keys) page, which sets the protocol and address for each client.

## Authentication

Use either request header:

```http
Authorization: Bearer YOUR_API_KEY
X-Api-Key: YOUR_API_KEY
```

## List models from the command line

These examples work on macOS, Linux, or any Bash terminal. On Windows, use WSL or Git Bash.

```bash
export STARRY_API_KEY='YOUR_API_KEY'
curl '{{api_base_url}}/models' \
  -H "Authorization: Bearer $STARRY_API_KEY"
```

## Send a chat request

Replace `MODEL_ID` with a model ID your key can use. This example uses OpenAI Chat Completions:

```bash
curl '{{api_base_url}}/chat/completions' \
  -H "Authorization: Bearer $STARRY_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "MODEL_ID",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": false
  }'
```

For models and clients that use only Anthropic Messages, use this form:

```bash
curl '{{site_url}}/v1/messages' \
  -H "x-api-key: $STARRY_API_KEY" \
  -H 'anthropic-version: 2023-06-01' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "MODEL_ID",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}]
  }'
```

## Call it from Python

Install the official OpenAI Python SDK first:

```bash
python -m pip install openai
```

Set the `STARRY_API_KEY` environment variable above, then run:

```python
import os
from openai import OpenAI

client = OpenAI(
    api_key=os.environ["STARRY_API_KEY"],
    base_url="{{api_base_url}}",
)
result = client.chat.completions.create(
    model="MODEL_ID",
    messages=[{"role": "user", "content": "Hello"}],
)
print(result.choices[0].message.content)
```

> Do not write a real key directly into project code. Test requests also create real usage.

## Hit an error?

See the [Error reference](#/documentation/errors). The most common are an invalid key (`401`), insufficient credits (`402`), and a mismatched model category (`403`).$doc$))),
    ('errors', jsonb_build_object(
        'title', '错误码速查', 'category', '遇到问题',
        'summary', '按状态码查看每种报错的含义和处理办法，以及向管理员求助时该准备哪些信息。', 'sortOrder', 70,
        'body', $doc$## 先看状态码

调用出错时，先看返回的**状态码**。本站会按你所用协议的格式返回错误：OpenAI 兼容的客户端看到 OpenAI 风格的错误，Claude Code 看到 Anthropic 风格的错误，信息里通常带有状态码和说明。

| 状态码 | 含义 | 怎么办 |
| --- | --- | --- |
| `400` | 请求内容无效：缺少 `model` 或消息，或这个模型不支持你使用的接口 | 检查请求；换成模型支持的协议，或用 [客户端配置](#/api-keys) 重新生成 |
| `401` | 密钥缺失或无效 | 确认用的是本站的 API Key（不是登录密码），完整复制，没被停用 |
| `402` | 余额或订阅额度不足，请求在发往模型之前就被拒绝，**不会扣费** | 去 [余额充值](#/payment) 或 [Coding Plan](#/coding-plans) |
| `403` | 密钥的模型分类与所请求的模型不匹配（`API_KEY_MODEL_PROVIDER_FORBIDDEN`） | 换成对应分类的密钥，或换成该密钥分类下的模型 |
| `413` | 请求内容太大 | 减少一次发送的上下文，例如文件或对话历史 |
| `429` | 请求过于频繁，达到密钥的额度上限，或上游暂时繁忙 | 稍等片刻再试，减少并发；持续出现就看密钥的用量和上限 |
| `502` | 上游模型服务返回了无法恢复的错误 | 稍后重试；反复出现请联系管理员 |
| `503` | 暂时没有可用的上游资源，或站点处于维护状态 | 见下文 |

## 503 的几种情况

- **暂时没有可用的上游**：站点背后的服务资源暂时不够，稍等一会儿再试。持续出现请联系管理员。
- **模型待定价**（`MODEL_PRICE_UNAVAILABLE`）：该模型还没有价格，不能调用。换一个模型，或联系管理员。
- **站点维护**（`SYSTEM_MAINTENANCE`）：站点正在维护，请等待管理员恢复。

## 配置类问题

这几类错误通常不是状态码，而是连不上或路径不对：

- **连不上**：检查本站能否正常打开；Base URL 用的是你访问本站的域名，而不是服务器上的 `127.0.0.1`。
- **404 或路径错误**：Base URL 多写或少写了 `/v1`。OpenAI 兼容的客户端要带 `/v1`；Claude Code 不要带。详见 [常见问题](#/documentation/faq)。

## 求助前先准备

向管理员求助时，请提供下面这些信息，可以更快定位问题：

1. 出错的**时间**（精确到分钟）；
2. 使用的**客户端**和版本；
3. **模型 ID**；
4. **状态码**和完整的错误信息；
5. 你已经尝试过的步骤。

> 提供错误信息前，**先隐藏完整的 API Key**（比如只保留前几位）。

准备好后，按 [如何提交工单](#/documentation/tickets) 联系管理员。$doc$,
        'english', jsonb_build_object(
            'title', 'Error reference', 'category', 'Troubleshooting',
            'summary', 'What each status code means and what to do, plus what to prepare when asking an administrator for help.',
            'body', $doc$## Start with the status code

When a call fails, look at the returned **status code**. This site returns errors in the format of the protocol you use: OpenAI-compatible clients see OpenAI-style errors, and Claude Code sees Anthropic-style errors. The message usually includes a status code and a description.

| Status | Meaning | What to do |
| --- | --- | --- |
| `400` | Invalid request: `model` or messages are missing, or the model does not support the endpoint you use | Check the request; use a protocol the model supports, or regenerate with [Client configuration](#/api-keys) |
| `401` | The key is missing or invalid | Make sure you use this site's API key (not your sign-in password), copied in full and not disabled |
| `402` | Insufficient balance or plan credits; rejected before reaching the model, so **nothing is charged** | Go to [Top up](#/payment) or [Coding Plan](#/coding-plans) |
| `403` | The key's model category does not match the requested model (`API_KEY_MODEL_PROVIDER_FORBIDDEN`) | Use a key in the right category, or a model in this key's category |
| `413` | The request is too large | Send less context at once, such as fewer files or less history |
| `429` | Too many requests, the key's spending limit was reached, or the upstream is briefly busy | Wait a moment and retry with less concurrency; if it persists, check the key's usage and limit |
| `502` | The upstream model service returned an unrecoverable error | Retry later; contact an administrator if it keeps happening |
| `503` | No upstream capacity is available right now, or the site is under maintenance | See below |

## The kinds of 503

- **No upstream capacity**: the resources behind the site are briefly insufficient. Wait a moment and retry. Contact an administrator if it persists.
- **Unpriced model** (`MODEL_PRICE_UNAVAILABLE`): the model has no price yet and cannot be called. Choose another model or contact an administrator.
- **Site maintenance** (`SYSTEM_MAINTENANCE`): the site is under maintenance. Wait for an administrator to restore it.

## Configuration problems

These usually show up as a failure to connect or a wrong path rather than a status code:

- **Cannot connect**: check that this site opens normally, and that the Base URL is the domain you use to open it, not the server's `127.0.0.1`.
- **404 or wrong path**: the Base URL has an extra or missing `/v1`. OpenAI-compatible clients need `/v1`; Claude Code must not have it. See the [FAQ](#/documentation/faq).

## Prepare before asking for help

When you contact an administrator, include the following so the problem can be located faster:

1. The **time** of the error, to the minute;
2. The **client** and its version;
3. The **model ID**;
4. The **status code** and the full error message;
5. The steps you have already tried.

> **Hide your full API key** before sharing error messages, for example by keeping only the first few characters.

Once ready, follow [Submit a support ticket](#/documentation/tickets) to contact an administrator.$doc$))),
    ('faq', jsonb_build_object(
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

待定价的模型暂时没有价格，不能调用。换一个模型，或联系管理员。

## 地址与连接

### Base URL 到底要不要带 /v1

| 客户端 | Base URL |
| --- | --- |
| OpenAI 兼容的应用、Codex CLI、OpenCode | 带 `/v1`：`{{api_base_url}}` |
| Claude Code（Anthropic 协议） | **不带** `/v1`：`{{site_url}}` |

写错的话会出现 404 或路径错误。

### 连接失败，或 5xx

先确认本站能正常打开，再检查 Base URL。服务如果部署在远程，本地客户端不能用服务器上的 `127.0.0.1`，要用你访问本站的域名。短暂的服务异常可以稍后重试；不要持续快速重试，以免产生额外调用。

### 429，请求太频繁

稍等一会儿再试，减少同时发出的请求。持续出现时，查看密钥的额度上限，并联系管理员。

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

An unpriced model has no price yet and cannot be called. Choose another model or contact an administrator.

## Addresses and connections

### Should the Base URL include /v1?

| Client | Base URL |
| --- | --- |
| OpenAI-compatible apps, Codex CLI, OpenCode | With `/v1`: `{{api_base_url}}` |
| Claude Code (Anthropic protocol) | **Without** `/v1`: `{{site_url}}` |

A wrong value causes 404 or path errors.

### Connection failure, or 5xx

First confirm this site opens normally, then check the Base URL. If the service is hosted remotely, a local client cannot use the server's `127.0.0.1`; use the domain you use to open this site. Retry temporary service errors later; avoid rapid repeated requests that could create extra usage.

### 429, too many requests

Wait a moment and retry with fewer concurrent requests. If it persists, check the key's spending limit and contact an administrator.

## Codex CLI

### refresh token was already used

An old ChatGPT sign-in remains in `auth.json`. Back it up and replace it with the minimal content containing only `OPENAI_API_KEY`, or run `codex logout` first. See [Set up Codex CLI](#/documentation/codex-cli).

## Still stuck

Follow [Submit a support ticket](#/documentation/tickets) and contact an administrator from [My tickets](#/tickets), with your client, the time, the model ID, the error message, and the steps already tried. Screenshots can help.

Before submitting, **hide your full API key**, the key inside import links, and sensitive payment details.$doc$))),
    ('tickets', jsonb_build_object(
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
- 编辑回复时离开页面，浏览器会提醒你确认。**没发送的内容不会被保存**，刷新页面后会丢失；
- 管理员的邮件通知是否开启，由站点管理员决定。$doc$,
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
- If you leave while editing a reply, the browser asks you to confirm. **Unsent text is not saved** and is lost when you refresh;
- Whether administrators receive email notifications is decided by the site administrator.$doc$)))
),
upgraded AS (
    UPDATE documentation_pages p
    SET draft = tutorials.content,
        published = tutorials.content,
        published_at = clock_timestamp(),
        version = p.version + 1,
        updated_at = clock_timestamp()
    FROM tutorials
    WHERE p.id = tutorials.id
      AND p.id IN ('getting-started', 'billing', 'api-keys', 'cc-switch', 'manual-api', 'faq')
      AND p.version = 2 AND p.deleted = FALSE AND p.published IS NOT NULL
      AND p.draft = p.published AND p.draft ? 'english'
    RETURNING p.id
)
INSERT INTO documentation_pages (id, draft, published, published_at)
SELECT id, content, content, clock_timestamp()
FROM tutorials
WHERE id NOT IN ('getting-started', 'billing', 'api-keys', 'cc-switch', 'manual-api', 'faq')
ON CONFLICT (id) DO NOTHING;
