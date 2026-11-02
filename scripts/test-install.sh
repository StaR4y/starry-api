#!/usr/bin/env bash
# Tests scripts/install.sh without Docker: the installer runs in library mode with root_cmd (every Docker and Compose call)
# and the downloads answered locally. scripts/test-install-e2e.sh runs the real thing against a Docker daemon.
set -euo pipefail
export COPYFILE_DISABLE=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALLER="${ROOT_DIR}/scripts/install.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-installer-test.XXXXXX")"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail_test() {
  printf '[install-test] ERROR: %s\n' "$*" >&2
  exit 1
}

# What the installer downloads for a release: the files of this checkout, plus markers to recognise them by.
RELEASE_FILES="$TEST_ROOT/release"
mkdir -p "$RELEASE_FILES/docker/postgresql/init" "$RELEASE_FILES/docker/egress" "$RELEASE_FILES/scripts"
cp "$ROOT_DIR/docker-compose.yml" "$RELEASE_FILES/docker-compose.yml"
cp "$ROOT_DIR/docker/postgresql/init/001-starry-api-roles.sh" "$RELEASE_FILES/docker/postgresql/init/"
cp "$ROOT_DIR/docker/egress/mihomo-entrypoint.sh" "$ROOT_DIR/docker/egress/Dockerfile" "$ROOT_DIR/docker/egress/bridge.py" \
  "$ROOT_DIR/docker/egress/requirements.txt" "$RELEASE_FILES/docker/egress/"
cp "$ROOT_DIR/docker-compose.tls-bridge.yml" "$RELEASE_FILES/"
cp "$ROOT_DIR/scripts/starry-api.sh" "$RELEASE_FILES/scripts/starry-api.sh"

# A deployment made by this installer.
prepare_image_deployment() {
  local case_dir="$1"

  mkdir -p "$case_dir/docker/postgresql/init" "$case_dir/docker/egress"
  printf 'old-compose\n' > "$case_dir/docker-compose.yml"
  printf 'old-init\n' > "$case_dir/docker/postgresql/init/001-starry-api-roles.sh"
  printf 'old-entrypoint\n' > "$case_dir/docker/egress/mihomo-entrypoint.sh"
  cat > "$case_dir/.env" <<'EOF'
HOST_BIND_ADDR=127.0.0.1
HOST_HTTP_PORT=18080
SERVER_PORT=8080
POSTGRES_BOOTSTRAP_PASSWORD=old-bootstrap-password
POSTGRES_MIGRATION_PASSWORD=old-migration-password
POSTGRES_PASSWORD=old-application-password
STARRY_IMAGE=ghcr.io/star4y/starry-api:v1.0.0
EOF
  remember_deployment "$case_dir"
}

# A deployment from the older jar bundle, which is what a running server may still have.
prepare_jar_deployment() {
  local case_dir="$1"
  local env_kind="$2"

  mkdir -p "$case_dir/app" "$case_dir/postgres-init" "$case_dir/runtime"
  printf 'old-jar\n' > "$case_dir/app/starry-api.jar"
  printf 'old-compose\n' > "$case_dir/docker-compose.artifact.yml"
  printf 'old-init\n' > "$case_dir/postgres-init/001-starry-api-roles.sh"
  printf 'old-recovery-tool\n' > "$case_dir/runtime/recover-data-management.sh"
  if [[ "$env_kind" == "legacy" ]]; then
    printf 'LEGACY_DATABASE_PASSWORD=old-password\n' > "$case_dir/.env"
  else
    cat > "$case_dir/.env" <<'EOF'
HOST_BIND_ADDR=127.0.0.1
HOST_HTTP_PORT=18080
SERVER_PORT=8080
POSTGRES_BOOTSTRAP_PASSWORD=old-bootstrap-password
POSTGRES_MIGRATION_PASSWORD=old-migration-password
POSTGRES_PASSWORD=old-application-password
EOF
  fi
  remember_deployment "$case_dir"
}

# Keeps a copy of everything a rollback has to put back.
remember_deployment() {
  local case_dir="$1"
  rm -rf "$case_dir.expected"
  mkdir -p "$case_dir.expected"
  (cd "$case_dir" && tar -cf - .) | (cd "$case_dir.expected" && tar -xf -)
}

# run_flow <scenario> <case-dir> <force-env> [enable-mihomo]: the installer's whole flow against <case-dir>. Output lands
# in output.log and every Docker and Compose call in commands.log, both next to the case directory. Returns its status.
run_flow() {
  local scenario="$1"
  local case_dir="$2"
  local force_env="$3"
  local enable_mihomo="${4:-false}"
  local status

  : > "$case_dir.commands.log"
  rm -f "$case_dir.health-count"
  set +e
  INSTALL_DIR="$case_dir" \
  INSTALLER="$INSTALLER" \
  SCENARIO="$scenario" \
  TRACE_FILE="$case_dir.commands.log" \
  HEALTH_FILE="$case_dir.health-count" \
  FORCE_ENV="$force_env" \
  ENABLE_MIHOMO="$enable_mihomo" \
  STARRY_VERSION="${FLOW_VERSION:-v2.0.0}" \
  STARRY_IMAGE="${FLOW_IMAGE:-}" \
  IMAGE_REPO="${FLOW_IMAGE_REPO:-}" \
  PULL_IMAGE="${FLOW_PULL_IMAGE:-true}" \
  REPO_RAW_BASE="$RELEASE_FILES" \
  CLI_LINK_PATH="" \
  STARRY_INSTALL_LIBRARY_ONLY=true \
    bash -c '
      source "$INSTALLER"
      ensure_docker() { :; }
      ensure_compose() { COMPOSE_CMD=(mock-compose); }
      download_file() {
        if [[ "$SCENARIO" == "download" && "$1" == *docker-compose.yml ]]; then
          return 1
        fi
        cp -- "$1" "$2"
      }

      root_cmd() {
        local rendered="$*"
        case "$rendered" in
          "mock-compose version --short") printf "2.30.0\n"; return 0 ;;
          "mkdir "*|"chmod "*) "$@"; return $? ;;
        esac
        printf "%s\n" "$rendered" >> "$TRACE_FILE"
        if [[ "$SCENARIO" == "legacy-health" && "$rendered" == *" up -d postgres" ]]; then
          mkdir -p "$INSTALL_DIR/data/postgresql"
          printf "17\n" > "$INSTALL_DIR/data/postgresql/PG_VERSION"
        fi
        case "$SCENARIO:$rendered" in
          postgres-up:*" up -d postgres" | first-install:*" up -d postgres") return 1 ;;
          config:*" config -q") return 1 ;;
          pull:"docker pull "*) return 1 ;;
          inspect:"docker image inspect "*) return 1 ;;
          service-up:*" up -d --no-build --force-recreate service"*) return 1 ;;
        esac
        return 0
      }

      wait_for_health() {
        local count=0
        if [[ -f "$HEALTH_FILE" ]]; then
          read -r count < "$HEALTH_FILE"
        fi
        count=$((count + 1))
        printf "%s\n" "$count" > "$HEALTH_FILE"
        case "$SCENARIO" in
          service-health|legacy-health) [[ "$count" -ne 1 ]] ;;
          never-healthy) return 1 ;;
          *) return 0 ;;
        esac
      }

      install_flow
    ' > "$case_dir.output.log" 2>&1
  status=$?
  set -e
  return "$status"
}

expect_flow_failure() {
  local scenario="$1"
  shift
  if run_flow "$scenario" "$@"; then
    fail_test "$scenario unexpectedly succeeded"
  fi
}

# Everything the deployment had is back, byte for byte, and nothing the failed run brought is left. .previous copies, the
# data directories the installer creates and the administration command, which works with either deployment, do not count.
assert_deployment_restored() {
  local case_dir="$1"
  local differences
  differences="$(diff -r \
    -x '*.previous' -x 'data' -x '.env.postgresql-retry' -x 'starry-api' \
    "$case_dir.expected" "$case_dir" 2>&1 || true)"
  [[ -z "$differences" ]] || fail_test "the deployment was not restored for $case_dir: $differences"
}

