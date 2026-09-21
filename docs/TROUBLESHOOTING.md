# Troubleshooting

## `R2DBC connection pooling configuration ... both have been used`

Cause:

`spring.r2dbc.url` used the `r2dbc:pool:` protocol while `spring.r2dbc.pool.*` was also configured.

Expected config:

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/starry_api?schema=starry_api
    pool:
      initial-size: 4
      max-size: 20
```

Do not use `r2dbc:pool:postgresql://` when Spring Boot pool properties are enabled.

## PostgreSQL connection attempts keep retrying

Cause:

PostgreSQL 17 is not running, is listening on a different host/port, or the JVM is routing the database socket through a system SOCKS proxy. On macOS, a proxy configured for `127.0.0.1` can intercept numeric loopback addresses even when `localhost` is in Java's non-proxy host list. Direct local runs therefore default to `localhost`.

Check:

```bash
pg_isready -h localhost -p 5432 -d starry_api
brew services list | grep postgresql
```

If `pg_isready` succeeds but Flyway reports a nested `UnknownHostException` for `127.0.0.1`, remove an explicit `POSTGRES_HOST=127.0.0.1` override or set:

```bash
export POSTGRES_HOST=localhost
```

Start Homebrew PostgreSQL 17:

```bash
brew services start postgresql@17
```

Create the database and least-privilege roles on a fresh local cluster:

```bash
export POSTGRES_USER=postgres
export POSTGRES_DB=starry_api
export STARRY_POSTGRES_MIGRATION_PASSWORD='<strong-local-migration-password>'
export STARRY_POSTGRES_APP_PASSWORD='<strong-local-application-password>'
createdb --username "$POSTGRES_USER" --encoding=UTF8 "$POSTGRES_DB"
./docker/postgresql/init/001-starry-api-roles.sh
```

## `relation "model_mappings" does not exist`

Possible causes:

- Flyway has not run yet.
- The database is not the one configured by `POSTGRES_DATABASE`.
- The connection `search_path` does not include schema `starry_api`.
- A previous failed startup left schema history inconsistent.

Check tables:

```bash
PGPASSWORD="$POSTGRES_PASSWORD" psql \
  -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
  -U "${POSTGRES_USERNAME:-starry_api}" -d starry_api \
  -c '\dt starry_api.*'
```

Check Flyway history:

```bash
PGPASSWORD="$POSTGRES_PASSWORD" psql \
  -h "${POSTGRES_HOST:-localhost}" -p "${POSTGRES_PORT:-5432}" \
  -U "${POSTGRES_USERNAME:-starry_api}" -d starry_api \
  -c 'SELECT installed_rank, version, description, success FROM starry_api.flyway_schema_history ORDER BY installed_rank'
```

For a fresh local database, the simplest reset is:

```bash
dropdb --if-exists starry_api
createdb --owner=starry_api_owner --encoding=UTF8 starry_api
psql --dbname=starry_api <<'SQL'
REVOKE ALL ON DATABASE starry_api FROM PUBLIC;
GRANT CONNECT ON DATABASE starry_api TO starry_api_migration, starry_api_runtime;
CREATE SCHEMA starry_api AUTHORIZATION starry_api_owner;
REVOKE ALL ON SCHEMA starry_api FROM PUBLIC;
GRANT USAGE ON SCHEMA starry_api TO starry_api_runtime;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
./gradlew :service:bootRun
```

Do not drop a production database.

## PostgreSQL Container Is Unhealthy

Cause:

The PostgreSQL process may still be initializing, the volume may be inaccessible, or the configured credentials may not match an existing data directory.

Inspect the container and readiness probe:

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml ps postgres
docker compose -f docker-compose.artifact.yml logs --tail=200 postgres
docker compose -f docker-compose.artifact.yml exec postgres \
  pg_isready -U starry_api -d starry_api
