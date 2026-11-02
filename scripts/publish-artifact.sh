#!/usr/bin/env bash
set -euo pipefail

APP_NAME="starry-api"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACT_DIR="${ARTIFACT_DIR:-artifacts}"
ARTIFACT_NAME="${ARTIFACT_NAME:-starry-api.jar}"
BUNDLE_NAME="${BUNDLE_NAME:-starry-api-bundle.tar.gz}"
WEBUI_DIST_NAME="${WEBUI_DIST_NAME:-starry-api-webui.tar.gz}"
COMPOSE_NAME="${COMPOSE_NAME:-docker-compose.artifact.yml}"
CHECKSUMS_NAME="${CHECKSUMS_NAME:-sha256sums.txt}"
RECOVERY_TOOL_NAME="${RECOVERY_TOOL_NAME:-recover-data-management.sh}"
ADMIN_COMMAND_NAME="${ADMIN_COMMAND_NAME:-starry-api.sh}"
RUN_TESTS="${RUN_TESTS:-true}"
SKIP_BUILD="${SKIP_BUILD:-false}"
COMMIT_ARTIFACTS="${COMMIT_ARTIFACTS:-false}"
PUSH_ARTIFACTS="${PUSH_ARTIFACTS:-false}"
BUILD_WITH_DOCKER="${BUILD_WITH_DOCKER:-auto}"
DOCKER_BUILD_IMAGE="${DOCKER_BUILD_IMAGE:-starry-api-artifact-builder}"
# Where the -sources jars of the libraries used under a copyleft license (NOTICE, section 3) are published: in the
# artifact directory next to the jar, and in the bundle. The build task :service:copyleftSources collects them.
SOURCES_DIR_NAME="third-party-sources"
COPYLEFT_SOURCE_LIBRARIES=(logback-classic logback-core jakarta.annotation-api jakarta.mail-api angus-mail)

log() {
  printf '[%s] %s\n' "$APP_NAME" "$*"
}

fail() {
  printf '[%s] ERROR: %s\n' "$APP_NAME" "$*" >&2
  exit 1
}

