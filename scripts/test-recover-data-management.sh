#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RECOVERY_SCRIPT="${ROOT_DIR}/scripts/recover-data-management.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-recovery-test.XXXXXX")"
TEST_ROOT="$(cd "$TEST_ROOT" && pwd -P)"
FAKE_BIN="$TEST_ROOT/fakebin"

cleanup() {
  rm -rf -- "$TEST_ROOT"
}
trap cleanup EXIT

fail_test() {
  printf '[recover-data-management-test] ERROR: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local file=$1
  local expected=$2
  grep -Fq -- "$expected" "$file" \
    || fail_test "expected '$expected' in $file"
}

assert_not_contains() {
  local file=$1
  local unexpected=$2
  if grep -Fq -- "$unexpected" "$file"; then
    fail_test "secret or unexpected text was exposed in $file"
  fi
}

file_mode() {
  local mode
  # GNU stat -f can print filesystem data before failing; discard that output.
  if mode=$(stat -c '%a' "$1" 2>/dev/null); then
    printf '%s\n' "$mode"
  else
    stat -f '%Lp' "$1"
  fi
}

prepare_security_directory() {
  local case_dir=$1
  local mode=${2:-700}

  mkdir -p "$case_dir/security"
  chmod "$mode" "$case_dir/security"
}

run_expect_double_input() {
  local case_dir=$1
  local status_mode=$2
  local first_key=$3
  local second_key=$4
  local expected_outcome=$5
  local block_lock_cleanup=${6:-false}
  local race_target_at_publish=${7:-false}
  local output_file="$case_dir/output.log"
  local recovery_file="$case_dir/security/recovery.key"

  expect <<EOF
set timeout 10
log_user 0
log_file -a -noappend "$output_file"
set env(LANG) C
set env(PATH) "$FAKE_BIN:/usr/bin:/bin"
set env(RECOVERY_TEST_STATUS) "$status_mode"
set env(RECOVERY_TEST_RACE_TARGET) "$race_target_at_publish"
set env(SERVER_PORT) 18080
set env(STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE) "$recovery_file"
spawn sh "$RECOVERY_SCRIPT" --lang en
expect {
  "Enter the original data-management security key: " {}
  eof { exit 80 }
  timeout { exit 81 }
}
if { "$block_lock_cleanup" eq "true" } {
  set blocker [open "$case_dir/security/.recovery.key.lock/blocker" w]
  puts \$blocker "intentional test blocker"
  close \$blocker
}
send -- "$first_key\r"
expect {
  "Enter the security key again: " {}
  eof { exit 82 }
  timeout { exit 83 }
}
send -- "$second_key\r"
expect eof
set wait_result [wait]
set child_exit [lindex \$wait_result 3]
if { "$expected_outcome" eq "success" } {
  if { \$child_exit != 0 } { exit 84 }
} else {
  if { \$child_exit == 0 } { exit 85 }
}
EOF
}

run_expect_without_input() {
  local case_dir=$1
  local status_mode=$2
  local output_file="$case_dir/output.log"
  local recovery_file="$case_dir/security/recovery.key"

  expect <<EOF
set timeout 10
log_user 0
log_file -a -noappend "$output_file"
set env(LANG) C
set env(PATH) "$FAKE_BIN:/usr/bin:/bin"
set env(RECOVERY_TEST_STATUS) "$status_mode"
set env(RECOVERY_TEST_RACE_TARGET) false
set env(SERVER_PORT) 18080
set env(STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE) "$recovery_file"
spawn sh "$RECOVERY_SCRIPT" --lang en
expect {
  eof {}
  "Enter the original data-management security key: " { exit 86 }
  timeout { exit 87 }
}
set wait_result [wait]
set child_exit [lindex \$wait_result 3]
if { \$child_exit == 0 } { exit 88 }
EOF
}

[[ -x "$RECOVERY_SCRIPT" ]] || fail_test "$RECOVERY_SCRIPT is not executable"