```

The checked-in Compose baseline uses `postgres:17-bookworm`, data checksums, and `./data/postgresql`. Changing `POSTGRES_PASSWORD` does not alter credentials in an already initialized cluster; update the role password explicitly or restore the prior `.env` value. Never remove a production volume as a troubleshooting shortcut.

## Tencent Exmail SMTP SSL Handshake Fails

Symptom:

```text
Could not connect to SMTP host: smtp.exmail.qq.com, port: 465
javax.net.ssl.SSLHandshakeException: Remote host terminated the handshake
```

For Tencent Exmail, `smtp.exmail.qq.com:465` must use implicit SSL. If the
server still terminates the handshake, check whether the SMTP host resolves to a
local proxy fake IP:

```bash
dig +short smtp.exmail.qq.com
openssl s_client -connect smtp.exmail.qq.com:465 -servername smtp.exmail.qq.com -tls1_2 </dev/null
```

If the address is in `198.18.0.0/15`, the host is being handled by a local
proxy/DNS fake-IP mode rather than Tencent's SMTP server. Add a DIRECT or
fake-ip-filter rule for `smtp.exmail.qq.com` and `hwsmtp.exmail.qq.com`, or
disable fake-IP mode for SMTP domains.

Recommended settings:

```text
SMTP host: smtp.exmail.qq.com
SMTP port: 465
SMTP security: SSL / TLS
SMTP username: the full Exmail address
SMTP from email: the same address as the username
```

## Tencent Exmail Rejects MAIL FROM

Symptom:

```text
501 mail from address must be same as authorization user
```

Exmail requires the SMTP envelope From address to equal the login username.
Set `smtpFromEmail` to the same mailbox as `smtpUsername` (for example both
`admin@company.com`). A display name can still differ via `smtpFromName`.
A `198.18.0.0/15` fake-IP in DNS is unrelated when the server already returned
this 501; the session reached Exmail and was rejected for the From address.

## WebUI Does Not Change After Installer Update

Cause:

The installer downloads the checked-in `artifacts/starry-api-bundle.tar.gz`. If the artifact bundle was not refreshed and pushed after frontend changes, the server will correctly deploy the old embedded WebUI.

Check the installed artifact metadata and service logs:

```bash
cd /opt/starry-api
cat app/build-info.json
sha256sum -c app/starry-api.jar.sha256
docker compose -f docker-compose.artifact.yml logs --tail=160 service
```

Refresh the artifact bundle on a developer machine or CI, commit `artifacts/`, push it, then rerun `scripts/install-server.sh` on the server.

## `Port 8080 was already in use`

Check the process:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

Use another port:

```bash
SERVER_PORT=8081 ./gradlew :service:bootRun
```

Or stop the process that owns the port.

## APIs Return `503 SYSTEM_MAINTENANCE`

Confirm that this is the fail-closed data-management hard lock rather than an ordinary upstream outage:

```bash
curl -fsS http://127.0.0.1:18080/system/status
```

If the response contains `"maintenance":true` and `"code":"SYSTEM_MAINTENANCE"`, do not delete the security state or restart repeatedly. Keep public traffic isolated, make sure exactly one `service` container is active, and use the utility already packaged in the image:

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# Use --lang en for English prompts.
docker compose -f docker-compose.artifact.yml restart service
# Wait for startup, then verify:
docker compose -f docker-compose.artifact.yml exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming'
```

