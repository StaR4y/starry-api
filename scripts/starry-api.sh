#!/usr/bin/env bash
#
# starry-api: server-side administration command for a Starry API Docker Compose deployment.
#
#   starry-api info              sign-in information (default)
#   starry-api reset-password    set a new temporary password for Root
#
# Run it on the server. Passwords are stored as salted hashes and can never be read back, so "info" shows the initial
# Root password only while Root has not changed it (it is read from the one-time line the service logs at its first
# start) and "reset-password" is the way to get back in. The data-management security key is not recoverable by design
# and this command can neither show nor reset it; it only reports its status.
#
# The database work is done by tech.starryapi.cli.AdminCli, a small program inside the service jar that is run in the
# service container with "docker compose exec". Nothing secret is ever written to a file by this script.

set -euo pipefail
set +x

PROGRAM_NAME="starry-api"
DOCS_URL="https://github.com/StaR4y/starry-api/blob/main/docs/OPERATIONS.md"
CLI_MAIN_CLASS="tech.starryapi.cli.AdminCli"
CLI_JVM_FLAGS="-Xmx96m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData"
HEALTH_WAIT_SECONDS="${STARRY_API_HEALTH_WAIT_SECONDS:-180}"

LANGUAGE=""
COMMAND=""
SHOW_SECRETS=false
ASSUME_YES=false
SHOW_HELP=false
ORIGINAL_ARGS="$*"

INSTALL_ROOT=""
COMPOSE_FILE=""
COMPOSE_KIND=""
ENV_FILE=""
BIND_ADDR="127.0.0.1"
HTTP_PORT="18080"
SERVER_PORT="8080"

CLI_OUT=""
CLI_ERR=""
CLI_STATUS=0
STATUS_OUT=""
CONTAINER_STATE=""
CONTAINER_HEALTH=""
CONTAINER_STARTED=""
RESET_USERNAME=""
RESET_PASSWORD=""

# --- language and messages ------------------------------------------------------------------------------------------

detect_language() {
  local locale_name="${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}"
  case "$locale_name" in
    zh|zh_*|zh-*) LANGUAGE=zh ;;
    *) LANGUAGE=en ;;
  esac
}

print_usage() {
  if [[ "$LANGUAGE" == zh ]]; then
    cat <<EOF
用法: $PROGRAM_NAME [--lang zh|en] [info] [--show-secrets]
      $PROGRAM_NAME [--lang zh|en] reset-password [--yes]
      $PROGRAM_NAME --help

在服务器上查看 Starry API 的登录信息，或重置 Root 密码。

命令:
  info              （默认）控制台地址、服务状态、Root 账号与密码状态、
                    数据管理安全密钥状态、Mihomo 状态。
  reset-password    为 Root 设置新的临时密码，重启服务，并只显示一次新密码。

选项:
  --show-secrets    与 info 一起使用：同时显示 .env 里保存的密钥
                    （数据库密码、控制器密钥等）。默认不显示。
  --yes             与 reset-password 一起使用：跳过确认。
  --lang zh|en      指定输出语言（默认根据系统语言自动选择）。
  --help            显示此帮助并退出。

请在服务器上以 root 运行，或使用可以操作 Docker 的用户，例如:
  sudo $PROGRAM_NAME info
密码只以加盐哈希保存，无法读回；数据管理安全密钥无法从服务器上找回或重置。
本命令不会把任何密钥写入文件。
EOF
  else
    cat <<EOF
Usage: $PROGRAM_NAME [--lang zh|en] [info] [--show-secrets]
       $PROGRAM_NAME [--lang zh|en] reset-password [--yes]
       $PROGRAM_NAME --help

Show how to sign in to this Starry API server, or reset the Root password.

Commands:
  info              (default) Console address, service state, Root account and
                    password state, data-management key status, Mihomo state.
  reset-password    Set a new temporary password for Root, restart the service
                    and print the password once.

Options:
  --show-secrets    With info: also print the secrets stored in .env (database
                    passwords, controller secret, ...). Hidden by default.
  --yes             With reset-password: do not ask for confirmation.
  --lang zh|en      Output language (auto-detected from the locale by default).
  --help            Show this help and exit.

Run it on the server, as root or as a user that may use Docker, for example:
  sudo $PROGRAM_NAME info
Passwords are stored as salted hashes and cannot be read back, and the
data-management security key cannot be recovered or reset from the server.
This command never writes a secret to a file.
EOF
  fi
}

