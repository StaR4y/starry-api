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

## Client-Side Environment Variables

The WebUI API Key page now generates Starry API client variables:

| Variable | Purpose |
| --- | --- |
| `STARRY_API_BASE_URL` | Generic base URL, usually `http://host:8080/v1`. |
| `STARRY_API_KEY` | Generated `sk-...` API key. |
| `OPENAI_BASE_URL`, `OPENAI_API_KEY` | OpenAI-compatible clients. |
| `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` | Anthropic/Claude-compatible clients. |
| `GEMINI_BASE_URL`, `GEMINI_API_KEY`, `GOOGLE_API_KEY` | Gemini-compatible clients. |
| `OPENCODE_STARRY_API_BASE_URL`, `OPENCODE_STARRY_API_KEY`, `OPENCODE_MODEL`, `OPENCODE_SMALL_MODEL` | OpenCode custom `starry` provider variables. |

These client variables do not replace server runtime variables yet.

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
| `MIN_RECHARGE_AMOUNT`, `MAX_RECHARGE_AMOUNT` | Per-order recharge amount range. |
| `DAILY_RECHARGE_LIMIT` | Per-user daily paid amount limit. `0` means no limit. |
| `ORDER_TIMEOUT_MINUTES` | Pending order expiry window. |
| `MAX_PENDING_ORDERS` | Maximum pending orders per user. |
| `ENABLED_PAYMENT_TYPES` | Comma-separated `alipay,wxpay`. |
| `BALANCE_RECHARGE_MULTIPLIER` | Credited balance multiplier. |
| `RECHARGE_FEE_RATE` | Extra fee rate added to the payable amount. |
| `EASYPAY_API_BASE`, `EASYPAY_PID`, `EASYPAY_PKEY` | EasyPay gateway URL and merchant credentials. |
| `EASYPAY_PAYMENT_MODE` | `popup` for submit-page redirect, `qrcode` for `mapi.php`. |

`EASYPAY_PKEY` is only accepted from the admin update API and is never returned to the WebUI; the admin page only shows whether it has already been configured.

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
