#!/usr/bin/env bash
#
# Tests for scripts/starry-api.sh with a stubbed docker, docker-compose, curl, sleep, id and ip on the PATH. The stub
# answers exactly what the command asks for ("compose ps", "inspect", "logs", "exec ... AdminCli <subcommand>",
# "restart") from files in $STUB, and records every call in $STUB/docker.log so the tests can tell what was and what was
# not run. The Java side (tech.starryapi.cli.AdminCli) has its own PostgreSQL tests.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="${ROOT_DIR}/scripts/starry-api.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-cli-test.XXXXXX")"
TEST_ROOT="$(cd "$TEST_ROOT" && pwd -P)"
FAKE_BIN="${TEST_ROOT}/fakebin"
DEPLOY="${TEST_ROOT}/opt/starry-api"
STUB="${TEST_ROOT}/stub"
CAPTURES="${TEST_ROOT}/captures"
SCRIPT_TMP="${TEST_ROOT}/script-tmp"

INITIAL_PASSWORD="0123456789abcdef0123456789abcdef0123"
TEMPORARY_PASSWORD="fedcba9876543210fedcba9876543210fedc"
POSTGRES_BOOTSTRAP_SECRET="bootstrap-secret-value-1111"
POSTGRES_MIGRATION_SECRET="migration-secret-value-2222"
POSTGRES_APPLICATION_SECRET="application-secret-value-3333"
CONTROLLER_SECRET="controller-secret-value-4444"
TLS_SECRET="tls-bridge-secret-value-5555"
ADMIN_TOKEN_SECRET="legacy-admin-token-value-6666"
CUSTOM_KEY_SECRET="custom-api-key-value-7777"

cleanup() {
  rm -rf -- "${TEST_ROOT:?}"
}
trap cleanup EXIT

fail_test() {
  printf '[starry-api-test] ERROR: %s\n' "$*" >&2
  if [[ -n "${CURRENT_OUTPUT:-}" && -f "$CURRENT_OUTPUT" ]]; then
    printf -- '--- output of the failing case ---\n' >&2
    sed 's/^/    /' "$CURRENT_OUTPUT" >&2
  fi
  if [[ -n "${STARRY_API_TEST_DEBUG:-}" && -f "${STUB}/docker.log" ]]; then
    printf -- '--- stub call log ---\n' >&2
    sed 's/^/    /' "${STUB}/docker.log" | tail -n 40 >&2
  fi
  exit 1
}

# --- assertions on a captured case -----------------------------------------------------------------------------------

output_of() { CURRENT_OUTPUT="${CAPTURES}/$1.out"; }

assert_contains() {
  local name="$1" expected="$2"
  output_of "$name"
  grep -Fq -- "$expected" "$CURRENT_OUTPUT" || fail_test "$name: expected '$expected' in the output"
}

assert_not_contains() {
  local name="$1" unexpected="$2"
  output_of "$name"
  if grep -Fq -- "$unexpected" "$CURRENT_OUTPUT"; then
    fail_test "$name: unexpected '$unexpected' in the output"
  fi
}

assert_count() {
  local name="$1" expected_count="$2" text="$3" actual
  output_of "$name"
  actual="$(grep -Fc -- "$text" "$CURRENT_OUTPUT" || true)"
  [[ "$actual" == "$expected_count" ]] || fail_test "$name: expected $expected_count line(s) with '$text', found $actual"
}

assert_exit() {
  local name="$1" expected="$2" actual
  output_of "$name"
  actual="$(cat "${CAPTURES}/$name.rc")"
  [[ "$actual" == "$expected" ]] || fail_test "$name: expected exit status $expected, got $actual"
}

assert_ran() {
  local pattern="$1"
  grep -Eq -- "$pattern" "${STUB}/docker.log" || fail_test "expected the stub to have been called with: $pattern"
}

assert_not_ran() {
  local pattern="$1"
  if grep -Eq -- "$pattern" "${STUB}/docker.log"; then
    fail_test "the stub must not have been called with: $pattern"
  fi
}

# --- the stubs ------------------------------------------------------------------------------------------------------

install_stubs() {
  mkdir -p "$FAKE_BIN" "$CAPTURES" "$SCRIPT_TMP"

  cat > "${FAKE_BIN}/docker" <<'STUB_DOCKER'
#!/usr/bin/env bash
# Stands in for both "docker" and "docker-compose".
tool="$(basename "$0")"
printf '%s %s\n' "$tool" "$*" >> "${STUB_DIR}/docker.log"

value() { if [[ -f "${STUB_DIR}/$1" ]]; then cat "${STUB_DIR}/$1"; else printf '%s' "$2"; fi; }

handle_compose() {
  local -a args=()
  while [[ "$#" -gt 0 ]]; do
    if [[ "$1" == "-f" ]]; then shift 2; continue; fi
    args+=("$1")
    shift
  done
  local sub="${args[0]:-}"
  local last="${args[$((${#args[@]} - 1))]}"
  case "$sub" in
    version) echo "Docker Compose version v2.29.0"; exit 0 ;;
    ps)
      if [[ -f "${STUB_DIR}/container.${last}" ]]; then echo "cid-${last}"; fi
      exit 0
      ;;
    logs) cat "${STUB_DIR}/logs.out" 2>/dev/null || true; exit 0 ;;
    restart) exit "$(value "restart.${last}.rc" "$(value restart.rc 0)")" ;;
    exec)
      local arg
      for arg in "${args[@]}"; do
        if [[ "$arg" == "curl" ]]; then exit "$(value health.rc 0)"; fi
      done
      printf 'cli:%s\n' "$last" >> "${STUB_DIR}/docker.log"
      if [[ -f "${STUB_DIR}/cli.unsupported" ]]; then
        echo 'Exception in thread "main" java.lang.ClassNotFoundException: tech.starryapi.cli.AdminCli' >&2
        exit 1
      fi
      case "$last" in
        status)
          cat "${STUB_DIR}/status.out"
          exit "$(value status.rc 0)"
          ;;
        check-root-password)
          candidate=""
          IFS= read -r candidate || true
          if [[ -f "${STUB_DIR}/check.fail" ]]; then
            printf 'error=database_unavailable\nboom\n' >&2
            exit 1
          fi
          if [[ "$candidate" == "$(cat "${STUB_DIR}/check.expected")" ]]; then
            echo "password_matches=true"
          else
            echo "password_matches=false"
          fi
          exit 0
          ;;
        reset-root-password)
          if [[ "$(value reset.rc 0)" != 0 ]]; then
            cat "${STUB_DIR}/reset.err" >&2
            exit "$(cat "${STUB_DIR}/reset.rc")"
          fi
          printf 'password=%s\n' "$STUB_RESET_PASSWORD"
          exit 0
          ;;
      esac
      exit 0
      ;;
  esac
  exit 0
}