# message <id> [arguments...]: the text for one message id in the selected language, without a trailing newline.
message() {
  local id="$1"
  shift
  if [[ "$LANGUAGE" == zh ]]; then
    case "$id" in
      invalid_arguments) printf '参数无效。使用 --help 查看帮助。' ;;
      invalid_language) printf -- '--lang 仅支持 zh 或 en。' ;;
      secrets_only_info) printf -- '--show-secrets 只能与 info 一起使用。' ;;
      yes_only_reset) printf -- '--yes 只能与 reset-password 一起使用。' ;;
      no_deployment) printf '找不到 Starry API 部署目录（已查找: %s）。请用环境变量 INSTALL_DIR 指定安装目录。' "$1" ;;
      docker_missing) printf '没有找到 docker 命令，请先安装 Docker。' ;;
      compose_missing) printf '没有找到 Docker Compose（docker compose 或 docker-compose）。' ;;
      need_root) printf '当前用户无法使用 Docker 或读取 .env。请以 root 运行: sudo %s %s' "$PROGRAM_NAME" "$1" ;;
      docker_down) printf '无法连接 Docker 守护进程，请确认 Docker 正在运行。' ;;
      service_not_running) printf '服务容器没有在运行（状态: %s）。请先启动: cd %s && docker compose -f %s up -d' "$1" "$2" "$3" ;;
      cli_unsupported) printf '当前安装的 Starry API 版本不包含管理命令。请重新执行安装脚本升级到最新版本后再试。' ;;
      cli_error) printf '管理命令执行失败: %s' "$1" ;;
      err_no_root) printf '还没有 Root 账号：请先让服务完整启动一次，它会自动创建 Root，然后再运行本命令。' ;;
      err_root_missing) printf 'Root 标记指向的账号不存在，数据库状态异常。请勿手工修改，先检查备份。' ;;
      err_schema_missing) printf '数据库还没有完成初始化迁移。请等服务完整启动一次后再试。' ;;
      err_database_unavailable) printf '无法连接数据库: %s' "$1" ;;
      reset_needs_terminal) printf '重置密码需要在交互式终端里输入 RESET 确认；如果确定要继续，请加 --yes。' ;;
      reset_cancelled) printf '没有输入 RESET，已取消，什么都没有改动。' ;;
      reset_failed_nochange) printf '重置失败，密码没有改动。' ;;
      restart_failed) printf '重置已经生效，但重启服务失败。请手动重启，让所有旧的控制台会话失效:' ;;
      restart_manual) printf '  cd %s && docker compose -f %s restart service' "$1" "$2" ;;
      health_timeout) printf '服务在 %s 秒内没有恢复响应。请查看日志: cd %s && docker compose -f %s logs --tail=100 service' "$1" "$2" "$3" ;;
      mihomo_restart_warning) printf '提示: 重启 Mihomo 容器失败，请手动执行: cd %s && docker compose -f %s restart mihomo' "$1" "$2" ;;
      *) printf '%s' "$id" ;;
    esac
  else
    case "$id" in
      invalid_arguments) printf 'Invalid arguments. Use --help for usage.' ;;
      invalid_language) printf -- '--lang accepts only zh or en.' ;;
      secrets_only_info) printf -- '--show-secrets can only be used with info.' ;;
      yes_only_reset) printf -- '--yes can only be used with reset-password.' ;;
      no_deployment) printf 'Could not find a Starry API deployment (looked in: %s). Set INSTALL_DIR to the install directory.' "$1" ;;
      docker_missing) printf 'The docker command was not found. Install Docker first.' ;;
      compose_missing) printf 'Docker Compose was not found (neither "docker compose" nor "docker-compose").' ;;
      need_root) printf 'This user cannot use Docker or read .env. Run as root: sudo %s %s' "$PROGRAM_NAME" "$1" ;;
      docker_down) printf 'Cannot reach the Docker daemon; make sure Docker is running.' ;;
      service_not_running) printf 'The service container is not running (state: %s). Start it first: cd %s && docker compose -f %s up -d' "$1" "$2" "$3" ;;
      cli_unsupported) printf 'The installed Starry API release does not include the administration command. Re-run the installer to update, then try again.' ;;
      cli_error) printf 'The administration command failed: %s' "$1" ;;
      err_no_root) printf 'There is no Root account yet: let the service finish its first start (it creates Root), then run this again.' ;;
      err_root_missing) printf 'The Root marker points to an account that does not exist; the database is in an unexpected state. Do not edit it by hand; check your backups first.' ;;
      err_schema_missing) printf 'The database has not been migrated yet. Wait for the service to finish its first start, then try again.' ;;
      err_database_unavailable) printf 'Could not use the database: %s' "$1" ;;
      reset_needs_terminal) printf 'Resetting the password must be confirmed by typing RESET in an interactive terminal; add --yes if you are sure.' ;;
      reset_cancelled) printf 'RESET was not typed. Cancelled; nothing was changed.' ;;
      reset_failed_nochange) printf 'The reset failed; the password was not changed.' ;;
      restart_failed) printf 'The reset took effect, but restarting the service failed. Restart it by hand so that every old console session is logged out:' ;;
      restart_manual) printf '  cd %s && docker compose -f %s restart service' "$1" "$2" ;;
      health_timeout) printf 'The service did not respond within %s seconds. Check the log: cd %s && docker compose -f %s logs --tail=100 service' "$1" "$2" "$3" ;;
      mihomo_restart_warning) printf 'Note: restarting the Mihomo container failed; run it by hand: cd %s && docker compose -f %s restart mihomo' "$1" "$2" ;;
      *) printf '%s' "$id" ;;
    esac
  fi
}

# say <id> [arguments...]: message plus newline on stdout; die <id> [arguments...]: the same on stderr, then exit 1.
say() {
  message "$@"
  printf '\n'
}

die() {
  message "$@" >&2
  printf '\n' >&2
  exit 1
}

# line <label-id> <value>: one "  label: value" line of the info report.
line() {
  printf '  %s%s\n' "$(label_text "$1")" "$(label_separator)$2"
}

label_separator() {
  if [[ "$LANGUAGE" == zh ]]; then printf '：'; else printf ': '; fi
}

label_text() {
  if [[ "$LANGUAGE" == zh ]]; then
    case "$1" in
      container) printf '容器' ;;
      health) printf '健康检查' ;;
      login_page) printf '控制台登录页' ;;
      local_address) printf '本机地址' ;;
      proxy_target) printf '反向代理目标' ;;
      username) printf '用户名' ;;
      email) printf '邮箱' ;;
      password) printf '密码' ;;
      status) printf '状态' ;;
      deployed) printf '部署' ;;
      switch) printf '控制台开关' ;;
      temp_password) printf '临时密码' ;;
      *) printf '%s' "$1" ;;
    esac
  else
    case "$1" in
      container) printf 'Container' ;;
      health) printf 'Health check' ;;
      login_page) printf 'Console login page' ;;
      local_address) printf 'On this server' ;;
      proxy_target) printf 'Reverse proxy target' ;;
      username) printf 'Username' ;;
      email) printf 'Email' ;;
      password) printf 'Password' ;;
      status) printf 'Status' ;;
      deployed) printf 'Deployment' ;;
      switch) printf 'Console switch' ;;
      temp_password) printf 'Temporary password' ;;
      *) printf '%s' "$1" ;;
    esac
  fi
}