health_calls_of() {
  cat "$1.health-count"
}

# --- Rollback of a deployment made by this installer -------------------------------------------------------------------

# Nothing touches the running stack before the release has proved itself usable: the image is pulled, the Compose file works.
for scenario in download config pull; do
  case_dir="$TEST_ROOT/early-$scenario"
  prepare_image_deployment "$case_dir"
  expect_flow_failure "$scenario" "$case_dir" false
  assert_deployment_restored "$case_dir"
  if grep -q ' up -d' "$case_dir.commands.log"; then
    fail_test "$scenario: a failure before the stack is touched must not start or recreate anything"
  fi
done
grep -q 'IMAGE_REPO or STARRY_IMAGE' "$TEST_ROOT/early-pull.output.log" \
  || fail_test "a failed pull must say how to name a mirror"

# A failure after the stack was touched restores the files, restarts the previous version from the image on this machine
# and checks that it is healthy.
for scenario in postgres-up service-up; do
  case_dir="$TEST_ROOT/image-$scenario"
  prepare_image_deployment "$case_dir"
  expect_flow_failure "$scenario" "$case_dir" false
  assert_deployment_restored "$case_dir"
  [[ "$(grep -Ec 'mock-compose -f docker-compose.yml up -d --no-build --force-recreate --remove-orphans$' "$case_dir.commands.log")" -eq 1 ]] \
    || fail_test "$scenario: expected one rollback of the stack: $(cat "$case_dir.commands.log")"
  [[ "$(health_calls_of "$case_dir")" -eq 1 ]] || fail_test "$scenario: the restored version must be checked once"
done
grep -q 'the previous deployment was restored and is healthy' "$TEST_ROOT/image-service-up.output.log" \
  || fail_test "a failed start must say that the previous version is back"

health_case="$TEST_ROOT/image-service-health"
prepare_image_deployment "$health_case"
expect_flow_failure service-health "$health_case" false
assert_deployment_restored "$health_case"
[[ "$(health_calls_of "$health_case")" -eq 2 ]] || fail_test "the new and the restored version must each be checked"
grep -q 'did not become healthy; the previous deployment was restored' "$health_case.output.log" \
  || fail_test "a failed health check must say that the previous version is back"

# When the old version does not come back either, the installer says so, and the files stay the old ones for a manual start.
broken_case="$TEST_ROOT/image-never-healthy"
prepare_image_deployment "$broken_case"
expect_flow_failure never-healthy "$broken_case" false
assert_deployment_restored "$broken_case"
grep -q 'and the automatic rollback failed' "$broken_case.output.log" \
  || fail_test "a failed rollback must be reported"

# An upgrade that works: the new image is in .env, and the rest of .env stays as it was.
upgrade_case="$TEST_ROOT/image-upgrade"
prepare_image_deployment "$upgrade_case"
run_flow success "$upgrade_case" false || fail_test "an upgrade failed: $(cat "$upgrade_case.output.log")"
grep -q '^STARRY_IMAGE=ghcr.io/star4y/starry-api:v2.0.0$' "$upgrade_case/.env" \
  || fail_test "an upgrade must point .env at the new release"
[[ "$(grep -c '^STARRY_IMAGE=' "$upgrade_case/.env")" -eq 1 ]] || fail_test "STARRY_IMAGE must be assigned once"
diff <(grep -v '^STARRY_IMAGE=' "$upgrade_case.expected/.env" | sort) <(grep -v '^STARRY_IMAGE=' "$upgrade_case/.env" | sort) > /dev/null \
  || fail_test "an upgrade must keep the rest of .env"
cmp -s "$upgrade_case/docker-compose.yml" "$RELEASE_FILES/docker-compose.yml" \
  || fail_test "an upgrade must install the Compose file of the release"
cmp -s "$upgrade_case/docker-compose.yml.previous" "$upgrade_case.expected/docker-compose.yml" \
  || fail_test "an upgrade must keep the previous Compose file"
cmp -s "$upgrade_case/.env.previous" "$upgrade_case.expected/.env" || fail_test "an upgrade must keep the previous .env"
[[ -x "$upgrade_case/docker/postgresql/init/001-starry-api-roles.sh" ]] \
  || fail_test "the PostgreSQL bootstrap must be executable"
cmp -s "$upgrade_case/docker/egress/mihomo-entrypoint.sh" "$RELEASE_FILES/docker/egress/mihomo-entrypoint.sh" \
  || fail_test "the Mihomo entrypoint must be installed unchanged"
[[ -x "$upgrade_case/starry-api" ]] || fail_test "the administration command must be installed"
grep -q '^docker pull ghcr.io/star4y/starry-api:v2.0.0$' "$upgrade_case.commands.log" \
  || fail_test "an upgrade must pull the image of the release"
grep -q 'up -d --no-build --force-recreate service$' "$upgrade_case.commands.log" \
  || fail_test "an upgrade must recreate the service from the image, without building"
grep -q 'up -d --no-build --remove-orphans postgres service$' "$upgrade_case.commands.log" \
  || fail_test "an upgrade must remove containers orphaned by the previous Compose definition"
grep -q '^Version: *v2.0.0 (ghcr.io/star4y/starry-api:v2.0.0)$' "$upgrade_case.output.log" \
  || fail_test "the summary must name the version and the image"
if grep -q -- '--build' "$upgrade_case.commands.log"; then
  fail_test "a deployment from the published image must never build"
fi

# The steps run in this order: a pull that fails leaves the stack alone.
pull_line="$(grep -n '^docker pull ' "$upgrade_case.commands.log" | head -n 1 | cut -d: -f1)"
up_line="$(grep -n ' up -d' "$upgrade_case.commands.log" | head -n 1 | cut -d: -f1)"
[[ "$pull_line" -lt "$up_line" ]] || fail_test "the image must be pulled before the stack is touched"

# An image that is already on the machine, as after docker load, is used without a registry.
loaded_case="$TEST_ROOT/image-loaded"
prepare_image_deployment "$loaded_case"
FLOW_PULL_IMAGE=false FLOW_IMAGE=starry-api:loaded run_flow success "$loaded_case" false \
  || fail_test "an image already on this machine must be usable: $(cat "$loaded_case.output.log")"
grep -q '^STARRY_IMAGE=starry-api:loaded$' "$loaded_case/.env" || fail_test "an exact STARRY_IMAGE must be written to .env"
if grep -q '^docker pull ' "$loaded_case.commands.log"; then
  fail_test "PULL_IMAGE=false must not pull"
fi
missing_case="$TEST_ROOT/image-missing"
prepare_image_deployment "$missing_case"
if FLOW_PULL_IMAGE=false FLOW_IMAGE=starry-api:absent run_flow inspect "$missing_case" false; then
  fail_test "PULL_IMAGE=false must fail when the image is not on this machine"
fi
assert_deployment_restored "$missing_case"

# --- Taking over a server that runs the older jar bundle ----------------------------------------------------------------

takeover_case="$TEST_ROOT/jar-takeover"
prepare_jar_deployment "$takeover_case" postgresql
run_flow success "$takeover_case" false || fail_test "taking over a jar deployment failed: $(cat "$takeover_case.output.log")"
[[ ! -e "$takeover_case/docker-compose.artifact.yml" ]] \
  || fail_test "the jar Compose file must move aside, because it marks a server that has not been taken over"
retired=("$takeover_case"/legacy-jar-deployment.*)
[[ "${#retired[@]}" -eq 1 && -d "${retired[0]}" ]] || fail_test "the jar deployment must be kept in one directory"
cmp -s "${retired[0]}/app/starry-api.jar" "$takeover_case.expected/app/starry-api.jar" \
  || fail_test "the old jar must be kept for the operator to delete"
for retired_path in docker-compose.artifact.yml postgres-init/001-starry-api-roles.sh runtime/recover-data-management.sh; do
  [[ -f "${retired[0]}/$retired_path" ]] || fail_test "$retired_path must be kept with the old deployment"