LANG=C sh "$RECOVERY_SCRIPT" --help > "$TEST_ROOT/help-en.log"
assert_contains "$TEST_ROOT/help-en.log" "Usage:"
assert_contains "$TEST_ROOT/help-en.log" "--lang zh|en"

LANG=C sh "$RECOVERY_SCRIPT" --lang zh --help > "$TEST_ROOT/help-zh.log"
assert_contains "$TEST_ROOT/help-zh.log" "用法:"
assert_contains "$TEST_ROOT/help-zh.log" "指定输出语言"

env -u LC_ALL -u LC_MESSAGES LANG=zh_CN.UTF-8 \
  sh "$RECOVERY_SCRIPT" --help > "$TEST_ROOT/help-auto.log"
assert_contains "$TEST_ROOT/help-auto.log" "用法:"

set +e
LANG=C sh "$RECOVERY_SCRIPT" --lang en </dev/null > "$TEST_ROOT/non-tty.log" 2>&1
non_tty_status=$?
set -e
[[ "$non_tty_status" -ne 0 ]] || fail_test "non-TTY invocation unexpectedly succeeded"
assert_contains "$TEST_ROOT/non-tty.log" "requires an interactive terminal"

awk '/^directory_owner_is_allowed\(\) \{/,/^}/ { print }' \
  "$RECOVERY_SCRIPT" > "$TEST_ROOT/directory-owner-function.sh"
[[ -s "$TEST_ROOT/directory-owner-function.sh" ]] \
  || fail_test "directory owner policy function was not found"
sh -c '
  . "$1"
  directory_owner_is_allowed 0 23456
  directory_owner_is_allowed 1000 1000
  ! directory_owner_is_allowed 1000 23456
' _ "$TEST_ROOT/directory-owner-function.sh" \
  || fail_test "directory owner policy does not preserve the root bind-mount exception"

mkdir -p "$FAKE_BIN"
REAL_MV_BINARY=$(command -v mv)
printf '%s\n' \
  '#!/bin/sh' \
  'case "${RECOVERY_TEST_STATUS:-maintenance}" in' \
  '  operational) document='\''{"data":{"maintenance":false,"code":null}}'\'' ;;' \
  '  *) document='\''{"data":{"maintenance":true,"code":"SYSTEM_MAINTENANCE"}}'\'' ;;' \
  'esac' \
  'printf '\''%s\n'\'' "$document"' \
  > "$FAKE_BIN/curl"
printf '%s\n' \
  '#!/bin/sh' \
  'input=$(cat)' \
  'case "$input" in' \
  '  *'\''"maintenance":true'\''*'\''"code":"SYSTEM_MAINTENANCE"'\''*) exit 0 ;;' \
  '  *) exit 1 ;;' \
  'esac' \
  > "$FAKE_BIN/jq"
printf '%s\n' \
  '#!/bin/sh' \
  "REAL_MV_BINARY='$REAL_MV_BINARY'" \
  'if [ "${RECOVERY_TEST_RACE_TARGET:-false}" = true ] && [ "$1" = -n ]; then' \
  '  printf '\''%s\n'\'' '\''publish-race-target-must-remain-unchanged'\'' > "$3"' \
  '  chmod 600 "$3"' \
  'fi' \
  'exec "$REAL_MV_BINARY" "$@"' \
  > "$FAKE_BIN/mv"
chmod 755 "$FAKE_BIN/curl" "$FAKE_BIN/jq" "$FAKE_BIN/mv"