# text <id> [arguments...]: longer sentences of the report.
text() {
  local id="$1"
  shift
  if [[ "$LANGUAGE" == zh ]]; then
    case "$id" in
      section_service) printf '服务' ;;
      section_console) printf '控制台' ;;
      section_root) printf 'Root 管理员' ;;
      section_key) printf '数据管理安全密钥' ;;
      section_mihomo) printf 'Mihomo 出站组件' ;;
      section_secrets) printf '.env 里的密钥' ;;
      not_running) printf '没有运行（状态: %s）' "$1" ;;
      running) printf '运行中%s' "$1" ;;
      up_since) printf '，启动于 %s' "$1" ;;
      health_ok) printf '正常（%s）' "$1" ;;
      health_down) printf '无响应（%s）；服务可能还在启动，稍后再试' "$1" ;;
      bind_loopback) printf '服务端口只绑定在 127.0.0.1：只有本机能访问，需要宿主机上的反向代理（如 Nginx）指向 %s；若反向代理在 Docker 里，请把 .env 的 HOST_BIND_ADDR 改成 0.0.0.0 后重启服务。' "$1" ;;
      bind_open) printf '服务端口绑定在 %s：明文 HTTP，请用防火墙限制该端口，只通过 HTTPS 反向代理对外开放。' "$1" ;;
      service_down_details) printf 'Root 账号和数据管理的详情需要服务在运行才能读取。启动服务后再运行本命令。' ;;
      cli_unsupported_info) printf '当前版本的服务不包含管理命令，无法读取 Root 和数据管理状态；重新执行安装脚本升级后再试。' ;;
      db_error) printf '读取数据库失败: %s' "$1" ;;
      no_root_yet) printf '还没有 Root 账号：服务第一次完整启动时会自动创建。' ;;
      root_error_missing) printf '异常：Root 标记指向的账号不存在。' ;;
      none) printf '（未设置）' ;;
      pw_initial_note) printf '这是尚未修改的初始密码，来自服务首次启动时只打印一次的日志。第一次登录会被强制修改密码。' ;;
      pw_changed) printf '已由管理员修改。密码只以加盐哈希保存，无法读回。忘记了请运行: %s reset-password' "$PROGRAM_NAME" ;;
      pw_unavailable_gone) printf '尚未修改，但初始密码已不在服务日志里（日志只保留当前容器，更新或重建容器后就没有了）。请运行: %s reset-password' "$PROGRAM_NAME" ;;
      pw_unavailable_stale) printf '尚未修改，但日志里的初始密码已经不是当前密码（之后被重置过），当前的临时密码无处可查。需要新密码请运行: %s reset-password' "$PROGRAM_NAME" ;;
      pw_unavailable_check) printf '尚未修改，但无法核对日志里的初始密码。请运行: %s reset-password' "$PROGRAM_NAME" ;;
      key_not_initialized) printf '尚未生成（Root 在控制台「数据管理」里生成，密钥只显示一次）' ;;
      key_initialized) printf '已生成，时间 %s，当前没有失败尝试' "$1" ;;
      key_initialized_failures) printf '已生成，时间 %s，已连续失败 %s 次（第 5 次失败会永久锁定并让站点进入维护模式）' "$1" "$2" ;;
      key_locked) printf '已锁定：连续失败 %s 次，锁定时间 %s；站点处于维护模式（503）' "$1" "$2" ;;
      key_error_missing) printf '异常：已初始化过，但状态文件丢失。请勿删除或重建任何文件' ;;
      key_error_invalid) printf '异常：状态文件内容无效。请勿手工修改' ;;
      key_error_unreadable) printf '异常：状态文件无法读取' ;;
      key_note) printf '安全密钥（dmsk-…）无法从服务器上显示、找回或重置：服务器上只保存它的加盐校验值。请保管好你保存下来的那一份。' ;;
      key_recovery) printf '被锁定或需要处理异常状态时，请按运维手册「恢复被锁定的数据管理」操作: %s' "$1" ;;
      mihomo_deployed) printf '已部署（.env 里有 COMPOSE_PROFILES=mihomo 和控制器密钥）' ;;
      mihomo_not_deployed) printf '未部署（重新运行安装脚本并加 ENABLE_MIHOMO=true 可部署，详见 docs/ACCOUNT_EGRESS.md）' ;;
      mihomo_incomplete) printf '不完整：.env 需要同时有 COMPOSE_PROFILES=mihomo 和 STARRY_EGRESS_CONTROLLER_SECRET' ;;
      mihomo_container) printf '，容器%s' "$1" ;;
      switch_on) printf '已打开' ;;
      switch_off) printf '已关闭' ;;
      switch_unset_on) printf '还没有人使用过开关，按 .env 的初始值：已打开' ;;
      switch_unset_off) printf '还没有人使用过开关，按 .env 的初始值：已关闭（在「系统设置 → 出站代理」里打开）' ;;
      switch_unknown) printf '未知（无法读取数据库）' ;;
      secrets_hidden) printf '.env 里保存的密钥（数据库密码、控制器密钥等）默认不显示；确实需要时加 --show-secrets。' ;;
      secrets_warning) printf '警告：下面的值可以完全控制你的数据库和服务。不要贴到聊天、工单或截图里，看完请清屏（clear）。' ;;
      secrets_none) printf '（.env 里没有找到密钥项）' ;;
      reset_locked_note) printf '注意：数据管理已被锁定，站点正处于维护模式，所有登录都会返回 503。新密码解决不了这个问题；请先按运维手册「恢复被锁定的数据管理」处理: %s' "$1" ;;
      reset_intro) printf '将为 Root 账号（%s）重置密码：' "$1" ;;
      reset_point_1) printf '生成一个新的随机临时密码，只显示这一次；' ;;
      reset_point_2) printf '下次登录时必须先修改密码（12–128 个字符）；' ;;
      reset_point_3) printf '重启服务（约几秒到一两分钟），期间无法访问；所有控制台会话都会被登出；' ;;
      reset_point_4) printf '数据管理安全密钥不受影响（它本来就无法重置）。' ;;
      reset_prompt) printf '输入 RESET 并回车以继续: ' ;;
      reset_running) printf '正在设置新密码…' ;;
      reset_restarting) printf '正在重启服务并等待恢复…' ;;
      reset_interrupted) printf '操作被中断，但密码已经重置。新的临时密码如下，请立即记录；服务可能还没有重启。' ;;
      result_title) printf 'Root 密码已重置。' ;;
      result_note) printf '这个密码只显示这一次，之后无法再读回；第一次登录会被要求设置新密码。' ;;
      result_restart_ok) printf '服务已重启并恢复正常，旧的控制台会话已全部登出。' ;;
      *) printf '%s' "$id" ;;
    esac
  else
    case "$id" in
      section_service) printf 'Service' ;;
      section_console) printf 'Console' ;;
      section_root) printf 'Root administrator' ;;
      section_key) printf 'Data-management security key' ;;
      section_mihomo) printf 'Mihomo egress component' ;;
      section_secrets) printf 'Secrets from .env' ;;
      not_running) printf 'not running (state: %s)' "$1" ;;
      running) printf 'running%s' "$1" ;;
      up_since) printf ', up since %s' "$1" ;;
      health_ok) printf 'OK (%s)' "$1" ;;
      health_down) printf 'not responding (%s); the service may still be starting, try again shortly' "$1" ;;
      bind_loopback) printf 'The service port is bound to 127.0.0.1: only this server can reach it, so a reverse proxy running on the host (for example Nginx) must point to %s. If the proxy runs inside Docker, set HOST_BIND_ADDR=0.0.0.0 in .env and restart the service.' "$1" ;;
      bind_open) printf 'The service port is bound to %s over plain HTTP: restrict it with a firewall and publish only the HTTPS reverse proxy.' "$1" ;;
      service_down_details) printf 'The Root account and data-management details can only be read while the service is running. Start it, then run this again.' ;;
      cli_unsupported_info) printf 'The running service does not include the administration command, so the Root and data-management state cannot be read; re-run the installer to update.' ;;
      db_error) printf 'Could not read the database: %s' "$1" ;;
      no_root_yet) printf 'There is no Root account yet: the service creates it when it first starts completely.' ;;
      root_error_missing) printf 'Unexpected: the Root marker points to an account that does not exist.' ;;
      none) printf '(none)' ;;
      pw_initial_note) printf 'This is the initial password, still unchanged; it comes from the line the service logs once at its first start. The first login forces you to change it.' ;;
      pw_changed) printf 'changed by the administrator. Passwords are stored as salted hashes and cannot be read back. Forgot it? Run: %s reset-password' "$PROGRAM_NAME" ;;
      pw_unavailable_gone) printf 'initial password not changed yet, but it is no longer in the service log (the log only covers the current container and is gone after an update or recreate). Run: %s reset-password' "$PROGRAM_NAME" ;;
      pw_unavailable_stale) printf 'a temporary password is set, but the one in the log is not the current one (it was reset since), and the current one cannot be looked up. For a new one run: %s reset-password' "$PROGRAM_NAME" ;;
      pw_unavailable_check) printf 'initial password not changed yet, but the one in the log could not be verified. Run: %s reset-password' "$PROGRAM_NAME" ;;
      key_not_initialized) printf 'not generated yet (Root generates it in the console under Data management; it is shown only once)' ;;
      key_initialized) printf 'generated at %s, no failed attempts' "$1" ;;
      key_initialized_failures) printf 'generated at %s, %s failed attempt(s) in a row (the 5th permanently locks it and puts the site into maintenance mode)' "$1" "$2" ;;
      key_locked) printf 'LOCKED after %s failed attempts at %s; the site is in maintenance mode (503)' "$1" "$2" ;;
      key_error_missing) printf 'ERROR: it was initialized, but its state file is missing. Do not delete or recreate any files' ;;
      key_error_invalid) printf 'ERROR: the state file is invalid. Do not edit it by hand' ;;
      key_error_unreadable) printf 'ERROR: the state file cannot be read' ;;
      key_note) printf 'The security key (dmsk-...) cannot be shown, recovered or reset from the server: only a salted verifier of it is stored here. Keep the copy you saved.' ;;
      key_recovery) printf 'If it is locked or the state is in error, follow "Recovering locked data management" (恢复被锁定的数据管理) in the operations guide: %s' "$1" ;;
      mihomo_deployed) printf 'deployed (.env has COMPOSE_PROFILES=mihomo and a controller secret)' ;;
      mihomo_not_deployed) printf 'not deployed (re-run the installer with ENABLE_MIHOMO=true to deploy it, see docs/ACCOUNT_EGRESS.md)' ;;
      mihomo_incomplete) printf 'INCOMPLETE: .env needs both COMPOSE_PROFILES=mihomo and STARRY_EGRESS_CONTROLLER_SECRET' ;;
      mihomo_container) printf ', container %s' "$1" ;;
      switch_on) printf 'on' ;;
      switch_off) printf 'off' ;;
      switch_unset_on) printf 'nobody has used the switch yet; the initial value from .env applies: on' ;;
      switch_unset_off) printf 'nobody has used the switch yet; the initial value from .env applies: off (switch it on in System settings > Outbound proxies)' ;;
      switch_unknown) printf 'unknown (the database could not be read)' ;;
      secrets_hidden) printf 'The secrets stored in .env (database passwords, controller secret, ...) are hidden; add --show-secrets if you really need them.' ;;
      secrets_warning) printf 'WARNING: the values below give full control of your database and services. Do not paste them into chats, tickets or screenshots, and clear the screen afterwards (clear).' ;;
      secrets_none) printf '(no secret entries found in .env)' ;;
      reset_locked_note) printf 'Note: data management is locked, so the site is in maintenance mode and every login returns 503. A new password does not change that; first follow "Recovering locked data management" (恢复被锁定的数据管理) in the operations guide: %s' "$1" ;;
      reset_intro) printf 'This resets the password of the Root account (%s):' "$1" ;;
      reset_point_1) printf 'a new random temporary password is generated and shown once;' ;;
      reset_point_2) printf 'it must be changed at the next login (12-128 characters);' ;;
      reset_point_3) printf 'the service restarts (a few seconds up to a minute or two) and is unreachable meanwhile; every console session is logged out;' ;;
      reset_point_4) printf 'the data-management security key is not affected (it cannot be reset).' ;;
      reset_prompt) printf 'Type RESET and press Enter to continue: ' ;;
      reset_running) printf 'Setting the new password...' ;;
      reset_restarting) printf 'Restarting the service and waiting for it to respond...' ;;
      reset_interrupted) printf 'Interrupted, but the password has already been reset. The new temporary password is below; write it down now. The service may not have been restarted yet.' ;;
      result_title) printf 'The Root password has been reset.' ;;
      result_note) printf 'It is shown only this once and cannot be read back later; you will be asked to choose a new password at the first login.' ;;
      result_restart_ok) printf 'The service was restarted and is responding; every old console session is logged out.' ;;
      *) printf '%s' "$id" ;;
    esac
  fi
}

