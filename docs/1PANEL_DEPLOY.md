# 1Panel Deployment

This deployment uses Docker Compose in 1Panel:

```text
1Panel reverse proxy / HTTPS
  -> server-ip:18080
  -> starry-api service container
  -> PostgreSQL 17 container
```

Docker-based 1Panel/OpenResty cannot use the host's `127.0.0.1` as an upstream. The installer binds the service to `0.0.0.0:${HOST_HTTP_PORT}` by default and prints a reverse proxy target using the server IP. Restrict `${HOST_HTTP_PORT}` with your firewall/security group and expose the service publicly through the 1Panel HTTPS site.

## One-Click Server Install

On a fresh Linux server, you can let the installer prepare Docker, download the checked-in `artifacts/starry-api-bundle.tar.gz`, generate `.env`, and start the stack:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://your-domain.example bash
```

For a private repository, save a GitHub token with read-only Contents permission once on the server:

```bash
sudo mkdir -p /opt/starry-api
sudo install -m 600 /dev/null /opt/starry-api/.github-token
sudo sh -c 'printf "%s" "replace-with-token" > /opt/starry-api/.github-token'
```

Then run updates without retyping the token:

```bash
GITHUB_TOKEN="$(sudo cat /opt/starry-api/.github-token)"
curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  -o /tmp/install-server.sh
sudo env PUBLIC_BASE_URL=https://your-domain.example bash /tmp/install-server.sh
```

After it finishes, create the 1Panel reverse proxy site with target:

```text
http://<server-ip>:18080
```

Manual source-build deployment steps are kept below for servers that build from the repository.

## 1. Prepare Server Directory

Open the 1Panel terminal or SSH into the server:

```bash
mkdir -p /opt/starry-api
cd /opt/starry-api
git clone https://github.com/StaR4y/starry-api.git .
```

For future source-build updates:

```bash
cd /opt/starry-api
git pull
docker compose up -d --build service
```

The service image includes the current WebUI build. If 1Panel still shows the old page after an update, rebuild the service image without cache and then refresh the browser cache:

```bash
cd /opt/starry-api
docker compose build --no-cache service
docker compose up -d service
docker compose logs --tail=160 service
```

## 2. Configure Environment

Create `.env`:

```bash
cp .env.example .env
nano .env
```

Set three distinct strong PostgreSQL passwords. `POSTGRES_BOOTSTRAP_PASSWORD` protects the database-level break-glass superuser and is never used by the application; restrict and audit access to it. The database and role names are fixed by the least-privilege bootstrap:

```dotenv
POSTGRES_BOOTSTRAP_PASSWORD=replace-with-strong-bootstrap-password
POSTGRES_MIGRATION_PASSWORD=replace-with-strong-migration-password
POSTGRES_PASSWORD=replace-with-strong-application-password
```

Recommended defaults:

```dotenv
POSTGRES_MAX_CONNECTIONS=100
POSTGRES_SHARED_BUFFERS=256MB
POSTGRES_EFFECTIVE_CACHE_SIZE=768MB
POSTGRES_POOL_INITIAL_SIZE=4
POSTGRES_POOL_MAX_SIZE=20
HOST_BIND_ADDR=0.0.0.0
HOST_HTTP_PORT=18080
KIRO_ALLOW_ANONYMOUS_PROXY=false
KIRO_ADMIN_SESSION_TTL=12h
KIRO_RATE_LIMIT_PER_MINUTE=120
KIRO_WEBUI_URL=https://your-domain.example/#/login
```

The Compose stack initializes database `starry_api`; Flyway creates and uses schema `starry_api`. Database files are persisted under `./data/postgresql`. These database defaults assume at least 2 GiB available memory; keep the sum of application pool maxima below the server connection ceiling and retune from production metrics when the workload grows.

Do not enable `KIRO_ALLOW_ANONYMOUS_PROXY=true` on a public service.

## 3. Start With Compose

Run:

```bash
./scripts/deploy-1panel.sh
```

The script will:

- verify Docker Compose is available
- refuse default database passwords
- build the Spring Boot service image
- start PostgreSQL 17 and the service
- print recent service logs

Manual equivalent:

```bash
docker compose up -d --build
docker compose logs -f service
```

## 4. Save First Admin Password

On the first successful startup, service logs include:

```text
Default admin user created. Save this password now.
Username: admin
Password: ...
```

The password is printed only when the application Root marker is unassigned and the
`admin_users` table is empty. It is not printed again.

If the second startup still sees `firstLogin=true`, logs only show a reminder:

```text
Default admin user already exists and still uses the first-login password.
The password was printed only during first startup and will not be shown again.
```

## 5. Configure 1Panel Reverse Proxy

In 1Panel:

```text
Website -> Create Website -> Reverse Proxy
```

Use:

```text
Proxy target: http://<server-ip>:18080
```

Then bind your domain and enable HTTPS in 1Panel.

If OpenResty/Nginx runs directly on the host, you may instead set `HOST_BIND_ADDR=127.0.0.1` in `.env` and proxy to `http://127.0.0.1:18080`. For the default 1Panel containerized OpenResty, using `127.0.0.1` as the proxy target usually points to the OpenResty container itself and results in `502 Bad Gateway`.