if command -v expect >/dev/null 2>&1; then
  VALID_KEY='dmsk-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
  SECOND_VALID_KEY='dmsk-fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210'
  INVALID_KEY='dmsk-invalid-format-must-never-appear-in-output'

  operational_case="$TEST_ROOT/not-maintenance"
  prepare_security_directory "$operational_case"
  run_expect_without_input "$operational_case" operational
  assert_contains "$operational_case/output.log" "not locked in SYSTEM_MAINTENANCE"
  [[ ! -e "$operational_case/security/recovery.key" ]] \
    || fail_test "non-maintenance status created a recovery file"

  mismatch_case="$TEST_ROOT/mismatch"
  prepare_security_directory "$mismatch_case"
  run_expect_double_input \
    "$mismatch_case" maintenance "$VALID_KEY" "$SECOND_VALID_KEY" failure
  assert_contains "$mismatch_case/output.log" "entries do not match"
  assert_not_contains "$mismatch_case/output.log" "$VALID_KEY"
  assert_not_contains "$mismatch_case/output.log" "$SECOND_VALID_KEY"
  [[ ! -e "$mismatch_case/security/recovery.key" ]] \
    || fail_test "mismatched input created a recovery file"

  invalid_case="$TEST_ROOT/invalid-format"
  prepare_security_directory "$invalid_case"
  run_expect_double_input \
    "$invalid_case" maintenance "$INVALID_KEY" "$INVALID_KEY" failure
  assert_contains "$invalid_case/output.log" "Invalid key format"
  assert_not_contains "$invalid_case/output.log" "$INVALID_KEY"
  [[ ! -e "$invalid_case/security/recovery.key" ]] \
    || fail_test "invalid input created a recovery file"

  existing_case="$TEST_ROOT/existing-file"
  prepare_security_directory "$existing_case"
  printf '%s\n' 'existing-recovery-file-must-remain-unchanged' \
    > "$existing_case/security/recovery.key"
  chmod 600 "$existing_case/security/recovery.key"
  run_expect_without_input "$existing_case" maintenance
  assert_contains "$existing_case/output.log" "Refusing to overwrite"
  [[ "$(<"$existing_case/security/recovery.key")" == \
      'existing-recovery-file-must-remain-unchanged' ]] \
    || fail_test "an existing recovery file was modified"
  [[ "$(file_mode "$existing_case/security/recovery.key")" == 600 ]] \
    || fail_test "an existing recovery file mode was modified"

  symlink_case="$TEST_ROOT/target-symlink"
  prepare_security_directory "$symlink_case"
  printf '%s\n' 'symlink-target-must-remain-unchanged' > "$symlink_case/sentinel"
  ln -s ../sentinel "$symlink_case/security/recovery.key"
  run_expect_without_input "$symlink_case" maintenance
  assert_contains "$symlink_case/output.log" "Refusing to overwrite"
  [[ -L "$symlink_case/security/recovery.key" ]] \
    || fail_test "the existing recovery symlink was replaced"
  [[ "$(readlink "$symlink_case/security/recovery.key")" == ../sentinel ]] \
    || fail_test "the existing recovery symlink target was changed"
  [[ "$(<"$symlink_case/sentinel")" == 'symlink-target-must-remain-unchanged' ]] \
    || fail_test "the target of an existing recovery symlink was modified"

  unsafe_case="$TEST_ROOT/unsafe-directory"
  prepare_security_directory "$unsafe_case" 0777
  run_expect_without_input "$unsafe_case" maintenance
  assert_contains "$unsafe_case/output.log" "Unsafe recovery directory"
  [[ "$(file_mode "$unsafe_case/security")" == 777 ]] \
    || fail_test "the rejected unsafe directory mode was modified"
  [[ ! -e "$unsafe_case/security/recovery.key" ]] \
    || fail_test "an unsafe directory received a recovery file"

  concurrent_lock_case="$TEST_ROOT/preexisting-concurrent-lock"
  prepare_security_directory "$concurrent_lock_case"
  mkdir "$concurrent_lock_case/security/.recovery.key.lock"
  chmod 700 "$concurrent_lock_case/security/.recovery.key.lock"
  run_expect_without_input "$concurrent_lock_case" maintenance
  assert_contains "$concurrent_lock_case/output.log" "staging lock"
  [[ -d "$concurrent_lock_case/security/.recovery.key.lock" ]] \
    || fail_test "a lock not acquired by this process was removed"
  [[ ! -e "$concurrent_lock_case/security/recovery.key" ]] \
    || fail_test "a preexisting concurrent lock did not block recovery"

  publish_race_case="$TEST_ROOT/publish-target-race"
  prepare_security_directory "$publish_race_case"
  run_expect_double_input \
    "$publish_race_case" maintenance "$VALID_KEY" "$VALID_KEY" failure false true
  assert_contains "$publish_race_case/output.log" "Refusing to overwrite"
  assert_not_contains "$publish_race_case/output.log" "$VALID_KEY"
  [[ "$(<"$publish_race_case/security/recovery.key")" == \
      'publish-race-target-must-remain-unchanged' ]] \
    || fail_test "a recovery target created during publication was overwritten"
  if find "$publish_race_case/security" -maxdepth 1 -name '.recovery.key.tmp.*' \
      -print -quit | grep -q .; then
    fail_test "a rejected publication race retained a temporary key file"
  fi

  happy_case="$TEST_ROOT/happy"
  prepare_security_directory "$happy_case"
  run_expect_double_input "$happy_case" maintenance "$VALID_KEY" "$VALID_KEY" success
  [[ -f "$happy_case/security/recovery.key" ]] \
    || fail_test "happy path did not create the recovery file"
  [[ "$(file_mode "$happy_case/security/recovery.key")" == 600 ]] \
    || fail_test "recovery file is not exactly mode 0600"
  [[ "$(<"$happy_case/security/recovery.key")" == "$VALID_KEY" ]] \
    || fail_test "recovery file content is incorrect"
  assert_contains "$happy_case/output.log" "docker compose restart service"
  assert_not_contains "$happy_case/output.log" "$VALID_KEY"

  lock_cleanup_case="$TEST_ROOT/lock-cleanup-warning"
  prepare_security_directory "$lock_cleanup_case"
  run_expect_double_input \
    "$lock_cleanup_case" maintenance "$VALID_KEY" "$VALID_KEY" success true
  [[ -f "$lock_cleanup_case/security/recovery.key" ]] \
    || fail_test "lock cleanup failure discarded a successfully staged recovery file"
  [[ "$(file_mode "$lock_cleanup_case/security/recovery.key")" == 600 ]] \
    || fail_test "recovery file with a lock cleanup warning is not mode 0600"
  assert_contains \
    "$lock_cleanup_case/output.log" "could not remove its staging lock directory"
  assert_not_contains \
    "$lock_cleanup_case/output.log" "Could not create the recovery file securely"
  assert_not_contains "$lock_cleanup_case/output.log" "$VALID_KEY"
  [[ -d "$lock_cleanup_case/security/.recovery.key.lock" ]] \
    || fail_test "the intentionally non-empty staging lock unexpectedly disappeared"

  if [[ "$(id -u)" == 0 ]]; then
    root_bind_case="$TEST_ROOT/root-bind-mount-owner"
    prepare_security_directory "$root_bind_case"
    if chown 65534 "$root_bind_case/security" 2>/dev/null; then
      chmod 700 "$root_bind_case/security"
      run_expect_double_input \
        "$root_bind_case" maintenance "$VALID_KEY" "$VALID_KEY" success
      [[ "$(file_mode "$root_bind_case/security/recovery.key")" == 600 ]] \
        || fail_test "root bind-mount recovery file is not exactly mode 0600"
      assert_not_contains "$root_bind_case/output.log" "$VALID_KEY"
    else
      printf '%s\n' \
        '[recover-data-management-test] chown is unavailable; root owner behavior covered by the extracted policy contract'
    fi
  fi
else
  printf '%s\n' \
    '[recover-data-management-test] expect is unavailable; PTY behavior checks skipped'
fi

printf '%s\n' '[recover-data-management-test] all recovery script checks passed'
