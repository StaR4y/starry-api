# Prebuilt Artifact Deployment

This project supports a source-build deployment and a prebuilt-artifact deployment.

Use the prebuilt path when the server should only download and run the latest packaged bundle from the repository instead of compiling Java and WebUI assets on the server.

There are two separate scripts:

| Script | Where to run | What it does |
| --- | --- | --- |
| `scripts/publish-artifact.sh` | Developer machine or CI | Builds the WebUI and boot jar, then writes the full deployment bundle under `artifacts/`. |
| `scripts/install-server.sh` | Linux server | Downloads `artifacts/starry-api-bundle.tar.gz`, verifies SHA256, extracts it, and starts Docker Compose without running Gradle or npm. |

## Artifact Directory

The repository-level `artifacts/` directory stores the deployable output:

| File | Purpose |
| --- | --- |
| `artifacts/starry-api-bundle.tar.gz` | Full server deployment bundle. |
| `artifacts/starry-api-bundle.tar.gz.sha256` | Checksum used by the server deploy script. |
| `artifacts/starry-api.jar` | Spring Boot executable jar with the WebUI embedded. |
| `artifacts/starry-api.jar.sha256` | Jar checksum, also kept inside the bundle. |
| `artifacts/starry-api-webui.tar.gz` | Standalone built WebUI dist archive for inspection or CDN use. |
| `artifacts/docker-compose.artifact.yml` | Compose file that runs the jar with a JRE image. |
| `artifacts/runtime/recover-data-management.sh` | Bilingual interactive recovery utility copied into the service image as `/app/bin/recover-data-management`. |
| `artifacts/runtime/recover-data-management.sh.sha256` | Recovery utility checksum verified by the installer. |
| `artifacts/sha256sums.txt` | Checksum manifest for deployable files. |
| `artifacts/build-info.json` | Source commit, branch, version, build time, size, and checksum metadata. |

## Publish After Each Commit

After every source commit that should be deployable, run this on a developer machine or CI, not on the server:

```bash
./scripts/publish-artifact.sh
git add artifacts
git commit -m "chore: publish prebuilt artifact"
git push
```

The script runs `./gradlew test :service:bootJar`, verifies that the jar contains the built WebUI, then refreshes the jar, WebUI archive, Compose file, recovery utility, full bundle, checksums, and build metadata.

The publish script needs either local Java 25 or Docker. If `java` is not available in `PATH`, it automatically falls back to Docker and builds through the project `Dockerfile`.

Force Docker-based publishing:

```bash
BUILD_WITH_DOCKER=true ./scripts/publish-artifact.sh
```

Force local Java/Gradle publishing:

```bash
BUILD_WITH_DOCKER=false ./scripts/publish-artifact.sh
```

To let the script create and push the artifact-only commit:

```bash
COMMIT_ARTIFACTS=true PUSH_ARTIFACTS=true ./scripts/publish-artifact.sh
```

The auto-commit mode refuses to run when there are already staged changes, so it does not accidentally include source edits.

If you need a faster local refresh after tests have already passed:

```bash
RUN_TESTS=false ./scripts/publish-artifact.sh
```

## One-Line Server Deploy

Run this on a Linux server. This command downloads the checked-in bundle from the repository and does not run Gradle/npm:

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

The deploy script will:

- install `curl`, `openssl`, Docker, and Docker Compose when missing
- create `/opt/starry-api/.env` with generated PostgreSQL credentials when missing
- download `artifacts/starry-api-bundle.tar.gz` and its checksum
- verify and extract the bundle under `/opt/starry-api`
- fall back to `artifacts/starry-api.jar` only when the bundle is not available
- install the checksum-verified recovery utility and copy it into the service image at `/app/bin/recover-data-management`
- start PostgreSQL 17 and the service runtime with PostgreSQL 17 client tools and `jq`
- wait for `/actuator/health`

It does not clone the repo and does not run Gradle or npm on the server.

## Update Existing Server

Rerun the same command. The script downloads the current bundle and atomically preserves the previous `.env`, jar and checksum, Compose file, PostgreSQL bootstrap script, recovery utility, and recovery checksum. It recreates the service with the new deployment unit; a startup or health failure restores the complete previous unit and recreates the prior stack before reporting failure.

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

## Variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `REPO_URL` | `https://github.com/StaR4y/starry-api.git` | GitHub repository used to derive raw artifact URLs. |
| `BRANCH` | `main` | Branch used to derive raw artifact URLs. |
| `REPO_RAW_BASE` | derived from `REPO_URL` and `BRANCH` | Raw repository base used to download scripts and artifacts. |
| `BUNDLE_URL` | `${REPO_RAW_BASE}/artifacts/starry-api-bundle.tar.gz` | Direct full bundle URL. |
| `BUNDLE_CHECKSUM_URL` | `${BUNDLE_URL}.sha256` | Direct full bundle checksum URL. |
| `ARTIFACT_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar` | Direct jar URL. |
| `CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar.sha256` | Direct checksum URL. |
| `BUILD_INFO_URL` | `${REPO_RAW_BASE}/artifacts/build-info.json` | Optional build metadata URL. |
| `RECOVERY_TOOL_URL` | `${REPO_RAW_BASE}/artifacts/runtime/recover-data-management.sh` | Direct recovery utility URL used only by the installer fallback path. |
| `RECOVERY_TOOL_CHECKSUM_URL` | `${RECOVERY_TOOL_URL}.sha256` | Direct recovery utility checksum URL. |
| `INSTALL_DIR` | `/opt/starry-api` | Server install directory. |
| `HOST_BIND_ADDR` | `0.0.0.0` | Host address for Compose port binding. |
| `HOST_HTTP_PORT` | `18080` | Host port exposed by Compose. |
| `SERVER_PORT` | `8080` | Internal Spring Boot port. |
| `PUBLIC_BASE_URL` | empty | Public HTTPS URL used in startup logs. |
| `FORCE_ENV` | `false` | Regenerate `.env` only before PostgreSQL has initialized; initialized clusters require an in-database role-password rotation and a manual `.env` update. |
| `INSTALL_DOCKER` | `true` | Install Docker when missing. |
| `GITHUB_TOKEN` | empty | Optional token for private raw artifact downloads. |
| `GITHUB_TOKEN_FILE` | `${INSTALL_DIR}/.github-token` | Optional local token file used when `GITHUB_TOKEN` is empty. |

Deploy a non-main branch artifact by setting `BRANCH`:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env BRANCH=my-branch bash
```

## Operations

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml logs -f service
docker compose -f docker-compose.artifact.yml restart service
docker compose -f docker-compose.artifact.yml down
```

For a data-management hard lock, keep exactly one service instance active and use an interactive terminal. The image-local utility supports Chinese and English prompts, hides and double-checks the original `dmsk-...` key, and writes only the fixed owner-only file `/app/security/recovery.key`:

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml exec service \
  /app/bin/recover-data-management --lang zh
# Use --lang en for English prompts.
docker compose -f docker-compose.artifact.yml restart service
# Wait for startup, then verify status and one-time-file consumption:
docker compose -f docker-compose.artifact.yml exec service \
  curl -fsS http://127.0.0.1:8080/system/status
docker compose -f docker-compose.artifact.yml exec service \
  sh -c 'test ! -e /app/security/recovery.key && test ! -e /app/security/.recovery.key.consuming'
```

Do not add `exec -T`, pass or pipe the key, or download a recovery script with `curl ... | sh`. A normal matching attempt produces `"maintenance":false`; a normal readable, valid-format but nonmatching attempt records `RECOVERY_FAILED`, consumes both recovery paths, and keeps maintenance active. If either path remains, cleanup is incomplete and the service stays fail-closed; repair the security volume and restart so staged cleanup can retry. See [Operations](OPERATIONS.md#recover-a-docker-compose-deployment) for the full incident workflow and direct host/JAR recovery.

The generated environment uses database `starry_api`, role `starry_api`, and schema `starry_api`. It also installs a configurable PostgreSQL OLTP baseline for connections, memory, WAL/checkpoints, planner statistics, and autovacuum; the defaults assume at least 2 GiB available memory. PostgreSQL files persist under `./data/postgresql`; do not delete that directory unless this is a disposable deployment.
