# Troubleshooting

## `R2DBC connection pooling configuration ... both have been used`

Cause:

`spring.r2dbc.url` used `r2dbc:pool:mysql://...` while `spring.r2dbc.pool.*` was also configured.

Expected config:

```yaml
spring:
  r2dbc:
    url: r2dbc:mysql://127.0.0.1:3306/kiro_proxy
    pool:
      initial-size: 8
      max-size: 80
```

Do not use `r2dbc:pool:mysql://` when Spring Boot pool properties are enabled.

## `Connection refused: /127.0.0.1:3306`

Cause:

MySQL is not running or is listening on a different host/port.

Check:

```bash
nc -z 127.0.0.1 3306
brew services list | grep mysql
```

Start Homebrew MySQL:

```bash
brew services start mysql
```

Create database:

```bash
mysql -uroot -e "CREATE DATABASE IF NOT EXISTS kiro_proxy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

## `Table 'kiro_proxy.model_mappings' doesn't exist`

Possible causes:

- Flyway has not run yet.
- The database is not the one configured by `MYSQL_DATABASE`.
- A previous failed startup left schema history inconsistent.

Check tables:

```bash
mysql -uroot -e "USE kiro_proxy; SHOW TABLES;"
```

Check Flyway history:

```bash
mysql -uroot -e "USE kiro_proxy; SELECT * FROM flyway_schema_history;"
```

For a fresh local database, the simplest reset is:

```bash
mysql -uroot -e "DROP DATABASE IF EXISTS kiro_proxy; CREATE DATABASE kiro_proxy CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
./gradlew :service:bootRun
```

Do not drop a production database.

## MySQL Container Logs `CPU does not support x86-64-v2`

Cause:

Some newer MySQL Docker tags are based on Oracle Linux 9, whose glibc requires the `x86-64-v2` CPU baseline. Older VPS CPUs cannot run those containers.

Fix the compose deployment by rolling MySQL back to the Oracle Linux 8 image:

```bash
cd /opt/starryapi-service
grep -q '^MYSQL_IMAGE=' .env \
  && sed -i 's|^MYSQL_IMAGE=.*|MYSQL_IMAGE=mysql:8.0.37-oraclelinux8|' .env \
  || echo 'MYSQL_IMAGE=mysql:8.0.37-oraclelinux8' >> .env
docker compose pull mysql
docker compose up -d --force-recreate mysql
docker compose up -d --build service
docker compose logs -f mysql
```

The default project compose file already uses this compatible MySQL 8.0 image.

## WebUI Does Not Change After Installer Update

Cause:

The installer pulls the latest repo and runs `docker compose up -d --build`. If the service image build fails, Docker Compose keeps the previous running container, so the old embedded WebUI is still served.

Check the build and service logs:

```bash
cd /opt/starryapi-service
docker compose build --no-cache service
docker compose up -d service
docker compose logs --tail=160 service
```

The successful image build must run the WebUI build and then `:service:bootJar`. The final jar should contain `BOOT-INF/classes/static/index.html`.

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