done
for retired_path in app postgres-init runtime; do
  [[ ! -e "$takeover_case/$retired_path" ]] || fail_test "$retired_path must not stay in the install directory"
done
grep -q 'delete them once you are satisfied' "$takeover_case.output.log" \
  || fail_test "the operator must be told where the old deployment went"
diff <(grep -v '^STARRY_IMAGE=' "$takeover_case/.env" | sort) <(sort "$takeover_case.expected/.env") > /dev/null \
  || fail_test "taking over must keep .env and only add the image"
# A second run finds a deployment of this installer.
run_flow success "$takeover_case" false || fail_test "a run after the takeover failed: $(cat "$takeover_case.output.log")"
[[ "$(find "$takeover_case" -maxdepth 1 -name 'legacy-jar-deployment.*' | wc -l | tr -d ' ')" -eq 1 ]] \
  || fail_test "only the takeover itself moves files aside"

# A takeover that fails puts the jar deployment back exactly as it was, built from its own Compose file. No file of the new
# deployment stays, or the administration command would not know which stack is running.
for scenario in postgres-up service-up service-health; do
  case_dir="$TEST_ROOT/jar-$scenario"
  prepare_jar_deployment "$case_dir" postgresql
  expect_flow_failure "$scenario" "$case_dir" false
  assert_deployment_restored "$case_dir"
  [[ ! -e "$case_dir/docker-compose.yml" ]] || fail_test "$scenario: the new Compose file must not stay after a rollback"
  [[ -f "$case_dir/docker-compose.artifact.yml" ]] || fail_test "$scenario: the jar deployment must stay in place"
  [[ "$(grep -Ec 'mock-compose -f docker-compose.artifact.yml up -d --build --force-recreate --remove-orphans$' "$case_dir.commands.log")" -eq 1 ]] \
    || fail_test "$scenario: the jar deployment must be rebuilt from its own file: $(cat "$case_dir.commands.log")"
done

# Regenerated credentials against a cluster that a failed run has already initialized are retained for a safe retry.
legacy_health_case="$TEST_ROOT/jar-legacy-health"
prepare_jar_deployment "$legacy_health_case" legacy
expect_flow_failure legacy-health "$legacy_health_case" true
assert_deployment_restored "$legacy_health_case"
[[ -f "$legacy_health_case/.env.postgresql-retry" ]] \
  || fail_test "a failed migration must retain the credentials of the initialized PostgreSQL cluster"
cp "$legacy_health_case/.env.postgresql-retry" "$legacy_health_case.expected-retry.env"
INSTALL_DIR="$legacy_health_case" \
FORCE_ENV=true \
STARRY_INSTALL_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    write_env_file
  ' _ "$INSTALLER"
cmp -s "$legacy_health_case/.env" "$legacy_health_case.expected-retry.env" \
  || fail_test "retry must reactivate the credentials matching the initialized PostgreSQL cluster"

postgres_case="$TEST_ROOT/jar-regenerated"
prepare_jar_deployment "$postgres_case" legacy
expect_flow_failure postgres-up "$postgres_case" true
assert_deployment_restored "$postgres_case"

# --- A fresh install -------------------------------------------------------------------------------------------------

# A failure leaves everything for a retry, and there is no earlier version to pretend to restore.
first_case="$TEST_ROOT/first-install"
mkdir -p "$first_case"
expect_flow_failure first-install "$first_case" false
for first_password in POSTGRES_BOOTSTRAP_PASSWORD POSTGRES_MIGRATION_PASSWORD POSTGRES_PASSWORD; do
  [[ "$(grep -E "^${first_password}=" "$first_case/.env" | cut -d= -f2- | wc -c)" -gt 32 ]] \
    || fail_test "a fresh .env must hold a generated ${first_password}"
done
grep -q '^  postgres:' "$first_case/docker-compose.yml" || fail_test "the Compose file must stay available for a retry"
grep -q '^STARRY_IMAGE=ghcr.io/star4y/starry-api:v2.0.0$' "$first_case/.env" \
  || fail_test "a fresh .env must name the image of the release"
grep -q 'Nothing to roll back to on a fresh install' "$first_case.output.log" \
  || fail_test "a failed fresh install must say that a retry reuses .env"
if grep -q -- '--force-recreate --remove-orphans' "$first_case.commands.log"; then
  fail_test "a fresh install must not try to restore a previous stack"
fi
[[ ! -e "$first_case/.env.previous" && ! -e "$first_case/docker-compose.yml.previous" ]] \
  || fail_test "a fresh install must not create false rollback files"
if grep -Fq "$(grep '^POSTGRES_PASSWORD=' "$first_case/.env" | cut -d= -f2-)" "$first_case.output.log" "$first_case.commands.log"; then
  fail_test "a generated password must never be printed"
fi

fresh_case="$TEST_ROOT/fresh"
mkdir -p "$fresh_case"
run_flow success "$fresh_case" false auto || fail_test "a fresh install failed: $(cat "$fresh_case.output.log")"
for fresh_dir in postgresql backups security plugins documentation model-ranking model-pricing; do
  [[ -d "$fresh_case/data/$fresh_dir" ]] || fail_test "the install directory must have data/$fresh_dir"
done
[[ "$(ls -ld "$fresh_case/data/security" | cut -c1-10)" == "drwx------" ]] || fail_test "data/security must be private"
[[ "$(ls -ld "$fresh_case/.env" | cut -c1-10)" == "-rw-------" ]] || fail_test ".env must be private"
grep -q 'up -d --no-build --force-recreate service mihomo$' "$fresh_case.commands.log" \
  || fail_test "with Mihomo deployed the service and mihomo must be recreated together"
grep -q 'up -d --no-build --remove-orphans postgres service mihomo$' "$fresh_case.commands.log" \
  || fail_test "with Mihomo deployed the final up must include mihomo"
grep -q '^Mihomo egress: *deployed - switch it on in the console' "$fresh_case.output.log" \
  || fail_test "the summary must say Mihomo is deployed and how to switch it on"
grep -q '^\[starry-api\] Mihomo is deployed: the image metacubex/mihomo .*GPL-3.0.*does not bundle or distribute it' "$fresh_case.output.log" \
  || fail_test "deploying Mihomo must say that its image is GPL-3.0 and not distributed by this project"
grep -q '^Mihomo license: *the image metacubex/mihomo .*GPL-3.0' "$fresh_case.output.log" \
  || fail_test "the summary must repeat the Mihomo image and its license"
if grep -Fq "$(grep '^STARRY_EGRESS_CONTROLLER_SECRET=' "$fresh_case/.env" | cut -d= -f2-)" "$fresh_case.output.log" "$fresh_case.commands.log"; then
  fail_test "the deployment must not print the controller secret"
fi

no_mihomo_case="$TEST_ROOT/fresh-without-mihomo"
mkdir -p "$no_mihomo_case"
run_flow success "$no_mihomo_case" false false || fail_test "a fresh install without Mihomo failed: $(cat "$no_mihomo_case.output.log")"
if grep -Eq '^(COMPOSE_PROFILES|STARRY_EGRESS_[A-Z_]*|MIHOMO_[A-Z_]*)=' "$no_mihomo_case/.env"; then
  fail_test "an install with ENABLE_MIHOMO=false must not add any Mihomo setting to .env"
fi
if grep -qi 'mihomo' "$no_mihomo_case.commands.log"; then
  fail_test "an install with ENABLE_MIHOMO=false must not mention the mihomo service in Compose commands"
fi
grep -q '^Mihomo egress: *not deployed (rerun with ENABLE_MIHOMO=true' "$no_mihomo_case.output.log" \
  || fail_test "the summary must say Mihomo is not deployed and how to deploy it"
if grep -qi 'mihomo license\|GPL-3.0' "$no_mihomo_case.output.log"; then
  fail_test "an install without Mihomo must not talk about the Mihomo license"
fi

