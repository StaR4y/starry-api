#!/usr/bin/env bash
# Checks what a release carries: no Mihomo binary and no saved container image, and the license files.
#
# Mihomo (the Clash.Meta core) is GPL-3.0 and is optional. Starry API only ever names its image in the Compose file, and
# Docker pulls the image from its registry at deploy time; no Mihomo binary or saved image may enter our image or the jar
# (see section 6 of NOTICE). This also fails when a license file is missing: LICENSE, NOTICE, the npm and Java notices,
# the LGPL-2.1 text and logback's notice, and the -sources jars of the libraries used under a copyleft license (section 3
# of NOTICE), which our image has to carry because it is the binary release those licenses talk about.
#
# Usage: scripts/check-release-contents.sh [--image IMAGE]... [JAR]...
#   --image IMAGE   a built image (service files under /app); it is created, never started
#   JAR             the executable jar (service/build/libs/starry-api.jar)
set -euo pipefail

APP_NAME="check-release"
problems=0
# The libraries used under a copyleft license; their -sources jars must be in the image (section 3 of NOTICE).
COPYLEFT_SOURCE_LIBRARIES=(logback-classic logback-core jakarta.annotation-api jakarta.mail-api angus-mail)
JAR_LICENSE_ENTRIES=(META-INF/LICENSE META-INF/NOTICE META-INF/JAVA-THIRD-PARTY-NOTICES.txt
  META-INF/licenses/LGPL-2.1.txt META-INF/licenses/logback-NOTICE.txt BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt)
IMAGE_LICENSE_FILES=(LICENSE NOTICE licenses/LGPL-2.1.txt licenses/logback-NOTICE.txt)

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

check_jar() {
  local jar="$1"
  local label="${2:-$1}"
  local listing

  listing="$(zip_entries "$jar")" || { problem "${label}: could not list the jar."; return; }
  check_entry_names "$label" <<<"$listing"
  require_names "$label" "$listing" "${JAR_LICENSE_ENTRIES[@]}"
}

# /app holds what this project adds to the base image, so everything in it is checked. Anywhere else in the image only a
# Mihomo binary is looked for: the base image's own files are not this project's to judge.
check_image() {
  local image="$1"
  local work container listing file base

  command -v docker >/dev/null 2>&1 || { problem "${image}: docker is needed to check an image."; return; }
  container="$(docker create "$image")" || { problem "${image}: could not create a container from the image."; return; }
  work="$(mktemp -d)"
  # As a tar stream: the image has read-only files, which "docker cp" cannot write into a directory of its own.
  if docker cp "${container}:/app" - | tar -xf - -C "$work" && [[ -d "$work/app" ]]; then
    listing="$(cd "$work/app" && find . -type f | sed 's#^\./##' | sort)"
    check_entry_names "$image" <<<"$listing"
    require_names "$image" "$listing" "${IMAGE_LICENSE_FILES[@]}" app.jar bin/recover-data-management
    require_copyleft_sources "$image" "$listing"
    while IFS= read -r -d '' file; do
      if is_native_executable "$file"; then
        problem "${image}: /app/${file#"$work"/app/} is a native executable; /app holds scripts, license files and jars only."
      fi
    done < <(find "$work/app" -type f -print0)
    if [[ -f "$work/app/app.jar" ]]; then
      check_jar "$work/app/app.jar" "${image}!/app/app.jar"
    fi
  else
    problem "${image}: has no /app directory."
  fi
  while IFS= read -r file; do
    base="$(printf '%s' "${file##*/}" | tr '[:upper:]' '[:lower:]')"
    case "$base" in
      mihomo-entrypoint.sh) ;;
      mihomo|mihomo-*|mihomo_*|mihomo.*)
        problem "${image}: /${file} looks like a Mihomo binary; it must not be distributed."
        ;;
    esac
  done < <(docker export "$container" | LC_ALL=C tar -tf - | sed 's#/$##')
  docker rm "$container" >/dev/null || true
  chmod -R u+rwX "$work"
  rm -rf "$work"
}

main() {
  [[ "$#" -gt 0 ]] || { printf 'Usage: %s [--image IMAGE]... [JAR]...\n' "$0" >&2; exit 2; }
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --image)
        [[ "$#" -ge 2 ]] || { printf 'Usage: %s [--image IMAGE]... [JAR]...\n' "$0" >&2; exit 2; }
        check_image "$2"
        shift 2
        ;;
      *.jar)
        if [[ -f "$1" ]]; then check_jar "$1"; else problem "${1}: no such file."; fi
        shift
        ;;
      *)
        problem "${1}: expected --image IMAGE or a .jar."
        shift
        ;;
    esac
  done
  if [[ "$problems" -gt 0 ]]; then
    printf '[%s] %d problem(s) found.\n' "$APP_NAME" "$problems" >&2
    exit 1
  fi
  printf '[%s] OK: no Mihomo binary or saved image, and the license files are present.\n' "$APP_NAME"
}

main "$@"
