# Starry API

Starry API is a Spring Boot + Vue management service for routing multiple LLM client protocols through a unified model and account layer.

The current runtime supports OpenAI-compatible, Anthropic-compatible, and Gemini-compatible inbound APIs. GPT/OpenAI model requests can use OpenAI API key or Codex token accounts directly, while Claude/Kiro-compatible requests use the Kiro/AWS CodeWhisperer upstream path isolated under `platform/kiro`. Provider-neutral response data lives in `platform/core`, so inbound protocol modules format a common completion result instead of depending on Kiro-specific records.

## Features

- OpenAI-compatible `GET /v1/models`
- OpenAI-compatible `POST /v1/chat/completions`
- OpenAI Responses-style `POST /v1/responses`
- Anthropic Messages-style `POST /v1/messages`
- Gemini GenerateContent-style `POST /v1beta/models/{model}:generateContent`
- Platform-scoped backend modules for OpenAI, Anthropic, Gemini, Kiro upstream, shared core records, and shared model catalog
- Platform-scoped frontend modules for provider registry, model grouping, and client setup scripts
- Admin APIs for upstream accounts, API keys, model mappings, request logs, and local administrators
- Account management supports Kiro accounts plus OpenAI API key and Codex token import/testing/routing, automatic health-test suspension after consecutive failures, and total/5h/7day quota progress.
- WebFlux, R2DBC MySQL, Flyway migrations, API key accounting, rate limits, account failover, and session affinity
- Vue/Vite management console embedded into the Spring Boot jar
- OpenAPI UI at `/docs`

## Requirements

- Java 25
- MySQL reachable by JDBC and R2DBC
- Node.js/npm for WebUI builds
- Gradle wrapper included in this repository

## Quick Start

Create the database:

```sql
CREATE DATABASE IF NOT EXISTS kiro_proxy
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

`kiro_proxy` remains the default database name for migration compatibility. You can override it with `MYSQL_DATABASE`.

Run the service:

```bash
export MYSQL_HOST=127.0.0.1
export MYSQL_PORT=3306
export MYSQL_DATABASE=kiro_proxy
export MYSQL_USERNAME=root
export MYSQL_PASSWORD=

./gradlew :service:bootRun
```

Or use the startup helper:

```bash
./scripts/start-proxy.sh
```

Check the service:

```bash
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/v1/models
```

On first startup, the service creates a local admin account:

```text
username: admin
password: <random password printed in startup logs once>
```

Open:

```text
http://127.0.0.1:8080/#/login
http://127.0.0.1:8080/docs
```

## Minimal Setup Flow

Login and export the returned admin token:

```bash
curl -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<startup-password>"}'

export ADMIN_TOKEN="adm-..."
```

If this is the first login, change the generated password:

```bash
curl -X POST http://127.0.0.1:8080/auth/password \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"oldPassword":"<startup-password>","newPassword":"<new-password>"}'
```

Optional: enable public admin registration with email verification:

```bash
curl -X PATCH http://127.0.0.1:8080/admin/users/registration-settings \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "registrationEnabled": true,
    "emailVerifyEnabled": true,
    "registrationEmailSuffixWhitelist": ["@example.com"],
    "smtpHost": "smtp.example.com",
    "smtpPort": 587,
    "smtpUsername": "noreply@example.com",
    "smtpPassword": "<smtp-password>",
    "smtpFromEmail": "noreply@example.com",
    "smtpFromName": "Starry API",
    "smtpUseTls": true,
    "smtpSecurity": "starttls"
  }'
```

For Tencent Exmail, use `smtpHost=smtp.exmail.qq.com`, `smtpPort=465`, and
`smtpSecurity=ssl`. Port `587` should use `smtpSecurity=starttls`.

Create a client API key:

```bash
curl -X POST http://127.0.0.1:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"default","creditsLimit":1000}'
```

API keys are bound to the logged-in local user. Normal users only see and manage their own keys; administrators can see all keys with their owner. New keys can be copied later from the console because the owning key record stores the full `sk-...` value.

Add an upstream Kiro account:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "email": "user@example.com",
    "accessToken": "kiro-access-token",
    "region": "us-east-1",
    "authMethod": "idc",
    "provider": "BuilderID"
  }'
```

