-- Teach the default tutorials about source tiers (号源分级): the key form's tier preference, the tier choice in the client
-- configuration dialog, the `model@tier` form on the model page, and the tier error codes (the error reference had one
-- row for every 403, and that row gave the wrong advice for SOURCE_TIER_NOT_SELECTABLE).
-- Written like V24: a tutorial is replaced only while it is still exactly the version V21-V24 left (identical draft and
-- publication, matching content hash). Anything an administrator edited, unpublished or deleted is left alone.
-- The hashes are md5(draft::text) of each page in a database migrated to V24.
WITH tutorials(id, previous_hash, content) AS (VALUES
    ('api-keys', 'b8206fe92f6e673dc3d936ea60650bc7', jsonb_build_object(
        'title', '创建与管理 API Key', 'category', '开始使用',
        'summary', '创建密钥时每个选项的含义、模型分类的限制、号源偏好、列表里的常用操作，以及保管密钥的习惯。', 'sortOrder', 30,
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
| 号源偏好（可选） | 只有站点为你选的模型分类设置了号池时才出现，默认“自动”。见下文“号源偏好”一节 |
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

## 号源偏好（可选）

站点可以把一个厂商的账号分成几个**号池**（也叫级别，1 级最好），每个号池有名称和价格系数。你选的模型分类所属的厂商设置了号池，并公开了号池名称时，创建和编辑密钥的表单里会多一项 **号源偏好**；没有这一项，说明这个分类没有可选的号池，不用管它。

| 选项 | 含义 |
| --- | --- |
| 自动（默认） | 按号池顺序，从 1 级开始；本次请求里 1 级的账号全部暂时不可用时，才由下一级服务 |
| 某个号池 | 请求**只**由这个号池服务，也只按它的价格计费。它的账号暂时都不可用时请求失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费 |
| 某个号池 及以下 | 由它和它以下的号池服务，按实际服务这个请求的号池计费 |

要点：

- **价格 = 模型的标准价 × 实际服务这个请求的号池的系数**。你不会为没有服务过你的号池付费；选项旁会显示每个号池的系数和价格。
- 偏好是这把密钥的**默认选择**。请求里写了模型后缀（`模型@标识`，见 [选择模型与查看价格](#/documentation/models-and-pricing)）或请求头 `X-Starry-Tier` 时，以请求为准：后缀优先于请求头，请求头优先于偏好。
- 偏好是提示，不是要求。这个入口或模型用不上所选的号池时，这个请求会忽略偏好，照常按自动服务，响应头 `X-Starry-Tier-Status` 是 `ignored`。
- 按号池服务的响应都带 `X-Starry-Tier`、`X-Starry-Tier-Level`、`X-Starry-Tier-Name`，告诉你这次由哪个号池服务。
- 不限模型分类的旧密钥不能设置偏好（`400`，`API_KEY_TIER_PREFERENCE_INVALID`）。要用就新建一把选了模型分类的密钥。
- 在编辑里换了模型分类，偏好会回到“自动”。

## 列表里的操作

列表显示密钥前缀、计费途径、模型分类、状态、**用量 / 上限** 和最近使用时间。

| 操作 | 作用 |
| --- | --- |
| 复制 API 地址 | 复制 Base URL（以 `/v1` 结尾），填到 OpenAI 兼容的客户端 |
| 复制密钥 | 复制完整密钥 |
| 客户端配置 | 打开连接信息和各客户端的现成配置，包含 **CC Switch 一键连接**。有号池可选时还能选一个**号池**写进配置 |
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
            'summary', 'What each option means when creating a key, the model-category limit, the tier preference, common list actions, and habits for keeping keys safe.',
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
| Tier preference (optional) | Appears only when the site has set up tiers for the model category you chose. The default is "Automatic". See "Tier preference" below |
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

## Tier preference (optional)

A site can split one provider's accounts into several **tiers** (also called levels; tier 1 is the best), each with a name and a price factor. When the provider of your model category has tiers and the site shows their names, the form for creating and editing a key has an extra **Tier preference** field. Without it, there are no tiers you can choose for that category, and you can ignore the topic.

| Choice | Meaning |
| --- | --- |
| Automatic (default) | Tiers are used in order, starting with tier 1; the next tier serves a request only when every account in tier 1 is temporarily unavailable for it |
| A tier | The request is served **only** by that tier and billed at its price. If all its accounts are temporarily unavailable, the request fails (`503`, `SOURCE_TIER_UNAVAILABLE`); it is never moved to another tier and nothing is charged |
| A tier and below | Served by that tier or any tier below it, and billed at the tier that actually served the request |

Good to know:

- **Price = the model's standard price × the factor of the tier that served the request.** You never pay for a tier that did not serve you; the choices show each tier's factor and price.
- The preference is the key's **default**. A model suffix (`model@tier`, see [Models and pricing](#/documentation/models-and-pricing)) or an `X-Starry-Tier` request header takes precedence: the suffix beats the header, and the header beats the preference.
- A preference is a hint, not a demand. When an endpoint or model cannot use the chosen tier, that request ignores the preference and is served automatically, and the `X-Starry-Tier-Status` response header is `ignored`.
- Responses served through tiers carry `X-Starry-Tier`, `X-Starry-Tier-Level`, and `X-Starry-Tier-Name`, which tell you which tier served the request.
- Older keys that are not tied to a model category cannot have a preference (`400`, `API_KEY_TIER_PREFERENCE_INVALID`). Create a new key with a model category to use one.
- If you change the model category while editing, the preference goes back to "Automatic".

## Actions in the list

The list shows the key prefix, billing source, model category, status, **Usage / Limit**, and last used time.

| Action | What it does |
| --- | --- |
| Copy API URL | Copies the Base URL (ending in `/v1`) for OpenAI-compatible clients |
| Copy key | Copies the full key |
| Client configuration | Opens connection details and ready-made client configurations, including **Connect with CC Switch**. When tiers are available you can also pick a **Tier** to write into the configuration |
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
    ('models-and-pricing', 'caf096d7ac0d4dc2ca3a07b026c75536', jsonb_build_object(
        'title', '选择模型与查看价格', 'category', '开始使用',
        'summary', '在哪里查模型 ID 和价格、怎么选模型、费用怎么算、号池价格和 `模型@标识` 的写法，以及“待定价”的模型为什么不能调用。', 'sortOrder', 35,
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

## 号池价格与 `模型@标识`

站点为某个厂商设置了号池，并公开了号池名称时，[模型 ID](#/models) 页里该厂商的模型卡片上会列出能服务它的号池。每个号池显示名称、**系数**（如 ×0.6）和乘过系数的参考价。

- **实际价格 = 模型的标准价 × 实际服务这个请求的号池的系数。** 模型的标价按第一个号池（通常是 1 级）计算；
- 自动模式下，请求先由 1 级服务；本次请求里 1 级的账号全部暂时不可用时，才由下一级服务。响应头 `X-Starry-Tier` 告诉你这次由哪个号池服务；
- 你的会话因此改由较低的号池服务后，同一个会话会留在那个号池，直到空闲约 10 分钟才回到 1 级（站点可以关闭这个行为）。较低的号池比 1 级贵时，这期间仍按较低号池的价格计费。想要稳定的价格，就固定一个号池。

**想让某个请求只用某个号池**：点号池旁的复制按钮，复制出 `模型@标识`，例如 `claude-sonnet-4.5@economy`，把它当作模型名填进客户端或请求。写法：

| 写法 | 含义 |
| --- | --- |
| `模型@economy` | 只由标识为 `economy` 的号池服务，只按它的价格计费 |
| `模型@economy+` | 由它和它以下的号池服务，按实际服务的号池计费 |
| `模型@L2` 或 `模型@2` | 按级别：第 2 级的号池 |
| `模型@auto` | 回到自动，并盖过请求头和密钥里的号源偏好 |

- 后缀是对**这一个请求**的要求，用不上就拒绝，并且不会扣费：号池不存在（`400`，`SOURCE_TIER_UNKNOWN`），这个模型或入口用不上它（`400`，`SOURCE_TIER_NOT_OFFERED`），站点不允许选择它（`403`，`SOURCE_TIER_NOT_SELECTABLE`）。虚线框的号池不允许选择，不能复制；
- 只用一个号池时，它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`，带 `Retry-After`），不会换到别的号池；想要能降级，就写 `+`；
- 请求头 `X-Starry-Tier` 和密钥的号源偏好是默认选择，用不上时只会被忽略，见 [创建与管理 API Key](#/documentation/api-keys)；
- 余额不够这次请求的预留时，`402` 的说明会列出预留更少的号池和写法；
- 号池的名称和价格由站点设置，没有公开时模型卡片上不显示号池，请求里也不需要写后缀。

## 标记为“待定价”的模型

**待定价** 的模型暂时没有价格，**调用会被拒绝**（`503`，`MODEL_PRICE_UNAVAILABLE`），并且不会扣费。请换一个模型。

## 模型天梯

[模型天梯](#/model-ranking) 展示 ProgramBench、FrontierBench 等公开基准榜单，每日更新，可以帮助你了解主流模型的相对水平。榜单上的模型不一定都在本站提供，具体请以 [模型 ID](#/models) 为准。$doc$,
        'english', jsonb_build_object(
            'title', 'Models and pricing', 'category', 'Getting started',
            'summary', 'Where to find model IDs and prices, how to choose a model, how costs are calculated, tier prices and the `model@tier` form, and why unpriced models cannot be called.',
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

## Tier prices and `model@tier`

When the site has set up tiers for a provider and shows their names, the model cards on [Model IDs](#/models) list the tiers that can serve the model. Each tier shows its name, its **factor** (such as ×0.6), and a reference price with the factor applied.

- **Actual price = the model's standard price × the factor of the tier that served the request.** The price shown for the model itself is that of the first tier (usually tier 1);
- In automatic mode a request is served by tier 1 first; the next tier serves it only when every account in tier 1 is temporarily unavailable for that request. The `X-Starry-Tier` response header tells you which tier served it;
- Once your session is served by a lower tier for that reason, the session stays there until it has been idle for about 10 minutes, then returns to tier 1 (the site can turn this off). If the lower tier costs more than tier 1, you are still billed at its price meanwhile. For a steady price, pin a tier.

**To make one request use one tier**, select the copy button next to the tier to get `model@tier`, for example `claude-sonnet-4.5@economy`, and use it as the model name in your client or request. Forms:

| Form | Meaning |
| --- | --- |
| `model@economy` | Served only by the tier whose identifier is `economy`, and billed at its price |
| `model@economy+` | Served by that tier or any tier below it, billed at the tier that served the request |
| `model@L2` or `model@2` | By level: the tier at level 2 |
| `model@auto` | Back to automatic, overriding the request header and the key's tier preference |

- A suffix is a demand for **that one request**, so it is rejected, with nothing charged, when it cannot be followed: the tier does not exist (`400`, `SOURCE_TIER_UNKNOWN`), this model or endpoint cannot use it (`400`, `SOURCE_TIER_NOT_OFFERED`), or the site does not let clients choose it (`403`, `SOURCE_TIER_NOT_SELECTABLE`). Tiers with a dashed outline cannot be chosen and cannot be copied;
- With a single tier, if all its accounts are temporarily unavailable the request fails (`503`, `SOURCE_TIER_UNAVAILABLE`, with `Retry-After`) and is not moved to another tier; add `+` if you want it to fall back;
- The `X-Starry-Tier` request header and the key's tier preference are defaults; when they cannot be used they are ignored, see [Create and manage API keys](#/documentation/api-keys);
- When your balance cannot cover the reservation for a request, the `402` message lists tiers that reserve less, with the forms to write;
- Tier names and prices are set by the site. When they are not shown, the model cards list no tiers and you do not need a suffix.

## Models marked "Unpriced"

An **Unpriced** model has no price yet, so **calls are rejected** (`503`, `MODEL_PRICE_UNAVAILABLE`) and nothing is charged. Choose another model.

## Model rankings

[Model rankings](#/model-ranking) shows public benchmarks such as ProgramBench and FrontierBench, updated daily, to help you gauge how leading models compare. Not every ranked model is offered here; rely on [Model IDs](#/models).$doc$))),
    ('claude-code', 'f355351f771d9672523f32df12437199', jsonb_build_object(
        'title', '手动配置 Claude Code', 'category', '连接客户端',
        'summary', '用环境变量把 Claude Code 连到本站：怎么复制现成配置、每个变量的含义、怎么指定号池，以及常见错误。', 'sortOrder', 45,
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

## 指定号池（可选）

站点设置了号池，并公开了号池名称时，[API Key](#/api-keys) 的 **客户端配置** 里，**客户端** 选 **Claude Code** 后会多一项 **号池**：选一个号池（或“…及以下”），复制出来的配置就带上它。没有这一项，说明站点没有为这把密钥的模型分类公开号池，可以跳过这一节。

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
- 只选一个号池时，它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费；
- 价格 = 模型的标准价 × 实际服务这个请求的号池的系数，见 [选择模型与查看价格](#/documentation/models-and-pricing)；
- 用 [CC Switch 一键接入](#/documentation/cc-switch) 时，号池写在模型名后面（后缀方式）；
- 不写号池，请求就按密钥里的 **号源偏好**，没有设置就自动，见 [创建与管理 API Key](#/documentation/api-keys)。

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| `401` | 密钥复制不完整、带了多余空格，或密钥被停用。重新复制完整密钥 |
| `403` | 密钥的模型分类不是 Anthropic，或模型 ID 不属于该分类。换成 Anthropic 分类的密钥。信息里是 `SOURCE_TIER_NOT_SELECTABLE` 时，是模型名后面的号池不允许选择，去掉 `@标识` |
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
- Price = the model's standard price × the factor of the tier that served the request; see [Models and pricing](#/documentation/models-and-pricing);
- With [CC Switch setup](#/documentation/cc-switch) the tier is written after the model name (the suffix way);
- Without a tier, requests follow the key's **Tier preference**, or are automatic when none is set; see [Create and manage API keys](#/documentation/api-keys).

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
    ('codex-cli', '383c2c824254c423a7edb6f8e1d69622', jsonb_build_object(
        'title', '手动配置 Codex CLI', 'category', '连接客户端',
        'summary', '编辑 config.toml 和 auth.json 把 Codex CLI 连到本站、怎么指定号池，以及 refresh token 报错的处理。', 'sortOrder', 50,
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

## 指定号池（可选）

站点设置了号池，并公开了号池名称时，[API Key](#/api-keys) 的 **客户端配置** 里，**客户端** 选 **Codex CLI** 后会多一项 **号池**，选好后复制出来的 `config.toml` 就带上它。没有这一项，说明站点没有为这把密钥的模型分类公开号池，可以跳过这一节。

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
- 只选一个号池时，它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费；
- 续写上一轮的响应时，如果它所在的号池不是你用后缀固定的那个，会返回 `409`（`RESPONSE_OWNER_TIER_MISMATCH`）；请求头和密钥的号源偏好则会被忽略，不报错；
- 价格见 [选择模型与查看价格](#/documentation/models-and-pricing)；不写号池时，请求按密钥里的 **号源偏好**，没有设置就自动。

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| `refresh token was already used` | 本机 `auth.json` 里还留着旧的 ChatGPT 授权。备份后换成上面的最简内容，或先运行 `codex logout` |
| `401` | 密钥复制不完整，或被停用 |
| `403` | 密钥的模型分类不是 OpenAI，或模型不属于该分类。信息里是 `SOURCE_TIER_NOT_SELECTABLE` 时，是 `model` 后面的号池不允许选择，去掉 `@标识` |
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
- For prices see [Models and pricing](#/documentation/models-and-pricing). Without a tier, requests follow the key's **Tier preference**, or are automatic when none is set.

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
    ('opencode', 'c2b9c93f670d5a9792d9c97549632f2f', jsonb_build_object(
        'title', '手动配置 OpenCode', 'category', '连接客户端',
        'summary', '在项目根目录放一个 opencode.json，用 OpenAI 兼容方式把 OpenCode 连到本站，以及怎么指定号池。', 'sortOrder', 55,
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

## 指定号池（可选）

站点设置了号池，并公开了号池名称时，[API Key](#/api-keys) 的 **客户端配置** 里，**客户端** 选 **OpenCode** 后会多一项 **号池**，选好后复制出来的 `opencode.json` 就带上它。没有这一项，说明站点没有为这把密钥的模型分类公开号池，可以跳过这一节。

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
- 只选一个号池时，它的账号暂时都不可用，请求会失败（`503`，`SOURCE_TIER_UNAVAILABLE`），不会换到别的号池，也不会扣费；
- [CC Switch 一键接入](#/documentation/cc-switch) 的 OpenCode 链接不写号池：请用上面的 `opencode.json`，或在密钥里设置 **号源偏好**，见 [创建与管理 API Key](#/documentation/api-keys)；
- 价格见 [选择模型与查看价格](#/documentation/models-and-pricing)。

## 常见问题

| 现象 | 原因和解决 |
| --- | --- |
| 列表里没有模型 | `models` 里没有列出该模型，或 `provider` 的名字和 `model` 里写的不一致 |
| `401` | 密钥复制不完整，或被停用 |
| `403` | 密钥的模型分类不包含该模型。例如密钥是 OpenAI 分类，却选了 `claude-*`。信息里是 `SOURCE_TIER_NOT_SELECTABLE` 时，是模型 ID 后面的号池不允许选择，去掉 `@标识` |
| `400`，信息里有 `SOURCE_TIER_…` | 模型 ID 后面的 `@标识` 不对，或这个模型用不上它。去掉它，或到 [模型 ID](#/models) 页复制正确的写法 |
| `503`，`SOURCE_TIER_UNAVAILABLE` | 你固定的号池暂时没有可用账号。按 `Retry-After` 稍后重试，或在标识后面加 `+` 允许降级 |
| `402` | 余额或订阅额度不足，见 [充值与计费](#/documentation/billing) |

更多错误见 [错误码速查](#/documentation/errors)。$doc$,
        'english', jsonb_build_object(
            'title', 'Set up OpenCode', 'category', 'Connect a client',
            'summary', 'Put an opencode.json in your project root to connect OpenCode to this site as an OpenAI-compatible provider, and how to choose a tier.',
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
- The OpenCode link of [CC Switch setup](#/documentation/cc-switch) does not write a tier: use the `opencode.json` above, or set a **Tier preference** on the key; see [Create and manage API keys](#/documentation/api-keys);
- For prices see [Models and pricing](#/documentation/models-and-pricing).

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
    ('errors', '702380d5c217f6f85168c4867a9dac59', jsonb_build_object(
        'title', '错误码速查', 'category', '遇到问题',
        'summary', '按状态码查看每种报错的含义和处理办法，包括号池相关的错误，以及求助时该准备哪些信息。', 'sortOrder', 70,
        'body', $doc$## 先看状态码

调用出错时，先看返回的**状态码**。本站会按你所用协议的格式返回错误：OpenAI 兼容的客户端看到 OpenAI 风格的错误，Claude Code 看到 Anthropic 风格的错误，信息里通常带有状态码和说明。

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
- **所选号池暂时没有可用账号**（`SOURCE_TIER_UNAVAILABLE`）：你固定了一个号池，它的账号暂时都用不了。响应带 `Retry-After`，表示建议等多少秒再试。这次请求不会扣费，也不会换到别的号池；想要能降级，在 `@标识` 后面加 `+`，或改回自动。
- **模型待定价**（`MODEL_PRICE_UNAVAILABLE`）：该模型还没有价格，不能调用。换一个模型即可。
- **站点维护**（`SYSTEM_MAINTENANCE`）：站点正在维护，请稍后再试。

## 号池相关的错误

站点设置了号池，而你在模型名后面写了 `@标识`、请求头或密钥的号源偏好时，可能遇到下面这些。它们都在扣费之前返回，**不会扣费**。写法和说明见 [选择模型与查看价格](#/documentation/models-and-pricing) 和 [创建与管理 API Key](#/documentation/api-keys)。

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

When the site has set up tiers and you write `@tier` after the model name, send a request header, or set a key's tier preference, you may meet the following. They are all returned before anything is charged, so **nothing is charged**. See [Models and pricing](#/documentation/models-and-pricing) and [Create and manage API keys](#/documentation/api-keys) for the forms and details.

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

Once ready, follow [Submit a support ticket](#/documentation/tickets) to contact an administrator.$doc$)))
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