# Writes the content of a directory as a gzip tar archive that names neither the account nor the ids of whoever built it.
create_tar_gz() {
  local archive="$1" directory="$2"
  local owner_options
  if tar --version 2>/dev/null | grep -q 'GNU tar'; then
    owner_options=(--owner=0 --group=0 --numeric-owner)
  else
    owner_options=(--uid 0 --gid 0 --uname '' --gname '')
  fi
  tar "${owner_options[@]}" -C "$directory" -czf "$archive" .
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

atomic_copy_file() {
  local source="$1"
  local destination="$2"
  local mode="$3"
  local temporary

  temporary="$(mktemp "${destination}.incoming.XXXXXX")" || return 1
  if ! cp "$source" "$temporary" || ! chmod "$mode" "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  if ! mv -f "$temporary" "$destination"; then
    rm -f "$temporary"
    return 1
  fi
  return 0
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

project_version() {
  sed -n "s/^[[:space:]]*version = ['\"]\\([^'\"]*\\)['\"].*/\\1/p" "$ROOT_DIR/build.gradle" | head -n 1
}

list_zip_entries() {
  local file="$1"
  if command -v jar >/dev/null 2>&1; then
    jar tf "$file"
  elif command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$file"
  else
    return 1
  fi
}

# Writes one entry of a zip or jar to a file.
extract_zip_entry() {
  local archive="$1" entry="$2" output="$3" work absolute
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$archive" "$entry" > "$output"
  elif command -v jar >/dev/null 2>&1; then
    work="$(mktemp -d)"
    absolute="$(cd "$(dirname "$archive")" && pwd)/$(basename "$archive")"
    (cd "$work" && jar xf "$absolute" "$entry")
    cp "$work/$entry" "$output"
    rm -rf "$work"
  else
    return 1
  fi
}

# Copies the -sources jars that :service:copyleftSources built into a directory, and fails unless there is exactly one
# for every library NOTICE says is used under a copyleft license.
collect_copyleft_sources() {
  local destination="$1" library count
  for library in "${COPYLEFT_SOURCE_LIBRARIES[@]}"; do
    count="$(find service/build/third-party-sources -maxdepth 1 -type f -name "${library}-[0-9]*-sources.jar" 2>/dev/null | wc -l | tr -d '[:space:]')"
    [[ "$count" == "1" ]] \
      || fail "Expected one ${library}-<version>-sources.jar in service/build/third-party-sources, found ${count}. Run ./gradlew :service:copyleftSources (it downloads the jars the first time); a release has to offer the source code of these libraries."
  done
  rm -rf "$destination"
  mkdir -p "$destination"
  cp service/build/third-party-sources/*-sources.jar "$destination/"
}

write_artifact_compose() {
  local output="$1"
  cat > "$output" <<'EOF'
services:
  postgres:
    image: postgres:17-bookworm
    container_name: starryapi-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: starry_api
      POSTGRES_USER: starry_api_bootstrap
      POSTGRES_PASSWORD: ${POSTGRES_BOOTSTRAP_PASSWORD}
      STARRY_POSTGRES_MIGRATION_PASSWORD: ${POSTGRES_MIGRATION_PASSWORD}
      STARRY_POSTGRES_APP_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_INITDB_ARGS: --encoding=UTF8 --locale=C.UTF-8 --data-checksums
      TZ: Asia/Shanghai
    volumes:
      - ./data/postgresql:/var/lib/postgresql/data
      - ./postgres-init/001-starry-api-roles.sh:/docker-entrypoint-initdb.d/001-starry-api-roles.sh:ro
    command:
      - postgres
      - -c
      - max_connections=${POSTGRES_MAX_CONNECTIONS:-100}
      - -c
      - shared_buffers=${POSTGRES_SHARED_BUFFERS:-256MB}
      - -c
      - effective_cache_size=${POSTGRES_EFFECTIVE_CACHE_SIZE:-768MB}
      - -c
      - work_mem=${POSTGRES_WORK_MEM:-4MB}
      - -c
      - maintenance_work_mem=${POSTGRES_MAINTENANCE_WORK_MEM:-64MB}
      - -c
      - autovacuum_work_mem=${POSTGRES_AUTOVACUUM_WORK_MEM:-64MB}
      - -c
      - autovacuum_max_workers=${POSTGRES_AUTOVACUUM_MAX_WORKERS:-4}
      - -c
      - autovacuum_naptime=${POSTGRES_AUTOVACUUM_NAPTIME:-30s}
      - -c
      - default_statistics_target=${POSTGRES_DEFAULT_STATISTICS_TARGET:-200}
      - -c
      - wal_compression=${POSTGRES_WAL_COMPRESSION:-on}
      - -c
      - min_wal_size=${POSTGRES_MIN_WAL_SIZE:-512MB}
      - -c
      - max_wal_size=${POSTGRES_MAX_WAL_SIZE:-2GB}
      - -c
      - checkpoint_completion_target=${POSTGRES_CHECKPOINT_COMPLETION_TARGET:-0.9}
      - -c
      - track_io_timing=${POSTGRES_TRACK_IO_TIMING:-on}
      - -c
      - idle_in_transaction_session_timeout=${POSTGRES_IDLE_IN_TRANSACTION_SESSION_TIMEOUT:-5min}
    shm_size: ${POSTGRES_SHM_SIZE:-256mb}
    stop_grace_period: 1m
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"$${POSTGRES_USER}\" -d \"$${POSTGRES_DB}\""]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 20s
    networks:
      - starryapi

  service:
    build:
      context: ./runtime
      # Always check for a newer base image so JDK and CA-certificate fixes arrive with the next deployment.
      pull: true
      dockerfile_inline: |
        FROM eclipse-temurin:25-jre-noble
        RUN set -eux; \
            apt-get update; \
            apt-get install -y --no-install-recommends ca-certificates curl jq; \
            install -d -m 0755 /usr/share/postgresql-common/pgdg; \
            curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc; \
            . /etc/os-release; \
            echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $${VERSION_CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list; \
            apt-get update; \
            apt-get install -y --no-install-recommends postgresql-client-17; \
            rm -rf /var/lib/apt/lists/*
        # CVE-2026-91403: eight eMudhra intermediate CAs whose private keys can be recomputed chain to emSign ECC Root CA - G3
        # and to AAA Certificate Services, and no JDK release distrusts them yet. None of the upstreams this gateway calls
        # chains to these roots (Sectigo sites validate through the USERTrust roots), so drop them from the JRE trust store.
        # Remove this step once the base image ships a JDK with the fix.
        RUN set -eu; \
            list() { keytool -J-Duser.language=en -list -v -cacerts -storepass changeit; }; \
            test "$$(list | grep -c '^Alias name: ')" -gt 100; \
            list | awk '/^Alias name: /{alias=substr($$0,13)} /^Owner: .*(emSign|CN=AAA Certificate Services,)/{print alias}' \
                | while IFS= read -r alias; do keytool -delete -noprompt -cacerts -storepass changeit -alias "$$alias"; done; \
            ! list | grep -E '^Owner: .*(emSign|CN=AAA Certificate Services,)'
        COPY --chmod=0555 recover-data-management.sh /app/bin/recover-data-management
    image: starryapi-runtime:java25-pg17
    container_name: starryapi-service
    restart: unless-stopped
    # The JVM is capped (JAVA_OPTS) and the container too, so a runaway heap ends in an exit and a restart
    # instead of a hung process. 2g leaves room for the 768m heap, direct buffers, metaspace and thread stacks.
    mem_limit: ${SERVICE_MEM_LIMIT:-2g}
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS -o /dev/null --max-time 4 http://127.0.0.1:${SERVER_PORT:-8080}/health || exit 1"]
      interval: 15s
      timeout: 6s
      retries: 4
      start_period: 90s
    depends_on:
      postgres:
        condition: service_healthy
    working_dir: /app
    volumes:
      - ./app/starry-api.jar:/app/app.jar:ro
      - ./data/backups:/app/backups
      - ./data/security:/app/security
      - ./data/plugins:/app/plugins
      - ./data/documentation:/app/documentation
    environment:
      SERVER_PORT: ${SERVER_PORT:-8080}
      SERVER_ADDRESS: 0.0.0.0
      POSTGRES_HOST: postgres
      POSTGRES_PORT: 5432
      POSTGRES_DATABASE: starry_api
      POSTGRES_USERNAME: starry_api
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_MIGRATION_USERNAME: starry_api_migration
      POSTGRES_MIGRATION_PASSWORD: ${POSTGRES_MIGRATION_PASSWORD}
      POSTGRES_SSL_MODE: ${POSTGRES_SSL_MODE:-disable}
      POSTGRES_POOL_INITIAL_SIZE: ${POSTGRES_POOL_INITIAL_SIZE:-4}
      POSTGRES_POOL_MAX_SIZE: ${POSTGRES_POOL_MAX_SIZE:-20}
      POSTGRES_POOL_MAX_ACQUIRE_TIME: ${POSTGRES_POOL_MAX_ACQUIRE_TIME:-5s}
      POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME: ${POSTGRES_POOL_MAX_CREATE_CONNECTION_TIME:-5s}
      POSTGRES_POOL_MAX_LIFE_TIME: ${POSTGRES_POOL_MAX_LIFE_TIME:-2h}
      POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES: ${POSTGRES_PREPARED_STATEMENT_CACHE_QUERIES:-256}
      POSTGRES_STATEMENT_TIMEOUT: ${POSTGRES_STATEMENT_TIMEOUT:-PT2M}
      POSTGRES_LOCK_WAIT_TIMEOUT: ${POSTGRES_LOCK_WAIT_TIMEOUT:-PT5S}
      FLYWAY_ENABLED: ${FLYWAY_ENABLED:-true}
      FLYWAY_CONNECT_RETRIES: ${FLYWAY_CONNECT_RETRIES:-10}
      SPRING_CODEC_MAX_IN_MEMORY_SIZE: ${SPRING_CODEC_MAX_IN_MEMORY_SIZE:-16MB}
      STARRY_ALLOW_ANONYMOUS_PROXY: ${STARRY_ALLOW_ANONYMOUS_PROXY:-${KIRO_ALLOW_ANONYMOUS_PROXY:-false}}
      STARRY_ADMIN_SESSION_TTL: ${STARRY_ADMIN_SESSION_TTL:-${KIRO_ADMIN_SESSION_TTL:-12h}}
      STARRY_RATE_LIMIT_PER_MINUTE: ${STARRY_RATE_LIMIT_PER_MINUTE:-${KIRO_RATE_LIMIT_PER_MINUTE:-120}}
      STARRY_ACCOUNT_SELECTION_STRATEGY: ${STARRY_ACCOUNT_SELECTION_STRATEGY:-${KIRO_ACCOUNT_SELECTION_STRATEGY:-round_robin}}
      STARRY_REQUEST_TIMEOUT: ${STARRY_REQUEST_TIMEOUT:-${KIRO_REQUEST_TIMEOUT:-300s}}
      STARRY_MAX_RETRIES: ${STARRY_MAX_RETRIES:-${KIRO_MAX_RETRIES:-2}}
      STARRY_BASE_COOLDOWN: ${STARRY_BASE_COOLDOWN:-${KIRO_BASE_COOLDOWN:-60s}}
      STARRY_QUOTA_RESET: ${STARRY_QUOTA_RESET:-${KIRO_QUOTA_RESET:-1h}}
      STARRY_PROBABILISTIC_RETRY: ${STARRY_PROBABILISTIC_RETRY:-${KIRO_PROBABILISTIC_RETRY:-0.1}}
      STARRY_MAX_REQUEST_BODY_BYTES: ${STARRY_MAX_REQUEST_BODY_BYTES:-${KIRO_MAX_REQUEST_BODY_BYTES:-10485760}}
      STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS: ${STARRY_RESPONSES_DEFAULT_OUTPUT_TOKENS:-16000}
      STARRY_SESSION_AFFINITY_ENABLED: ${STARRY_SESSION_AFFINITY_ENABLED:-${KIRO_SESSION_AFFINITY_ENABLED:-true}}
      STARRY_SESSION_AFFINITY_TTL: ${STARRY_SESSION_AFFINITY_TTL:-${KIRO_SESSION_AFFINITY_TTL:-10m}}
      STARRY_SESSION_AFFINITY_MAXIMUM_SIZE: ${STARRY_SESSION_AFFINITY_MAXIMUM_SIZE:-${KIRO_SESSION_AFFINITY_MAXIMUM_SIZE:-100000}}
      STARRY_WEBUI_URL: ${STARRY_WEBUI_URL:-${KIRO_WEBUI_URL:-}}
      STARRY_SOURCE_URL: ${STARRY_SOURCE_URL:-https://github.com/StaR4y/starry-api}
      STARRY_ADMIN_TOKEN: ${STARRY_ADMIN_TOKEN:-${KIRO_ADMIN_TOKEN:-}}
      STARRY_TOKEN_REFRESH_INTERVAL_MS: ${STARRY_TOKEN_REFRESH_INTERVAL_MS:-${KIRO_TOKEN_REFRESH_INTERVAL_MS:-3600000}}
      STARRY_TOKEN_REFRESH_INITIAL_DELAY_MS: ${STARRY_TOKEN_REFRESH_INITIAL_DELAY_MS:-${KIRO_TOKEN_REFRESH_INITIAL_DELAY_MS:-60000}}
      STARRY_KIRO_PREFERRED_ENDPOINT: ${STARRY_KIRO_PREFERRED_ENDPOINT:-${KIRO_PREFERRED_ENDPOINT:-codewhisperer}}
      STARRY_PLUGINS_DISABLED: ${STARRY_PLUGINS_DISABLED:-false}
      STARRY_PLUGIN_DIR: /app/plugins
      STARRY_DOCUMENTATION_ATTACHMENT_DIR: /app/documentation
      STARRY_BACKUP_DIR: /app/backups
      STARRY_MAX_IMPORT_BYTES: ${STARRY_MAX_IMPORT_BYTES:-268435456}
      STARRY_DATA_TRANSFER_PROCESS_TIMEOUT: ${STARRY_DATA_TRANSFER_PROCESS_TIMEOUT:-30m}
      STARRY_DATA_MANAGEMENT_SECURITY_FILE: /app/security/data-management.json
      STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE: /app/security/recovery.key
      STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED: ${STARRY_DATA_MANAGEMENT_REQUIRE_INITIALIZED:-false}
      STARRY_PAYMENT_RECONCILIATION_INTERVAL: ${STARRY_PAYMENT_RECONCILIATION_INTERVAL:-PT30S}
      STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY: ${STARRY_PAYMENT_RECONCILIATION_INITIAL_DELAY:-PT30S}
      STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL: ${STARRY_PAYMENT_PROVIDER_RECONCILIATION_INTERVAL:-PT30S}
      STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY: ${STARRY_PAYMENT_PROVIDER_RECONCILIATION_INITIAL_DELAY:-PT45S}
      STARRY_STATS_AGGREGATION_ENABLED: ${STARRY_STATS_AGGREGATION_ENABLED:-true}
      STARRY_STATS_AGGREGATION_INTERVAL: ${STARRY_STATS_AGGREGATION_INTERVAL:-PT1M}
      STARRY_STATS_AGGREGATION_INITIAL_DELAY: ${STARRY_STATS_AGGREGATION_INITIAL_DELAY:-PT30S}
      STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS: ${STARRY_STATS_AGGREGATION_MAX_ADVANCE_HOURS:-1}
      STARRY_STATS_AGGREGATION_LOOKBACK_HOURS: ${STARRY_STATS_AGGREGATION_LOOKBACK_HOURS:-6}
      STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN: ${STARRY_STATS_AGGREGATION_MAX_BATCHES_PER_RUN:-6}
      STARRY_STATS_MAINTENANCE_CRON: "${STARRY_STATS_MAINTENANCE_CRON:-0 25 3 * * *}"
      STARRY_STATS_PARTITION_MONTHS_AHEAD: ${STARRY_STATS_PARTITION_MONTHS_AHEAD:-12}
      STARRY_STATS_RAW_RETENTION_MONTHS: ${STARRY_STATS_RAW_RETENTION_MONTHS:-4}
      STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE: ${STARRY_STATS_RAW_DEFAULT_DELETE_BATCH_SIZE:-10000}
      STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES: ${STARRY_STATS_RAW_DEFAULT_DELETE_MAX_BATCHES:-20}
      STARRY_STATS_HOURLY_RETENTION_DAYS: ${STARRY_STATS_HOURLY_RETENTION_DAYS:-90}
      STARRY_STATS_HOURLY_DELETE_BATCH_SIZE: ${STARRY_STATS_HOURLY_DELETE_BATCH_SIZE:-10000}
      STARRY_STATS_HOURLY_DELETE_MAX_BATCHES: ${STARRY_STATS_HOURLY_DELETE_MAX_BATCHES:-20}
      STARRY_STATS_USAGE_EVENT_RETENTION_DAYS: ${STARRY_STATS_USAGE_EVENT_RETENTION_DAYS:-400}
      STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE: ${STARRY_STATS_USAGE_EVENT_DELETE_BATCH_SIZE:-10000}
      STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES: ${STARRY_STATS_USAGE_EVENT_DELETE_MAX_BATCHES:-100}
      STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED: ${STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_ENABLED:-true}
      STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON: "${STARRY_ACCOUNT_ALLOCATION_MAINTENANCE_CRON:-0 35 * * * *}"
      STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS: ${STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS:-7}
      STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS: ${STARRY_ACCOUNT_ALLOCATION_EVENT_RETENTION_DAYS:-90}
      STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE: ${STARRY_ACCOUNT_ALLOCATION_DELETE_BATCH_SIZE:-10000}
      STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES: ${STARRY_ACCOUNT_ALLOCATION_DELETE_MAX_BATCHES:-20}
      # Optional Mihomo: deployed by the "mihomo" profile together with the controller secret, and switched on by an
      # administrator in the console (see docs/ACCOUNT_EGRESS.md). STARRY_EGRESS_MIHOMO_ENABLED is only the initial
      # state of that switch for setups that never used it; every other STARRY_EGRESS_* setting keeps its default.
      STARRY_EGRESS_MIHOMO_ENABLED: ${STARRY_EGRESS_MIHOMO_ENABLED:-false}
      STARRY_EGRESS_CONTROLLER_SECRET: ${STARRY_EGRESS_CONTROLLER_SECRET:-}
      # glibc keeps one malloc arena per thread by default, which inflates resident memory of a busy JVM.
      MALLOC_ARENA_MAX: "2"
      JAVA_OPTS: "${JAVA_OPTS:--Xms64m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30}"
    ports:
      - "${HOST_BIND_ADDR:-127.0.0.1}:${HOST_HTTP_PORT:-18080}:${SERVER_PORT:-8080}"
    command: ["sh", "-c", "exec java $${JAVA_OPTS:-} -jar /app/app.jar"]
    networks:
      - starryapi

  # Optional Mihomo (Clash.Meta): only Clash subscriptions and protocols the Java backend cannot speak need it. The
  # installer deploys it idle (COMPOSE_PROFILES=mihomo and STARRY_EGRESS_CONTROLLER_SECRET in .env) and an administrator
  # switches it on in the console (docs/ACCOUNT_EGRESS.md). The official image is used as is; Starry API never bundles
  # the binary. It shares the service network namespace, so the controller (9090) and the per-node listeners stay on
  # 127.0.0.1 and nothing is published.
  mihomo:
    profiles: ["mihomo"]
    image: metacubex/mihomo:${MIHOMO_VERSION:-v1.19.32}
    network_mode: "service:service"
    restart: unless-stopped
    entrypoint: ["/bin/sh", "/entrypoint.sh"]
    environment:
      # Compose interpolates inactive profiles too, so this must not be required with ":?"; the entrypoint refuses an
      # empty secret instead.
      STARRY_EGRESS_CONTROLLER_SECRET: ${STARRY_EGRESS_CONTROLLER_SECRET:-}
      GOMEMLIMIT: ${MIHOMO_GOMEMLIMIT:-200MiB}
    volumes:
      - ./docker/egress/mihomo-entrypoint.sh:/entrypoint.sh:ro
    read_only: true
    tmpfs:
      - /tmp:size=64m
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: ${MIHOMO_MEM_LIMIT:-256m}

networks:
  starryapi:
    name: starryapi
EOF
}

write_bundle_readme() {
  local output="$1"
  cat > "$output" <<'EOF'
Starry API prebuilt deployment bundle.

This package is generated by scripts/publish-artifact.sh and is intended for
servers. It already contains the backend boot jar and the built WebUI assets.
No Gradle or npm build runs on the server. Compose only builds the small runtime
layer that adds the PostgreSQL 17 client tools required for backup and restore.

Package layout:

- app/starry-api.jar: executable Spring Boot jar with WebUI files embedded.
- app/starry-api.jar.sha256: jar checksum.
- starry-api-webui.tar.gz: standalone WebUI dist archive for inspection or CDN use (with LICENSE and NOTICE).
- docker-compose.artifact.yml: compose file that runs the jar with a JRE image. It also defines the optional
  Mihomo service (the installer deploys it idle on a fresh install; an administrator switches it on in the console,
  and COMPOSE_PROFILES=mihomo with STARRY_EGRESS_CONTROLLER_SECRET deploys it by hand, see docs/ACCOUNT_EGRESS.md).
  The official Mihomo image is pulled at deploy time; no Mihomo binary is shipped here.
- docker-compose.tls-bridge.yml: experimental, opt-in overlay for the Anthropic TLS fingerprint bridge.
- docker/egress/: Mihomo entrypoint script and the TLS bridge build context.
- docs/ACCOUNT_EGRESS.md: account egress deployment and limits.
- LICENSE: the GNU Affero General Public License, version 3 (AGPL-3.0-only), the license of Starry API.
- NOTICE: license elections for dependencies, additional permission for plugins, data credits, trademarks, and the
  statement that the optional Mihomo component is GPL-3.0 and not distributed.
- THIRD-PARTY-NOTICES.txt: licenses of the npm packages bundled into the web console (also served by the console).
- JAVA-THIRD-PARTY-NOTICES.txt: the Java libraries in the jar with the license each declares, and the license texts
  (Apache-2.0, MIT) that many of the jars do not carry themselves (also in the jar under META-INF/).
- licenses/: LGPL-2.1.txt and logback-NOTICE.txt, the license text and the notice of logback, which is used under LGPL-2.1.
- third-party-sources/: the -sources jars of the libraries used under a copyleft license (logback-classic, logback-core,
  jakarta.annotation-api, jakarta.mail-api, angus-mail), which is the source code those licenses require us to offer.
- postgres-init/001-starry-api-roles.sh: first-start least-privilege role bootstrap.
- runtime/recover-data-management.sh: interactive container recovery tool.
- runtime/recover-data-management.sh.sha256: recovery tool checksum.
- starry-api.sh: the "starry-api" administration command (sign-in information, Root password reset); the installer
  installs it as <install dir>/starry-api and links it into /usr/local/bin.
- starry-api.sh.sha256: administration command checksum.
- build-info.json: source commit and artifact metadata.
- sha256sums.txt: checksums for files inside the bundle.
EOF
}

assert_clean_staging_area_for_auto_commit() {
  if [[ "$COMMIT_ARTIFACTS" != "true" ]]; then
    return
  fi
  git -C "$ROOT_DIR" diff --cached --quiet \
    || fail "Staged changes already exist. Commit or unstage them before COMMIT_ARTIFACTS=true."
}

has_java() {
  command -v java >/dev/null 2>&1
}

use_docker_build() {
  case "$BUILD_WITH_DOCKER" in
    true)
      return 0
      ;;
    false)
      return 1
      ;;
    auto)
      ! has_java
      ;;
    *)
      fail "BUILD_WITH_DOCKER must be auto, true, or false."
      ;;
  esac
}

source_tree_dirty() {
  local artifact_exclude="$ARTIFACT_DIR"
  if [[ "$artifact_exclude" == "$ROOT_DIR"/* ]]; then
    artifact_exclude="${artifact_exclude#"$ROOT_DIR"/}"
  elif [[ "$artifact_exclude" == /* ]]; then
    artifact_exclude=""
  fi

  if [[ -n "$artifact_exclude" ]]; then
    git status --porcelain --untracked-files=normal -- . ":(exclude)${artifact_exclude}" | grep -q .
  else
    git status --porcelain --untracked-files=normal -- . | grep -q .
  fi
}

select_boot_jar() {
  local candidate selected count
  selected=""
  count=0
  while IFS= read -r candidate; do
    selected="$candidate"
    count=$((count + 1))
  done < <(find service/build/libs -maxdepth 1 -type f -name 'service-*.jar' ! -name '*-plain.jar' | sort)

  if [[ "$count" -ne 1 ]]; then
    fail "Expected exactly one boot jar in service/build/libs, found ${count}. Run a clean build before publishing."
  fi
  printf '%s\n' "$selected"
}

build_with_gradle() {
  if [[ "$RUN_TESTS" == "true" ]]; then
    log "Running a clean test and boot-jar build..."
    ./gradlew clean test :service:bootJar :service:copyleftSources
  else
    log "Running a clean boot-jar build..."
    ./gradlew clean :service:bootJar :service:copyleftSources
  fi
}

build_with_docker() {
  command -v docker >/dev/null 2>&1 \
    || fail "Docker was not found. Install Docker, or set BUILD_WITH_DOCKER=false and ensure JDK 25 is available."

  local image_tag container_id
  image_tag="${DOCKER_BUILD_IMAGE}:$(git rev-parse --short=12 HEAD 2>/dev/null || printf latest)"
  log "Java is not available locally; building artifact with Docker image ${image_tag}..."
  docker build --target build -t "$image_tag" .

  if [[ "$RUN_TESTS" == "true" ]]; then
    log "Running tests inside Docker..."
    docker run --rm "$image_tag" sh -c 'chmod +x gradlew && ./gradlew --no-daemon test -PskipWebuiBuild=true'
  else
    log "Skipping tests; copying Docker-built boot jar..."
  fi

  container_id="$(docker create "$image_tag" true)"
  trap 'docker rm -f "$container_id" >/dev/null 2>&1 || true' RETURN

  rm -rf service/build/libs
  mkdir -p service/build/libs
  docker cp "${container_id}:/workspace/service/build/libs/." service/build/libs/
  rm -rf webui/dist
  mkdir -p webui/dist
  docker cp "${container_id}:/workspace/webui/dist/." webui/dist/
  docker rm "$container_id" >/dev/null
  trap - RETURN

  # The -sources jars of the copyleft libraries are not needed by the image itself, so a second container collects them.
  log "Collecting the -sources jars of the copyleft libraries inside Docker..."
  container_id="$(docker create "$image_tag" sh -c 'chmod +x gradlew && ./gradlew --no-daemon -q :service:copyleftSources -PskipWebuiBuild=true')"
  trap 'docker rm -f "$container_id" >/dev/null 2>&1 || true' RETURN
  docker start -a "$container_id" || fail "Collecting the -sources jars inside Docker failed."
  rm -rf service/build/third-party-sources
  mkdir -p service/build/third-party-sources
  docker cp "${container_id}:/workspace/service/build/third-party-sources/." service/build/third-party-sources/
  docker rm "$container_id" >/dev/null
  trap - RETURN
}

main() {
  cd "$ROOT_DIR"

  command -v git >/dev/null 2>&1 || fail "git is required."
  [[ -x ./gradlew ]] || fail "gradlew is missing or not executable."
  assert_clean_staging_area_for_auto_commit

  log "This script is for developers/CI to refresh repository artifacts."
  log "Do not run it on the server. Servers should use scripts/install-server.sh."

  local version branch commit built_at java_version dirty jar_path artifact_path checksum size_bytes checksum_path info_path
  local webui_dist_path webui_checksum webui_size compose_path compose_checksum init_path init_checksum
  local recovery_path recovery_checksum recovery_checksum_path checksums_path bundle_path bundle_checksum bundle_size tmp_dir stage_dir
  local admin_command_path admin_command_checksum admin_command_checksum_path
  local jar_entries required_entry notice_file sources_dir webui_stage source_jar
  version="${VERSION:-$(project_version)}"
  version="${version:-0.1.0-SNAPSHOT}"
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || printf 'unknown')"
  commit="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"
  built_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  if has_java; then
    java_version="$(java -version 2>&1 | head -n 1 || true)"
  else
    java_version="docker:${DOCKER_BUILD_IMAGE}"
  fi
  if source_tree_dirty; then
    dirty=true
    log "Working tree has uncommitted changes; build-info.json will record dirty=true."
  else
    dirty=false
  fi

  if [[ "$SKIP_BUILD" == "true" ]]; then
    log "Skipping build step; using existing service/build/libs and webui/dist outputs."
  elif use_docker_build; then
    build_with_docker
  else
    has_java || fail "Java is not available. Install JDK 25, set JAVA_HOME, or run with BUILD_WITH_DOCKER=true."
    build_with_gradle
  fi

  jar_path="$(select_boot_jar)"

  mkdir -p "$ARTIFACT_DIR/postgres-init" "$ARTIFACT_DIR/runtime"
  artifact_path="${ARTIFACT_DIR}/${ARTIFACT_NAME}"
  checksum_path="${artifact_path}.sha256"
  info_path="${ARTIFACT_DIR}/build-info.json"

  cp "$jar_path" "$artifact_path"
  if ! list_zip_entries "$artifact_path" | grep -q '^BOOT-INF/classes/static/index.html$'; then
    fail "Boot jar does not contain the built WebUI. Expected BOOT-INF/classes/static/index.html."
  fi
  [[ -f webui/dist/index.html ]] || fail "WebUI dist is missing. Expected webui/dist/index.html."
  [[ -f webui/dist/THIRD-PARTY-NOTICES.txt ]] \
    || fail "WebUI dist has no THIRD-PARTY-NOTICES.txt. Rebuild the console (npm run build writes it)."
  [[ -f LICENSE && -f NOTICE ]] || fail "LICENSE and NOTICE must be at the root of the repository."
  jar_entries="$(list_zip_entries "$artifact_path")" || fail "Could not list the entries of the boot jar."
  for required_entry in META-INF/LICENSE META-INF/NOTICE META-INF/JAVA-THIRD-PARTY-NOTICES.txt \
      META-INF/licenses/LGPL-2.1.txt META-INF/licenses/logback-NOTICE.txt BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt; do
    grep -qxF "$required_entry" <<<"$jar_entries" || fail "Boot jar does not contain ${required_entry}."
  done
  [[ -f licenses/LGPL-2.1.txt && -f licenses/logback-NOTICE.txt ]] \
    || fail "licenses/LGPL-2.1.txt and licenses/logback-NOTICE.txt must be in the repository (logback is used under LGPL-2.1)."
  sources_dir="${ARTIFACT_DIR}/${SOURCES_DIR_NAME}"
  collect_copyleft_sources "$sources_dir"

  checksum="$(sha256_file "$artifact_path")"
  size_bytes="$(wc -c < "$artifact_path" | tr -d '[:space:]')"
  printf '%s  %s\n' "$checksum" "$ARTIFACT_NAME" > "$checksum_path"

  # The standalone console archive carries LICENSE and NOTICE too: its THIRD-PARTY-NOTICES.txt points at them.
  tmp_dir="$(mktemp -d)"
  webui_stage="${tmp_dir}/webui"
  mkdir -p "$webui_stage"
  cp -R webui/dist/. "$webui_stage/"
  cp "$ROOT_DIR/LICENSE" "$webui_stage/LICENSE"
  cp "$ROOT_DIR/NOTICE" "$webui_stage/NOTICE"
  webui_dist_path="${ARTIFACT_DIR}/${WEBUI_DIST_NAME}"
  create_tar_gz "$webui_dist_path" "$webui_stage"
  webui_checksum="$(sha256_file "$webui_dist_path")"
  webui_size="$(wc -c < "$webui_dist_path" | tr -d '[:space:]')"
  printf '%s  %s\n' "$webui_checksum" "$WEBUI_DIST_NAME" > "${webui_dist_path}.sha256"

  compose_path="${ARTIFACT_DIR}/${COMPOSE_NAME}"
  write_artifact_compose "$compose_path"
  compose_checksum="$(sha256_file "$compose_path")"
  init_path="${ARTIFACT_DIR}/postgres-init/001-starry-api-roles.sh"
  cp docker/postgresql/init/001-starry-api-roles.sh "$init_path"
  chmod 0755 "$init_path"
  init_checksum="$(sha256_file "$init_path")"
  recovery_path="${ARTIFACT_DIR}/runtime/${RECOVERY_TOOL_NAME}"
  recovery_checksum_path="${recovery_path}.sha256"
  atomic_copy_file scripts/recover-data-management.sh "$recovery_path" 0555 \
    || fail "Could not publish the data-management recovery tool atomically."
  recovery_checksum="$(sha256_file "$recovery_path")"
  printf '%s  %s\n' "$recovery_checksum" "$RECOVERY_TOOL_NAME" > "$recovery_checksum_path"
  admin_command_path="${ARTIFACT_DIR}/${ADMIN_COMMAND_NAME}"
  admin_command_checksum_path="${admin_command_path}.sha256"
  atomic_copy_file scripts/starry-api.sh "$admin_command_path" 0755 \
    || fail "Could not publish the administration command atomically."
  admin_command_checksum="$(sha256_file "$admin_command_path")"
  printf '%s  %s\n' "$admin_command_checksum" "$ADMIN_COMMAND_NAME" > "$admin_command_checksum_path"

  cat > "$info_path" <<EOF
{
  "artifact": "$(json_escape "$ARTIFACT_NAME")",
  "bundle": "$(json_escape "$BUNDLE_NAME")",
  "webuiDist": "$(json_escape "$WEBUI_DIST_NAME")",
  "compose": "$(json_escape "$COMPOSE_NAME")",
  "recoveryTool": "runtime/$(json_escape "$RECOVERY_TOOL_NAME")",
  "adminCommand": "$(json_escape "$ADMIN_COMMAND_NAME")",
  "version": "$(json_escape "$version")",
  "branch": "$(json_escape "$branch")",
  "commit": "$(json_escape "$commit")",
  "dirty": ${dirty},
  "builtAt": "$(json_escape "$built_at")",
  "sourceJar": "$(json_escape "$jar_path")",
  "sizeBytes": ${size_bytes},
  "webuiSizeBytes": ${webui_size},
  "sha256": "$(json_escape "$checksum")",
  "webuiSha256": "$(json_escape "$webui_checksum")",
  "composeSha256": "$(json_escape "$compose_checksum")",
  "recoveryToolSha256": "$(json_escape "$recovery_checksum")",
  "adminCommandSha256": "$(json_escape "$admin_command_checksum")",
  "java": "$(json_escape "$java_version")"
}
EOF

  checksums_path="${ARTIFACT_DIR}/${CHECKSUMS_NAME}"
  {
    printf '%s  %s\n' "$checksum" "$ARTIFACT_NAME"
    printf '%s  %s\n' "$webui_checksum" "$WEBUI_DIST_NAME"
    printf '%s  %s\n' "$compose_checksum" "$COMPOSE_NAME"
    printf '%s  %s\n' "$init_checksum" "postgres-init/001-starry-api-roles.sh"
    printf '%s  %s\n' "$recovery_checksum" "runtime/${RECOVERY_TOOL_NAME}"
    printf '%s  %s\n' "$(sha256_file "$recovery_checksum_path")" "runtime/${RECOVERY_TOOL_NAME}.sha256"
    printf '%s  %s\n' "$admin_command_checksum" "$ADMIN_COMMAND_NAME"
    printf '%s  %s\n' "$(sha256_file "$admin_command_checksum_path")" "${ADMIN_COMMAND_NAME}.sha256"
    printf '%s  %s\n' "$(sha256_file "$info_path")" "build-info.json"
    for source_jar in "$sources_dir"/*-sources.jar; do
      printf '%s  %s\n' "$(sha256_file "$source_jar")" "${SOURCES_DIR_NAME}/$(basename "$source_jar")"
    done
  } > "$checksums_path"

  bundle_path="${ARTIFACT_DIR}/${BUNDLE_NAME}"
  stage_dir="${tmp_dir}/starry-api"
  mkdir -p "$stage_dir/app" "$stage_dir/postgres-init" "$stage_dir/runtime"
  cp "$artifact_path" "$stage_dir/app/starry-api.jar"
  cp "$checksum_path" "$stage_dir/app/starry-api.jar.sha256"
  cp "$webui_dist_path" "$stage_dir/${WEBUI_DIST_NAME}"
  cp "${webui_dist_path}.sha256" "$stage_dir/${WEBUI_DIST_NAME}.sha256"
  cp "$compose_path" "$stage_dir/docker-compose.artifact.yml"
  cp "$init_path" "$stage_dir/postgres-init/001-starry-api-roles.sh"
  cp "$recovery_path" "$stage_dir/runtime/${RECOVERY_TOOL_NAME}"
  cp "$recovery_checksum_path" "$stage_dir/runtime/${RECOVERY_TOOL_NAME}.sha256"
  cp "$admin_command_path" "$stage_dir/${ADMIN_COMMAND_NAME}"
  cp "$admin_command_checksum_path" "$stage_dir/${ADMIN_COMMAND_NAME}.sha256"
  cp "$info_path" "$stage_dir/build-info.json"
  cp "$ROOT_DIR/LICENSE" "$stage_dir/LICENSE"
  cp "$ROOT_DIR/NOTICE" "$stage_dir/NOTICE"
  cp "$ROOT_DIR/webui/dist/THIRD-PARTY-NOTICES.txt" "$stage_dir/THIRD-PARTY-NOTICES.txt"
  extract_zip_entry "$artifact_path" META-INF/JAVA-THIRD-PARTY-NOTICES.txt "$stage_dir/JAVA-THIRD-PARTY-NOTICES.txt" \
    || fail "Could not read META-INF/JAVA-THIRD-PARTY-NOTICES.txt from the boot jar."
  mkdir -p "$stage_dir/licenses" "$stage_dir/${SOURCES_DIR_NAME}"
  cp "$ROOT_DIR/licenses/LGPL-2.1.txt" "$stage_dir/licenses/LGPL-2.1.txt"
  cp "$ROOT_DIR/licenses/logback-NOTICE.txt" "$stage_dir/licenses/logback-NOTICE.txt"
  cp "$sources_dir"/*-sources.jar "$stage_dir/${SOURCES_DIR_NAME}/"
  mkdir -p "$stage_dir/docker/egress" "$stage_dir/docs"
  cp "$ROOT_DIR/docker-compose.tls-bridge.yml" "$stage_dir/docker-compose.tls-bridge.yml"
  for egress_file in Dockerfile requirements.txt bridge.py mihomo-entrypoint.sh; do
    cp "$ROOT_DIR/docker/egress/$egress_file" "$stage_dir/docker/egress/$egress_file"
  done
  cp "$ROOT_DIR/docs/ACCOUNT_EGRESS.md" "$stage_dir/docs/ACCOUNT_EGRESS.md"
  {
    printf '%s  %s\n' "$(sha256_file "$stage_dir/app/starry-api.jar")" "app/starry-api.jar"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/app/starry-api.jar.sha256")" "app/starry-api.jar.sha256"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/${WEBUI_DIST_NAME}")" "$WEBUI_DIST_NAME"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/${WEBUI_DIST_NAME}.sha256")" "${WEBUI_DIST_NAME}.sha256"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/docker-compose.artifact.yml")" "docker-compose.artifact.yml"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/postgres-init/001-starry-api-roles.sh")" "postgres-init/001-starry-api-roles.sh"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/runtime/${RECOVERY_TOOL_NAME}")" "runtime/${RECOVERY_TOOL_NAME}"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/runtime/${RECOVERY_TOOL_NAME}.sha256")" "runtime/${RECOVERY_TOOL_NAME}.sha256"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/${ADMIN_COMMAND_NAME}")" "$ADMIN_COMMAND_NAME"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/${ADMIN_COMMAND_NAME}.sha256")" "${ADMIN_COMMAND_NAME}.sha256"
    printf '%s  %s\n' "$(sha256_file "$stage_dir/build-info.json")" "build-info.json"
    for notice_file in LICENSE NOTICE THIRD-PARTY-NOTICES.txt JAVA-THIRD-PARTY-NOTICES.txt licenses/LGPL-2.1.txt licenses/logback-NOTICE.txt; do
      printf '%s  %s\n' "$(sha256_file "$stage_dir/$notice_file")" "$notice_file"
    done
    for source_jar in "$stage_dir/${SOURCES_DIR_NAME}"/*-sources.jar; do
      printf '%s  %s\n' "$(sha256_file "$source_jar")" "${SOURCES_DIR_NAME}/$(basename "$source_jar")"
    done
    for egress_file in docker-compose.tls-bridge.yml docker/egress/Dockerfile docker/egress/requirements.txt docker/egress/bridge.py docker/egress/mihomo-entrypoint.sh docs/ACCOUNT_EGRESS.md; do
      printf '%s  %s\n' "$(sha256_file "$stage_dir/$egress_file")" "$egress_file"
    done
  } > "$stage_dir/sha256sums.txt"
  write_bundle_readme "$stage_dir/README.txt"
  create_tar_gz "$bundle_path" "$stage_dir"
  rm -rf "$tmp_dir"
  bundle_checksum="$(sha256_file "$bundle_path")"
  bundle_size="$(wc -c < "$bundle_path" | tr -d '[:space:]')"
  printf '%s  %s\n' "$bundle_checksum" "$BUNDLE_NAME" > "${bundle_path}.sha256"

  # The release has to carry the license files and the source code of the copyleft libraries, and no Mihomo binary.
  bash "$ROOT_DIR/scripts/check-bundle-contents.sh" "$bundle_path" \
    || fail "The bundle or the jar is missing license files (see above); do not publish ${ARTIFACT_DIR}."

  log "Updated ${artifact_path}"
  log "SHA256 ${checksum}"
  log "Updated ${webui_dist_path}"
  log "Updated ${bundle_path} (${bundle_size} bytes)"
  log "Bundle SHA256 ${bundle_checksum}"

  if [[ "$COMMIT_ARTIFACTS" == "true" ]]; then
    git add "$ARTIFACT_DIR"
    if git diff --cached --quiet; then
      log "No artifact changes to commit."
    else
      git commit -m "chore: publish prebuilt artifact for ${commit:0:12}"
    fi
    if [[ "$PUSH_ARTIFACTS" == "true" ]]; then
      git push origin "$branch"
    fi
  else
    log "Review and commit the artifact files:"
    log "  git add ${ARTIFACT_DIR}"
    log "  git commit -m \"chore: publish prebuilt artifact\""
  fi
}

main "$@"
