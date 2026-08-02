#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALLER="${ROOT_DIR}/scripts/install-server.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-installer-test.XXXXXX")"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail_test() {
  printf '[install-server-test] ERROR: %s\n' "$*" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

prepare_existing_deployment() {
  local case_dir="$1"
  local env_kind="$2"

  mkdir -p "$case_dir/app" "$case_dir/postgres-init" "$case_dir/input"
  printf 'old-jar\n' > "$case_dir/app/starry-api.jar"
  printf 'old-compose\n' > "$case_dir/docker-compose.artifact.yml"
  printf 'old-init\n' > "$case_dir/postgres-init/001-starry-api-roles.sh"
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

  cp "$case_dir/app/starry-api.jar" "$case_dir/expected.jar"
  cp "$case_dir/docker-compose.artifact.yml" "$case_dir/expected-compose.yml"
  cp "$case_dir/postgres-init/001-starry-api-roles.sh" "$case_dir/expected-init.sh"
  cp "$case_dir/.env" "$case_dir/expected.env"
}

prepare_new_artifact() {
  local case_dir="$1"
  local checksum

  mkdir -p "$case_dir/input"
  printf 'new-jar\n' > "$case_dir/input/starry-api.jar"
  checksum="$(sha256_file "$case_dir/input/starry-api.jar")"
  printf '%s  starry-api.jar\n' "$checksum" > "$case_dir/input/starry-api.jar.sha256"
}

run_failure_case() {
  local scenario="$1"
  local case_dir="$2"
  local force_env="$3"
  local status

  : > "$case_dir/commands.log"
  set +e
  INSTALL_DIR="$case_dir" \
  INSTALLER="$INSTALLER" \
  SCENARIO="$scenario" \
  TRACE_FILE="$case_dir/commands.log" \
  HEALTH_FILE="$case_dir/health-count" \
  FORCE_ENV="$force_env" \
  STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
    bash -c '
      source "$INSTALLER"
      COMPOSE_CMD=(mock-compose)

      root_cmd() {
        local rendered="$*"
        printf "%s\n" "$rendered" >> "$TRACE_FILE"
        if [[ "$SCENARIO" == "legacy-health" ]] \
          && [[ "$rendered" == *" up -d postgres" ]]; then
          mkdir -p "$INSTALL_DIR/data/postgresql"
          printf "17\n" > "$INSTALL_DIR/data/postgresql/PG_VERSION"
        fi
        if [[ "$SCENARIO" == "postgres-up" || "$SCENARIO" == "first-install" ]] \
          && [[ "$rendered" == *" up -d postgres" ]]; then
          return 1
        fi
        return 0
      }

      wait_for_health() {
        local count=0
        if [[ -f "$HEALTH_FILE" ]]; then
          read -r count < "$HEALTH_FILE"
        fi
        count=$((count + 1))
        printf "%s\n" "$count" > "$HEALTH_FILE"
        if [[ "$SCENARIO" == "service-health" || "$SCENARIO" == "legacy-health" ]] \
          && [[ "$count" -eq 1 ]]; then
          return 1
        fi
        return 0
      }

      backup_current_artifact
      trap "deployment_exit_handler \$?" EXIT
      write_env_file
      load_runtime_env
      write_compose_file
      install_verified_artifact \
        "$INSTALL_DIR/input/starry-api.jar" \
        "$INSTALL_DIR/input/starry-api.jar.sha256"
      deploy_stack
      DEPLOYMENT_COMMITTED=true
    ' > "$case_dir/output.log" 2>&1
  status=$?
  set -e

  [[ "$status" -ne 0 ]] || fail_test "$scenario unexpectedly succeeded"
}

