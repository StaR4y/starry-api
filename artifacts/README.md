# Prebuilt Artifacts

This directory stores the latest checked-in deployable package for servers that should not build Gradle/npm sources.

Generated files:

- `starry-api.jar`: Spring Boot executable jar with the WebUI embedded.
- `starry-api.jar.sha256`: SHA256 checksum for the jar.
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
