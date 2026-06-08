# Prebuilt Artifact Deployment

This project supports a source-build deployment and a prebuilt-artifact deployment.

Use the prebuilt path when the server should only download and run the latest packaged jar from the repository instead of compiling Java and WebUI assets on the server.

## Artifact Directory

The repository-level `artifacts/` directory stores the deployable output:

| File | Purpose |
| --- | --- |
| `artifacts/starry-api.jar` | Spring Boot executable jar with the WebUI embedded. |
| `artifacts/starry-api.jar.sha256` | Checksum used by the server deploy script. |
| `artifacts/build-info.json` | Source commit, branch, version, build time, size, and checksum metadata. |

## Publish After Each Commit

After every source commit that should be deployable, build and publish the artifact files:

```bash
./scripts/publish-artifact.sh
git add artifacts
git commit -m "chore: publish prebuilt artifact"
git push
```

The script runs `./gradlew test :service:bootJar`, copies the executable jar to `artifacts/starry-api.jar`, and refreshes checksum/build metadata.

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

Run this on a Linux server:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/deploy-prebuilt-artifact.sh | sudo bash
```

With a public domain:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/deploy-prebuilt-artifact.sh \
  | sudo env PUBLIC_BASE_URL=https://api.example.com bash
```

The deploy script will:

- install `curl`, `openssl`, Docker, and Docker Compose when missing
- create `/opt/starry-api/.env` with generated MySQL credentials when missing
- download `artifacts/starry-api.jar` and `artifacts/starry-api.jar.sha256`
- verify the jar checksum
- write `docker-compose.artifact.yml`
- start MySQL and an `eclipse-temurin:25-jre` service container
- wait for `/actuator/health`

It does not clone the repo and does not run Gradle or npm on the server.

## Update Existing Server

Rerun the same command. The script downloads the current artifact, keeps the previous jar as `app/starry-api.jar.previous`, and restarts the service container.

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/deploy-prebuilt-artifact.sh | sudo bash
```

## Variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `REPO_RAW_BASE` | `https://raw.githubusercontent.com/StaR4y/starry-api/main` | Raw repository base used to download scripts and artifacts. |
| `ARTIFACT_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar` | Direct jar URL. |
| `CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar.sha256` | Direct checksum URL. |
| `BUILD_INFO_URL` | `${REPO_RAW_BASE}/artifacts/build-info.json` | Optional build metadata URL. |
| `INSTALL_DIR` | `/opt/starry-api` | Server install directory. |
| `HOST_BIND_ADDR` | `0.0.0.0` | Host address for Compose port binding. |
| `HOST_HTTP_PORT` | `18080` | Host port exposed by Compose. |
| `SERVER_PORT` | `8080` | Internal Spring Boot port. |
| `PUBLIC_BASE_URL` | empty | Public HTTPS URL used in startup logs. |
| `FORCE_ENV` | `false` | Regenerate `.env` even if it already exists. |
| `INSTALL_DOCKER` | `true` | Install Docker when missing. |
| `GITHUB_TOKEN` | empty | Optional token for private raw artifact downloads. |

Deploy a non-main branch artifact by changing `REPO_RAW_BASE`:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/deploy-prebuilt-artifact.sh \
  | sudo env REPO_RAW_BASE=https://raw.githubusercontent.com/StaR4y/starry-api/my-branch bash
```

## Operations

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml logs -f service
docker compose -f docker-compose.artifact.yml restart service
docker compose -f docker-compose.artifact.yml down
```

Do not delete `./data/mysql` unless this is a disposable deployment.