heading() {
  printf '\n%s\n' "$(text "$1")"
}

# --- arguments ------------------------------------------------------------------------------------------------------

parse_arguments() {
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --lang)
        [[ "$#" -ge 2 ]] || die invalid_language
        case "$2" in
          zh|en) LANGUAGE="$2" ;;
          *) die invalid_language ;;
        esac
        shift 2
        ;;
      --lang=zh) LANGUAGE=zh; shift ;;
      --lang=en) LANGUAGE=en; shift ;;
      --lang=*) die invalid_language ;;
      --help|-h|help) SHOW_HELP=true; shift ;;
      --show-secrets) SHOW_SECRETS=true; shift ;;
      --yes|-y) ASSUME_YES=true; shift ;;
      info|reset-password)
        [[ -z "$COMMAND" ]] || die invalid_arguments
        COMMAND="$1"
        shift
        ;;
      *) die invalid_arguments ;;
    esac
  done
  COMMAND="${COMMAND:-info}"
  if [[ "$SHOW_SECRETS" == true && "$COMMAND" != info ]]; then
    die secrets_only_info
  fi
  if [[ "$ASSUME_YES" == true && "$COMMAND" != reset-password ]]; then
    die yes_only_reset
  fi
}

# --- deployment, Docker and .env ------------------------------------------------------------------------------------

