#!/bin/sh

set -eu
set +x

PROGRAM_NAME=${0##*/}
DEFAULT_RECOVERY_FILE=/app/security/recovery.key
LANGUAGE=
SHOW_HELP=false
TEMPORARY_FILE=
LOCK_DIRECTORY=
LOCK_ACQUIRED=false
TERMINAL_ECHO_DISABLED=false
RECOVERY_KEY=
CONFIRMATION_KEY=

detect_language() {
  locale_name=${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}
  case "$locale_name" in
    zh|zh_*|zh-*) LANGUAGE=zh ;;
    *) LANGUAGE=en ;;
  esac
}

print_usage() {
  if [ "$LANGUAGE" = zh ]; then
    cat <<EOF
用法: $PROGRAM_NAME [--lang zh|en] [--help]

在 Starry API 容器内安全暂存数据管理恢复密钥。

选项:
  --lang zh|en  指定输出语言（默认根据系统语言自动选择）
  --help        显示此帮助并退出

请通过交互式终端运行，例如：
  docker compose exec service /app/bin/recover-data-management
EOF
  else
    cat <<EOF
Usage: $PROGRAM_NAME [--lang zh|en] [--help]

Securely stage the data-management recovery key inside the Starry API container.

Options:
  --lang zh|en  Select the output language (auto-detected from the locale by default)
  --help        Show this help and exit

Run this command from an interactive terminal, for example:
  docker compose exec service /app/bin/recover-data-management
EOF
  fi
}

message() {
  message_id=$1
  if [ "$LANGUAGE" = zh ]; then
    case "$message_id" in
      invalid_arguments) printf '%s\n' "参数无效。使用 --help 查看帮助。" ;;
      invalid_language) printf '%s\n' "--lang 仅支持 zh 或 en。" ;;
      tty_required) printf '%s\n' "必须从交互式终端运行此命令（Docker Compose 请勿使用 -T）。" ;;
      missing_command) printf '容器缺少必需命令: %s\n' "$2" ;;
      status_unavailable) printf '%s\n' "无法读取本容器的 /system/status；请确认服务仍在运行。" ;;
      not_locked) printf '%s\n' "系统当前未处于 SYSTEM_MAINTENANCE 锁定状态，已拒绝创建恢复文件。" ;;
      unsafe_path) printf '%s\n' "恢复文件必须使用绝对路径。" ;;
      unsafe_directory) printf '恢复目录不安全: %s（不可经符号链接访问、不可由组或其他用户写入；容器非 root 运行时还必须属于当前用户）。\n' "$2" ;;
      file_exists) printf '恢复文件或暂存锁已存在，已拒绝覆盖: %s\n' "$2" ;;
      prompt_key) printf '%s' "请输入原始数据管理安全密钥: " ;;
      prompt_confirm) printf '%s' "请再次输入安全密钥: " ;;
      input_failed) printf '%s\n' "无法从终端读取安全密钥。" ;;
      mismatch) printf '%s\n' "两次输入不一致，未创建恢复文件。" ;;
      invalid_key) printf '%s\n' "密钥格式无效；必须为 dmsk- 后跟 64 个小写十六进制字符。" ;;
      stage_failed) printf '%s\n' "无法安全创建恢复文件，系统仍保持锁定。" ;;
      lock_cleanup_warning) printf '恢复文件已安全创建，但无法删除本进程的暂存锁目录: %s；恢复仍可继续，请在确认没有恢复进程运行后人工检查该目录。\n' "$2" ;;
      staged) printf '%s\n' "恢复密钥已安全暂存，且不会显示在终端或命令历史中。" ;;
      restart) printf '%s\n' "请退出容器终端，并在 Docker Compose 项目目录执行:" ;;
    esac
  else
    case "$message_id" in
      invalid_arguments) printf '%s\n' "Invalid arguments. Use --help for usage." ;;
      invalid_language) printf '%s\n' "--lang accepts only zh or en." ;;
      tty_required) printf '%s\n' "This command requires an interactive terminal (do not use -T with Docker Compose)." ;;
      missing_command) printf 'Required command is unavailable in the container: %s\n' "$2" ;;
      status_unavailable) printf '%s\n' "Could not read /system/status from this container; verify that the service is still running." ;;
      not_locked) printf '%s\n' "The system is not locked in SYSTEM_MAINTENANCE; refusing to create a recovery file." ;;
      unsafe_path) printf '%s\n' "The recovery file must use an absolute path." ;;
      unsafe_directory) printf 'Unsafe recovery directory: %s (it must contain no symlink path component, must not be writable by group or others, and must be owned by the current user when the container is non-root).\n' "$2" ;;
      file_exists) printf 'Refusing to overwrite an existing recovery file or staging lock: %s\n' "$2" ;;
      prompt_key) printf '%s' "Enter the original data-management security key: " ;;
      prompt_confirm) printf '%s' "Enter the security key again: " ;;
      input_failed) printf '%s\n' "Could not read the security key from the terminal." ;;
      mismatch) printf '%s\n' "The entries do not match; no recovery file was created." ;;
      invalid_key) printf '%s\n' "Invalid key format; expected dmsk- followed by 64 lowercase hexadecimal characters." ;;
      stage_failed) printf '%s\n' "Could not create the recovery file securely; the system remains locked." ;;
      lock_cleanup_warning) printf 'The recovery file was created securely, but this process could not remove its staging lock directory: %s. Recovery can continue; inspect that directory manually after confirming no recovery process is running.\n' "$2" ;;
      staged) printf '%s\n' "The recovery key was staged securely and was not printed or added to shell history." ;;
      restart) printf '%s\n' "Exit the container terminal, then run this in the Docker Compose project directory:" ;;
    esac
  fi
}