# A source checkout is not an install directory: the installer would replace its Compose file.
checkout_case="$TEST_ROOT/checkout"
mkdir -p "$checkout_case"
printf 'rootProject.name = "x"\n' > "$checkout_case/settings.gradle"
expect_flow_failure success "$checkout_case" false
grep -q 'is a source checkout' "$checkout_case.output.log" || fail_test "a source checkout must be refused"

# --- Which release and which image -----------------------------------------------------------------------------------

# release_values <env assignments...>: runs resolve_release and prints the outcome as NAME=value lines.
release_values() {
  env -u STARRY_VERSION -u STARRY_IMAGE -u IMAGE_REPO -u REPO_RAW_BASE STARRY_INSTALL_LIBRARY_ONLY=true "$@" bash -c '
    source "$1"
    curl() {
      printf "%s" "${FAKE_LATEST_URL:-}"
      [[ -n "${FAKE_LATEST_URL:-}" ]]
    }
    resolve_release > /dev/null
    printf "RELEASE_TAG=%s\nDEPLOY_IMAGE=%s\nREPO_RAW_BASE=%s\n" "$RELEASE_TAG" "$DEPLOY_IMAGE" "$REPO_RAW_BASE"
  ' _ "$INSTALLER"
}

release_case="$TEST_ROOT/release-values"
mkdir -p "$release_case"
base_env=(INSTALL_DIR="$release_case")

# The default is the latest release, found from where its redirect ends; files and image come from that same tag.
values="$(release_values "${base_env[@]}" FAKE_LATEST_URL=https://github.com/StaR4y/starry-api/releases/tag/v3.1.4)"
grep -q '^RELEASE_TAG=v3.1.4$' <<<"$values" || fail_test "the latest release must be taken from the redirect: $values"
grep -q '^DEPLOY_IMAGE=ghcr.io/star4y/starry-api:v3.1.4$' <<<"$values" || fail_test "the default image must be the release: $values"
grep -q '^REPO_RAW_BASE=https://raw.githubusercontent.com/StaR4y/starry-api/v3.1.4$' <<<"$values" \
  || fail_test "the files must come from the same tag as the image: $values"

# No release yet: the redirect ends at the releases page, or the request fails.
for no_release in https://github.com/StaR4y/starry-api/releases ""; do
  if output="$(release_values "${base_env[@]}" FAKE_LATEST_URL="$no_release" 2>&1)"; then
    fail_test "installing the latest release must fail when there is none: $output"
  fi
  grep -q 'Could not find a published release' <<<"$output" || fail_test "the failure must say what is missing: $output"
  grep -q 'docs/DEPLOYMENT.md' <<<"$output" || fail_test "the failure must point at building from source: $output"
done

values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3)"
grep -q '^DEPLOY_IMAGE=ghcr.io/star4y/starry-api:v1.2.3$' <<<"$values" || fail_test "a named release must be installed: $values"

# A mirror: named once, and kept by later upgrades that only move the tag. A registry port is not a tag.
values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3 IMAGE_REPO=registry.example.com:5000/mirror/starry-api)"
grep -q '^DEPLOY_IMAGE=registry.example.com:5000/mirror/starry-api:v1.2.3$' <<<"$values" || fail_test "IMAGE_REPO must name the repository: $values"
printf 'STARRY_IMAGE=registry.example.com:5000/mirror/starry-api:v1.0.0\n' > "$release_case/.env"
values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3)"
grep -q '^DEPLOY_IMAGE=registry.example.com:5000/mirror/starry-api:v1.2.3$' <<<"$values" || fail_test "an upgrade must keep the mirror: $values"
printf 'STARRY_IMAGE=registry.example.com:5000/starry-api\n' > "$release_case/.env"
values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3)"
grep -q '^DEPLOY_IMAGE=registry.example.com:5000/starry-api:v1.2.3$' <<<"$values" || fail_test "a registry port is not a tag: $values"
printf 'STARRY_IMAGE=registry.example.com/starry-api@sha256:abc\n' > "$release_case/.env"
values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3)"
grep -q '^DEPLOY_IMAGE=registry.example.com/starry-api:v1.2.3$' <<<"$values" || fail_test "a pinned digest must give way to the release: $values"
printf 'POSTGRES_PASSWORD=x\n' > "$release_case/.env"
values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3)"
grep -q '^DEPLOY_IMAGE=ghcr.io/star4y/starry-api:v1.2.3$' <<<"$values" || fail_test "a .env that names no image must get the default: $values"

# An exact image and a mirror of the files.
values="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3 STARRY_IMAGE=my/own:image REPO_RAW_BASE=https://mirror.example.com/files)"
grep -q '^DEPLOY_IMAGE=my/own:image$' <<<"$values" || fail_test "STARRY_IMAGE must be used as it is: $values"
grep -q '^REPO_RAW_BASE=https://mirror.example.com/files$' <<<"$values" || fail_test "REPO_RAW_BASE must be used as it is: $values"

# Whatever ends up in .env or in a URL is checked first.
for bad_version in 'v1 2' 'v1;id' '../v1' '-v1'; do
  if output="$(release_values "${base_env[@]}" STARRY_VERSION="$bad_version" 2>&1)"; then
    fail_test "STARRY_VERSION=${bad_version} must be refused: $output"
  fi
done
if output="$(release_values "${base_env[@]}" STARRY_VERSION=v1.2.3 STARRY_IMAGE='my image' 2>&1)"; then
  fail_test "an image reference with a space must be refused: $output"
fi

# The installer's variable does not reach Compose: a rollback must get the image of the restored .env.
leaked="$(env STARRY_INSTALL_LIBRARY_ONLY=true STARRY_IMAGE=my/own:image bash -c 'source "$1"; printf "%s" "${STARRY_IMAGE-unset}"' _ "$INSTALLER")"
[[ "$leaked" == "unset" ]] || fail_test "STARRY_IMAGE must not stay in the environment of the Compose commands"

# --- The Compose file ------------------------------------------------------------------------------------------------

compose_file="${ROOT_DIR}/docker-compose.yml"
grep -q '^    image: ${STARRY_IMAGE:-ghcr.io/star4y/starry-api:latest}$' "$compose_file" \
  || fail_test "the service must run the published image by default"
grep -q 'DEFAULT_IMAGE_REPO="ghcr.io/star4y/starry-api"' "$INSTALLER" \
  || fail_test "the installer and the Compose file must name the same image"
grep -q -- '- ./docker/postgresql/init/001-starry-api-roles.sh:/docker-entrypoint-initdb.d/' "$compose_file" \
  || fail_test "the PostgreSQL bootstrap must be mounted from the path the installer downloads it to"
grep -q -- '- ./docker/egress/mihomo-entrypoint.sh:/entrypoint.sh:ro' "$compose_file" \
  || fail_test "the Mihomo entrypoint must be mounted from the path the installer downloads it to"
grep -q -- '- ./data/documentation:/app/documentation$' "$compose_file" \
  || fail_test "Compose must keep documentation attachments outside the container"
grep -q 'STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS: ${STARRY_ACCOUNT_ALLOCATION_LEASE_RETENTION_DAYS:-7}$' "$compose_file" \
  || fail_test "Compose must hold the retention defaults, because a generated .env does not repeat them"
grep -q 'profiles: \["mihomo"\]' "$compose_file" || fail_test "the mihomo service must belong to the mihomo profile"
grep -q 'image: metacubex/mihomo:${MIHOMO_VERSION:-v1.19.32}$' "$compose_file" \
  || fail_test "the mihomo service must use the pinned official image"
grep -q 'STARRY_EGRESS_MIHOMO_ENABLED: ${STARRY_EGRESS_MIHOMO_ENABLED:-false}$' "$compose_file" \
  || fail_test "the service must pass the legacy Mihomo switch (the console switch's initial value), default false"
grep -q 'STARRY_EGRESS_CONTROLLER_SECRET: ${STARRY_EGRESS_CONTROLLER_SECRET:-}$' "$compose_file" \
  || fail_test "the service and mihomo must both receive the controller secret"
if grep -E 'STARRY_EGRESS_|MIHOMO_' "$compose_file" | grep -q ':?'; then
  fail_test "Compose interpolates inactive profiles: no Mihomo setting may be mandatory"
