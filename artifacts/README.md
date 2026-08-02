# Prebuilt Artifacts

This directory stores the latest checked-in deployable package for servers that should not build Gradle/npm sources.

The deployment bundle targets PostgreSQL 17. Its Compose stack initializes database `starry_api`, while Flyway and R2DBC use schema `starry_api`; the service runtime includes PostgreSQL 17 client tools for custom-format backup and restore.

Generated files:

- `starry-api-bundle.tar.gz`: full server deployment bundle.
- `starry-api-bundle.tar.gz.sha256`: SHA256 checksum for the full bundle.
- `starry-api.jar`: Spring Boot executable jar with the WebUI embedded.
- `starry-api.jar.sha256`: SHA256 checksum for the jar.
- `starry-api-webui.tar.gz`: standalone built WebUI dist archive.
- `starry-api-webui.tar.gz.sha256`: SHA256 checksum for the WebUI archive.
- `docker-compose.artifact.yml`: Docker Compose file used by the server installer.
- `sha256sums.txt`: checksum manifest for deployable files.
- `build-info.json`: source commit, branch, version, size, and checksum metadata.

Update these files after each source commit that should be deployable:

```bash
./scripts/publish-artifact.sh
git add artifacts
git commit -m "chore: publish prebuilt artifact"
git push
```

Or let the script commit and push only the artifact files:

```bash
COMMIT_ARTIFACTS=true PUSH_ARTIFACTS=true ./scripts/publish-artifact.sh
```

`publish-artifact.sh` is only for a developer machine or CI. Do not run it on the server.

The generated Compose file persists PostgreSQL under `./data/postgresql` and application backups under `./data/backups`.

Servers should only download and deploy the already checked-in bundle:

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

For a private repository, pass a GitHub token with read-only Contents permission:

```bash
GITHUB_TOKEN=replace-with-token
curl -fsSL -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env GITHUB_TOKEN="${GITHUB_TOKEN}" bash
```

The publish script needs either local Java 25 or Docker. When local Java is missing it automatically falls back to Docker; you can force that path with:

```bash
BUILD_WITH_DOCKER=true ./scripts/publish-artifact.sh
```
