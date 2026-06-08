# Server One-Click Install

This installer is for Linux servers. It deploys the project with Docker Compose:

```text
Nginx / 1Panel reverse proxy
  -> server-ip:18080
  -> starry-api service container with embedded WebUI
  -> mysql container
```

The installer binds the service port to `0.0.0.0:${HOST_HTTP_PORT}` by default so Docker-based 1Panel/OpenResty can reach it through the host port. Restrict that port with your firewall/security group and expose the service publicly through HTTPS reverse proxy. If Nginx runs directly on the host instead of in Docker, you may set `HOST_BIND_ADDR=127.0.0.1`.

If the server should not compile Java/WebUI sources, use the prebuilt artifact installer instead:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/deploy-prebuilt-artifact.sh | sudo bash
```

See [Prebuilt Artifact Deployment](PREBUILT_ARTIFACTS.md) for the artifact publishing and download-only deployment flow.

## One-Line Install

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

With a public domain:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://api.example.com bash
```

The installer will:

- install `git`, `curl`, `openssl`, Docker, and Docker Compose when missing
- clone or update the repo under `/opt/starry-api`
- create `.env` with generated MySQL passwords
- build the service image, including WebUI static files
- start MySQL and the service with Docker Compose
- wait for `/actuator/health`
- print recent logs so the first admin password can be saved

The default MySQL image is pinned to `mysql:8.0.37-oraclelinux8` for older x86-64 servers that do not support `x86-64-v2`. You can override it in `.env` with `MYSQL_IMAGE=...`.

## Configuration Variables

Set variables before running the installer:

```bash
sudo env \
  INSTALL_DIR=/opt/starry-api \
  HOST_BIND_ADDR=0.0.0.0 \
  HOST_HTTP_PORT=18080 \
  PUBLIC_BASE_URL=https://api.example.com \
  bash scripts/install-server.sh
```

Supported variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `REPO_URL` | `https://github.com/StaR4y/starry-api.git` | Git repository to deploy |
| `BRANCH` | `main` | Git branch to deploy |
| `INSTALL_DIR` | `/opt/starry-api` | Server install directory |
| `HOST_BIND_ADDR` | `0.0.0.0` | Host address for compose port binding; use `127.0.0.1` only when reverse proxy runs on the host |
| `HOST_HTTP_PORT` | `18080` | Local host port exposed by compose |
| `SERVER_PORT` | `8080` | Internal Spring Boot port |
| `PUBLIC_BASE_URL` | empty | Public HTTPS URL used in startup logs |
| `FORCE_ENV` | `false` | Regenerate `.env` even if it already exists |
| `INSTALL_DOCKER` | `true` | Install Docker when missing |

## MySQL Image Compatibility

If the MySQL container logs show:

```text
Fatal glibc error: CPU does not support x86-64-v2
```

Roll MySQL back to the Oracle Linux 8 image:

```bash
cd /opt/starry-api
grep -q '^MYSQL_IMAGE=' .env \
  && sed -i 's|^MYSQL_IMAGE=.*|MYSQL_IMAGE=mysql:8.0.37-oraclelinux8|' .env \
  || echo 'MYSQL_IMAGE=mysql:8.0.37-oraclelinux8' >> .env
docker compose pull mysql
docker compose up -d --force-recreate mysql
docker compose up -d --build service
```

## 1Panel Reverse Proxy

After the installer finishes, create a 1Panel reverse proxy site:

```text
Proxy address: http://<server-ip>:18080
Domain:        your domain
HTTPS:         enable in 1Panel
```

If your 1Panel OpenResty runs in Docker, `127.0.0.1` means the OpenResty container itself and usually causes `502 Bad Gateway`. Use the server intranet/public IP printed by the installer. Only use `http://127.0.0.1:18080` when Nginx runs directly on the host.

Then open:

```text
https://your-domain.example/#/login
```

## First Login

The initial admin user is:

```text
username: admin
password: printed once in first startup logs
```

The password is only printed when the admin table is created. Save it immediately, then log in and change it.

## Operations

```bash
cd /opt/starry-api
docker compose logs -f service
docker compose restart service
docker compose down
```

Update:

```bash
cd /opt/starry-api
git pull
./scripts/install-server.sh
```

The update path rebuilds the WebUI with Docker and embeds the generated static files into the Spring Boot jar. If the browser still shows an old UI, check the service build logs first:

```bash
cd /opt/starry-api
docker compose build --no-cache service
docker compose up -d service
docker compose logs --tail=160 service
```

When a build fails, Compose keeps the previous running container, so the old WebUI can remain visible even though `git pull` succeeded.

Do not delete `./data/mysql` unless you want to reset the database.
