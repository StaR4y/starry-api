package tech.starryapi.assistant;

import java.time.Instant;
import java.util.List;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Translation;

/**
 * A small replica of the seeded tutorials for the retrieval tests: the same kinds of headings (structure words such as
 * 操作, 验证, 下一步, 规则 and 常见问题 in many of them), a table of status codes, and an English translation for some pages.
 */
final class AssistantDocsCorpus {

    private AssistantDocsCorpus() {
    }

    static List<Document> pages() {
        return List.of(
            new Document("getting-started", new Content("第一次使用，从这里开始", "开始使用", "登录、充值、创建 API Key，三步开始", """
                ## 先看全貌
                先登录并修改密码，再充值，最后创建 API Key，就可以开始调用模型了。

                ## 1. 登录并修改密码
                用管理员给的账号登录，第一次登录后请先修改密码。

                ## 2. 充值或开通订阅
                在余额充值页面充值，或者开通 Coding Plan 订阅。

                ## 3. 创建 API Key
                在 API Key 页面点新建，复制保存好密钥。

                ## 下一步
                按你使用的工具，阅读对应的配置页面。
                """, 10,
                new Translation("Start here", "Getting started", "Sign in, top up and create an API key", """
                ## The big picture
                Sign in and change your password, then top up, then create an API key, and you can call models.

                ## 1. Sign in and change your password
                Sign in with the account you were given and change the password first.

                ## 2. Top up or subscribe
                Top up on the balance page, or subscribe to a Coding Plan.

                ## 3. Create an API key
                On the API Key page select New, then copy and keep the key.

                ## Next steps
                Read the setup page for the tool you use.
                """)), Instant.EPOCH, List.of()),
            new Document("console-tour", new Content("控制台导览", "开始使用", "首页和各个菜单的作用", """
                ## 数据统计怎么读
                首页的数据统计显示累计调用次数、用量和余额的变化，用量按天汇总。

                ## 新闻公告
                首页的新闻栏会显示管理员发布的公告和更新说明。

                ## 左侧菜单
                左侧菜单按功能分组，常用的有 API Key、余额充值、使用文档和工单。
                """, 20,
                new Translation("Console tour", "Getting started", "The home page and what each menu is for", """
                ## How to read the statistics
                The statistics on the home page show the number of calls, the usage and the balance over time, with usage summed by day.

                ## News and announcements
                The news bar on the home page shows the announcements administrators publish.

                ## The left menu
                The left menu is grouped by function: API keys, top-ups, the documentation and tickets are the ones you use most.
                """)), Instant.EPOCH, List.of()),
            new Document("billing", new Content("充值与计费", "开始使用", "在线充值、兑换码，以及余额是怎么扣的", """
                ## 两种计费途径，先选对
                余额按用量扣费，Coding Plan 订阅按额度计算。先确定你用哪一种。

                ## 给余额充值
                ### 在线充值
                打开余额充值页面，选择金额，在线支付。到账后余额立即增加。

                ### 使用兑换码
                在余额充值页面输入兑换码，额度立即到账。

                ### 付了款但余额没变？
                先刷新页面，再看订单状态；一直没到账就提交工单，并附上订单号。

                ## 余额是怎么扣的
                每次请求按模型的单价和用量扣费，用多少扣多少。扣费后余额会减少。请求被拒绝时不扣费。

                ## 需要知道的规则
                余额不会过期。余额不足时请求会被拒绝，充值后即可继续使用。
                """, 30,
                new Translation("Top-ups and billing", "Getting started", "Online top-up, redeem codes and how the balance is charged", """
                ## Two billing sources: choose the right one
                The balance is charged by usage, a Coding Plan by its credit. Decide which one you use.

                ## Top up your balance
                ### Online top-up
                Open the top-up page, choose an amount and pay online. The balance increases at once.

                ### Redeem a code
                Enter the redeem code on the top-up page and the credit arrives at once.

                ### Paid but the balance did not change?
                Refresh the page and check the order status; if it still has not arrived, submit a ticket with the order number.

                ## How the balance is charged
                Each request is charged by the model price and usage. The balance goes down as you use it. Rejected requests are not charged.

                ## Rules to know
                The balance does not expire. When it runs out requests are rejected; top up to continue.
                """)), Instant.EPOCH, List.of()),
            new Document("coding-plan", new Content("使用 Coding Plan 订阅", "开始使用", "订阅额度、开通和续费", """
                ## Coding Plan 是什么
                Coding Plan 是按周期给额度的订阅，适合每天都用的人。

                ## 开通订阅
                在订阅页面选择套餐，在线支付后立即生效。

                ## 让密钥使用订阅额度
                在 API Key 里把计费方式改成订阅。

                ## 需要知道的规则
                订阅额度用完后请求会被拒绝；到期后不会自动续费，需要重新开通。
                """, 40,
                null), Instant.EPOCH, List.of()),
            new Document("api-keys", new Content("创建与管理 API Key", "开始使用", "创建、查看、修改和删除 API Key", """
                ## 创建一把密钥
                点击新建 API Key，填写名称，选择模型分类，保存后复制密钥。

                ## 密钥是什么
                密钥是你调用接口的凭证，请妥善保管，不要发给别人。

                ## 模型分类：一把密钥一个厂商
                每把密钥只能使用一个厂商的模型，创建时选好。

                ## 列表里的操作
                在 API Key 列表里点编辑，可以修改名称、额度上限和模型分类；点删除可以永久删除这把密钥。

                ## 好习惯
                给每个用途建一把密钥，定期检查，不用的及时删除。

                ## 下一步
                拿到密钥以后，去配置你的客户端。
                """, 50,
                new Translation("Create and manage API keys", "Getting started", "Create, view, edit and delete API keys", """
                ## Create a key
                Select New API key, enter a name, choose a model category, save and copy the key.

                ## What a key is
                A key is your credential for the API. Keep it safe and do not share it.

                ## Model category: one provider per key
                Each key can use the models of one provider only. Choose it when you create the key.

                ## Actions in the list
                In the API key list select Edit to change the name, spending limit and model category; select Delete to remove the key for good.

                ## Good habits
                Make one key per purpose, review them regularly and delete the ones you do not use.

                ## Next steps
                Once you have a key, set up your client.
                """)), Instant.EPOCH, List.of()),
            new Document("claude-code", new Content("手动配置 Claude Code", "连接客户端", "把 Claude Code 接到本站", """
                ## 开始之前
                已安装 Claude Code，并且有一把 Anthropic 分类的 API Key。

                ## 手动设置环境变量
                设置 ANTHROPIC_BASE_URL 和 ANTHROPIC_AUTH_TOKEN 两个环境变量，然后重新打开终端，再运行 claude。

                ## 验证
                运行 claude，问一句话，能收到回复就说明配置成功。

                ## 常见问题
                提示 401 时检查密钥是否完整；连不上时检查 ANTHROPIC_BASE_URL 是否带了 /v1，它不应该带。
                """, 60,
                new Translation("Set up Claude Code", "Connect a client", "Connect Claude Code to this site", """
                ## Before you start
                Claude Code is installed and you have an API key of the Anthropic category.

                ## Set environment variables manually
                Set the two environment variables ANTHROPIC_BASE_URL and ANTHROPIC_AUTH_TOKEN, reopen the terminal and run claude.

                ## Verify
                Run claude and ask one question; a reply means the setup works.

                ## Common problems
                On a 401 check that the key is complete; if it cannot connect check that ANTHROPIC_BASE_URL has no /v1.
                """)), Instant.EPOCH, List.of()),
            new Document("codex-cli", new Content("手动配置 Codex CLI", "连接客户端", "把 Codex CLI 接到本站", """
                ## 开始之前
                已安装 Codex CLI，并且有一把 OpenAI 分类的 API Key。

                ## 手动配置
                编辑用户目录下的 config.toml，填入地址和密钥，然后运行 codex。

                ## 验证
                运行 codex，问一句话，能收到回复就说明配置成功。

                ## 常见问题
                提示 refresh token 已被使用时，删除旧的登录信息后重新配置。
                """, 70,
                new Translation("Set up Codex CLI", "Connect a client", "Connect Codex CLI to this site", """
                ## Before you start
                Codex CLI is installed and you have an API key of the OpenAI category.

                ## Manual setup
                Edit config.toml in your user directory, fill in the address and the key, and run codex.

                ## Verify
                Run codex and ask one question; a reply means the setup works.

                ## Common problems
                If the refresh token was already used, delete the old sign-in data and set it up again.
                """)), Instant.EPOCH, List.of()),
            new Document("opencode", new Content("手动配置 OpenCode", "连接客户端", "把 OpenCode 接到本站", """
                ## 开始之前
                已安装 OpenCode，并且有一把 OpenAI 分类的 API Key。

                ## 手动配置
                在项目根目录创建 opencode.json，填入地址和密钥。

                ## 验证
                运行 opencode，问一句话，能收到回复就说明配置成功。
                """, 80,
                null), Instant.EPOCH, List.of()),
            new Document("manual-api", new Content("手动接入 API", "连接客户端", "用 curl 或 Python 直接调用", """
                ## 准备地址与密钥
                在控制台复制站点地址，再创建一把密钥。

                ## 用 curl 调用
                用 curl 发一个 POST 请求到 /v1/chat/completions，带上 Authorization 头。

                ## 在 Python 中调用
                用 OpenAI 的 Python 库，把 base_url 设为站点地址加 /v1，api_key 填你的密钥，然后调用 chat.completions.create。
                """, 90,
                null), Instant.EPOCH, List.of()),
            new Document("errors", new Content("错误码速查", "遇到问题", "请求失败时先看状态码", """
                ## 先看状态码
                调用出错时，先看返回的状态码。

                | 状态码 | 含义 | 怎么办 |
                | --- | --- | --- |
                | 400 | 请求内容无效 | 检查请求，换成模型支持的协议 |
                | 401 | 密钥缺失或无效 | 确认用的是本站的 API Key，完整复制 |
                | 402 | 余额或订阅额度不足，不会扣费 | 去充值或开通订阅 |
                | 429 | 请求过于频繁，或模型服务暂时繁忙 | 稍等片刻再试；持续出现就看密钥的用量和上限 |
                | 502 | 模型服务返回了错误 | 稍后重试；反复出现就提交工单 |
                | 503 | 模型服务暂时繁忙，或站点正在维护 | 稍后再试 |

                ## 求助前先准备
                把状态码、发生时间和请求信息记下来，提交工单时一起写上。
                """, 100,
                new Translation("Error reference", "Problems", "Look at the status code first", """
                ## Start with the status code
                When a call fails, look at the returned status code.

                | Status | Meaning | What to do |
                | --- | --- | --- |
                | 400 | Invalid request | Check the request and use a protocol the model supports |
                | 401 | The key is missing or invalid | Use this site's API key, copied in full |
                | 402 | Insufficient balance or plan credits; nothing is charged | Top up or subscribe |
                | 429 | Too many requests, or the model service is busy | Wait and retry; if it persists check the key's usage and limit |
                | 502 | The model service returned an error | Retry later; submit a ticket if it keeps happening |
                | 503 | The model service is busy or the site is under maintenance | Try again later |

                ## Before asking for help
                Write down the status code, the time and the request, and put them in the ticket.
                """)), Instant.EPOCH, List.of()),
            new Document("faq", new Content("常见问题与求助", "遇到问题", "最常被问到的问题", """
                ## 密钥与登录
                ### 提示 401，或者密钥无效
                返回 401 Invalid API key 表示密钥填错了或者已被停用，请回到 API Key 页面重新复制。

                ### 怎么修改密码
                点右上角的头像，选择修改密码。

                ## 额度与计费
                ### 为什么实际扣费和我估算的不一样
                实际扣费按模型的单价和真实用量计算，缓存命中的部分更便宜。

                ### 付款了，余额没变
                先刷新页面；一直没到账就提交工单。

                ## Codex CLI
                ### 提示 refresh token 已被使用
                删除旧的登录信息后重新配置。

                ## 地址与连接
                ### Base URL 要不要带 /v1
                Claude Code 不带 /v1，OpenAI 兼容的客户端要带。
                """, 110,
                null), Instant.EPOCH, List.of()),
            new Document("tickets", new Content("如何提交工单", "遇到问题", "遇到问题时怎么联系管理员", """
                ## 提交一张工单
                打开我的工单，点新建工单，写清问题，附件最多 3 个，每个不超过 2 MB。

                ## 状态的含义
                待处理表示管理员还没看，处理中表示正在排查，已解决表示问题处理完了。

                ## 写出好懂的工单
                写清发生的时间、状态码和你做了什么。
                """, 120,
                null), Instant.EPOCH, List.of())
        );
    }
}
