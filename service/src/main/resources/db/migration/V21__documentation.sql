CREATE TABLE documentation_pages (
    id VARCHAR(64) PRIMARY KEY,
    draft JSONB NOT NULL CHECK (jsonb_typeof(draft) = 'object'),
    published JSONB CHECK (published IS NULL OR jsonb_typeof(published) = 'object'),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    published_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE documentation_attachments (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES documentation_pages(id),
    filename VARCHAR(180) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 67108864),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX documentation_attachments_document_idx ON documentation_attachments(document_id);
GRANT SELECT, INSERT, UPDATE ON documentation_pages TO starry_api_runtime;
GRANT SELECT, INSERT, DELETE ON documentation_attachments TO starry_api_runtime;

-- These initial publications are ordinary editable documents after installation.
INSERT INTO documentation_pages (id, draft, published, published_at)
SELECT id, content, content, clock_timestamp()
FROM (VALUES
    ('getting-started', jsonb_build_object(
        'title', '第一次使用，从这里开始', 'category', '开始使用',
        'summary', '准备额度、创建密钥，再把本站接入你的本地客户端。', 'sortOrder', 10,
        'body', $doc$## 1. 准备可用额度

打开 [余额充值](#/payment)，选择金额和支付方式，完成付款后回到本站确认余额到账。也可以在 [Coding Plan](#/coding-plans) 购买适合自己的订阅。两种方式选择一种即可。

## 2. 创建 API Key

进入 [API Key](#/api-keys)，新建一把密钥，填写一个自己认得的名称，选择模型分类和计费途径。

- **余额计费**：从钱包余额扣费。
- **Coding Plan 计费**：使用有效订阅的额度，不会自动改用钱包余额。

创建成功后妥善保存完整密钥。密钥相当于调用本站的密码，不要发给别人，也不要放进截图或公开代码仓库。

## 3. 用 CC Switch 接入本地

推荐安装 [CC Switch](https://ccswitch.io)，再回到 API Key 页面，打开该密钥的 **客户端配置**。在 **CC Switch 一键连接** 中选择你使用的客户端，点击 **打开**，允许浏览器启动 CC Switch，确认导入后启用该配置。

详细步骤见 [CC Switch 一键接入](#/documentation/cc-switch)。CC Switch 负责管理配置，Claude Code、Codex CLI 等客户端仍需单独安装。

## 4. 发起第一次调用

重新打开本地客户端，选择本站支持的模型，发送一句简短的问题。在 [模型 ID](#/models) 可以查看可用模型和当前价格。

遇到问题先看 [常见问题](#/documentation/faq)。仍无法解决时，在 [我的工单](#/tickets) 联系管理员，附上时间、客户端名称和已隐藏密钥的错误信息。$doc$)),
    ('billing', jsonb_build_object(
        'title', '充值与计费', 'category', '开始使用',
        'summary', '了解余额和订阅的区别，确认付款与额度。', 'sortOrder', 20,
        'body', $doc$## 使用余额

1. 打开 [余额充值](#/payment)，选择金额与可用的支付方式。
2. 按支付页面提示完成付款。
3. 回到本站确认订单状态和余额，再进行 API 调用。

付款后余额暂未更新时，先查看订单状态，不要立即重复付款。长时间未到账可提交工单，提供订单号和付款时间，不要提供支付密码。

如果页面没有可用支付方式，请联系管理员。若收到本站兑换码，可在充值页面使用兑换入口。

## 使用 Coding Plan

进入 [Coding Plan](#/coding-plans)，查看当前开放的订阅、适用模型和额度规则。购买后需要创建或选择 **Coding Plan 计费** 的 API Key。

订阅额度用完或到期后，该密钥不会自动扣钱包余额。需要使用余额时，请切换到余额计费的密钥。

## 怎样查看价格

[模型 ID](#/models) 展示本站当前模型价格。实际费用与使用的模型、输入和输出用量、缓存情况以及本站计费规则有关。

余额与订阅是两种不同的计费途径。创建密钥时选好用途，例如把日常开发和临时测试分开，便于管理。$doc$)),
    ('api-keys', jsonb_build_object(
        'title', '创建与管理 API Key', 'category', '开始使用',
        'summary', '选择计费途径，为不同客户端准备独立密钥。', 'sortOrder', 30,
        'body', $doc$## 创建密钥

进入 [API Key](#/api-keys)，点击新建，填写名称、选择模型分类，再选择 **余额计费** 或 **Coding Plan 计费**。根据自己的需要填写页面中其余限制项后提交。

建议每台设备或每个应用使用独立密钥，例如“笔记本开发”“测试脚本”。不再使用时及时停用或删除。

## 接入客户端

创建成功后可直接进入客户端配置。已有密钥也可以从列表中的 **客户端配置** 入口打开。

优先使用 **CC Switch 一键连接**。不能使用 CC Switch 时，可复制配置页面中的手动配置，或阅读 [手动接入 API](#/documentation/manual-api)。

## 保存完整密钥

带省略号或星号的密钥只用于展示，不能用于 API 请求。如果历史密钥无法在配置入口中恢复，请使用你自己保存的完整密钥；没有保存时，新建一把并更新本地配置。

不要把密钥提交到 Git、发到群聊或粘贴到工单。发现泄露时立即停用该密钥，并创建新密钥替换。$doc$)),
    ('cc-switch', jsonb_build_object(
        'title', 'CC Switch 一键接入', 'category', '连接客户端',
        'summary', '推荐方式：从本站直接导入 Claude Code、Codex CLI 或 OpenCode 配置。', 'sortOrder', 40,
        'body', $doc$## 下载与安装

访问 [CC Switch 官方网站](https://ccswitch.io)，下载与你系统对应的版本并安装。也可以使用 [官方 GitHub 下载页](https://github.com/farion1231/cc-switch/releases/latest)。

CC Switch 支持 Windows、macOS 和 Linux。它用于管理客户端配置；请先安装你准备使用的 Claude Code、Codex CLI 或 OpenCode。

## 从本站导入

1. 打开 [API Key](#/api-keys)，找到要使用的密钥。
2. 点击该密钥的 **客户端配置**。
3. 在 **CC Switch 一键连接** 中选择与你密钥模型分类匹配的客户端。
4. 点击 **打开**，在浏览器提示中允许启动 CC Switch。
5. 检查导入的站点地址和模型，确认导入，并在 CC Switch 中启用该配置。
6. 重新打开本地客户端，发送一个简短请求测试。

## 浏览器没有打开 CC Switch

先确认已经安装并启动过 CC Switch，然后重新点击 **打开**。也可以点击 **复制链接**，使用 CC Switch 支持的链接导入入口。若你的版本没有该入口，请升级或改用客户端配置页的手动配置。

导入链接中包含完整密钥。只在自己的设备上使用，不要分享该链接。

## 仍然连接不上

检查本站地址是否能从本地访问、密钥是否有效，以及计费途径是否有可用额度。服务器部署在远程时，本地客户端不能使用服务器的 `127.0.0.1` 地址，应使用你访问本站的域名。

手动设置方法见 [手动接入 API](#/documentation/manual-api)。$doc$)),
    ('manual-api', jsonb_build_object(
        'title', '手动接入 API', 'category', '连接客户端',
        'summary', '为其他应用配置地址和密钥，也可以用命令行测试。', 'sortOrder', 50,
        'body', $doc$## 准备地址与密钥

本站地址：

```text
{{site_url}}
```

OpenAI 兼容接口的 Base URL：

```text
{{api_base_url}}
```

在应用的 API Key 一栏填写本站生成的完整密钥。模型名称从 [模型 ID](#/models) 复制。不同应用可能自动添加 `/v1`，请避免配置成 `/v1/v1`。

Claude Code、Codex CLI 和 OpenCode 建议直接使用 [API Key](#/api-keys) 页的 **客户端配置**，其中的配置会按客户端区分协议和地址。

## 用命令行查看模型

以下示例适用于 macOS、Linux 或其他 Bash 终端。Windows 可在 WSL / Git Bash 中执行。把占位内容换成自己的密钥，再运行。

```bash
export STARRY_API_KEY='替换为你的完整 API Key'
curl '{{api_base_url}}/models' \
  -H "Authorization: Bearer $STARRY_API_KEY"
```

## 发起一个对话请求

将 `MODEL_ID` 换成此密钥可使用的模型 ID。下面演示 OpenAI Chat Completions 兼容接口；只支持 Responses 或 Anthropic Messages 的模型，应使用对应客户端配置和协议。

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

## 在 Python 中调用

先安装官方 OpenAI Python SDK：

```bash
python -m pip install openai
```

设置上面的 `STARRY_API_KEY` 环境变量后，运行：

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

请不要把真实密钥直接写进项目代码。测试请求也会产生实际用量。$doc$)),
    ('faq', jsonb_build_object(
        'title', '常见问题与求助', 'category', '遇到问题',
        'summary', '从常见错误出发，快速检查配置和额度。', 'sortOrder', 60,
        'body', $doc$## 401 或密钥无效

确认使用的是本站 API Key，不是登录密码，也不是其他平台的密钥。检查复制时是否带入空格，以及是否误用了省略显示的密钥。失效或已停用的密钥需要重新创建。

## 余额不足或订阅不可用

先查看当前密钥的计费途径。余额计费需要钱包有余额；Coding Plan 计费需要有效订阅、剩余额度和支持的模型。两者不会自动相互兜底。

## 模型不存在或不支持

从 [模型 ID](#/models) 复制完整名称，确认模型分类与密钥一致。检查客户端使用的协议是否适合该模型，必要时重新使用客户端配置入口。

## 429 或请求过于频繁

稍等一会儿再试，减少同时发起的请求。持续出现时，查看密钥或订阅的额度限制，并联系管理员。

## 连接失败或 5xx

先确认本站能正常打开，再检查 Base URL 是否重复包含 `/v1`。短暂服务异常可稍后重试；不要持续快速重试，以免产生额外调用。

## 联系管理员

在 [我的工单](#/tickets) 新建工单，说明你使用的客户端、发生时间、模型 ID、错误信息和已经尝试的步骤。可以附上截图或其他附件。

提交前隐藏完整 API Key、导入链接中的密钥、支付凭证中的敏感信息。$doc$))
) AS initial(id, content);
