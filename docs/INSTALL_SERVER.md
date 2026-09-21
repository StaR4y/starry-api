# Server One-Click Install

This installer is for Linux servers. It deploys the checked-in prebuilt bundle with Docker Compose:

```text
Nginx / 1Panel reverse proxy
  -> server-ip:18080
  -> starry-api service container with embedded WebUI
  -> PostgreSQL 17 container
```

The installer binds the service port to `0.0.0.0:${HOST_HTTP_PORT}` by default so Docker-based 1Panel/OpenResty can reach it through the host port. Restrict that port with your firewall/security group and expose the service publicly through HTTPS reverse proxy. If Nginx runs directly on the host instead of in Docker, you may set `HOST_BIND_ADDR=127.0.0.1`.

It downloads `artifacts/starry-api-bundle.tar.gz` and does not run Gradle or npm on the server. Compose builds only a small JRE runtime layer containing PostgreSQL 17 client tools, `jq`, and the checked recovery utility. See [Prebuilt Artifact Deployment](PREBUILT_ARTIFACTS.md) for the artifact publishing flow.

## One-Line Install

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

With a public domain:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://api.example.com bash
```

For a private repository, GitHub raw URLs return `404` unless authenticated. Save a fine-grained token with read-only Contents permission once on the server:

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
sudo bash /tmp/install-server.sh
```

If you already have a local checkout on the server, you can run `sudo bash scripts/install-server.sh`; it will read `/opt/starry-api/.github-token` automatically.

The installer will:

- install `curl`, `openssl`, Docker, and Docker Compose when missing
- download `artifacts/starry-api-bundle.tar.gz` and verify `artifacts/starry-api-bundle.tar.gz.sha256`
- extract the prebuilt jar, WebUI archive, Compose file, checksum-verified recovery utility, and metadata
- create `.env` with distinct generated PostgreSQL bootstrap, migration, and runtime passwords
- write `docker-compose.artifact.yml`
- start PostgreSQL 17 and the service container with Docker Compose
- wait for `/actuator/health`
- remove containers orphaned by the previous Compose definition after the new service is healthy
- print recent logs so the first admin password can be saved

The installer pins the database container to `postgres:17-bookworm`, initializes database `starry_api`, and runs the application in schema `starry_api`. PostgreSQL data is stored under `/opt/starry-api/data/postgresql`.

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
| `RECOVERY_TOOL_URL` | `${REPO_RAW_BASE}/artifacts/runtime/recover-data-management.sh` | Direct recovery utility URL for the single-jar fallback |
| `RECOVERY_TOOL_CHECKSUM_URL` | `${RECOVERY_TOOL_URL}.sha256` | Direct recovery utility checksum URL |
| `INSTALL_DIR` | `/opt/starry-api` | Server install directory |
| `HOST_BIND_ADDR` | `0.0.0.0` | Host address for compose port binding; use `127.0.0.1` only when reverse proxy runs on the host |
| `HOST_HTTP_PORT` | `18080` | Local host port exposed by compose |
| `SERVER_PORT` | `8080` | Internal Spring Boot port |
| `PUBLIC_BASE_URL` | empty | Public HTTPS URL used in startup logs |
| `FORCE_ENV` | `false` | Generate a new PostgreSQL `.env` before initialization, or reactivate credentials retained from a failed deployment retry |
| `INSTALL_DOCKER` | `true` | Install Docker when missing |
| `GITHUB_TOKEN` | empty | Optional token for private raw artifact downloads |
| `GITHUB_TOKEN_FILE` | `${INSTALL_DIR}/.github-token` | Optional local token file used when `GITHUB_TOKEN` is empty |

## PostgreSQL Configuration

The generated `.env` contains the production baseline:

```dotenv
POSTGRES_BOOTSTRAP_PASSWORD=<generated-secret>
POSTGRES_MIGRATION_PASSWORD=<generated-secret>
POSTGRES_PASSWORD=<generated-secret>
POSTGRES_MAX_CONNECTIONS=100
POSTGRES_SHARED_BUFFERS=256MB
POSTGRES_EFFECTIVE_CACHE_SIZE=768MB
POSTGRES_POOL_INITIAL_SIZE=4
POSTGRES_POOL_MAX_SIZE=20
```

The database and schema are fixed as `starry_api`; Flyway uses `starry_api_migration` and application traffic uses `starry_api`. `starry_api_bootstrap` remains a PostgreSQL superuser for cluster ownership and database-level break-glass recovery, but it is not used by the service or published outside the internal Compose network. Protect and audit its password independently. The generated PostgreSQL profile assumes at least 2 GiB available memory and SSD-backed storage. Size every replica's `POSTGRES_POOL_MAX_SIZE` against `POSTGRES_MAX_CONNECTIONS`, leaving headroom for Flyway, maintenance, monitoring, and operator sessions. The installer refuses to generate new passwords after `data/postgresql/PG_VERSION` exists because changing `.env` alone cannot rotate credentials in an initialized cluster. The only automatic exception is a matching `.env.postgresql-retry` retained during rollback of a failed first PostgreSQL deployment; `FORCE_ENV=true` reactivates that file instead of generating new secrets. Otherwise, rotate roles in PostgreSQL and update `.env` manually.

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

The password is printed only when the application Root marker is unassigned and `admin_users` is empty. Save it immediately, then log in and change it; it is not printed again.

## Operations

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml logs -f service
docker compose -f docker-compose.artifact.yml restart service
docker compose -f docker-compose.artifact.yml down
```

### Locked data-management recovery

The installed service image includes a bilingual interactive recovery utility. While the locked service is running in maintenance mode, execute it from a real terminal, enter the original `dmsk-...` key twice, then restart only the service:

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# Use --lang en for English prompts.

docker compose -f docker-compose.artifact.yml restart service
# Wait for the service to finish starting, then verify:
docker compose -f docker-compose.artifact.yml exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming && echo "recovery key files consumed"'
```

Do not use `docker compose exec -T`: the utility requires a TTY so it can hide and double-check the key. Recovery succeeds only when the response contains `"maintenance":false` and both recovery paths are absent. A normal readable, valid-format but nonmatching key is audited and consumed while maintenance remains active; run the utility again with the correct key. If cleanup fails, the service stays fail-closed and startup retries `/app/security/.recovery.key.consuming`; inspect storage and logs rather than staging another key or deleting files manually. The tool is shipped in the bundled runtime image at `/app/bin/recover-data-management`; do not download or pipe a cloud recovery script with `curl ... | sh`. See [Operations](OPERATIONS.md#recover-a-docker-compose-deployment) for the full cleanup-failure procedure and direct host/JAR recovery.

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

Deployment files are installed with atomic renames. Before an update, the installer preserves `.env` (mode `0600`), the current jar and checksum, `docker-compose.artifact.yml`, `postgres-init/001-starry-api-roles.sh`, and the recovery utility and checksum as adjacent `.previous` files. If any later deployment step fails, it restores that complete deployment unit. Once Compose has begun changing containers, the installer also recreates the previous stack, removes new orphan containers, and verifies its health. If PostgreSQL initialized during an attempted transition from an older deployment, its generated credentials are retained as `.env.postgresql-retry` while the previous `.env` is restored; a later `FORCE_ENV=true` retry reuses those exact credentials. Automatic rollback is attempted at most once per installer run.

On a first installation there is no previous deployment to restore. A failed first attempt keeps the generated credentials, verified artifact and recovery utility, Compose file, and PostgreSQL data in place for a deterministic retry; it never labels that partial installation as a successful rollback.

Do not delete `./data/postgresql` unless you intend to permanently reset the PostgreSQL cluster.