fi
if grep -E 'STARRY_EGRESS_TLS|STARRY_TLS_SECRET|tls-bridge' "$compose_file" >/dev/null; then
  fail_test "the TLS bridge must not be coupled to the base Compose file"
fi
grep -q 'STARRY_TLS_SECRET: ${STARRY_TLS_SECRET:?' "${ROOT_DIR}/docker-compose.tls-bridge.yml" \
  || fail_test "the opt-in TLS bridge overlay must require its secret"
if grep -vE '^[[:space:]]*#' "${ROOT_DIR}/docker-compose.tls-bridge.yml" | grep -qi 'mihomo'; then
  fail_test "the TLS bridge overlay must not depend on Mihomo"
fi

# The real parser, when Compose is on this machine: the file works with a generated .env, and with .env.example.
if docker compose version >/dev/null 2>&1; then
  for compose_env in "$fresh_case/.env" "${ROOT_DIR}/.env.example"; do
    docker compose --env-file "$compose_env" -f "$compose_file" config -q \
      || fail_test "docker compose rejects the Compose file with $compose_env"
  done
  rendered_image="$(docker compose --env-file "$fresh_case/.env" -f "$compose_file" config --images | grep starry-api || true)"
  [[ "$rendered_image" == "ghcr.io/star4y/starry-api:v2.0.0" ]] \
    || fail_test "Compose must run the image named by .env, got: $rendered_image"
fi

# --- Optional Mihomo component ---------------------------------------------------------------------------------------
#
# ENABLE_MIHOMO is auto (default), true or false. Mihomo is deployed by COMPOSE_PROFILES=mihomo plus a controller secret
# in .env; the installer never writes the backend switch, because an administrator uses the console for that.

# Runs one installer function in library mode against an install directory; stdout and stderr land in $output_file.
# An ENABLE_MIHOMO of "default" leaves the variable unset, as it is when the installer is run without it.
run_installer_function() {
  local install_dir="$1"
  local enable_mihomo="$2"
  local output_file="$3"
  local function_name="$4"
  local status
  local -a mihomo_env=("ENABLE_MIHOMO=${enable_mihomo}")

  [[ "$enable_mihomo" != "default" ]] || mihomo_env=(-u ENABLE_MIHOMO)
  set +e
  env "${mihomo_env[@]}" \
  INSTALL_DIR="$install_dir" \
  FORCE_ENV=false \
  STARRY_INSTALL_LIBRARY_ONLY=true \
    bash -c 'source "$1"; "$2"' _ "$INSTALLER" "$function_name" > "$output_file" 2>&1
  status=$?
  set -e
  return "$status"
}

env_count() {
  grep -c -E "^$2=" "$1" || true
}

env_value() {
  grep -E "^$2=" "$1" | tail -n 1 | cut -d= -f2-
}

# Deployed: the profile (which starts the container) and the secret (which makes Mihomo available), each assigned once.
assert_mihomo_deployed_env() {
  local env_file="$1"
  local label="$2"
  local secret

  [[ "$(env_count "$env_file" COMPOSE_PROFILES)" -eq 1 ]] \
    || fail_test "$label: COMPOSE_PROFILES must be assigned exactly once"
  [[ "$(env_count "$env_file" STARRY_EGRESS_CONTROLLER_SECRET)" -eq 1 ]] \
    || fail_test "$label: STARRY_EGRESS_CONTROLLER_SECRET must be assigned exactly once"
  secret="$(env_value "$env_file" STARRY_EGRESS_CONTROLLER_SECRET)"
  [[ "$secret" =~ ^[A-Za-z0-9_-]{32,}$ ]] \
    || fail_test "$label: the controller secret must be long and accepted by the Mihomo entrypoint"
}

assert_no_mihomo_env() {
  if grep -Eq '^(COMPOSE_PROFILES|STARRY_EGRESS_[A-Z_]*|MIHOMO_[A-Z_]*)=' "$1"; then
    fail_test "$2: no Mihomo setting may be in .env"
  fi
}

file_mode() {
  ls -ld "$1" | cut -c1-10
}

# The default is auto.
default_value="$(env -u ENABLE_MIHOMO STARRY_INSTALL_LIBRARY_ONLY=true bash -c 'source "$1"; printf "%s" "$ENABLE_MIHOMO"' _ "$INSTALLER")"
[[ "$default_value" == "auto" ]] || fail_test "ENABLE_MIHOMO must default to auto, not '${default_value}'"

# A fresh install deploys Mihomo by default (auto): the profile and a generated secret, and not the backend switch.
mihomo_fresh="$TEST_ROOT/mihomo-fresh"
mkdir -p "$mihomo_fresh"
run_installer_function "$mihomo_fresh" default "$mihomo_fresh.log" write_env_file \
  || fail_test "a fresh install with the default ENABLE_MIHOMO failed: $(cat "$mihomo_fresh.log")"
assert_mihomo_deployed_env "$mihomo_fresh/.env" "fresh install (default)"
[[ "$(env_value "$mihomo_fresh/.env" COMPOSE_PROFILES)" == "mihomo" ]] \
  || fail_test "fresh install: COMPOSE_PROFILES must select the mihomo profile"
[[ "$(env_value "$mihomo_fresh/.env" STARRY_SOURCE_URL)" == "https://github.com/StaR4y/starry-api" ]] \
  || fail_test "a fresh .env must name the source address that the console links to (AGPL-3.0, section 13)"
[[ "$(env_count "$mihomo_fresh/.env" STARRY_EGRESS_MIHOMO_ENABLED)" -eq 0 ]] \
  || fail_test "fresh install: the installer must leave the backend switch to the console"
grep -q '^POSTGRES_BOOTSTRAP_PASSWORD=' "$mihomo_fresh/.env" \
  || fail_test "fresh install with Mihomo must still write the database settings"
[[ "$(file_mode "$mihomo_fresh/.env")" == "-rw-------" ]] \
  || fail_test "the .env file with the Mihomo secret must stay private"
if grep -Fq "$(env_value "$mihomo_fresh/.env" STARRY_EGRESS_CONTROLLER_SECRET)" "$mihomo_fresh.log"; then
  fail_test "the controller secret must never be printed"
fi
[[ ! -e "$mihomo_fresh/.env.previous" ]] \
  || fail_test "a fresh install has no previous .env to keep"
if ls "$mihomo_fresh"/.env.incoming.* >/dev/null 2>&1; then
  fail_test "a fresh install must not leave temporary .env files behind"
fi

# auto spelled out and true give a fresh install the same Mihomo settings; false leaves them out.
mihomo_fresh_auto="$TEST_ROOT/mihomo-fresh-auto"
mkdir -p "$mihomo_fresh_auto"
run_installer_function "$mihomo_fresh_auto" auto "$mihomo_fresh_auto.log" write_env_file \
  || fail_test "a fresh install with ENABLE_MIHOMO=auto failed: $(cat "$mihomo_fresh_auto.log")"
assert_mihomo_deployed_env "$mihomo_fresh_auto/.env" "fresh install (auto)"
mihomo_fresh_true="$TEST_ROOT/mihomo-fresh-true"
mkdir -p "$mihomo_fresh_true"
run_installer_function "$mihomo_fresh_true" true "$mihomo_fresh_true.log" write_env_file \
  || fail_test "a fresh install with ENABLE_MIHOMO=true failed: $(cat "$mihomo_fresh_true.log")"
assert_mihomo_deployed_env "$mihomo_fresh_true/.env" "fresh install (true)"
[[ "$(env_count "$mihomo_fresh_true/.env" STARRY_EGRESS_MIHOMO_ENABLED)" -eq 0 ]] \
  || fail_test "fresh install (true): the installer must leave the backend switch to the console"
mihomo_default="$TEST_ROOT/mihomo-default"
mkdir -p "$mihomo_default"
run_installer_function "$mihomo_default" false "$mihomo_default.log" write_env_file \
  || fail_test "a fresh install with ENABLE_MIHOMO=false failed: $(cat "$mihomo_default.log")"
