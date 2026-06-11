# Configuration

Runtime configuration is read from environment variables through `service/src/main/resources/application.yml`.

The service is being refactored from a Kiro-only proxy into Starry API. Some runtime variable names still use the `KIRO_` prefix for deployment compatibility. Treat those as the current upstream-adapter/runtime variables, not as frontend or public API naming.

## Server

| Variable | Default | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port. |
| `SPRING_CODEC_MAX_IN_MEMORY_SIZE` | `16MB` | Max in-memory body size for WebFlux codecs. |
| `JAVA_OPTS` | empty | Optional JVM flags used by container/scripts. |

## MySQL

The service uses JDBC for Flyway migration and R2DBC for application queries.

| Variable | Default | Description |
| --- | --- | --- |
| `MYSQL_HOST` | `127.0.0.1` | MySQL host. |
| `MYSQL_PORT` | `3306` | MySQL port. |
| `MYSQL_DATABASE` | `kiro_proxy` | Database name. Kept for migration compatibility. |
| `MYSQL_USERNAME` | `root` | MySQL username. |
| `MYSQL_PASSWORD` | empty | MySQL password. |
| `MYSQL_POOL_INITIAL_SIZE` | `8` | R2DBC pool initial size. |
| `MYSQL_POOL_MAX_SIZE` | `80` | R2DBC pool max size. |
| `FLYWAY_ENABLED` | `true` | Run schema migration on startup. |

Create the default local database:

```sql
CREATE DATABASE IF NOT EXISTS kiro_proxy
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

## Runtime and Upstream Adapter

| Variable | Default | Description |
| --- | --- | --- |
| `KIRO_ADMIN_TOKEN` | empty | Optional legacy static admin token. Prefer local admin login. |
| `KIRO_WEBUI_URL` | empty | WebUI login URL printed in startup logs. |
| `KIRO_ADMIN_SESSION_TTL` | `12h` | Local admin session lifetime. |
| `KIRO_ALLOW_ANONYMOUS_PROXY` | `false` | Allow proxy calls without API key. Keep `false` publicly. |
| `KIRO_REQUEST_TIMEOUT` | `90s` | Upstream request timeout. |
| `KIRO_MAX_RETRIES` | `2` | Upstream retry count for recoverable errors. |
| `KIRO_RATE_LIMIT_PER_MINUTE` | `120` | Fixed-window per API key rate limit. `0` disables rate limit. |
| `KIRO_ACCOUNT_SELECTION_STRATEGY` | `round_robin` | `round_robin` or `sticky`. |
| `KIRO_SESSION_AFFINITY_ENABLED` | `true` | Route same session hint to same upstream account when possible. |
| `KIRO_SESSION_AFFINITY_TTL` | `10m` | Session affinity TTL. |
| `KIRO_BASE_COOLDOWN` | `60s` | Base cooldown for recoverable account errors. |
| `KIRO_QUOTA_RESET` | `1h` | Planned quota reset duration. |
| `KIRO_PROBABILISTIC_RETRY` | `0.1` | Chance to probe a cooling account early. |
| `KIRO_MAX_REQUEST_BODY_BYTES` | `10485760` | Max inbound request content length. |
| `KIRO_TOKEN_REFRESH_INTERVAL_MS` | `3600000` | Background Kiro token refresh interval. |
| `KIRO_TOKEN_REFRESH_INITIAL_DELAY_MS` | `60000` | Delay before first background token refresh. |
| `KIRO_PREFERRED_ENDPOINT` | `codewhisperer` | Preferred Kiro/AWS endpoint: `codewhisperer`, `amazonq`, or `amazonq-cli`. |

## Client-Side API Key Setup

The WebUI API Key page generates API-key based client configuration:

| Client | Generated configuration |
| --- | --- |
| Codex CLI | `~/.codex/config.toml` plus minimal `~/.codex/auth.json`, using the HTTP Responses API and a direct Starry API-key bearer token. |
| Claude Code | Shell environment variables: `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL`, `ANTHROPIC_SMALL_FAST_MODEL`, and Claude Code traffic flags. |
| OpenCode | Project-level `opencode.json` with a `starry` OpenAI-compatible provider. |
| CC Switch | `ccswitch://v1/import?resource=provider...` deep links for Claude Code, Codex CLI, and OpenCode. |

These client settings are generated per API key and do not replace server runtime variables. For Codex CLI, replace stale ChatGPT OAuth `auth.json` content with the generated API-key JSON if Codex reports that a refresh token was already used.