Recommended public base URL:

```text
https://your-domain.example
```

Set `KIRO_WEBUI_URL` in `.env` to the public WebUI login page, so startup logs point to the browser login page instead of only the API login endpoint:

```dotenv
KIRO_WEBUI_URL=https://your-domain.example/#/login
```

## 6. Login and Change Admin Password

Login:

```bash
curl -X POST https://your-domain.example/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<first-startup-password>"}'
```

Export `data.accessToken`:

```bash
export ADMIN_TOKEN="adm-..."
```

First-login sessions can only change password. Other admin APIs return `403 FIRST_LOGIN_PASSWORD_CHANGE_REQUIRED`.

Change password:

```bash
curl -X POST https://your-domain.example/auth/password \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"oldPassword":"<first-startup-password>","newPassword":"<new-password>"}'
```

The change revokes every existing console session for the account. Sign in again with the new password before continuing.

## Emergency Data-Management Recovery

If five incorrect data-management security-key attempts lock the site, keep public traffic isolated and make sure only one service instance is active. The Docker image includes the reviewed recovery utility; from the 1Panel terminal or an interactive SSH session run:

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# English prompts: use --lang en.

docker compose -f docker-compose.artifact.yml restart service
# Wait for the service to finish starting, then verify:
docker compose -f docker-compose.artifact.yml exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming && echo "recovery key files consumed"'
```

The utility hides the original `dmsk-...` key and requires identical double entry. Do not add `-T`, pass the key in an argument, or pipe a downloaded recovery script through `curl ... | sh`. A normal matching attempt returns the site to `"maintenance":false`; a normal readable, valid-format but nonmatching attempt is audited and consumed while maintenance remains enabled, so rerun the utility and restart with the correct key. If either recovery path remains, cleanup was not confirmed: do not stage another key or delete files manually, and follow the storage/log procedure in [Operations](OPERATIONS.md#recover-a-docker-compose-deployment). Source-build deployments using `docker-compose.yml` omit `-f docker-compose.artifact.yml`.

## 7. Import Kiro Upstream Accounts

Create an import file on the server:

```bash
nano accounts.import.json
```

Kiro Account Manager export format can be used directly:

```json
{
  "version": "kiro-account-manager",
  "exportedAt": 1760000000000,
  "upsert": true,
  "accounts": [
    {
      "id": "kiro-main",
      "email": "user@example.com",
      "idp": "BuilderId",
      "credentials": {
        "accessToken": "kiro-access-token",
        "refreshToken": "optional-refresh-token",
        "region": "us-east-1",
        "authMethod": "IdC",
        "provider": "BuilderId"
      }
    }
  ],
  "groups": [],
  "tags": []
}
```

Import:

```bash
curl -X POST https://your-domain.example/admin/accounts/import \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  --data-binary @accounts.import.json
```

Check:

```bash
curl https://your-domain.example/admin/accounts \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 8. Create Client API Key

```bash
curl -X POST https://your-domain.example/admin/api-keys \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"default","creditsLimit":1000,"modelProvider":"OpenAI"}'
```

Save `data.key`. Current keys are stored per owner and can also be copied later from the API Key page; legacy hash-only keys cannot be recovered.

Client configuration:

```text
Base URL: https://your-domain.example/v1
API Key:  sk-...
```

## Operations

View logs:

```bash
docker compose logs -f service
```

Restart:

```bash
docker compose restart service
```

Update:

```bash
git pull
./scripts/deploy-1panel.sh
```

Stop:

```bash
docker compose down
```

Do not delete `./data/postgresql` unless this is a disposable deployment. Removing it permanently deletes the PostgreSQL cluster.