fail() {
  message "$@" >&2
  exit 1
}

directory_owner_is_allowed() {
  candidate_current_uid=$1
  candidate_owner_uid=$2
  [ "$candidate_current_uid" = 0 ] || [ "$candidate_owner_uid" = "$candidate_current_uid" ]
}

read_numeric_mode() {
  stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null
}

cleanup() {
  exit_status=$?
  trap - EXIT HUP INT TERM
  if [ "$TERMINAL_ECHO_DISABLED" = true ]; then
    stty echo 2>/dev/null || true
    printf '\n' >&2
  fi
  RECOVERY_KEY=
  CONFIRMATION_KEY=
  if [ -n "$TEMPORARY_FILE" ] && [ -f "$TEMPORARY_FILE" ] && [ ! -L "$TEMPORARY_FILE" ]; then
    : > "$TEMPORARY_FILE" 2>/dev/null || true
    rm -f "$TEMPORARY_FILE" 2>/dev/null || true
  fi
  if [ "$LOCK_ACQUIRED" = true ] \
    && [ -n "$LOCK_DIRECTORY" ] \
    && [ -d "$LOCK_DIRECTORY" ] \
    && [ ! -L "$LOCK_DIRECTORY" ]; then
    rmdir "$LOCK_DIRECTORY" 2>/dev/null || true
  fi
  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

detect_language
while [ "$#" -gt 0 ]; do
  case "$1" in
    --lang)
      [ "$#" -ge 2 ] || fail invalid_language
      case "$2" in
        zh|en) LANGUAGE=$2 ;;
        *) fail invalid_language ;;
      esac
      shift 2
      ;;
    --lang=zh) LANGUAGE=zh; shift ;;
    --lang=en) LANGUAGE=en; shift ;;
    --lang=*) fail invalid_language ;;
    --help|-h) SHOW_HELP=true; shift ;;
    *) fail invalid_arguments ;;
  esac
done

if [ "$SHOW_HELP" = true ]; then
  print_usage
  exit 0
fi

if [ ! -t 0 ] || [ ! -t 2 ]; then
  fail tty_required
fi

for required_command in curl jq mktemp stat id dirname basename chmod mkdir mv rm rmdir stty; do
  command -v "$required_command" >/dev/null 2>&1 || fail missing_command "$required_command"
done

SERVER_PORT_VALUE=${SERVER_PORT:-8080}
STATUS_URL="http://127.0.0.1:${SERVER_PORT_VALUE}/system/status"
if ! STATUS_DOCUMENT=$(curl --fail --silent --show-error --noproxy '*' --max-time 5 "$STATUS_URL"); then
  fail status_unavailable
fi
if ! printf '%s' "$STATUS_DOCUMENT" \
  | jq -e '.data.maintenance == true and .data.code == "SYSTEM_MAINTENANCE"' >/dev/null 2>&1; then
  STATUS_DOCUMENT=
  fail not_locked
fi
STATUS_DOCUMENT=