assert_no_mihomo_env "$mihomo_default/.env" "fresh install (false)"

# An existing .env is not changed by auto or false, so an upgrade never gains a container silently.
mihomo_untouched="$TEST_ROOT/mihomo-untouched"
prepare_jar_deployment "$mihomo_untouched" postgresql
for untouched_option in default auto false; do
  run_installer_function "$mihomo_untouched" "$untouched_option" "$mihomo_untouched.log" write_env_file \
    || fail_test "ENABLE_MIHOMO=${untouched_option} on an existing .env failed: $(cat "$mihomo_untouched.log")"
  cmp -s "$mihomo_untouched/.env" "$mihomo_untouched.expected/.env" \
    || fail_test "ENABLE_MIHOMO=${untouched_option} must not change an existing .env"
  [[ ! -e "$mihomo_untouched/.env.previous" ]] \
    || fail_test "ENABLE_MIHOMO=${untouched_option} must not rewrite .env.previous"
done
run_installer_function "$mihomo_untouched" auto "$mihomo_untouched.log" write_env_file
grep -q 'ENABLE_MIHOMO=true' "$mihomo_untouched.log" \
  || fail_test "an upgrade that does not deploy Mihomo must say how to add it"

# true adds the profile and the secret to an existing .env, keeps the old file as .env.previous and does not touch the rest.
mihomo_existing="$TEST_ROOT/mihomo-existing"
prepare_jar_deployment "$mihomo_existing" postgresql
chmod 0644 "$mihomo_existing/.env"
run_installer_function "$mihomo_existing" true "$mihomo_existing.log" write_env_file \
  || fail_test "adding Mihomo to an existing .env failed: $(cat "$mihomo_existing.log")"
assert_mihomo_deployed_env "$mihomo_existing/.env" "existing .env"
[[ "$(env_count "$mihomo_existing/.env" STARRY_EGRESS_MIHOMO_ENABLED)" -eq 0 ]] \
  || fail_test "adding Mihomo must not write the backend switch"
cmp -s "$mihomo_existing/.env.previous" "$mihomo_existing.expected/.env" \
  || fail_test "adding Mihomo must keep the untouched .env as .env.previous"
original_lines="$(wc -l < "$mihomo_existing.expected/.env" | tr -d '[:space:]')"
head -n "$original_lines" "$mihomo_existing/.env" | cmp -s - "$mihomo_existing.expected/.env" \
  || fail_test "adding Mihomo must only append to an existing .env"
[[ "$(env_value "$mihomo_existing/.env" POSTGRES_PASSWORD)" == "old-application-password" ]] \
  || fail_test "adding Mihomo must keep the database credentials"
[[ "$(file_mode "$mihomo_existing/.env")" == "-rw-------" ]] \
  || fail_test "the updated .env must be private"
if grep -Fq "$(env_value "$mihomo_existing/.env" STARRY_EGRESS_CONTROLLER_SECRET)" "$mihomo_existing.log"; then
  fail_test "the controller secret must never be printed when updating an existing .env"
fi
if ls "$mihomo_existing"/.env.incoming.* >/dev/null 2>&1; then
  fail_test "adding Mihomo must not leave temporary .env files behind"
fi

# Running it again adds nothing, whichever option: no duplicate keys, same secret, same file.
cp "$mihomo_existing/.env" "$mihomo_existing/after-first.env"
for repeat_option in true auto false default; do
  run_installer_function "$mihomo_existing" "$repeat_option" "$mihomo_existing.log" write_env_file \
    || fail_test "repeating with ENABLE_MIHOMO=${repeat_option} failed: $(cat "$mihomo_existing.log")"
  cmp -s "$mihomo_existing/.env" "$mihomo_existing/after-first.env" \
    || fail_test "ENABLE_MIHOMO=${repeat_option} must leave a deployment that has Mihomo unchanged"
  assert_mihomo_deployed_env "$mihomo_existing/.env" "repeated run (${repeat_option})"
done
cmp -s "$mihomo_existing/.env.previous" "$mihomo_existing.expected/.env" \
  || fail_test "a repeated run must keep the first .env.previous"

# A deployment from before the console switch (profile, switch and secret in .env) keeps all three, under every option.
mihomo_legacy="$TEST_ROOT/mihomo-legacy"
prepare_jar_deployment "$mihomo_legacy" postgresql
cat >> "$mihomo_legacy/.env" <<'EOF'
COMPOSE_PROFILES=mihomo
STARRY_EGRESS_MIHOMO_ENABLED=true
STARRY_EGRESS_CONTROLLER_SECRET=Legacy_secret-1234567890abcdef12345678
EOF
cp "$mihomo_legacy/.env" "$mihomo_legacy/expected-legacy.env"
for legacy_option in default auto true false; do
  run_installer_function "$mihomo_legacy" "$legacy_option" "$mihomo_legacy.log" write_env_file \
    || fail_test "ENABLE_MIHOMO=${legacy_option} on a legacy Mihomo .env failed: $(cat "$mihomo_legacy.log")"
  cmp -s "$mihomo_legacy/.env" "$mihomo_legacy/expected-legacy.env" \
    || fail_test "ENABLE_MIHOMO=${legacy_option} must keep STARRY_EGRESS_MIHOMO_ENABLED=true and the rest as they were"
done
[[ "$(env_value "$mihomo_legacy/.env" STARRY_EGRESS_MIHOMO_ENABLED)" == "true" ]] \
  || fail_test "the legacy switch is the initial value of the console switch and must stay"
[[ ! -e "$mihomo_legacy/.env.previous" ]] \
  || fail_test "nothing changed, so nothing is kept as .env.previous"

# Settings that are already partly there are merged, not duplicated; the existing secret and switch are kept.
mihomo_merge="$TEST_ROOT/mihomo-merge"
prepare_jar_deployment "$mihomo_merge" postgresql
cat >> "$mihomo_merge/.env" <<'EOF'
COMPOSE_PROFILES="monitoring"
STARRY_EGRESS_MIHOMO_ENABLED=false
STARRY_EGRESS_CONTROLLER_SECRET=Existing_secret-1234567890abcdef
EOF
run_installer_function "$mihomo_merge" true "$mihomo_merge.log" write_env_file \
  || fail_test "merging Mihomo settings failed: $(cat "$mihomo_merge.log")"
assert_mihomo_deployed_env "$mihomo_merge/.env" "merged .env"
[[ "$(env_value "$mihomo_merge/.env" COMPOSE_PROFILES)" == "monitoring,mihomo" ]] \
  || fail_test "merging must keep the other Compose profiles and add mihomo"
[[ "$(env_value "$mihomo_merge/.env" STARRY_EGRESS_CONTROLLER_SECRET)" == "Existing_secret-1234567890abcdef" ]] \
  || fail_test "an existing controller secret must be kept"
[[ "$(env_value "$mihomo_merge/.env" STARRY_EGRESS_MIHOMO_ENABLED)" == "false" ]] \
  || fail_test "merging must not turn an existing backend switch on"

# A secret the Mihomo entrypoint would refuse is rejected before anything changes.
mihomo_badsecret="$TEST_ROOT/mihomo-badsecret"
prepare_jar_deployment "$mihomo_badsecret" postgresql
printf 'STARRY_EGRESS_CONTROLLER_SECRET=bad secret$value\n' >> "$mihomo_badsecret/.env"
cp "$mihomo_badsecret/.env" "$mihomo_badsecret/expected-bad.env"
if run_installer_function "$mihomo_badsecret" true "$mihomo_badsecret.log" write_env_file; then
  fail_test "an unusable controller secret must be rejected"
fi
grep -Fq 'STARRY_EGRESS_CONTROLLER_SECRET' "$mihomo_badsecret.log" \
  || fail_test "the rejection must name the offending setting"
if grep -Fq 'bad secret' "$mihomo_badsecret.log"; then
  fail_test "a rejected secret must not be echoed"
fi
cmp -s "$mihomo_badsecret/.env" "$mihomo_badsecret/expected-bad.env" \
  || fail_test "a rejected secret must leave .env untouched"
