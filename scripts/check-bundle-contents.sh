#!/usr/bin/env bash
# Checks what a release carries: no Mihomo binary and no saved container image, and the license files.
#
# Mihomo (the Clash.Meta core) is GPL-3.0 and is optional. Starry API only ever names its image in the Compose files, and
# Docker pulls the image from its registry at deploy time; no Mihomo binary or saved image may enter a bundle or the jar
# (see section 6 of NOTICE). This also fails when a license file is missing: LICENSE, NOTICE, the npm and Java notices,
# the LGPL-2.1 text and logback's notice, and the -sources jars of the libraries used under a copyleft license (section 3
# of NOTICE), which the bundle has to carry because it is the binary release those licenses talk about.
#
# Usage: scripts/check-bundle-contents.sh FILE...
#   *.tar.gz, *.tgz  a deployment bundle (starry-api-bundle.tar.gz)
#   *.jar            the executable jar (service-*.jar or starry-api.jar)
set -euo pipefail

APP_NAME="check-bundle"
problems=0
# The libraries used under a copyleft license; their -sources jars must be in the bundle (section 3 of NOTICE).
COPYLEFT_SOURCE_LIBRARIES=(logback-classic logback-core jakarta.annotation-api jakarta.mail-api angus-mail)
JAR_LICENSE_ENTRIES=(META-INF/LICENSE META-INF/NOTICE META-INF/JAVA-THIRD-PARTY-NOTICES.txt
  META-INF/licenses/LGPL-2.1.txt META-INF/licenses/logback-NOTICE.txt BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt)

problem() {
  printf '[%s] ERROR: %s\n' "$APP_NAME" "$*" >&2
  problems=$((problems + 1))
}

# Reads entry names on stdin; $1 names the archive in messages. The deployment's own mihomo-entrypoint.sh is fine.
check_entry_names() {
  local label="$1"
  local entry base
  while IFS= read -r entry; do
    [[ -n "$entry" ]] || continue
    base="$(printf '%s' "${entry##*/}" | tr '[:upper:]' '[:lower:]')"
    case "$base" in
      mihomo-entrypoint.sh) ;;
      mihomo|mihomo-*|mihomo_*|mihomo.*|clash|clash-*|clash_*|clash.*)
        problem "${label}: ${entry} looks like a Mihomo (Clash) binary or release asset; it must not be distributed."
        ;;
    esac
    case "$entry" in
      oci-layout|*/oci-layout|repositories|*/repositories|layer.tar|*/layer.tar|blobs/sha256/*|*/blobs/sha256/*)
        problem "${label}: ${entry} is part of a saved container image; images must be pulled at deploy time, not shipped."
        ;;
    esac
    case "$base" in
      starry-api-webui.tar.gz) ;;
      *.tar|*.tar.gz|*.tgz|*.tar.xz|*.tar.zst)
        problem "${label}: ${entry} is a nested archive that could hold a saved image."
        ;;
    esac
  done
}

# True for the first bytes of an ELF, Mach-O or Windows executable.
is_native_executable() {
  local magic
  magic="$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')"
  case "$magic" in
    7f454c46|feedface|feedfacf|cefaedfe|cffaedfe) return 0 ;;
  esac
  [[ "${magic:0:4}" == "4d5a" ]]
}

zip_entries() {
  if command -v unzip >/dev/null 2>&1; then
    unzip -Z1 "$1"
  elif command -v jar >/dev/null 2>&1; then
    jar tf "$1"
  else
    printf '[%s] ERROR: unzip or jar is needed to list %s\n' "$APP_NAME" "$1" >&2
    return 1
  fi
}

require_names() {
  local label="$1" listing="$2"
  shift 2
  local required
  for required in "$@"; do
    grep -qxF "$required" <<<"$listing" || problem "${label}: ${required} is missing."
  done
}

# Fails for every copyleft library whose -sources jar is not in the listing's third-party-sources/ directory.
require_copyleft_sources() {
  local label="$1" listing="$2" library
  for library in "${COPYLEFT_SOURCE_LIBRARIES[@]}"; do
    grep -qE "^third-party-sources/${library//./\\.}-[0-9][^/]*-sources\\.jar$" <<<"$listing" \
      || problem "${label}: third-party-sources/${library}-<version>-sources.jar is missing."
  done
}

check_bundle() {
  local bundle="$1"
  local listing work file

  listing="$(LC_ALL=C tar -tzf "$bundle")" || { problem "${bundle}: not a readable gzip-compressed tar archive."; return; }
  # The bundle is made with "tar -C stage ." so every name starts with "./".
  listing="$(sed 's#^\./##' <<<"$listing")"
  check_entry_names "$bundle" <<<"$listing"
  require_names "$bundle" "$listing" LICENSE NOTICE THIRD-PARTY-NOTICES.txt JAVA-THIRD-PARTY-NOTICES.txt \
    licenses/LGPL-2.1.txt licenses/logback-NOTICE.txt app/starry-api.jar
  require_copyleft_sources "$bundle" "$listing"

  work="$(mktemp -d)"
  if tar --no-same-owner -xzf "$bundle" -C "$work"; then
    while IFS= read -r -d '' file; do
      if is_native_executable "$file"; then
        problem "${bundle}: ${file#"$work"/} is a native executable; the bundle holds scripts, configuration and the jar only."
      fi
    done < <(find "$work" -type f -print0)
    if [[ -f "$work/app/starry-api.jar" ]]; then
      check_jar "$work/app/starry-api.jar" "${bundle}!app/starry-api.jar"
    fi
    if [[ -f "$work/starry-api-webui.tar.gz" ]]; then
      listing="$(LC_ALL=C tar -tzf "$work/starry-api-webui.tar.gz" | sed 's#^\./##')"
      check_entry_names "${bundle}!starry-api-webui.tar.gz" <<<"$listing"
      require_names "${bundle}!starry-api-webui.tar.gz" "$listing" THIRD-PARTY-NOTICES.txt LICENSE NOTICE
    fi
  else
    problem "${bundle}: could not be extracted."
  fi
  rm -rf "$work"
}

check_jar() {
  local jar="$1"
  local label="${2:-$1}"
  local listing

  listing="$(zip_entries "$jar")" || { problem "${label}: could not list the jar."; return; }
  check_entry_names "$label" <<<"$listing"
  require_names "$label" "$listing" "${JAR_LICENSE_ENTRIES[@]}"
}

main() {
  [[ "$#" -gt 0 ]] || { printf 'Usage: %s FILE...\n' "$0" >&2; exit 2; }
  local file
  for file in "$@"; do
    [[ -f "$file" ]] || { problem "${file}: no such file."; continue; }
    case "$file" in
      *.tar.gz|*.tgz) check_bundle "$file" ;;
      *.jar) check_jar "$file" ;;
      *) problem "${file}: expected a .tar.gz bundle or a .jar." ;;
    esac
  done
  if [[ "$problems" -gt 0 ]]; then
    printf '[%s] %d problem(s) found.\n' "$APP_NAME" "$problems" >&2
    exit 1
  fi
  printf '[%s] OK: no Mihomo binary or saved image, and the license files are present.\n' "$APP_NAME"
}

main "$@"