RECOVERY_FILE=${STARRY_DATA_MANAGEMENT_RECOVERY_KEY_FILE:-$DEFAULT_RECOVERY_FILE}
case "$RECOVERY_FILE" in
  /*) ;;
  *) fail unsafe_path ;;
esac

RECOVERY_DIRECTORY=$(dirname "$RECOVERY_FILE")
RECOVERY_NAME=$(basename "$RECOVERY_FILE")

path_component=$RECOVERY_DIRECTORY
while :; do
  if [ -L "$path_component" ]; then
    fail unsafe_directory "$RECOVERY_DIRECTORY"
  fi
  [ "$path_component" = / ] && break
  parent_component=$(dirname "$path_component")
  [ "$parent_component" != "$path_component" ] || break
  path_component=$parent_component
done

if [ ! -d "$RECOVERY_DIRECTORY" ] || [ -L "$RECOVERY_DIRECTORY" ]; then
  fail unsafe_directory "$RECOVERY_DIRECTORY"
fi

directory_owner=$(stat -c '%u' "$RECOVERY_DIRECTORY" 2>/dev/null \
  || stat -f '%u' "$RECOVERY_DIRECTORY" 2>/dev/null) \
    || fail unsafe_directory "$RECOVERY_DIRECTORY"
current_user=$(id -u)
directory_mode=$(read_numeric_mode "$RECOVERY_DIRECTORY") \
  || fail unsafe_directory "$RECOVERY_DIRECTORY"
case "$directory_mode" in
  *[!0-7]*|'') fail unsafe_directory "$RECOVERY_DIRECTORY" ;;
esac
directory_permissions=$((0$directory_mode))
if ! directory_owner_is_allowed "$current_user" "$directory_owner" \
  || [ $((directory_permissions & 0022)) -ne 0 ]; then
  fail unsafe_directory "$RECOVERY_DIRECTORY"
fi

if [ -e "$RECOVERY_FILE" ] || [ -L "$RECOVERY_FILE" ]; then
  fail file_exists "$RECOVERY_FILE"
fi

LOCK_DIRECTORY="${RECOVERY_DIRECTORY}/.${RECOVERY_NAME}.lock"
if ! (umask 077 && mkdir "$LOCK_DIRECTORY") 2>/dev/null; then
  fail file_exists "$LOCK_DIRECTORY"
fi
LOCK_ACQUIRED=true
chmod 700 "$LOCK_DIRECTORY" || fail stage_failed

TERMINAL_ECHO_DISABLED=true
stty -echo || fail input_failed
message prompt_key >&2
if ! IFS= read -r RECOVERY_KEY; then
  fail input_failed
fi
stty echo || fail input_failed
TERMINAL_ECHO_DISABLED=false
printf '\n' >&2

TERMINAL_ECHO_DISABLED=true
stty -echo || fail input_failed
message prompt_confirm >&2
if ! IFS= read -r CONFIRMATION_KEY; then
  fail input_failed
fi
stty echo || fail input_failed
TERMINAL_ECHO_DISABLED=false
printf '\n' >&2

if [ "$RECOVERY_KEY" != "$CONFIRMATION_KEY" ]; then
  fail mismatch
fi
key_payload=${RECOVERY_KEY#dmsk-}
if [ "$key_payload" = "$RECOVERY_KEY" ] || [ "${#key_payload}" -ne 64 ]; then
  fail invalid_key
fi
case "$key_payload" in
  *[!0123456789abcdef]*) fail invalid_key ;;
esac
key_payload=

umask 077
TEMPORARY_FILE=$(mktemp "${RECOVERY_DIRECTORY}/.${RECOVERY_NAME}.tmp.XXXXXX") \
  || fail stage_failed
chmod 600 "$TEMPORARY_FILE" || fail stage_failed
temporary_mode=$(read_numeric_mode "$TEMPORARY_FILE") || fail stage_failed
if [ "$temporary_mode" != 600 ]; then
  fail stage_failed
fi
if ! printf '%s\n' "$RECOVERY_KEY" > "$TEMPORARY_FILE"; then
  fail stage_failed
fi

RECOVERY_KEY=
CONFIRMATION_KEY=
if [ -e "$RECOVERY_FILE" ] || [ -L "$RECOVERY_FILE" ]; then
  fail file_exists "$RECOVERY_FILE"
fi
if ! mv -n "$TEMPORARY_FILE" "$RECOVERY_FILE"; then
  # GNU and BSD mv use different exit codes when -n refuses an existing target.
  if [ -e "$RECOVERY_FILE" ] || [ -L "$RECOVERY_FILE" ]; then
    fail file_exists "$RECOVERY_FILE"
  fi
  fail stage_failed
fi
if [ -e "$TEMPORARY_FILE" ] || [ -L "$TEMPORARY_FILE" ]; then
  fail file_exists "$RECOVERY_FILE"
fi
TEMPORARY_FILE=

lock_directory_to_remove=$LOCK_DIRECTORY
LOCK_ACQUIRED=false
LOCK_DIRECTORY=
if ! rmdir "$lock_directory_to_remove" 2>/dev/null; then
  message lock_cleanup_warning "$lock_directory_to_remove" >&2
fi

message staged
message restart
printf '%s\n' '  docker compose restart service'
