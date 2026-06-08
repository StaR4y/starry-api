# Server One-Click Install

This installer is for Linux servers. It deploys the checked-in prebuilt bundle with Docker Compose:

```text
Nginx / 1Panel reverse proxy
  -> server-ip:18080
  -> starry-api service container with embedded WebUI
  -> mysql container
```

The installer binds the service port to `0.0.0.0:${HOST_HTTP_PORT}` by default so Docker-based 1Panel/OpenResty can reach it through the host port. Restrict that port with your firewall/security group and expose the service publicly through HTTPS reverse proxy. If Nginx runs directly on the host instead of in Docker, you may set `HOST_BIND_ADDR=127.0.0.1`.

It downloads `artifacts/starry-api-bundle.tar.gz` from the repository and does not run Gradle, npm, or Docker build on the server. See [Prebuilt Artifact Deployment](PREBUILT_ARTIFACTS.md) for the artifact publishing flow.

## One-Line Install

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

With a public domain:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://api.example.com bash
```

For a private repository, GitHub raw URLs return `404` unless authenticated:

```bash
GITHUB_TOKEN=replace-with-token
curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env GITHUB_TOKEN="${GITHUB_TOKEN}" bash
```

The installer will:

- install `curl`, `openssl`, Docker, and Docker Compose when missing
- download `artifacts/starry-api-bundle.tar.gz` and verify `artifacts/starry-api-bundle.tar.gz.sha256`
- extract the prebuilt jar, WebUI archive, Compose file, and metadata
- create `.env` with generated MySQL passwords
- write `docker-compose.artifact.yml`
- start MySQL and the service container with Docker Compose
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
| `REPO_URL` | `https://github.com/StaR4y/starry-api.git` | GitHub repository used to derive raw artifact URLs |
| `BRANCH` | `main` | Branch used to derive raw artifact URLs |
| `REPO_RAW_BASE` | derived from `REPO_URL` and `BRANCH` | Raw repository base used to download artifacts |
| `BUNDLE_URL` | `${REPO_RAW_BASE}/artifacts/starry-api-bundle.tar.gz` | Direct full bundle URL |
| `BUNDLE_CHECKSUM_URL` | `${BUNDLE_URL}.sha256` | Direct full bundle checksum URL |
| `ARTIFACT_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar` | Direct jar URL |
| `CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar.sha256` | Direct checksum URL |
| `BUILD_INFO_URL` | `${REPO_RAW_BASE}/artifacts/build-info.json` | Optional build metadata URL |
| `INSTALL_DIR` | `/opt/starry-api` | Server install directory |
| `HOST_BIND_ADDR` | `0.0.0.0` | Host address for compose port binding; use `127.0.0.1` only when reverse proxy runs on the host |
| `HOST_HTTP_PORT` | `18080` | Local host port exposed by compose |
| `SERVER_PORT` | `8080` | Internal Spring Boot port |
| `PUBLIC_BASE_URL` | empty | Public HTTPS URL used in startup logs |
| `FORCE_ENV` | `false` | Regenerate `.env` even if it already exists |
| `INSTALL_DOCKER` | `true` | Install Docker when missing |
| `GITHUB_TOKEN` | empty | Optional token for private raw artifact downloads |

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
docker compose -f docker-compose.artifact.yml pull mysql
docker compose -f docker-compose.artifact.yml up -d --force-recreate mysql
docker compose -f docker-compose.artifact.yml up -d service
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
docker compose -f docker-compose.artifact.yml logs -f service
docker compose -f docker-compose.artifact.yml restart service
docker compose -f docker-compose.artifact.yml down
```

Update:

```bash
cd /opt/starry-api
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

The update path downloads the latest checked-in bundle and restarts the service. If the browser still shows an old UI, check that the artifact was refreshed and pushed before running the installer:

```bash
cd /opt/starry-api
cat app/build-info.json
docker compose -f docker-compose.artifact.yml logs --tail=160 service
```

When artifact download or checksum verification fails, the script keeps the previous jar as `app/starry-api.jar.previous`.

Do not delete `./data/mysql` unless you want to reset the database.