Run the utility from a real TTY without `exec -T`; it hides the original `dmsk-...` key and requires identical double entry. A normal matching attempt clears maintenance and consumes both recovery paths. A normal readable, valid-format but nonmatching attempt records `RECOVERY_FAILED`, consumes both paths, and leaves maintenance active, so run the utility and restart again with the correct key. Do not place the key in arguments, pipes, `.env`, or a downloaded `curl ... | sh` recovery script. Source Compose deployments omit `-f docker-compose.artifact.yml`; direct host/JAR recovery is documented in [Operations](OPERATIONS.md#recover-a-direct-hostjar-deployment).

If maintenance remains active after a matching key, or either file still exists, cleanup was not confirmed and recovery is not complete. Do not rerun the utility or delete the files manually. Inspect the security volume and service log without reading the key contents:

```bash
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'ls -ld /app/security; ls -l /app/security/recovery.key /app/security/.recovery.key.consuming 2>/dev/null || true'
docker compose -f docker-compose.artifact.yml logs --tail=200 service
```

Repair the volume's ownership, permissions, free-space, or filesystem error and restart `service`. Startup retries staged cleanup; the service remains fail-closed until cleanup, deletion, and directory synchronization succeed. Escalate for audited operator recovery if the staged file cannot be cleared after repairing storage.

## Missing First Startup Admin Password

The application Root password is printed once when first-start bootstrap finds both an unassigned Root marker and an empty `admin_users` table:

```text
Default admin user created. Save this password now.
Username: admin
Password: ...
```

If a non-Root account loses its password, an administrator can reset that account:

```bash
curl -X POST http://127.0.0.1:8080/admin/users/{userId}/reset-password \
  -H 'Authorization: Bearer adm-...'
```

Root is different: another administrator cannot reset or replace it. If a valid Root session still exists, Root can change or self-reset its password and all Root sessions will then be revoked. If both the Root password and every Root session are lost, the application deliberately does not create a replacement. For a disposable local database only, drop and recreate the entire database to trigger first-start bootstrap; never use that procedure on production data. Production recovery requires stopping traffic and performing an audited database-owner recovery or restoring a trusted backup.

## Admin APIs Return `403 FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED`

Cause:

The admin account has logged in with a first-start or reset password and has not changed it yet.

Fix:

```bash
curl -X POST http://127.0.0.1:8080/auth/password \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer adm-...' \
  -d '{"oldPassword":"<current-password>","newPassword":"<new-password>"}'
```

Password changes revoke all existing console sessions; sign in again with the new password.

The Root password is never printed again after first bootstrap and cannot be reset by another administrator. New passwords must contain 12 to 128 characters.

## Admin APIs Return `401`

Cause:

The request is missing a local admin session token, the session has expired, or the optional legacy `KIRO_ADMIN_TOKEN` does not match.

Login with the local admin account:

```bash
curl -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<password-from-startup-logs>"}'
```

Then call admin APIs with the returned `data.accessToken`:

```bash
curl http://127.0.0.1:8080/admin/accounts \
  -H 'Authorization: Bearer adm-...'
```

If `KIRO_ADMIN_TOKEN` is configured, legacy token auth is also accepted:

```bash
curl http://127.0.0.1:8080/admin/accounts \
  -H 'X-Admin-Token: change-me'
```

## Proxy APIs Return `401`

Cause:

Missing or invalid API key.

Create a key:

```bash
curl -X POST http://127.0.0.1:8080/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer adm-...' \
  -d '{"name":"default","modelProvider":"OpenAI"}'
```

Use returned `data.key`:

```bash
curl http://127.0.0.1:8080/v1/models \
  -H 'Authorization: Bearer sk-...'
```

## Proxy APIs Return `503 NO_AVAILABLE_ACCOUNT`

Cause:

No enabled upstream account is available.

Check accounts:

```bash
curl http://127.0.0.1:8080/admin/accounts \
  -H 'Authorization: Bearer adm-...'
```

Add an account:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer adm-...' \
  -d '{"email":"user@example.com","accessToken":"kiro-access-token","region":"us-east-1"}'
```

Reset a suspended/cooling account:

```bash
curl -X POST http://127.0.0.1:8080/admin/accounts/{accountId}/reset \
  -H 'Authorization: Bearer adm-...'
```

## Proxy APIs Return `429`

Possible causes:

- API key `creditsLimit` has been reached.
- `KIRO_RATE_LIMIT_PER_MINUTE` has been reached.
- The active upstream adapter returned quota/rate-limit errors.

Check API key usage:

```bash
curl http://127.0.0.1:8080/admin/api-keys \
  -H 'Authorization: Bearer adm-...'
```

## macOS Netty DNS Warning

Warning:

```text
Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider
```

The project includes the Apple Silicon native dependency:

```gradle
runtimeOnly 'io.netty:netty-resolver-dns-native-macos:4.1.132.Final:osx-aarch_64'
```

If you run on Intel macOS, use the `osx-x86_64` classifier instead.

## Useful Verification Commands

```bash
./gradlew test
./gradlew build
curl http://127.0.0.1:8080/health
curl http://127.0.0.1:8080/v1/models
curl http://127.0.0.1:8080/actuator/health
```
