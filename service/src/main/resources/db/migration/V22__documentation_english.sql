-- Add translations only to untouched default publications. Never replace edited content.
WITH translations(id, content) AS (VALUES
('getting-started', jsonb_build_object(
    'title', 'Start here', 'category', 'Getting started',
    'summary', 'Add credits, create a key, and connect your local client.',
    'body', $doc$## 1. Add credits

Open [Top up](#/payment), choose an amount and payment method, complete payment, and return to confirm your balance. Alternatively, buy a subscription in [Coding Plan](#/coding-plans). Choose the billing source that suits you.

## 2. Create an API key

Open [API keys](#/api-keys), create a key, give it a recognizable name, and choose a model category and billing source.

- **Balance billing** uses your wallet balance.
- **Coding Plan** uses an active subscription's credits. It never falls back to your wallet automatically.

Save the full key securely. Treat it like a password: do not share it, include it in screenshots, or commit it to a public repository.

## 3. Connect with CC Switch

Install [CC Switch](https://ccswitch.io), then open **Client configuration** for your API key. Under **Connect with CC Switch**, choose your client and click **Open**. Allow the browser to launch CC Switch, confirm the import, and activate the configuration.

See [CC Switch setup](#/documentation/cc-switch) for details. CC Switch manages configurations; clients such as Claude Code and Codex CLI must be installed separately.

## 4. Make your first request

Restart your local client, select a supported model, and send a short question. [Model IDs](#/models) lists available models and current prices.

For help, check the [FAQ](#/documentation/faq). If the issue persists, open [My tickets](#/tickets) and include the time, client name, and error details with your key removed.$doc$)),
('billing', jsonb_build_object(
    'title', 'Top-ups and billing', 'category', 'Getting started',
    'summary', 'Understand wallet balance, subscriptions, payments, and credits.',
    'body', $doc$## Use your wallet balance

1. Open [Top up](#/payment) and choose an amount and available payment method.
2. Complete payment and return to confirm the order is credited.
3. Select **Balance billing** when creating your API key.

Payment amounts are in CNY. The credits you receive depend on the site's conversion rate and fees shown before payment. Changing the interface language does not convert currencies.

If an administrator gave you a redemption code, enter it on the top-up page. Each code can be used once.

## Use a Coding Plan

Visit [Coding Plan](#/coding-plans) to review a plan's price, quota, validity, and supported model categories. Purchase with your wallet balance, then choose **Coding Plan** as the API key's billing source.

Balance billing and Coding Plan billing are separate. A key using an expired or exhausted plan will not charge your wallet automatically. Check the key's category and billing source if a request is rejected.

## Check model prices

See [Model IDs](#/models) for current prices. The default is the official model cost in USD multiplied by the site's billing multiplier. A multiplier of 1.0 means the official cost; 0.1 means one tenth. Provider, series, or model overrides may apply.

The reference total price assumes equal input and output usage. Actual charges use the configured billing rule and actual token usage, including cache usage where applicable.

## Payment not credited

Do not repeatedly pay the same order. Check its status, wait briefly, and contact an administrator through [My tickets](#/tickets) if needed. Include the order number and payment time, with sensitive payment details removed.$doc$)),
('api-keys', jsonb_build_object(
    'title', 'Create and manage API keys', 'category', 'Getting started',
    'summary', 'Choose a billing source and keep each client key secure.',
    'body', $doc$## Create a key

Open [API keys](#/api-keys) and select **Create API key**. Enter a name, select a model category, and choose **Balance billing** or **Coding Plan**. Set any additional limits you need, then submit.

Use a separate key for each device or application, such as "Development laptop" or "Test script". Disable or delete keys you no longer use.

## Connect a client

After creating a key, open its **Client configuration**. Existing keys have the same action in the key list.

Use **Connect with CC Switch** when possible. Otherwise, copy the manual configuration or read [Manual API setup](#/documentation/manual-api).

## Keep the full key

Keys displayed with asterisks or an ellipsis are for display only and cannot authenticate requests. If a legacy key cannot be recovered in client configuration, use the full key you saved earlier or create a new key and update your client.

Never paste keys into Git, group chats, or support tickets. Disable a leaked key immediately and replace it.$doc$)),
('cc-switch', jsonb_build_object(
    'title', 'CC Switch setup', 'category', 'Connect a client',
    'summary', 'Recommended: import Claude Code, Codex CLI, or OpenCode configuration directly.',
    'body', $doc$## Download and install

Download the version for your system from the [CC Switch official website](https://ccswitch.io) or the [official GitHub releases page](https://github.com/farion1231/cc-switch/releases/latest).

CC Switch supports Windows, macOS, and Linux. It manages configurations, so install your intended client, such as Claude Code, Codex CLI, or OpenCode, separately.

## Import from this site

1. Open [API keys](#/api-keys) and find your key.
2. Select **Client configuration**.
3. Under **Connect with CC Switch**, choose a client matching the key's model category.
4. Click **Open** and allow your browser to launch CC Switch.
5. Check the imported site address and model, confirm, and activate the configuration in CC Switch.
6. Restart your client and send a short test request.

## CC Switch did not open

Confirm that CC Switch is installed and has been launched at least once, then try **Open** again. You can also use **Copy link** with a link-import feature supported by your CC Switch version. Otherwise, update CC Switch or use the manual client configuration.

Import links contain your full API key. Use them only on your own devices and never share them.

## Still cannot connect

Check that the site is reachable, your key is valid, and the selected billing source has credits. For a remote deployment, use the site's public domain; your local client cannot use the remote server's `127.0.0.1` address.

See [Manual API setup](#/documentation/manual-api) for other options.$doc$)),
('manual-api', jsonb_build_object(
    'title', 'Manual API setup', 'category', 'Connect a client',
    'summary', 'Configure other applications or test requests from the command line.',
    'body', $doc$## Your address and key

Site address:

```text
{{site_url}}
```

OpenAI-compatible Base URL:

```text
{{api_base_url}}
```

Enter the full key generated on this site into your application's API key field. Copy a model name from [Model IDs](#/models). Some applications add `/v1` automatically; avoid `/v1/v1`.

For Claude Code, Codex CLI, and OpenCode, prefer **Client configuration** on the [API keys](#/api-keys) page. It provides the appropriate protocol and address for each client.

## List available models

These examples work in Bash on macOS or Linux. On Windows, use WSL or Git Bash. Replace the placeholder with your full key.

```bash
export STARRY_API_KEY='YOUR_API_KEY'
curl '{{api_base_url}}/models' \
  -H "Authorization: Bearer $STARRY_API_KEY"
```

## Send a chat request

Replace `MODEL_ID` with a model accessible to your key. This example uses the OpenAI Chat Completions protocol. Models supporting only Responses or Anthropic Messages require the corresponding protocol and client configuration.

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

## Use Python

Install the OpenAI Python SDK:

```bash
python -m pip install openai
```

Set `STARRY_API_KEY` as above, then run:

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

Keep real keys out of source code. Test requests incur actual usage charges.$doc$)),
('faq', jsonb_build_object(
    'title', 'FAQ and support', 'category', 'Troubleshooting',
    'summary', 'Check common errors, configuration, and available credits.',
    'body', $doc$## 401 or invalid key

Use an API key from this site, not your sign-in password or a key from another platform. Check for extra spaces or a truncated display value. Replace expired or disabled keys.

## Insufficient balance or unavailable subscription

Check the key's billing source. Balance billing needs wallet credits; Coding Plan needs an active subscription with remaining quota and support for the selected model. They do not fall back to one another.

## Model not found or unsupported

Copy the full name from [Model IDs](#/models), confirm its category matches your key, and check that your client uses a compatible protocol. Try the client configuration action again if needed.

## 429 or too many requests

Wait briefly and reduce concurrent requests. If it persists, check key or plan limits and contact an administrator.

## Connection failure or 5xx

Confirm the site opens, then check that the Base URL does not contain `/v1` twice. Retry temporary errors later. Avoid rapid repeated requests that could create extra usage.

## Contact an administrator

Create a ticket in [My tickets](#/tickets). Include your client, time of occurrence, model ID, error message, and steps already tried. Screenshots and other attachments can help.

Remove full API keys, secrets from import links, and sensitive payment details before submitting.$doc$))
)
UPDATE documentation_pages p
SET draft = p.draft || jsonb_build_object('english', translations.content),
    published = p.published || jsonb_build_object('english', translations.content),
    version = p.version + 1
FROM translations
WHERE p.id = translations.id AND p.version = 1 AND p.deleted = FALSE
  AND p.draft = p.published AND NOT (p.draft ? 'english');