case "$tool" in
  docker)
    case "${1:-}" in
      info) exit "$(value info.rc 0)" ;;
      inspect) cat "${STUB_DIR}/inspect.${!#}" 2>/dev/null || exit 1; exit 0 ;;
      compose)
        if [[ -f "${STUB_DIR}/plugin.absent" ]]; then
          echo "docker: 'compose' is not a docker command." >&2
          exit 1
        fi
        shift
        handle_compose "$@"
        ;;
    esac
    exit 0
    ;;
  docker-compose) handle_compose "$@" ;;
esac
STUB_DOCKER
  cp "${FAKE_BIN}/docker" "${FAKE_BIN}/docker-compose"

  cat > "${FAKE_BIN}/curl" <<'STUB_CURL'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "${STUB_DIR}/docker.log"
if [[ -f "${STUB_DIR}/health.rc" ]]; then exit "$(cat "${STUB_DIR}/health.rc")"; fi
exit 0
STUB_CURL

  cat > "${FAKE_BIN}/sleep" <<'STUB_SLEEP'
#!/usr/bin/env bash
if [[ -f "${STUB_DIR}/sleep.real" ]]; then exec /bin/sleep "$(cat "${STUB_DIR}/sleep.real")"; fi
exit 0
STUB_SLEEP

  cat > "${FAKE_BIN}/id" <<'STUB_ID'
#!/usr/bin/env bash
if [[ "${1:-}" == "-u" && -f "${STUB_DIR}/uid" ]]; then cat "${STUB_DIR}/uid"; exit 0; fi
exec /usr/bin/id "$@"
STUB_ID

  cat > "${FAKE_BIN}/ip" <<'STUB_IP'