[[ ! -e "$mihomo_badsecret/.env.previous" ]] \
  || fail_test "a rejected secret must not rewrite .env.previous"

# The credentials retained after a failed first deployment are reused as they are by auto, and get Mihomo with true.
mihomo_retry="$TEST_ROOT/mihomo-retry"
prepare_jar_deployment "$mihomo_retry" postgresql
mkdir -p "$mihomo_retry/data/postgresql"
printf '17\n' > "$mihomo_retry/data/postgresql/PG_VERSION"
cp "$mihomo_retry/.env" "$mihomo_retry/.env.postgresql-retry"
retry_run() {
  local option="$1"
  INSTALL_DIR="$mihomo_retry" ENABLE_MIHOMO="$option" FORCE_ENV=true STARRY_INSTALL_LIBRARY_ONLY=true \
    bash -c 'source "$1"; write_env_file' _ "$INSTALLER" > "$mihomo_retry.log" 2>&1
}
retry_run auto || fail_test "reusing retained credentials with auto failed: $(cat "$mihomo_retry.log")"
cmp -s "$mihomo_retry/.env" "$mihomo_retry.expected/.env" \
  || fail_test "auto must reuse retained credentials as they are"
retry_run true || fail_test "reusing retained credentials with true failed: $(cat "$mihomo_retry.log")"
assert_mihomo_deployed_env "$mihomo_retry/.env" "retained credentials with true"

# The option itself is validated.
for good_option in auto true false; do
  run_installer_function "$mihomo_fresh" "$good_option" "$TEST_ROOT/mihomo-option.log" validate_enable_mihomo \
    || fail_test "ENABLE_MIHOMO=${good_option} must be accepted"
done
if run_installer_function "$mihomo_fresh" maybe "$TEST_ROOT/mihomo-option.log" validate_enable_mihomo; then
  fail_test "ENABLE_MIHOMO must be auto, true or false"
fi
grep -q 'auto, true or false' "$TEST_ROOT/mihomo-option.log" \
  || fail_test "the rejection must list the accepted values"

# The state shown in the final summary follows .env.
mihomo_state_of() {
  run_installer_function "$1" false "$1.state" mihomo_state_line || fail_test "could not read the Mihomo state"
  cat "$1.state"
}
[[ "$(mihomo_state_of "$mihomo_default")" == "not deployed"* ]] || fail_test "summary must report an install without Mihomo as not deployed"
mihomo_state_text="$(mihomo_state_of "$mihomo_fresh")"
[[ "$mihomo_state_text" == "deployed - switch it on in the console under 系统设置 → 出站代理"* ]] \
  || fail_test "summary must report a deployed Mihomo and where to switch it on, got: ${mihomo_state_text}"
[[ "$mihomo_state_text" != *STARRY_EGRESS_MIHOMO_ENABLED* ]] \
  || fail_test "a fresh deployment is not switched on by .env, so the summary must not say so"
[[ "$(mihomo_state_of "$mihomo_legacy")" == "deployed - "*"STARRY_EGRESS_MIHOMO_ENABLED=true in .env keeps it on"* ]] \
  || fail_test "summary must say that a legacy switch in .env keeps Mihomo on"
[[ "$(mihomo_state_of "$mihomo_untouched")" == "not deployed (rerun with ENABLE_MIHOMO=true"* ]] \
  || fail_test "summary must tell how to deploy Mihomo when it is not"
mihomo_partial="$TEST_ROOT/mihomo-partial"
mkdir -p "$mihomo_partial"
printf 'COMPOSE_PROFILES=mihomo\n' > "$mihomo_partial/.env"
[[ "$(mihomo_state_of "$mihomo_partial")" == INCOMPLETE* ]] || fail_test "summary must flag a profile without a secret"
printf 'STARRY_EGRESS_CONTROLLER_SECRET=Only_a_secret-1234567890abcdef12\n' > "$mihomo_partial/.env"
[[ "$(mihomo_state_of "$mihomo_partial")" == INCOMPLETE* ]] || fail_test "summary must flag a secret without a profile"

# --- The starry-api administration command ---------------------------------------------------------------------------
#
# The installer downloads scripts/starry-api.sh of the release, installs it as ${INSTALL_DIR}/starry-api and links it into
# the PATH. A release without it (or a failed download) only skips it, and the link never replaces a file that is not a link
# to a Starry API installation.

cli_root="$TEST_ROOT/cli"
mkdir -p "$cli_root"

# cli_install <case-dir> <steps...>: runs installer functions in library mode against $case_dir/install with every
# download answered from a local file ($case_dir/downloads/scripts/starry-api.sh for the command) and the link in
# $case_dir/bin. Everything the steps print lands in $case_dir/output.log. Returns the exit status of the steps.
cli_install() {
  local case_dir="$1"
  shift
  local status
  mkdir -p "$case_dir/install"
  set +e
  INSTALL_DIR="$case_dir/install" \
  REPO_RAW_BASE="$case_dir/downloads" \
  CLI_LINK_PATH="${CLI_LINK_PATH_OVERRIDE-$case_dir/bin/starry-api}" \
  STARRY_INSTALL_LIBRARY_ONLY=true \
    bash -c '
      source "$1"
      shift
      download_file() { cp -- "$1" "$2"; }
      for step in "$@"; do
        $step
      done
      printf "CLI_COMMAND=%s\n" "$CLI_COMMAND"
    ' _ "$INSTALLER" "$@" > "$case_dir/output.log" 2>&1
  status=$?
  set -e
  return "$status"
}

# Offered by the release: installed, executable and linked.
cli_case="$cli_root/offered"
mkdir -p "$cli_case/downloads/scripts" "$cli_case/bin"
printf '#!/usr/bin/env bash\necho new-command\n' > "$cli_case/downloads/scripts/starry-api.sh"
cli_install "$cli_case" ensure_cli_tool || fail_test "installing the administration command failed: $(cat "$cli_case/output.log")"
cmp -s "$cli_case/install/starry-api" "$cli_case/downloads/scripts/starry-api.sh" || fail_test "the command must be installed unchanged"
[[ -x "$cli_case/install/starry-api" ]] || fail_test "the command must be executable"
[[ "$(readlink "$cli_case/bin/starry-api")" == "$cli_case/install/starry-api" ]] || fail_test "the command must be linked"
grep -q '^CLI_COMMAND=starry-api$' "$cli_case/output.log" || fail_test "a linked command must be named starry-api in the summary"

# Not offered: skipped with a notice, and the deployment goes on.
skip_case="$cli_root/not-offered"
mkdir -p "$skip_case/downloads" "$skip_case/bin"
cli_install "$skip_case" ensure_cli_tool || fail_test "a release without the command must not fail the installation"
grep -q 'skipping it' "$skip_case/output.log" || fail_test "a skipped command must be mentioned"
[[ ! -e "$skip_case/install/starry-api" && ! -L "$skip_case/bin/starry-api" ]] || fail_test "nothing may be installed or linked for a skipped command"
grep -q '^CLI_COMMAND=$' "$skip_case/output.log" || fail_test "the summary must not name a command that is not there"

# An empty download is no command either, and an earlier copy stays and is still linked.
keep_case="$cli_root/keeps-earlier"
mkdir -p "$keep_case/downloads/scripts" "$keep_case/bin" "$keep_case/install"
: > "$keep_case/downloads/scripts/starry-api.sh"
printf '#!/usr/bin/env bash\necho earlier\n' > "$keep_case/install/starry-api"
chmod 0755 "$keep_case/install/starry-api"
cli_install "$keep_case" ensure_cli_tool || fail_test "a failed download must not fail the installation"
grep -q 'earlier' "$keep_case/install/starry-api" || fail_test "an earlier copy must stay when the download gives nothing"
[[ -L "$keep_case/bin/starry-api" ]] || fail_test "an earlier copy must still be linked"

