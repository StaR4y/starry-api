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
```

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

## Missing First Startup Admin Password

The default local admin password is printed once in the startup guide logs when the `admin_users` table is empty:

```text
Default admin user created. Save this password now.
Username: admin
Password: ...
```

If you lost it, use an existing admin session to reset the user password:

```bash
curl -X POST http://127.0.0.1:8080/admin/users/{userId}/reset-password \
  -H 'Authorization: Bearer adm-...'
```

For a disposable local database only, you can drop and recreate the database to trigger first-start bootstrap again. Do not do this in production.

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

The password is never printed again after the first bootstrap. If it is lost, another admin must reset it, or a disposable local database can be recreated.

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
  -d '{"name":"default"}'
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