assert_existing_deployment_restored() {
  local case_dir="$1"
  local expected_health_calls="$2"
  local rollback_commands health_calls

  cmp -s "$case_dir/app/starry-api.jar" "$case_dir/expected.jar" \
    || fail_test "application jar was not restored for $case_dir"
  cmp -s "$case_dir/docker-compose.artifact.yml" "$case_dir/expected-compose.yml" \
    || fail_test "Compose file was not restored for $case_dir"
  cmp -s "$case_dir/postgres-init/001-starry-api-roles.sh" "$case_dir/expected-init.sh" \
    || fail_test "PostgreSQL init script was not restored for $case_dir"
  cmp -s "$case_dir/.env" "$case_dir/expected.env" \
    || fail_test "environment file was not restored for $case_dir"

  rollback_commands="$(grep -Ec ' up -d --build --force-recreate --remove-orphans$' "$case_dir/commands.log" || true)"
  [[ "$rollback_commands" -eq 1 ]] \
    || fail_test "expected one rollback stack recreation, found $rollback_commands for $case_dir"
  read -r health_calls < "$case_dir/health-count"
  [[ "$health_calls" -eq "$expected_health_calls" ]] \
    || fail_test "expected $expected_health_calls health checks, found $health_calls for $case_dir"
}

postgres_case="$TEST_ROOT/postgres-up"
prepare_existing_deployment "$postgres_case" legacy
prepare_new_artifact "$postgres_case"
run_failure_case postgres-up "$postgres_case" true
assert_existing_deployment_restored "$postgres_case" 1

health_case="$TEST_ROOT/service-health"
prepare_existing_deployment "$health_case" postgresql
prepare_new_artifact "$health_case"
run_failure_case service-health "$health_case" false
assert_existing_deployment_restored "$health_case" 2

legacy_health_case="$TEST_ROOT/legacy-health"
prepare_existing_deployment "$legacy_health_case" legacy
prepare_new_artifact "$legacy_health_case"
run_failure_case legacy-health "$legacy_health_case" true
assert_existing_deployment_restored "$legacy_health_case" 2
[[ -f "$legacy_health_case/.env.postgresql-retry" ]] \
  || fail_test "failed legacy migration must retain the initialized PostgreSQL credentials"
cp "$legacy_health_case/.env.postgresql-retry" "$legacy_health_case/expected-retry.env"
INSTALL_DIR="$legacy_health_case" \
FORCE_ENV=true \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$1"
    write_env_file
  ' _ "$INSTALLER"
cmp -s "$legacy_health_case/.env" "$legacy_health_case/expected-retry.env" \
  || fail_test "retry must reactivate the credentials matching the initialized PostgreSQL cluster"

first_case="$TEST_ROOT/first-install"
mkdir -p "$first_case/app" "$first_case/postgres-init"
prepare_new_artifact "$first_case"
run_failure_case first-install "$first_case" false
cmp -s "$first_case/app/starry-api.jar" "$first_case/input/starry-api.jar" \
  || fail_test "first-install artifact should remain available for a retry"
grep -q '^POSTGRES_BOOTSTRAP_PASSWORD=' "$first_case/.env" \
  || fail_test "first-install environment should remain available for a retry"
grep -q '^  postgres:' "$first_case/docker-compose.artifact.yml" \
  || fail_test "first-install Compose file should remain available for a retry"
first_rollback_commands="$(grep -Ec ' up -d --build --force-recreate --remove-orphans$' "$first_case/commands.log" || true)"
[[ "$first_rollback_commands" -eq 0 ]] \
  || fail_test "first install must not attempt to restore a nonexistent previous stack"
[[ ! -e "$first_case/app/starry-api.jar.previous" ]] \
  || fail_test "first install must not create a false rollback artifact"

success_case="$TEST_ROOT/success"
mkdir -p "$success_case"
printf 'retry-secret\n' > "$success_case/.env.postgresql-retry"
: > "$success_case/commands.log"
INSTALL_DIR="$success_case" \
INSTALLER="$INSTALLER" \
TRACE_FILE="$success_case/commands.log" \
STARRY_INSTALL_SERVER_LIBRARY_ONLY=true \
  bash -c '
    source "$INSTALLER"
    COMPOSE_CMD=(mock-compose)
    root_cmd() {
      printf "%s\n" "$*" >> "$TRACE_FILE"
    }
    wait_for_health() {
      return 0
    }
    deploy_stack
  '
grep -q ' up -d --remove-orphans postgres service$' "$success_case/commands.log" \
  || fail_test "successful deployment must remove containers orphaned by the previous Compose definition"
[[ ! -e "$success_case/.env.postgresql-retry" ]] \
  || fail_test "successful deployment must remove the no-longer-needed retry credential copy"

printf '[install-server-test] rollback scenarios passed\n'