## Registration Settings

Registration is configured from the admin console under `账号 -> 注册设置`.

| Setting key | Purpose |
| --- | --- |
| `registration_enabled` | Enable public self-registration. |
| `email_verify_enabled` | Require email verification for public self-registration. |
| `invite_code_enabled` | Enable one-time invite-code registration. |
| `registration_email_suffix_whitelist` | Optional JSON email suffix whitelist. |
| `smtp_security` | SMTP encryption mode: `auto`, `ssl`, `starttls`, or `none`. |

When invite-code registration is enabled, administrators can generate one-time codes in the same settings dialog. Users pass `inviteCode` to `POST /auth/register`; a valid code allows direct registration even if public registration is disabled.

For Tencent Exmail, configure `smtp.exmail.qq.com:465` with `smtp_security=ssl`, or
`smtp.exmail.qq.com:587` with `smtp_security=starttls`.

## Payment Settings

Online balance recharge is configured from the admin console, not environment variables:

- Admin path: `支付设置`
- User path: `余额充值`
- Provider: EasyPay-compatible gateway
- Webhook: `GET/POST /payment/webhook/easypay`

The settings follow the sub2api-style keys stored in `system_settings`:

| Key | Purpose |
| --- | --- |
| `payment_enabled` | Enable user recharge order creation. |
| `MIN_RECHARGE_AMOUNT`, `MAX_RECHARGE_AMOUNT` | Per-order recharge amount range. `MAX_RECHARGE_AMOUNT = 0` means no maximum limit. |
| `DAILY_RECHARGE_LIMIT` | Per-user daily paid amount limit. `0` means no limit. |
| `ORDER_TIMEOUT_MINUTES` | Pending order expiry window. |
| `MAX_PENDING_ORDERS` | Maximum pending orders per user. |
| `ENABLED_PAYMENT_TYPES` | Comma-separated `alipay,wxpay`. |
| `BALANCE_RECHARGE_MULTIPLIER` | Credited balance multiplier. |
| `API_BILLING_MULTIPLIER` | Balance deducted per billable token. Default `0.0000166666666667`, so 100 balance covers about 6,000,000 tokens. |
| `RECHARGE_FEE_RATE` | Extra fee rate added to the payable amount. |
| `EASYPAY_API_BASE`, `EASYPAY_PID`, `EASYPAY_PKEY` | EasyPay gateway URL and merchant credentials. |
| `EASYPAY_PAYMENT_MODE` | `popup` for submit-page redirect, `qrcode` for `mapi.php`. |

`EASYPAY_PKEY` is only accepted from the admin update API and is never returned to the WebUI; the admin page only shows whether it has already been configured.

## Performance Metrics

The service records performance samples into `performance_metrics` every 30 seconds after startup:

- system CPU load
- process CPU load
- JVM heap and non-heap memory
- system memory
- Linux network receive/transmit counters and per-second rates

The admin dashboard reads `GET /admin/performance` and displays the latest 120 samples. Samples older than 7 days are purged by the scheduled sampler.

## Recommended Local Environment

```bash
export SERVER_PORT=8080
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_DATABASE=kiro_proxy
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=
export MYSQL_POOL_INITIAL_SIZE=8
export MYSQL_POOL_MAX_SIZE=80
export KIRO_WEBUI_URL=http://127.0.0.1:5173/#/login
export KIRO_ADMIN_SESSION_TTL=12h
export KIRO_ALLOW_ANONYMOUS_PROXY=false
export KIRO_RATE_LIMIT_PER_MINUTE=120

./gradlew :service:bootRun
```

## Account Selection

`round_robin`:

- Successful requests advance to the next available account.
- Better for distributing load across accounts.

`sticky`:

- Keeps using the same selected account when possible.
- Better for cache locality and conversation continuity.

Session affinity is applied before normal selection when a session hint is present.

## Production Defaults

- Save and change the first-start `admin` password immediately.
- Use local admin login for normal administration.
- Set `KIRO_ADMIN_TOKEN` only for emergency or automation paths.
- Keep `KIRO_ALLOW_ANONYMOUS_PROXY=false`.
- Put the service behind TLS.
- Use a dedicated MySQL user.
- Monitor `/actuator/health` and `/actuator/prometheus`.
- Disable Swagger/OpenAPI in public production environments if needed:

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```