# The directory this script really lives in, following symlinks (the installer links /usr/local/bin/starry-api to it).
resolve_script_dir() {
  local source="${BASH_SOURCE[0]}" directory target
  while [[ -L "$source" ]]; do
    directory="$(cd -P "$(dirname "$source")" && pwd)"
    target="$(readlink "$source")"
    if [[ "$target" == /* ]]; then
      source="$target"
    else
      source="${directory}/${target}"
    fi
  done
  (cd -P "$(dirname "$source")" && pwd)
}

is_deployment_dir() {
  [[ -n "$1" && -d "$1" && ( -f "$1/docker-compose.artifact.yml" || -f "$1/docker-compose.yml" ) ]]
}

# The script's own directory, else INSTALL_DIR, else /opt/starry-api. A checkout keeps this script in scripts/ and the
# Compose file one level up, so that parent counts as the script's directory.
locate_deployment() {
  local script_dir candidate tried=""
  script_dir="$(resolve_script_dir)"
  local -a candidates=("$script_dir")
  if [[ "$(basename "$script_dir")" == scripts ]]; then
    candidates+=("$(dirname "$script_dir")")
  fi
  candidates+=("${INSTALL_DIR:-}" "/opt/starry-api")
  for candidate in "${candidates[@]}"; do
    [[ -n "$candidate" ]] || continue
    tried="${tried:+${tried}, }${candidate}"
    if is_deployment_dir "$candidate"; then
      INSTALL_ROOT="$(cd -P "$candidate" && pwd)"
      break
    fi
  done
  [[ -n "$INSTALL_ROOT" ]] || die no_deployment "$tried"
  cd "$INSTALL_ROOT"
  if [[ -f docker-compose.artifact.yml ]]; then
    COMPOSE_FILE="docker-compose.artifact.yml"
  else
    COMPOSE_FILE="docker-compose.yml"
  fi
  ENV_FILE="${INSTALL_ROOT}/.env"
}

# docker compose (plugin) or docker-compose, in the same order as the installer's ensure_compose.
detect_compose() {
  command -v docker >/dev/null 2>&1 || die docker_missing
  if docker compose version >/dev/null 2>&1; then
    COMPOSE_KIND=plugin
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_KIND=standalone
  else
    die compose_missing
  fi
}

compose() {
  if [[ "$COMPOSE_KIND" == plugin ]]; then
    docker compose -f "$COMPOSE_FILE" "$@"
  else
    docker-compose -f "$COMPOSE_FILE" "$@"
  fi
}

# The installer runs as root and calls Docker directly. Here the same is done as root, or as a user who can use Docker
# and read .env (the file is 0600 and holds the database passwords); anyone else is told to use sudo.
check_access() {
  if ! docker info >/dev/null 2>&1; then
    if [[ "$(id -u)" -ne 0 ]]; then
      die need_root "$ORIGINAL_ARGS"
    fi
    die docker_down
  fi
  if [[ "$(id -u)" -ne 0 && -e "$ENV_FILE" && ! -r "$ENV_FILE" ]]; then
    die need_root "$ORIGINAL_ARGS"
  fi
}

# The last assignment of a key in .env without surrounding quotes, as Compose reads it; empty when absent.
env_value() {
  local value
  [[ -r "$ENV_FILE" ]] || return 0
  value="$(grep -E "^$1=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true)"
  value="${value%$'\r'}"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"
  printf '%s' "$value"
}

load_settings() {
  local value
  value="$(env_value HOST_BIND_ADDR)"
  BIND_ADDR="${value:-127.0.0.1}"
  value="$(env_value HOST_HTTP_PORT)"
  HTTP_PORT="${value:-18080}"
  value="$(env_value SERVER_PORT)"
  SERVER_PORT="${value:-8080}"
}

# Same rules as the installer's local_access_host, detect_host_ip, reverse_proxy_target and webui_url.
local_access_host() {
  if [[ "$BIND_ADDR" == "0.0.0.0" || "$BIND_ADDR" == "::" ]]; then
    printf '127.0.0.1'
  else
    printf '%s' "$BIND_ADDR"
  fi
}

detect_host_ip() {
  local ip_addr=""
  if command -v ip >/dev/null 2>&1; then
    ip_addr="$(ip route get 1.1.1.1 2>/dev/null | awk '{for (i=1;i<=NF;i++) if ($i=="src") {print $(i+1); exit}}' || true)"
  fi
  if [[ -z "$ip_addr" ]] && command -v hostname >/dev/null 2>&1; then
    ip_addr="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  fi
  printf '%s' "${ip_addr:-SERVER_IP}"
}

bound_to_loopback() {
  [[ "$BIND_ADDR" == "127.0.0.1" || "$BIND_ADDR" == "localhost" ]]
}

reverse_proxy_target() {
  if bound_to_loopback; then
    printf 'http://127.0.0.1:%s' "$HTTP_PORT"
  else
    printf 'http://%s:%s' "$(detect_host_ip)" "$HTTP_PORT"
  fi
}

local_login_url() {
  printf 'http://%s:%s/#/login' "$(local_access_host)" "$HTTP_PORT"
}

# The address administrators should use: the one the installer wrote into STARRY_WEBUI_URL (the public HTTPS address
# when PUBLIC_BASE_URL was given), else the local one.
console_url() {
  local configured
  configured="$(env_value STARRY_WEBUI_URL)"
  if [[ -n "$configured" ]]; then
    printf '%s' "$configured"
  else
    local_login_url
  fi
}

# The installer's mihomo_profile_listed and mihomo_state, working on .env.
mihomo_profile_listed() {
  local profiles item
  local -a items=()
  [[ -r "$ENV_FILE" ]] || return 1
  profiles="$(env_value COMPOSE_PROFILES)"
  IFS=, read -r -a items <<< "$profiles" || true
  for item in ${items[@]+"${items[@]}"}; do
    item="${item//[[:space:]]/}"
    if [[ "$item" == "mihomo" || "$item" == "*" ]]; then
      return 0
    fi
  done
  return 1
}

mihomo_state() {
  local listed=false secret=false
  mihomo_profile_listed && listed=true
  [[ -n "$(env_value STARRY_EGRESS_CONTROLLER_SECRET)" ]] && secret=true
  if [[ "$listed" == true && "$secret" == true ]]; then
    printf 'deployed'
  elif [[ "$listed" == false && "$secret" == false ]]; then
    printf 'not-deployed'
  else
    printf 'incomplete'
  fi
}

# --- the service container ------------------------------------------------------------------------------------------

container_id() {
  local ids
  ids="$(compose ps -a -q "$1" 2>/dev/null || compose ps -q "$1" 2>/dev/null || true)"
  printf '%s\n' "$ids" | head -n 1
}

# Sets CONTAINER_STATE (running, exited, ... or "missing"), CONTAINER_HEALTH (healthy, unhealthy, starting or empty)
# and CONTAINER_STARTED for one Compose service.
inspect_service() {
  local id details
  CONTAINER_STATE="missing"
  CONTAINER_HEALTH=""
  CONTAINER_STARTED=""
  id="$(container_id "$1")"
  [[ -n "$id" ]] || return 0
  details="$(docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.StartedAt}}' "$id" 2>/dev/null || true)"
  [[ -n "$details" ]] || return 0
  IFS='|' read -r CONTAINER_STATE CONTAINER_HEALTH CONTAINER_STARTED <<< "$details"
}

service_url_for_health() {
  printf 'http://%s:%s/health' "$(local_access_host)" "$HTTP_PORT"
}

# Whether the published port answers on /health, as a reverse proxy would see it (inside the container when this host
# has no curl).
check_health() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --noproxy '*' -m 3 -o /dev/null "$(service_url_for_health)" >/dev/null 2>&1
  else
    check_health_in_container
  fi
}

check_health_in_container() {
  compose exec -T service curl -fsS -m 3 -o /dev/null "http://127.0.0.1:${SERVER_PORT}/health" >/dev/null 2>&1
}

# After a restart it is the service that has to answer; a published port that is not reachable from this host (a
# firewall, an unusual HOST_BIND_ADDR) must not make a healthy service look dead.
wait_for_health() {
  local attempt=0
  while (( attempt < HEALTH_WAIT_SECONDS )); do
    if check_health || check_health_in_container; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

# run_cli <subcommand>: runs tech.starryapi.cli.AdminCli inside the service container. Standard output goes to CLI_OUT,
# standard error (never a secret) to CLI_ERR and the exit status to CLI_STATUS. The caller chooses the standard input.
run_cli() {
  local error_file
  error_file="$(mktemp "${TMPDIR:-/tmp}/starry-api-cli.XXXXXX")"
  CLI_STATUS=0
  # shellcheck disable=SC2086
  CLI_OUT="$(compose exec -T service java $CLI_JVM_FLAGS -Dloader.main="$CLI_MAIN_CLASS" -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher "$1" 2>"$error_file")" || CLI_STATUS=$?
  CLI_ERR="$(cat "$error_file" 2>/dev/null || true)"
  rm -f -- "$error_file"
}

cli_unsupported() {
  [[ "$CLI_STATUS" -ne 0 ]] || return 1
  case "$CLI_ERR" in
    *ClassNotFoundException*|*"Could not find or load main class"*|*NoClassDefFoundError*) return 0 ;;
  esac
  return 1
}

# The value of a key=value line of the last run_cli output, or of the saved output of "status".
cli_value() {
  printf '%s\n' "$CLI_OUT" | sed -n "s/^$1=//p" | head -n 1
}

status_value() {
  printf '%s\n' "$STATUS_OUT" | sed -n "s/^$1=//p" | head -n 1
}

cli_error_code() {
  printf '%s\n' "$CLI_ERR" | sed -n 's/^error=//p' | head -n 1
}

cli_error_detail() {
  printf '%s\n' "$CLI_ERR" | sed -n '2p'
}

# Stops with a localized message for a failed run_cli.
die_cli_failure() {
  local code detail
  if cli_unsupported; then
    die cli_unsupported
  fi
  code="$(cli_error_code)"
  detail="$(cli_error_detail)"
  case "$code" in
    no_root) die err_no_root ;;
    root_missing) die err_root_missing ;;
    schema_missing) die err_schema_missing ;;
    database_unavailable) die err_database_unavailable "${detail#Could not use the database: }" ;;
    *) die cli_error "${detail:-${CLI_ERR:-exit status ${CLI_STATUS}}}" ;;
  esac
}

# --- info -----------------------------------------------------------------------------------------------------------

# The password the service printed once at its first start, if the current container's log still has it.
logged_initial_password() {
  compose logs --no-color service 2>/dev/null \
    | grep -a 'StartupGuideLogger' \
    | sed -n -E 's/.*: Password: ([0-9a-f]{36})[[:space:]]*$/\1/p' \
    | tail -n 1 || true
}

# Shows the Root password line: the initial password only when Root has not changed it and the logged one is still the
# current one (a reset leaves the old line in the log), otherwise what to do.
print_root_password() {
  local candidate
  if [[ "$(status_value root_first_login)" != true ]]; then
    line password "$(text pw_changed)"
    return 0
  fi
  candidate="$(logged_initial_password)"
  if [[ -z "$candidate" ]]; then
    line password "$(text pw_unavailable_gone)"
    return 0
  fi
  run_cli check-root-password < <(printf '%s\n' "$candidate")
  if [[ "$CLI_STATUS" -ne 0 ]]; then
    line password "$(text pw_unavailable_check)"
  elif [[ "$(cli_value password_matches)" == true ]]; then
    line password "$candidate"
    printf '    %s\n' "$(text pw_initial_note)"
  else
    line password "$(text pw_unavailable_stale)"
  fi
  candidate=""
}

print_service_section() {
  local health_url
  heading section_service
  if [[ "$CONTAINER_STATE" == running ]]; then
    local extra=""
    [[ -z "$CONTAINER_HEALTH" ]] || extra=" (${CONTAINER_HEALTH})"
    [[ -z "$CONTAINER_STARTED" ]] || extra="${extra}$(text up_since "$CONTAINER_STARTED")"
    line container "$(text running "$extra")"
  else
    line container "$(text not_running "$CONTAINER_STATE")"
  fi
  health_url="$(service_url_for_health)"
  if check_health; then
    line health "$(text health_ok "$health_url")"
  else
    line health "$(text health_down "$health_url")"
  fi
}

print_console_section() {
  heading section_console
  line login_page "$(console_url)"
  if [[ "$(console_url)" != "$(local_login_url)" ]]; then
    line local_address "$(local_login_url)"
  fi
  line proxy_target "$(reverse_proxy_target)"
  if bound_to_loopback; then
    printf '  %s\n' "$(text bind_loopback "$(reverse_proxy_target)")"
  else
    printf '  %s\n' "$(text bind_open "$BIND_ADDR")"
  fi
}

print_root_section() {
  local database root_assigned email
  heading section_root
  database="$(status_value database)"
  if [[ "$database" != ok ]]; then
    line status "$(text db_error "$(status_value database_error)")"
    return 0
  fi
  root_assigned="$(status_value root_assigned)"
  if [[ "$root_assigned" != true ]]; then
    line status "$(text no_root_yet)"
    return 0
  fi
  if [[ -n "$(status_value root_error)" ]]; then
    line status "$(text root_error_missing)"
    return 0
  fi
  email="$(status_value root_email)"
  line username "$(status_value root_username)"
  line email "${email:-$(text none)}"
  print_root_password
}

print_key_section() {
  local state error
  heading section_key
  state="$(status_value data_management)"
  case "$state" in
    not_initialized)
      line status "$(text key_not_initialized)"
      ;;
    initialized)
      if [[ "$(status_value data_management_failed_attempts)" == 0 ]]; then
        line status "$(text key_initialized "$(status_value data_management_initialized_at)")"
      else
        line status "$(text key_initialized_failures "$(status_value data_management_initialized_at)" "$(status_value data_management_failed_attempts)")"
      fi
      ;;
    locked)
      line status "$(text key_locked "$(status_value data_management_failed_attempts)" "$(status_value data_management_locked_at)")"
      ;;
    error)
      error="$(status_value data_management_error)"
      case "$error" in
        state_missing) line status "$(text key_error_missing)" ;;
        state_unreadable) line status "$(text key_error_unreadable)" ;;
        *) line status "$(text key_error_invalid)" ;;
      esac
      ;;
  esac
  printf '  %s\n' "$(text key_note)"
  printf '  %s\n' "$(text key_recovery "$DOCS_URL")"
}

print_mihomo_section() {
  local state initial description switch
  heading section_mihomo
  state="$(mihomo_state)"
  case "$state" in
    deployed)
      inspect_service mihomo
      description="$(text mihomo_deployed)$(text mihomo_container "$CONTAINER_STATE")"
      line deployed "$description"
      switch="$(status_value egress_mihomo_enabled)"
      initial="$(env_value STARRY_EGRESS_MIHOMO_ENABLED)"
      case "$switch" in
        true) line switch "$(text switch_on)" ;;
        false) line switch "$(text switch_off)" ;;
        unset)
          if [[ "$initial" == true ]]; then
            line switch "$(text switch_unset_on)"
          else
            line switch "$(text switch_unset_off)"
          fi
          ;;
        *) line switch "$(text switch_unknown)" ;;
      esac
      ;;
    incomplete) line deployed "$(text mihomo_incomplete)" ;;
    *) line deployed "$(text mihomo_not_deployed)" ;;
  esac
}

# Secret-looking entries of .env: names with PASSWORD, SECRET, TOKEN or KEY in them, minus the settings that only
# happen to contain the word (token refresh intervals, key file paths, switches).
is_secret_name() {
  local upper
  upper="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  case "$upper" in
    *_FILE|*_MS|*_ENABLED|STARRY_TOKEN_REFRESH_*|KIRO_TOKEN_REFRESH_*) return 1 ;;
    *PASSWORD*|*SECRET*|*TOKEN*|*KEY*) return 0 ;;
  esac
  return 1
}

print_secrets_section() {
  local entry name value printed=false
  heading section_secrets
  printf '  %s\n' "$(text secrets_warning)" >&2
  if [[ -r "$ENV_FILE" ]]; then
    while IFS= read -r entry || [[ -n "$entry" ]]; do
      entry="${entry%$'\r'}"
      [[ "$entry" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
      name="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
      value="${value%\"}"
      value="${value#\"}"
      value="${value%\'}"
      value="${value#\'}"
      [[ -n "$value" ]] || continue
      if is_secret_name "$name"; then
        printf '  %s=%s\n' "$name" "$value"
        printed=true
      fi
    done < "$ENV_FILE"
  fi
  if [[ "$printed" == false ]]; then
    printf '  %s\n' "$(text secrets_none)"
  fi
  value=""
}

command_info() {
  STATUS_OUT=""
  printf '%s  ·  %s\n' "Starry API" "$INSTALL_ROOT"
  inspect_service service
  print_service_section
  print_console_section

  if [[ "$CONTAINER_STATE" == running ]]; then
    run_cli status </dev/null
    if cli_unsupported; then
      heading section_root
      line status "$(text cli_unsupported_info)"
    elif [[ "$CLI_STATUS" -ne 0 ]]; then
      heading section_root
      line status "$(text db_error "$(cli_error_detail)")"
    else
      STATUS_OUT="$CLI_OUT"
      print_root_section
      print_key_section
    fi
  else
    heading section_root
    line status "$(text service_down_details)"
  fi
  print_mihomo_section

  printf '\n'
  if [[ "$SHOW_SECRETS" == true ]]; then
    print_secrets_section
  else
    printf '%s\n' "$(text secrets_hidden)"
  fi
}

# --- reset-password -------------------------------------------------------------------------------------------------

print_reset_result() {
  local interrupted="$1"
  printf '\n'
  if [[ "$interrupted" == true ]]; then
    printf '%s\n' "$(text reset_interrupted)"
  else
    printf '%s\n' "$(text result_title)"
  fi
  line login_page "$(console_url)"
  line username "$RESET_USERNAME"
  line temp_password "$RESET_PASSWORD"
  printf '  %s\n' "$(text result_note)"
}

# A trap handler runs while the interrupted command's redirections (the ">/dev/null 2>&1" of a health probe, say) are
# still in effect, so it writes to descriptor 3, the terminal saved before the password existed.
on_interrupt() {
  trap - INT TERM HUP
  if [[ -n "$RESET_PASSWORD" ]]; then
    print_reset_result true >&3
  fi
  exit 130
}

confirm_reset() {
  local answer=""
  if [[ "$ASSUME_YES" == true ]]; then
    return 0
  fi
  if [[ ! -t 0 || ! -t 2 ]]; then
    die reset_needs_terminal
  fi
  printf '\n%s\n' "$(text reset_intro "$RESET_USERNAME")"
  printf '  - %s\n' "$(text reset_point_1)" "$(text reset_point_2)" "$(text reset_point_3)" "$(text reset_point_4)"
  printf '\n' >&2
  printf '%s' "$(text reset_prompt)" >&2
  IFS= read -r answer || answer=""
  [[ "$answer" == "RESET" ]] || die reset_cancelled
}

command_reset_password() {
  local restarted=true
  inspect_service service
  if [[ "$CONTAINER_STATE" != running ]]; then
    die service_not_running "$CONTAINER_STATE" "$INSTALL_ROOT" "$COMPOSE_FILE"
  fi

  # Find out first whether there is a Root to reset, so that nothing is asked or restarted for nothing.
  run_cli status </dev/null
  if [[ "$CLI_STATUS" -ne 0 ]]; then
    die_cli_failure
  fi
  if [[ "$(cli_value database)" != ok ]]; then
    die err_database_unavailable "$(cli_value database_error)"
  fi
  if [[ "$(cli_value root_assigned)" != true ]]; then
    die err_no_root
  fi
  RESET_USERNAME="$(cli_value root_username)"
  if [[ -z "$RESET_USERNAME" ]]; then
    die err_root_missing
  fi
  if [[ "$(cli_value data_management)" == locked ]]; then
    printf '\n%s\n' "$(text reset_locked_note "$DOCS_URL")"
  fi

  confirm_reset

  printf '%s\n' "$(text reset_running)"
  run_cli reset-root-password </dev/null
  if [[ "$CLI_STATUS" -ne 0 ]]; then
    message reset_failed_nochange >&2
    printf '\n' >&2
    die_cli_failure
  fi
  RESET_PASSWORD="$(cli_value password)"
  CLI_OUT=""
  if [[ -z "$RESET_PASSWORD" ]]; then
    die reset_failed_nochange
  fi
  # From here on the new password exists only in this process; do not lose it on an interrupt.
  exec 3>&1
  trap on_interrupt INT TERM HUP

  printf '%s\n' "$(text reset_restarting)"
  if ! compose restart service >/dev/null 2>&1; then
    restarted=false
  fi
  if [[ "$restarted" == true ]] && mihomo_profile_listed && [[ -n "$(container_id mihomo)" ]]; then
    # Mihomo shares the service's network namespace; a restart of the service leaves it in the old one.
    compose restart mihomo >/dev/null 2>&1 || {
      message mihomo_restart_warning "$INSTALL_ROOT" "$COMPOSE_FILE" >&2
      printf '\n' >&2
    }
  fi
  if [[ "$restarted" == true ]] && ! wait_for_health; then
    print_reset_result false
    die health_timeout "$HEALTH_WAIT_SECONDS" "$INSTALL_ROOT" "$COMPOSE_FILE"
  fi

  print_reset_result false
  trap - INT TERM HUP
  if [[ "$restarted" == true ]]; then
    printf '  %s\n' "$(text result_restart_ok)"
  else
    message restart_failed >&2
    printf '\n' >&2
    message restart_manual "$INSTALL_ROOT" "$COMPOSE_FILE" >&2
    printf '\n' >&2
    exit 1
  fi
}

# --- main -----------------------------------------------------------------------------------------------------------

main() {
  detect_language
  parse_arguments "$@"
  if [[ "$SHOW_HELP" == true ]]; then
    print_usage
    exit 0
  fi
  locate_deployment
  detect_compose
  check_access
  load_settings
  case "$COMMAND" in
    info) command_info ;;
    reset-password) command_reset_password ;;
  esac
}

main "$@"