#!/usr/bin/env bash
echo "1.1.1.1 via 10.0.0.1 dev eth0 src 10.0.0.5 uid 0"
STUB_IP

  chmod 0755 "${FAKE_BIN}"/*
}

write_env_file() {
  cat > "${DEPLOY}/.env" <<EOF
HOST_BIND_ADDR=${ENV_BIND_ADDR:-127.0.0.1}
SERVER_PORT=8080
HOST_HTTP_PORT=18080

POSTGRES_BOOTSTRAP_PASSWORD=${POSTGRES_BOOTSTRAP_SECRET}
POSTGRES_MIGRATION_PASSWORD=${POSTGRES_MIGRATION_SECRET}
POSTGRES_PASSWORD=${POSTGRES_APPLICATION_SECRET}
POSTGRES_SSL_MODE=disable
STARRY_ADMIN_TOKEN=${ADMIN_TOKEN_SECRET}
STARRY_TLS_SECRET="${TLS_SECRET}"
STARRY_SOME_API_KEY=${CUSTOM_KEY_SECRET}
STARRY_EMPTY_SECRET=
STARRY_TOKEN_REFRESH_INTERVAL_MS=3600000
STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE=/app/security/recovery.key
STARRY_WEBUI_URL=${ENV_WEBUI_URL:-http://127.0.0.1:18080/#/login}
${ENV_MIHOMO_LINES:-}
EOF
  chmod 0600 "${DEPLOY}/.env"
}

mihomo_env() {
  case "$1" in
    deployed) ENV_MIHOMO_LINES="COMPOSE_PROFILES=mihomo
STARRY_EGRESS_CONTROLLER_SECRET=${CONTROLLER_SECRET}" ;;
    incomplete) ENV_MIHOMO_LINES="STARRY_EGRESS_CONTROLLER_SECRET=${CONTROLLER_SECRET}" ;;
    deployed-initially-on) ENV_MIHOMO_LINES="COMPOSE_PROFILES=mihomo
STARRY_EGRESS_CONTROLLER_SECRET=${CONTROLLER_SECRET}
STARRY_EGRESS_MIHOMO_ENABLED=true" ;;
    *) ENV_MIHOMO_LINES="" ;;
  esac
}

# write_status <first_login: true|false> <data-management lines...>: what AdminCli status prints.
write_status() {
  local first_login="$1"
  shift
  {
    printf 'database=ok\nroot_assigned=true\nroot_user_id=abc123\nroot_username=admin\nroot_email=%s\n' "${STATUS_EMAIL:-}"
    printf 'root_enabled=true\nroot_first_login=%s\negress_mihomo_enabled=%s\n' "$first_login" "${STATUS_MIHOMO:-unset}"
    if [[ "$#" -eq 0 ]]; then
      printf 'data_management=not_initialized\n'
    else
      printf '%s\n' "$@"
    fi
  } > "${STUB}/status.out"
}

write_log_with_initial_password() {
  {
    printf 'service-1  | 2026-10-24T07:17:06.179Z  INFO 1 --- [starryapi-service] [           main] t.starryapi.admin.StartupGuideLogger     : Starry API startup checks passed\n'
    printf 'service-1  | 2026-10-24T07:17:06.179Z  WARN 1 --- [starryapi-service] [           main] t.starryapi.admin.StartupGuideLogger     : Username: admin\n'
    printf 'service-1  | 2026-10-24T07:17:06.179Z  WARN 1 --- [starryapi-service] [           main] t.starryapi.admin.StartupGuideLogger     : Password: %s\n' "$INITIAL_PASSWORD"
    printf 'service-1  | 2026-10-24T07:17:07.000Z  INFO 1 --- [starryapi-service] [           main] t.starryapi.StarryApiApplication         : Started in 9s\n'
  } > "${STUB}/logs.out"
}

# Resets the deployment, the stub answers and the installed command to the "freshly installed, healthy" state.
reset_world() {
  rm -rf -- "${STUB:?}" "${DEPLOY:?}" "${SCRIPT_TMP:?}"
  mkdir -p "$STUB" "$DEPLOY" "$SCRIPT_TMP" "$DEPLOY/data/security"
  : > "${STUB}/docker.log"
  cp "$SCRIPT" "${DEPLOY}/starry-api"
  chmod 0755 "${DEPLOY}/starry-api"
  printf 'services: {}\n' > "${DEPLOY}/docker-compose.artifact.yml"
  ENV_BIND_ADDR=""
  ENV_WEBUI_URL=""
  mihomo_env none
  write_env_file
  printf 'running|healthy|2026-10-24T07:17:02Z\n' > "${STUB}/inspect.cid-service"
  : > "${STUB}/container.service"
  write_status true
  write_log_with_initial_password
  printf '%s\n' "$INITIAL_PASSWORD" > "${STUB}/check.expected"
  STATUS_EMAIL=""
  STATUS_MIHOMO=""
}

# run_case <name> <arguments...>: runs the installed command with the stubs; output in captures/<name>.out and the
# exit status in captures/<name>.rc. The command is run directly, so its "#!/usr/bin/env bash" line is what chooses bash.
run_case() {
  local name="$1"
  shift
  local status=0
  : > "${STUB}/docker.log"
  env PATH="${FAKE_BIN}:/usr/bin:/bin" STUB_DIR="$STUB" TMPDIR="$SCRIPT_TMP" \
    LANG="${CASE_LANG:-C}" LC_ALL= LC_MESSAGES= \
    STUB_RESET_PASSWORD="$TEMPORARY_PASSWORD" \
    STARRY_API_HEALTH_WAIT_SECONDS="${CASE_HEALTH_WAIT:-3}" \
    ${CASE_EXTRA_ENV:-} \
    "${CASE_COMMAND:-${DEPLOY}/starry-api}" "$@" > "${CAPTURES}/${name}.out" 2>&1 < "${CASE_STDIN:-/dev/null}" || status=$?
  printf '%s\n' "$status" > "${CAPTURES}/${name}.rc"
  CURRENT_OUTPUT="${CAPTURES}/${name}.out"
}

# run_case_tty <name> <typed answer> <arguments...>: the same on a pseudo terminal, answering the confirmation prompt.
run_case_tty() {
  local name="$1" answer="$2"
  local status=0
  shift 2
  : > "${STUB}/docker.log"
  expect <<EOF > /dev/null || status=$?
set timeout 30
log_user 0
log_file -a -noappend "${CAPTURES}/${name}.out"
set env(PATH) "${FAKE_BIN}:/usr/bin:/bin"
set env(STUB_DIR) "${STUB}"
set env(TMPDIR) "${SCRIPT_TMP}"
set env(LANG) C
set env(LC_ALL) ""
set env(STUB_RESET_PASSWORD) "${TEMPORARY_PASSWORD}"
set env(STARRY_API_HEALTH_WAIT_SECONDS) 3
spawn ${DEPLOY}/starry-api $*
expect {
  "Type RESET and press Enter to continue: " { send -- "${answer}\r" }
  eof { exit 90 }
  timeout { exit 91 }
}
expect eof
set wait_result [wait]
exit [lindex \$wait_result 3]
EOF
  printf '%s\n' "$status" > "${CAPTURES}/${name}.rc"
  CURRENT_OUTPUT="${CAPTURES}/${name}.out"
}

SECRET_VALUES=(
  "$POSTGRES_BOOTSTRAP_SECRET" "$POSTGRES_MIGRATION_SECRET" "$POSTGRES_APPLICATION_SECRET"
  "$CONTROLLER_SECRET" "$TLS_SECRET" "$ADMIN_TOKEN_SECRET" "$CUSTOM_KEY_SECRET"
)

assert_no_env_secrets() {
  local name="$1" secret
  for secret in "${SECRET_VALUES[@]}"; do
    assert_not_contains "$name" "$secret"
  done
}

install_stubs
[[ -f "$SCRIPT" ]] || fail_test "$SCRIPT is missing"
/bin/bash -n "$SCRIPT" || fail_test "starry-api.sh must parse with /bin/bash"

# --- help and arguments ----------------------------------------------------------------------------------------------

reset_world
run_case help-en --lang en --help
assert_exit help-en 0
assert_contains help-en "Usage: starry-api"
assert_contains help-en "reset-password"
assert_contains help-en "--show-secrets"
assert_contains help-en "--yes"
assert_contains help-en "--lang zh|en"
assert_contains help-en "cannot be recovered or reset"
assert_not_ran 'compose|inspect|info'

run_case help-zh --lang zh --help
assert_exit help-zh 0
assert_contains help-zh "用法: starry-api"
assert_contains help-zh "指定输出语言"
assert_contains help-zh "无法从服务器上找回或重置"

CASE_LANG=zh_CN.UTF-8 run_case help-auto-zh -h
assert_contains help-auto-zh "用法: starry-api"
CASE_LANG=en_US.UTF-8 run_case help-auto-en help
assert_contains help-auto-en "Usage: starry-api"

run_case bad-lang --lang fr info
assert_exit bad-lang 1
assert_contains bad-lang "--lang accepts only zh or en."
run_case bad-argument frobnicate
assert_exit bad-argument 1
assert_contains bad-argument "Invalid arguments"
run_case two-commands info reset-password
assert_exit two-commands 1
run_case secrets-with-reset reset-password --show-secrets
assert_exit secrets-with-reset 1
assert_contains secrets-with-reset "--show-secrets can only be used with info."
run_case yes-with-info info --yes
assert_exit yes-with-info 1
assert_contains yes-with-info "--yes can only be used with reset-password."
assert_not_ran 'exec'

# --- finding the deployment ------------------------------------------------------------------------------------------

# Through a symlink on the PATH (what the installer creates), from another directory.
reset_world
mkdir -p "${TEST_ROOT}/usr-local-bin"
ln -s "${DEPLOY}/starry-api" "${TEST_ROOT}/usr-local-bin/starry-api"
(cd "$TEST_ROOT" && CASE_COMMAND="${TEST_ROOT}/usr-local-bin/starry-api" run_case via-symlink --lang en)
assert_exit via-symlink 0
assert_contains via-symlink "${DEPLOY}"
assert_ran 'docker compose -f docker-compose.artifact.yml'

# Through a relative symlink.
ln -sf ../opt/starry-api/starry-api "${TEST_ROOT}/usr-local-bin/relative-starry-api"
CASE_COMMAND="${TEST_ROOT}/usr-local-bin/relative-starry-api" run_case via-relative-symlink --lang en
assert_exit via-relative-symlink 0
assert_contains via-relative-symlink "${DEPLOY}"

# A copy of the script outside any deployment uses INSTALL_DIR ...
reset_world
mkdir -p "${TEST_ROOT}/elsewhere"
cp "$SCRIPT" "${TEST_ROOT}/elsewhere/starry-api"
chmod 0755 "${TEST_ROOT}/elsewhere/starry-api"
CASE_COMMAND="${TEST_ROOT}/elsewhere/starry-api" CASE_EXTRA_ENV="INSTALL_DIR=${DEPLOY}" run_case install-dir-env --lang en
assert_exit install-dir-env 0
assert_contains install-dir-env "${DEPLOY}"

# ... and, with neither, says where it looked.
if [[ ! -f /opt/starry-api/docker-compose.artifact.yml && ! -f /opt/starry-api/docker-compose.yml ]]; then
  CASE_COMMAND="${TEST_ROOT}/elsewhere/starry-api" CASE_EXTRA_ENV="INSTALL_DIR=${TEST_ROOT}/nothing-here" run_case no-deployment --lang en
  assert_exit no-deployment 1
  assert_contains no-deployment "Could not find a Starry API deployment"
  assert_contains no-deployment "${TEST_ROOT}/nothing-here"
  assert_contains no-deployment "/opt/starry-api"
fi

# A source checkout: scripts/starry-api.sh next to docker-compose.yml one level up; the artifact file wins when both exist.
reset_world
rm -f "${DEPLOY}/docker-compose.artifact.yml" "${DEPLOY}/starry-api"
mkdir -p "${DEPLOY}/scripts"
cp "$SCRIPT" "${DEPLOY}/scripts/starry-api.sh"
chmod 0755 "${DEPLOY}/scripts/starry-api.sh"
printf 'services: {}\n' > "${DEPLOY}/docker-compose.yml"
CASE_COMMAND="${DEPLOY}/scripts/starry-api.sh" run_case checkout --lang en
assert_exit checkout 0
assert_ran 'docker compose -f docker-compose.yml '
assert_not_ran 'docker-compose.artifact.yml'
printf 'services: {}\n' > "${DEPLOY}/docker-compose.artifact.yml"
: > "${STUB}/docker.log"
CASE_COMMAND="${DEPLOY}/scripts/starry-api.sh" run_case checkout-artifact --lang en
assert_ran 'docker compose -f docker-compose.artifact.yml '

# docker-compose (v1) when the plugin is missing, as the installer's ensure_compose does.
reset_world
: > "${STUB}/plugin.absent"
run_case standalone-compose --lang en
assert_exit standalone-compose 0
assert_ran '^docker-compose -f docker-compose.artifact.yml '
assert_contains standalone-compose "Starry API"

# --- access ----------------------------------------------------------------------------------------------------------

reset_world
printf '1\n' > "${STUB}/info.rc"
printf '1000\n' > "${STUB}/uid"
run_case needs-root --lang en info
assert_exit needs-root 1
assert_contains needs-root "Run as root: sudo starry-api --lang en info"
assert_not_ran 'exec'
printf '0\n' > "${STUB}/uid"
run_case docker-down --lang en info
assert_exit docker-down 1
assert_contains docker-down "Cannot reach the Docker daemon"
printf '0\n' > "${STUB}/info.rc"
printf '1000\n' > "${STUB}/uid"
if [[ "$(/usr/bin/id -u)" != "0" ]]; then
  chmod 0000 "${DEPLOY}/.env"
  run_case unreadable-env --lang zh reset-password
  chmod 0600 "${DEPLOY}/.env"
  assert_exit unreadable-env 1
  assert_contains unreadable-env "请以 root 运行: sudo starry-api --lang zh reset-password"
  assert_not_ran 'exec'
fi
run_case user-with-docker-access --lang en
assert_exit user-with-docker-access 0

reset_world
rm -f "${STUB}/plugin.absent"
mkdir -p "${TEST_ROOT}/no-docker-bin"
for tool in grep sed cut tail head tr awk cat rm dirname basename readlink mktemp bash env; do
  [[ -x "/usr/bin/$tool" ]] && ln -sf "/usr/bin/$tool" "${TEST_ROOT}/no-docker-bin/$tool" || ln -sf "/bin/$tool" "${TEST_ROOT}/no-docker-bin/$tool"
done
status=0
env PATH="${TEST_ROOT}/no-docker-bin" STUB_DIR="$STUB" LANG=C LC_ALL= "${DEPLOY}/starry-api" > "${CAPTURES}/no-docker.out" 2>&1 || status=$?
printf '%s\n' "$status" > "${CAPTURES}/no-docker.rc"
assert_exit no-docker 1
assert_contains no-docker "The docker command was not found"

# --- info: no secrets by default ---------------------------------------------------------------------------------------

reset_world
STATUS_EMAIL="root@example.com" write_status true
run_case info-default --lang en
assert_exit info-default 0
assert_contains info-default "Service"
assert_contains info-default "Container: running (healthy), up since 2026-10-24T07:17:02Z"
assert_contains info-default "Health check: OK (http://127.0.0.1:18080/health)"
assert_contains info-default "Console login page: http://127.0.0.1:18080/#/login"
assert_contains info-default "Reverse proxy target: http://127.0.0.1:18080"
assert_contains info-default "bound to 127.0.0.1"
assert_contains info-default "Username: admin"
assert_contains info-default "Email: root@example.com"
assert_contains info-default "Password: ${INITIAL_PASSWORD}"
assert_contains info-default "initial password, still unchanged"
assert_contains info-default "--show-secrets"
assert_no_env_secrets info-default
assert_not_contains info-default "WARNING"
assert_count info-default 1 "$INITIAL_PASSWORD"
assert_ran 'cli:status'
assert_ran 'cli:check-root-password'
assert_not_ran 'restart'
assert_not_ran "$INITIAL_PASSWORD"

CASE_LANG=zh_CN.UTF-8 run_case info-default-zh
assert_exit info-default-zh 0
assert_contains info-default-zh "控制台登录页"
assert_contains info-default-zh "Root 管理员"
assert_contains info-default-zh "数据管理安全密钥"
assert_contains info-default-zh "密码：${INITIAL_PASSWORD}"
assert_contains info-default-zh "只绑定在 127.0.0.1"
assert_no_env_secrets info-default-zh

# The console address the installer wrote (the public HTTPS one) is the one to use; the local one is listed too.
ENV_WEBUI_URL="https://api.example.com/#/login" write_env_file
run_case info-public-url --lang en
ENV_WEBUI_URL="" write_env_file
assert_contains info-public-url "Console login page: https://api.example.com/#/login"
assert_contains info-public-url "On this server: http://127.0.0.1:18080/#/login"

# A service port that is open to the network is reported with the address a reverse proxy should use.
ENV_BIND_ADDR="0.0.0.0" write_env_file
run_case info-open --lang en
assert_contains info-open "Reverse proxy target: http://10.0.0.5:18080"
assert_contains info-open "bound to 0.0.0.0 over plain HTTP"
assert_not_contains info-open "bound to 127.0.0.1"
assert_contains info-open "Console login page: http://127.0.0.1:18080/#/login"
ENV_BIND_ADDR="" write_env_file

# --- info --show-secrets -----------------------------------------------------------------------------------------------

run_case info-secrets --lang en info --show-secrets
assert_exit info-secrets 0
assert_contains info-secrets "WARNING: the values below give full control"
assert_contains info-secrets "POSTGRES_BOOTSTRAP_PASSWORD=${POSTGRES_BOOTSTRAP_SECRET}"
assert_contains info-secrets "POSTGRES_MIGRATION_PASSWORD=${POSTGRES_MIGRATION_SECRET}"
assert_contains info-secrets "POSTGRES_PASSWORD=${POSTGRES_APPLICATION_SECRET}"
assert_contains info-secrets "STARRY_ADMIN_TOKEN=${ADMIN_TOKEN_SECRET}"
assert_contains info-secrets "STARRY_TLS_SECRET=${TLS_SECRET}"
assert_contains info-secrets "STARRY_SOME_API_KEY=${CUSTOM_KEY_SECRET}"
assert_not_contains info-secrets "STARRY_EMPTY_SECRET"
assert_not_contains info-secrets "STARRY_TOKEN_REFRESH_INTERVAL_MS"
assert_not_contains info-secrets "RECOVERY_KEY_FILE"
assert_not_contains info-secrets "HOST_HTTP_PORT="
assert_not_contains info-secrets "--show-secrets if you really need"

mihomo_env deployed
write_env_file
run_case info-secrets-mihomo --lang zh info --show-secrets
assert_contains info-secrets-mihomo "警告：下面的值可以完全控制你的数据库和服务"
assert_contains info-secrets-mihomo "STARRY_EGRESS_CONTROLLER_SECRET=${CONTROLLER_SECRET}"
mihomo_env none
write_env_file

# --- info: the Root password -------------------------------------------------------------------------------------------

# Still the initial password, but the log line is gone (the container was recreated): no password, and what to do.
: > "${STUB}/logs.out"
run_case pw-gone --lang en
assert_not_contains pw-gone "$INITIAL_PASSWORD"
assert_contains pw-gone "initial password not changed yet, but it is no longer in the service log"
assert_contains pw-gone "starry-api reset-password"
assert_not_ran 'cli:check-root-password'

# The log still has the line but the password was reset since: the stale password must not be shown.
write_log_with_initial_password
printf 'some-other-current-password\n' > "${STUB}/check.expected"
run_case pw-stale --lang en
assert_not_contains pw-stale "$INITIAL_PASSWORD"
assert_contains pw-stale "the one in the log is not the current one"
assert_contains pw-stale "starry-api reset-password"

# The check itself failing is not a reason to show an unverified password.
printf '%s\n' "$INITIAL_PASSWORD" > "${STUB}/check.expected"
: > "${STUB}/check.fail"
run_case pw-unverified --lang en
assert_not_contains pw-unverified "$INITIAL_PASSWORD"
assert_contains pw-unverified "could not be verified"
rm -f "${STUB}/check.fail"

# Root has changed the password: the log line is never even looked at, and nothing is shown.
write_status false
run_case pw-changed --lang en
assert_not_contains pw-changed "$INITIAL_PASSWORD"
assert_contains pw-changed "changed by the administrator"
assert_contains pw-changed "cannot be read back"
assert_contains pw-changed "starry-api reset-password"
assert_not_ran 'cli:check-root-password'
assert_not_ran ' logs '
run_case pw-changed-zh --lang zh
assert_contains pw-changed-zh "已由管理员修改"
assert_not_contains pw-changed-zh "$INITIAL_PASSWORD"

# No Root yet.
printf 'database=ok\nroot_assigned=false\negress_mihomo_enabled=unset\ndata_management=not_initialized\n' > "${STUB}/status.out"
run_case no-root --lang en
assert_exit no-root 0
assert_contains no-root "There is no Root account yet"
assert_not_contains no-root "Username:"

# Database trouble is reported, not hidden.
printf 'database=error\ndatabase_error=Connection refused\ndata_management=not_initialized\n' > "${STUB}/status.out"
run_case db-error --lang en
assert_exit db-error 0
assert_contains db-error "Could not read the database: Connection refused"

# --- info: the data-management key -------------------------------------------------------------------------------------

write_status false "data_management=not_initialized"
run_case key-none --lang en
assert_contains key-none "not generated yet"
assert_contains key-none "cannot be shown, recovered or reset from the server"
assert_contains key-none "恢复被锁定的数据管理"
assert_contains key-none "docs/OPERATIONS.md"
assert_not_contains key-none "reset the security key"

write_status false "data_management=initialized" "data_management_initialized_at=2026-09-01T08:00:00Z" "data_management_failed_attempts=0"
run_case key-ok --lang en
assert_contains key-ok "generated at 2026-09-01T08:00:00Z, no failed attempts"

write_status false "data_management=initialized" "data_management_initialized_at=2026-09-01T08:00:00Z" "data_management_failed_attempts=3"
run_case key-failures --lang en
assert_contains key-failures "3 failed attempt(s) in a row"

write_status false "data_management=locked" "data_management_initialized_at=2026-09-01T08:00:00Z" "data_management_failed_attempts=5" "data_management_locked_at=2026-09-03T10:30:00Z"
run_case key-locked --lang en
assert_contains key-locked "LOCKED after 5 failed attempts at 2026-09-03T10:30:00Z"
assert_contains key-locked "maintenance mode (503)"
assert_contains key-locked "Recovering locked data management"
run_case key-locked-zh --lang zh
assert_contains key-locked-zh "已锁定：连续失败 5 次，锁定时间 2026-09-03T10:30:00Z"
assert_contains key-locked-zh "恢复被锁定的数据管理"

write_status false "data_management=error" "data_management_error=state_missing"
run_case key-missing --lang en
assert_contains key-missing "ERROR: it was initialized, but its state file is missing. Do not delete or recreate any files"
write_status false "data_management=error" "data_management_error=state_invalid"
run_case key-invalid --lang en
assert_contains key-invalid "ERROR: the state file is invalid"
write_status false "data_management=error" "data_management_error=state_unreadable"
run_case key-unreadable --lang en
assert_contains key-unreadable "ERROR: the state file cannot be read"

# Whatever the status, the command offers no reset of the key and prints nothing that looks like one.
for key_case in key-none key-ok key-failures key-locked key-missing key-invalid key-unreadable; do
  assert_not_contains "$key_case" "dmsk-0"
  assert_not_contains "$key_case" "verifier="
  assert_not_contains "$key_case" "salt="
  assert_not_contains "$key_case" "reset-key"
  assert_not_contains "$key_case" "AAAAAAAA"
done

# --- info: service state -----------------------------------------------------------------------------------------------

write_status true
printf 'exited||2026-10-24T07:00:00Z\n' > "${STUB}/inspect.cid-service"
run_case svc-exited --lang en
assert_exit svc-exited 0
assert_contains svc-exited "Container: not running (state: exited)"
assert_contains svc-exited "can only be read while the service is running"
assert_not_ran 'cli:'
assert_not_contains svc-exited "$INITIAL_PASSWORD"

rm -f "${STUB}/container.service"
run_case svc-missing --lang en
assert_contains svc-missing "not running (state: missing)"

printf 'running||2026-10-24T07:17:02Z\n' > "${STUB}/inspect.cid-service"
: > "${STUB}/container.service"
printf '7\n' > "${STUB}/health.rc"
run_case svc-unhealthy --lang en
assert_contains svc-unhealthy "Health check: not responding"
assert_contains svc-unhealthy "Container: running, up since"
rm -f "${STUB}/health.rc"

# An older release without the administration command.
: > "${STUB}/cli.unsupported"
run_case old-release --lang en
assert_exit old-release 0
assert_contains old-release "does not include the administration command"
assert_not_contains old-release "$INITIAL_PASSWORD"
rm -f "${STUB}/cli.unsupported"

# --- info: Mihomo ------------------------------------------------------------------------------------------------------

run_case mihomo-none --lang en
assert_contains mihomo-none "not deployed"
assert_contains mihomo-none "ENABLE_MIHOMO=true"

mihomo_env incomplete
write_env_file
run_case mihomo-incomplete --lang en
assert_contains mihomo-incomplete "INCOMPLETE"

mihomo_env deployed
write_env_file
printf 'running||2026-10-24T07:17:03Z\n' > "${STUB}/inspect.cid-mihomo"
: > "${STUB}/container.mihomo"
STATUS_MIHOMO=unset write_status true
run_case mihomo-deployed-unset --lang en
assert_contains mihomo-deployed-unset "deployed (.env has COMPOSE_PROFILES=mihomo and a controller secret), container running"
assert_contains mihomo-deployed-unset "nobody has used the switch yet"
assert_contains mihomo-deployed-unset "applies: off"
assert_no_env_secrets mihomo-deployed-unset

STATUS_MIHOMO=true write_status true
run_case mihomo-deployed-on --lang en
assert_contains mihomo-deployed-on "Console switch: on"
STATUS_MIHOMO=false write_status true
run_case mihomo-deployed-off --lang zh
assert_contains mihomo-deployed-off "控制台开关：已关闭"

mihomo_env deployed-initially-on
write_env_file
STATUS_MIHOMO=unset write_status true
run_case mihomo-initially-on --lang en
assert_contains mihomo-initially-on "applies: on"

printf 'exited||2026-10-24T07:00:00Z\n' > "${STUB}/inspect.cid-mihomo"
run_case mihomo-stopped --lang en
assert_contains mihomo-stopped "container exited"

# --- reset-password ----------------------------------------------------------------------------------------------------

# Without a terminal and without --yes nothing is asked, changed or restarted.
reset_world
run_case reset-refused --lang en reset-password
assert_exit reset-refused 1
assert_contains reset-refused "must be confirmed by typing RESET in an interactive terminal"
assert_not_ran 'cli:reset-root-password'
assert_not_ran 'restart'
assert_not_contains reset-refused "$TEMPORARY_PASSWORD"

# --yes: reset, restart, wait for /health, show the username and the password once.
: > "${STUB}/docker.log"
run_case reset-yes --lang en reset-password --yes
assert_exit reset-yes 0
assert_contains reset-yes "The Root password has been reset."
assert_contains reset-yes "Username: admin"
assert_contains reset-yes "Temporary password: ${TEMPORARY_PASSWORD}"
assert_contains reset-yes "The service was restarted and is responding"
assert_contains reset-yes "you will be asked to choose a new password"
assert_count reset-yes 1 "$TEMPORARY_PASSWORD"
assert_not_contains reset-yes "$INITIAL_PASSWORD"
assert_ran '^docker compose -f docker-compose.artifact.yml exec -T service java .*-Dloader.main=tech.starryapi.cli.AdminCli -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher reset-root-password$'
assert_ran '^docker compose -f docker-compose.artifact.yml restart service$'
assert_ran '^curl .*http://127.0.0.1:18080/health$'
assert_not_ran "$TEMPORARY_PASSWORD"
# Order: the Root is looked up, then the password is reset, then the service restarts, then health is checked.
status_line="$(grep -n '^cli:status$' "${STUB}/docker.log" | head -n 1 | cut -d: -f1)"
reset_line="$(grep -n '^cli:reset-root-password$' "${STUB}/docker.log" | head -n 1 | cut -d: -f1)"
restart_line="$(grep -n 'restart service$' "${STUB}/docker.log" | head -n 1 | cut -d: -f1)"
health_line="$(grep -n '^curl ' "${STUB}/docker.log" | tail -n 1 | cut -d: -f1)"
[[ "$status_line" -lt "$reset_line" && "$reset_line" -lt "$restart_line" && "$restart_line" -lt "$health_line" ]] \
  || fail_test "reset-password must look up Root, reset, restart and then wait for health, in that order"
# Mihomo is not deployed here: only the service restarts.
assert_not_ran 'restart mihomo'

run_case reset-yes-zh --lang zh reset-password -y
assert_exit reset-yes-zh 0
assert_contains reset-yes-zh "Root 密码已重置。"
assert_contains reset-yes-zh "临时密码：${TEMPORARY_PASSWORD}"
assert_contains reset-yes-zh "旧的控制台会话已全部登出"

# The secret never reaches a file: the stub log, the command's temp dir and the deployment hold no copy of it.
if grep -rIl -- "$TEMPORARY_PASSWORD" "$STUB" "$SCRIPT_TMP" "$DEPLOY" 2>/dev/null | grep -q .; then
  fail_test "the temporary password was written to a file"
fi
[[ -z "$(ls -A "$SCRIPT_TMP")" ]] || fail_test "the command must not leave temporary files behind"

# On a terminal: the confirmation explains what happens, and only RESET goes on.
: > "${STUB}/docker.log"
if command -v expect >/dev/null 2>&1; then
  run_case_tty reset-tty-ok RESET --lang en reset-password
  assert_exit reset-tty-ok 0
  assert_contains reset-tty-ok "This resets the password of the Root account (admin)"
  assert_contains reset-tty-ok "shown once"
  assert_contains reset-tty-ok "must be changed at the next login"
  assert_contains reset-tty-ok "every console session is logged out"
  assert_contains reset-tty-ok "security key is not affected"
  assert_contains reset-tty-ok "Type RESET and press Enter to continue:"
  assert_contains reset-tty-ok "Temporary password: ${TEMPORARY_PASSWORD}"
  assert_ran 'cli:reset-root-password'
  assert_ran 'restart service$'

  : > "${STUB}/docker.log"
  for wrong_answer in reset yes RESET2 ""; do
    : > "${STUB}/docker.log"
    run_case_tty reset-tty-wrong "$wrong_answer" --lang en reset-password
    assert_exit reset-tty-wrong 1
    assert_contains reset-tty-wrong "RESET was not typed. Cancelled; nothing was changed."
    assert_not_contains reset-tty-wrong "$TEMPORARY_PASSWORD"
    assert_not_ran 'cli:reset-root-password'
    assert_not_ran 'restart'
  done
else
  printf '[starry-api-test] expect is unavailable; terminal confirmation checks skipped\n'
fi

# Restarting fails: the password is still shown, with the command to restart by hand.
: > "${STUB}/docker.log"
printf '1\n' > "${STUB}/restart.rc"
run_case reset-restart-fails --lang en reset-password --yes
assert_exit reset-restart-fails 1
assert_contains reset-restart-fails "Temporary password: ${TEMPORARY_PASSWORD}"
assert_contains reset-restart-fails "restarting the service failed"
assert_contains reset-restart-fails "cd ${DEPLOY} && docker compose -f docker-compose.artifact.yml restart service"
assert_not_contains reset-restart-fails "The service was restarted and is responding"
assert_count reset-restart-fails 1 "$TEMPORARY_PASSWORD"
rm -f "${STUB}/restart.rc"

# The service does not come back in time: the password is still shown, with where to look.
: > "${STUB}/docker.log"
printf '7\n' > "${STUB}/health.rc"
CASE_HEALTH_WAIT=2 run_case reset-health-timeout --lang en reset-password --yes
assert_exit reset-health-timeout 1
assert_contains reset-health-timeout "Temporary password: ${TEMPORARY_PASSWORD}"
assert_contains reset-health-timeout "did not respond within 2 seconds"
assert_contains reset-health-timeout "logs --tail=100 service"
assert_count reset-health-timeout 1 "$TEMPORARY_PASSWORD"
rm -f "${STUB}/health.rc"

# Mihomo shares the service's network namespace, so a deployed one is restarted after the service.
: > "${STUB}/docker.log"
mihomo_env deployed
write_env_file
printf 'running||2026-10-24T07:17:03Z\n' > "${STUB}/inspect.cid-mihomo"
: > "${STUB}/container.mihomo"
run_case reset-mihomo --lang en reset-password --yes
assert_exit reset-mihomo 0
assert_ran '^docker compose -f docker-compose.artifact.yml restart service$'
assert_ran '^docker compose -f docker-compose.artifact.yml restart mihomo$'
service_restart="$(grep -n 'restart service$' "${STUB}/docker.log" | head -n 1 | cut -d: -f1)"
mihomo_restart="$(grep -n 'restart mihomo$' "${STUB}/docker.log" | head -n 1 | cut -d: -f1)"
[[ "$service_restart" -lt "$mihomo_restart" ]] || fail_test "Mihomo must be restarted after the service"
printf '1\n' > "${STUB}/restart.mihomo.rc"
run_case reset-mihomo-fails --lang en reset-password --yes
assert_exit reset-mihomo-fails 0
assert_contains reset-mihomo-fails "restarting the Mihomo container failed"
assert_contains reset-mihomo-fails "Temporary password: ${TEMPORARY_PASSWORD}"
rm -f "${STUB}/restart.mihomo.rc" "${STUB}/container.mihomo" "${STUB}/inspect.cid-mihomo"
mihomo_env none
write_env_file

# Nothing to reset, or the reset fails: no restart, no password, a reason.
printf 'database=ok\nroot_assigned=false\ndata_management=not_initialized\n' > "${STUB}/status.out"
: > "${STUB}/docker.log"
run_case reset-no-root --lang en reset-password --yes
assert_exit reset-no-root 1
assert_contains reset-no-root "There is no Root account yet"
assert_not_ran 'cli:reset-root-password'
assert_not_ran 'restart'

write_status true
printf '1\n' > "${STUB}/reset.rc"
printf 'error=database_unavailable\nCould not use the database: Connection refused\n' > "${STUB}/reset.err"
: > "${STUB}/docker.log"
run_case reset-db-down --lang en reset-password --yes
assert_exit reset-db-down 1
assert_contains reset-db-down "The reset failed; the password was not changed."
assert_contains reset-db-down "Could not use the database: Connection refused"
assert_not_contains reset-db-down "$TEMPORARY_PASSWORD"
assert_not_ran 'restart'
rm -f "${STUB}/reset.rc" "${STUB}/reset.err"

printf 'database=error\ndatabase_error=Connection refused\ndata_management=not_initialized\n' > "${STUB}/status.out"
: > "${STUB}/docker.log"
run_case reset-status-db-down --lang en reset-password --yes
assert_exit reset-status-db-down 1
assert_contains reset-status-db-down "Could not use the database: Connection refused"
assert_not_ran 'cli:reset-root-password'
write_status true

: > "${STUB}/cli.unsupported"
: > "${STUB}/docker.log"
run_case reset-old-release --lang en reset-password --yes
assert_exit reset-old-release 1
assert_contains reset-old-release "does not include the administration command"
assert_not_ran 'cli:reset-root-password'
assert_not_ran 'restart'
rm -f "${STUB}/cli.unsupported"

printf 'exited||2026-10-24T07:00:00Z\n' > "${STUB}/inspect.cid-service"
: > "${STUB}/docker.log"
run_case reset-svc-down --lang en reset-password --yes
assert_exit reset-svc-down 1
assert_contains reset-svc-down "The service container is not running (state: exited)"
assert_contains reset-svc-down "docker compose -f docker-compose.artifact.yml up -d"
assert_not_ran 'cli:'
assert_not_ran 'restart'
printf 'running|healthy|2026-10-24T07:17:02Z\n' > "${STUB}/inspect.cid-service"

# A locked data management: the site is in maintenance mode, so the command says that a new password does not help yet.
write_status true "data_management=locked" "data_management_initialized_at=2026-09-01T08:00:00Z" "data_management_failed_attempts=5" "data_management_locked_at=2026-09-03T10:30:00Z"
run_case reset-locked --lang en reset-password --yes
assert_exit reset-locked 0
assert_contains reset-locked "maintenance mode"
assert_contains reset-locked "Recovering locked data management"
assert_contains reset-locked "Temporary password: ${TEMPORARY_PASSWORD}"
write_status true

# An interrupt after the reset still shows the password: it exists nowhere else.
: > "${STUB}/docker.log"
printf '7\n' > "${STUB}/health.rc"
printf '0.05\n' > "${STUB}/sleep.real"
env PATH="${FAKE_BIN}:/usr/bin:/bin" STUB_DIR="$STUB" TMPDIR="$SCRIPT_TMP" LANG=C LC_ALL= \
  STUB_RESET_PASSWORD="$TEMPORARY_PASSWORD" STARRY_API_HEALTH_WAIT_SECONDS=100000 \
  "${DEPLOY}/starry-api" reset-password --yes > "${CAPTURES}/reset-interrupt.out" 2>&1 < /dev/null &
interrupted_pid=$!
for _ in $(seq 1 100); do
  grep -q 'Restarting the service' "${CAPTURES}/reset-interrupt.out" 2>/dev/null && break
  /bin/sleep 0.1
done
/bin/sleep 0.3
kill -TERM "$interrupted_pid"
interrupt_status=0
wait "$interrupted_pid" || interrupt_status=$?
printf '%s\n' "$interrupt_status" > "${CAPTURES}/reset-interrupt.rc"
assert_exit reset-interrupt 130
assert_contains reset-interrupt "Interrupted, but the password has already been reset"
assert_contains reset-interrupt "Temporary password: ${TEMPORARY_PASSWORD}"
rm -f "${STUB}/health.rc" "${STUB}/sleep.real"

# --- other interpreters ------------------------------------------------------------------------------------------------
#
# The shebang picked the first bash on the PATH above; /bin/bash (3.2 on macOS) and any other bash found must give the
# same answers for the two commands.

checked_interpreters=" "
for interpreter in /bin/bash "$(command -v bash)" /opt/homebrew/bin/bash /usr/local/bin/bash; do
  [[ -x "$interpreter" ]] || continue
  case "$checked_interpreters" in *" $interpreter "*) continue ;; esac
  checked_interpreters="${checked_interpreters}${interpreter} "
  reset_world
  status=0
  : > "${STUB}/docker.log"
  env PATH="${FAKE_BIN}:/usr/bin:/bin" STUB_DIR="$STUB" TMPDIR="$SCRIPT_TMP" LANG=C LC_ALL= \
    STUB_RESET_PASSWORD="$TEMPORARY_PASSWORD" STARRY_API_HEALTH_WAIT_SECONDS=3 \
    "$interpreter" "${DEPLOY}/starry-api" info > "${CAPTURES}/interp-info.out" 2>&1 < /dev/null || status=$?
  printf '%s\n' "$status" > "${CAPTURES}/interp-info.rc"
  assert_exit interp-info 0
  assert_contains interp-info "Password: ${INITIAL_PASSWORD}"
  assert_no_env_secrets interp-info
  status=0
  env PATH="${FAKE_BIN}:/usr/bin:/bin" STUB_DIR="$STUB" TMPDIR="$SCRIPT_TMP" LANG=C LC_ALL= \
    STUB_RESET_PASSWORD="$TEMPORARY_PASSWORD" STARRY_API_HEALTH_WAIT_SECONDS=3 \
    "$interpreter" "${DEPLOY}/starry-api" reset-password --yes > "${CAPTURES}/interp-reset.out" 2>&1 < /dev/null || status=$?
  printf '%s\n' "$status" > "${CAPTURES}/interp-reset.rc"
  assert_exit interp-reset 0
  assert_contains interp-reset "Temporary password: ${TEMPORARY_PASSWORD}"
done

printf '[starry-api-test] info, secrets, initial password, key status, reset-password, and installation lookup scenarios passed\n'