Create an OpenAI account for account management tests:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "email": "team@example.com",
    "accessToken": "sk-...",
    "authMethod": "api-key",
    "provider": "OpenAI",
    "baseUrl": "https://api.openai.com/v1"
  }'
```

Call through OpenAI-compatible Chat Completions:

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer sk-REPLACE_ME' \
  -d '{
    "model": "gpt-4o",
    "messages": [
      {"role": "user", "content": "Say hello in one sentence."}
    ],
    "max_tokens": 256
  }'
```

Call through Gemini-compatible GenerateContent:

```bash
curl -X POST http://127.0.0.1:8080/v1beta/models/gemini-2.5-pro:generateContent \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer sk-REPLACE_ME' \
  -d '{
    "contents": [
      {"role": "user", "parts": [{"text": "Say hello in one sentence."}]}
    ],
    "generationConfig": {"maxOutputTokens": 256}
  }'
```

## Module Layout

```text
api
  Spring API contracts and shared DTOs

service
  admin / account / apikey / model / stats / config / common
  proxy                     Core routing, auth, rate limit, accounting
  platform/core             Provider-neutral completion result, usage, tool-use records
  platform/catalog          Unified model catalog
  platform/openai           OpenAI request/response/catalog module
  platform/anthropic        Anthropic request/response/catalog module
  platform/gemini           Gemini request/response/catalog module
  platform/kiro             Kiro/AWS upstream adapter module
  platform/upstream         Shared upstream error abstraction

webui
  src/api                   Typed admin/proxy API clients
  src/platforms             Provider registry and platform-scoped setup modules
    openai / anthropic / gemini / opencode
  src/views                 Management pages
  src/stores                Pinia auth state
```

## Documentation

- [API Reference](docs/API.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Configuration](docs/CONFIGURATION.md)
- [Package Guide](docs/PACKAGE_GUIDE.md)
- [Platform Module Plan](docs/PLAN.md)
- [Server One-Click Install](docs/INSTALL_SERVER.md)
- [1Panel Deployment](docs/1PANEL_DEPLOY.md)
- [Prebuilt Artifact Deployment](docs/PREBUILT_ARTIFACTS.md)
- [Operations](docs/OPERATIONS.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)

## Verification

```bash
./gradlew test
npm --prefix webui run build
```

For a full packaged jar:

```bash
./gradlew :service:bootJar
```

To update the checked-in deployable bundle after a source commit:

```bash
./scripts/publish-artifact.sh
git add artifacts
git commit -m "chore: publish prebuilt artifact"
git push
```

Servers should not run this publishing script. To deploy the already checked-in bundle on a server:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

For a private repository, save a read-only Contents GitHub token once on the server. The installer will reuse it automatically:

```bash
sudo mkdir -p /opt/starry-api
sudo install -m 600 /dev/null /opt/starry-api/.github-token
sudo sh -c 'printf "%s" "replace-with-token" > /opt/starry-api/.github-token'
```

Then update with:

```bash
GITHUB_TOKEN="$(sudo cat /opt/starry-api/.github-token)"
curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  -o /tmp/install-server.sh
sudo bash /tmp/install-server.sh
```

## Current Boundaries

- OpenAI/GPT requests route to OpenAI API key or Codex token accounts.
- Claude/Kiro-compatible requests route to the Kiro/AWS upstream adapter.
- Anthropic and Gemini are currently inbound compatibility modules unless mapped to a supported upstream route.
- Inbound protocol response factories consume `PlatformCompletionResult`; they should not import Kiro result records.
- `stream=true` still emits SSE after upstream completion; true chunk passthrough is a later streaming milestone.
- Image/document/file conversion is partial.
- Distributed rate limiting is not implemented.