# The link: created when absent, replaced when it points into a Starry API installation, otherwise left alone.
link_case="$cli_root/link"
mkdir -p "$link_case/install" "$link_case/bin" "$link_case/elsewhere" "$link_case/old-install" "$link_case/plain-dir"
printf '#!/usr/bin/env bash\n' > "$link_case/install/starry-api"
chmod 0755 "$link_case/install/starry-api"
link_target="$link_case/install/starry-api"
printf '#!/usr/bin/env bash\n' > "$link_case/old-install/starry-api"
printf 'services: {}\n' > "$link_case/old-install/docker-compose.artifact.yml"
printf '#!/usr/bin/env bash\n' > "$link_case/plain-dir/starry-api"
printf 'precious\n' > "$link_case/elsewhere/other-tool"

run_link() {
  cli_install "$link_case" link_cli_tool || fail_test "linking the administration command must never fail the installation"
}

run_link
[[ "$(readlink "$link_case/bin/starry-api")" == "$link_target" ]] || fail_test "a missing link must be created"
grep -q '^CLI_COMMAND=starry-api$' "$link_case/output.log" || fail_test "a created link must be reported as starry-api"

run_link
[[ "$(readlink "$link_case/bin/starry-api")" == "$link_target" ]] || fail_test "a correct link must stay as it is"
grep -q '^CLI_COMMAND=starry-api$' "$link_case/output.log" || fail_test "a correct link must be reported as starry-api"

rm -f "$link_case/bin/starry-api"
ln -s "$link_case/old-install/starry-api" "$link_case/bin/starry-api"
run_link
[[ "$(readlink "$link_case/bin/starry-api")" == "$link_target" ]] \
  || fail_test "a link into another Starry API installation must be repointed to this one"

rm -f "$link_case/bin/starry-api"
printf 'a regular file that is not ours\n' > "$link_case/bin/starry-api"
run_link
[[ ! -L "$link_case/bin/starry-api" && "$(cat "$link_case/bin/starry-api")" == "a regular file that is not ours" ]] \
  || fail_test "a regular file at the link path must never be replaced"
grep -q 'Leaving .* alone' "$link_case/output.log" || fail_test "a link that is left alone must be mentioned"
grep -q "^CLI_COMMAND=${link_target}\$" "$link_case/output.log" \
  || fail_test "when the link is not made the summary must name the full path"

rm -f "$link_case/bin/starry-api"
ln -s "$link_case/elsewhere/other-tool" "$link_case/bin/starry-api"
run_link
[[ "$(readlink "$link_case/bin/starry-api")" == "$link_case/elsewhere/other-tool" ]] \
  || fail_test "a link to an unrelated file must not be replaced"

rm -f "$link_case/bin/starry-api"
ln -s "$link_case/plain-dir/starry-api" "$link_case/bin/starry-api"
run_link
[[ "$(readlink "$link_case/bin/starry-api")" == "$link_case/plain-dir/starry-api" ]] \
  || fail_test "a link to a script outside any installation directory must not be replaced"

rm -f "$link_case/bin/starry-api"
mkdir "$link_case/bin/starry-api"
run_link
[[ -d "$link_case/bin/starry-api" && ! -L "$link_case/bin/starry-api" ]] || fail_test "a directory at the link path must be left alone"
rmdir "$link_case/bin/starry-api"

rmdir "${link_case:?}/bin"
run_link
[[ ! -e "$link_case/bin" ]] || fail_test "a missing link directory must not be created"
grep -q "^CLI_COMMAND=${link_target}\$" "$link_case/output.log" || fail_test "without a link directory the summary must name the full path"

mkdir -p "$link_case/bin"
CLI_LINK_PATH_OVERRIDE="" cli_install "$link_case" link_cli_tool || fail_test "an empty CLI_LINK_PATH must not fail the installation"
[[ ! -e "$link_case/bin/starry-api" && ! -L "$link_case/bin/starry-api" ]] || fail_test "an empty CLI_LINK_PATH must skip the link"

rm -f "$link_case/install/starry-api"
run_link
[[ ! -e "$link_case/bin/starry-api" && ! -L "$link_case/bin/starry-api" ]] || fail_test "nothing may be linked when the command is not installed"
grep -q '^CLI_COMMAND=$' "$link_case/output.log" || fail_test "without an installed command the summary must not name one"

# --- scripts/docker-up.sh ----------------------------------------------------------------------------------------------

# A checkout with a stand-in for docker that records what it is asked.
up_case="$TEST_ROOT/docker-up"
mkdir -p "$up_case/checkout/scripts" "$up_case/bin"
cp "$ROOT_DIR/scripts/docker-up.sh" "$up_case/checkout/scripts/"
cp "$ROOT_DIR/.env.example" "$up_case/checkout/.env.example"
cat > "$up_case/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$DOCKER_TRACE"
SH
chmod 0755 "$up_case/bin/docker"
run_docker_up() {
  PATH="$up_case/bin:$PATH" DOCKER_TRACE="$up_case/docker.log" bash "$up_case/checkout/scripts/docker-up.sh" "$@" > "$up_case/output.log" 2>&1
}
up_env="$up_case/checkout/.env"

# The first run makes .env: three different generated passwords, nothing from the example left, private.
run_docker_up || fail_test "docker-up.sh failed: $(cat "$up_case/output.log")"
for up_password in POSTGRES_BOOTSTRAP_PASSWORD POSTGRES_MIGRATION_PASSWORD POSTGRES_PASSWORD; do
  [[ "$(grep -c "^${up_password}=" "$up_env")" -eq 1 ]] || fail_test "${up_password} must be assigned once"
  [[ "$(grep "^${up_password}=" "$up_env" | cut -d= -f2-)" =~ ^[0-9a-f]{48}$ ]] || fail_test "${up_password} must be generated"
done
[[ "$(grep -E '^POSTGRES_(BOOTSTRAP_|MIGRATION_)?PASSWORD=' "$up_env" | cut -d= -f2- | sort -u | wc -l | tr -d ' ')" -eq 3 ]] \
  || fail_test "the generated passwords must differ"
if grep -v '^#' "$up_env" | grep -q 'change-me\|your-domain'; then
  fail_test "nothing of the example may stay in a generated .env"
fi
[[ "$(ls -ld "$up_env" | cut -c1-10)" == "-rw-------" ]] || fail_test "a generated .env must be private"
if grep -Fq "$(grep '^POSTGRES_PASSWORD=' "$up_env" | cut -d= -f2-)" "$up_case/output.log"; then
  fail_test "docker-up.sh must not print a password"
fi
grep -q '^compose up -d$' "$up_case/docker.log" || fail_test "docker-up.sh must start the stack"
grep -q '^Console: *http://127.0.0.1:18080/#/login$' "$up_case/output.log" || fail_test "docker-up.sh must say where the console is"

# Later runs keep .env, and pass their options on.
cp "$up_env" "$up_case/env-before"
run_docker_up --build || fail_test "docker-up.sh --build failed: $(cat "$up_case/output.log")"
cmp -s "$up_env" "$up_case/env-before" || fail_test "a later run must keep .env"
grep -q '^compose up -d --build$' "$up_case/docker.log" || fail_test "docker-up.sh must pass --build on"

# A .env that nobody finished is refused before anything starts.
for up_bad in \
  'POSTGRES_BOOTSTRAP_PASSWORD=change-me-bootstrap-password' \
  'POSTGRES_PASSWORD=' \
  "POSTGRES_PASSWORD=$(grep '^POSTGRES_MIGRATION_PASSWORD=' "$up_env" | cut -d= -f2-)"; do
  cp "$up_case/env-before" "$up_env"
  printf '%s\n' "$up_bad" >> "$up_env"
  : > "$up_case/docker.log"
  if run_docker_up; then
    fail_test "docker-up.sh must refuse this .env: ${up_bad%%=*}"
  fi
  grep -q 'Refusing to deploy' "$up_case/output.log" || fail_test "the refusal must say why"
  if grep -q '^compose up' "$up_case/docker.log"; then
    fail_test "a refused .env must not start anything"
  fi
done

printf '[install-test] release, image, rollback, takeover, administration command, and Mihomo scenarios passed\n'
