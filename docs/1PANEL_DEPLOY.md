# 1Panel Deployment

This deployment uses Docker Compose in 1Panel:

```text
1Panel reverse proxy / HTTPS
  -> server-ip:18080
  -> starry-api service container
  -> mysql container
```

Docker-based 1Panel/OpenResty cannot use the host's `127.0.0.1` as an upstream. The installer binds the service to `0.0.0.0:${HOST_HTTP_PORT}` by default and prints a reverse proxy target using the server IP. Restrict `${HOST_HTTP_PORT}` with your firewall/security group and expose the service publicly through the 1Panel HTTPS site.

## One-Click Server Install

On a fresh Linux server, you can let the installer prepare Docker, download the checked-in `artifacts/starry-api-bundle.tar.gz`, generate `.env`, and start the stack:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://your-domain.example bash
```

For a private repository, pass a GitHub token with read-only Contents permission:

```bash
GITHUB_TOKEN=replace-with-token
curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env GITHUB_TOKEN="${GITHUB_TOKEN}" PUBLIC_BASE_URL=https://your-domain.example bash
```

After it finishes, create the 1Panel reverse proxy site with target:

```text
http://<server-ip>:18080
```

Manual source-build deployment steps are kept below for existing servers or custom MySQL setups.

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

Set strong database passwords:

```dotenv
MYSQL_ROOT_PASSWORD=replace-with-strong-root-password
MYSQL_PASSWORD=replace-with-strong-db-password
```

Recommended defaults:

```dotenv
MYSQL_IMAGE=mysql:8.0.37-oraclelinux8
HOST_BIND_ADDR=0.0.0.0
HOST_HTTP_PORT=18080
KIRO_ALLOW_ANONYMOUS_PROXY=false
KIRO_ADMIN_SESSION_TTL=12h
KIRO_RATE_LIMIT_PER_MINUTE=120
KIRO_WEBUI_URL=https://your-domain.example/#/login
```

`MYSQL_IMAGE=mysql:8.0.37-oraclelinux8` rolls MySQL back to an Oracle Linux 8 image and avoids `Fatal glibc error: CPU does not support x86-64-v2` on older VPS CPUs.

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
- start MySQL and the service
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

The password is printed only when the `admin_users` table is empty. It is not printed again.

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
  -d '{"name":"default","creditsLimit":1000}'
```

Save `data.key`. It is shown only once.

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

Do not delete `./data/mysql` unless this is a disposable deployment.
