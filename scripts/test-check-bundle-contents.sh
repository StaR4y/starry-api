#!/usr/bin/env bash
# Tests scripts/check-bundle-contents.sh with small made-up bundles.
set -euo pipefail
export COPYFILE_DISABLE=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${ROOT_DIR}/scripts/check-bundle-contents.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-bundle-check-test.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

fail_test() {
  printf '[check-bundle-test] ERROR: %s\n' "$*" >&2
  exit 1
}

# make_jar FILE ENTRY...: a zip with the named (empty) entries, built without needing zip or jar.
make_jar() {
  local file="$1"
  shift
  python3 -I - "$file" "$@" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    for name in sys.argv[2:]:
        archive.writestr(name, "x")
PY
}

GOOD_JAR_ENTRIES=(META-INF/LICENSE META-INF/NOTICE META-INF/JAVA-THIRD-PARTY-NOTICES.txt
  META-INF/licenses/LGPL-2.1.txt META-INF/licenses/logback-NOTICE.txt BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt
  BOOT-INF/classes/tech/starryapi/egress/MihomoRuntime.class)
SOURCE_JARS=(logback-classic-1.5.38 logback-core-1.5.38 jakarta.annotation-api-3.0.0 jakarta.mail-api-2.1.5 angus-mail-2.0.5)

# make_bundle NAME [EXTRA_FILE...]: a bundle with everything a release has, plus extra files (path:content).
make_bundle() {
  local name="$1"
  shift
  local stage="$TEST_ROOT/$name.stage"
  local extra
  mkdir -p "$stage/app" "$stage/docker/egress" "$stage/licenses" "$stage/third-party-sources"
  printf 'license\n' > "$stage/LICENSE"
  printf 'notice\n' > "$stage/NOTICE"
  printf 'third party\n' > "$stage/THIRD-PARTY-NOTICES.txt"
  printf 'java third party\n' > "$stage/JAVA-THIRD-PARTY-NOTICES.txt"
  printf 'lgpl\n' > "$stage/licenses/LGPL-2.1.txt"
  printf 'logback\n' > "$stage/licenses/logback-NOTICE.txt"
  for source in "${SOURCE_JARS[@]}"; do
    make_jar "$stage/third-party-sources/${source}-sources.jar" "${source%%-[0-9]*}/Source.java"
  done
  printf '#!/bin/sh\n' > "$stage/docker/egress/mihomo-entrypoint.sh"
  make_jar "$stage/app/starry-api.jar" "${GOOD_JAR_ENTRIES[@]}"
  printf 'compose mentions metacubex/mihomo only by name\n' > "$stage/docker-compose.artifact.yml"
  for extra in "$@"; do
    mkdir -p "$stage/$(dirname "${extra%%:*}")"
    printf '%s' "${extra#*:}" > "$stage/${extra%%:*}"
  done
  tar -C "$stage" -czf "$TEST_ROOT/$name.tar.gz" .
  printf '%s' "$TEST_ROOT/$name.tar.gz"
}

expect_ok() {
  "$CHECK" "$@" > "$TEST_ROOT/out.log" 2>&1 || fail_test "expected $* to pass: $(cat "$TEST_ROOT/out.log")"
}

expect_problem() {
  local pattern="$1"
  shift
  if "$CHECK" "$@" > "$TEST_ROOT/out.log" 2>&1; then
    fail_test "expected $* to fail on '${pattern}'"
  fi
  grep -q -- "$pattern" "$TEST_ROOT/out.log" || fail_test "expected '${pattern}' in: $(cat "$TEST_ROOT/out.log")"
}

# A bundle that carries our own entrypoint script, the Compose file and a jar with Mihomo-named Java classes is fine.
good="$(make_bundle good)"
expect_ok "$good"
grep -q 'OK: no Mihomo binary' "$TEST_ROOT/out.log" || fail_test "the check must say what it verified"

# A Mihomo binary, a release asset of it, or a native executable under any name.
expect_problem 'looks like a Mihomo' "$(make_bundle binary 'docker/egress/mihomo:binary')"
expect_problem 'looks like a Mihomo' "$(make_bundle asset 'mihomo-linux-amd64-v1.19.32.gz:gzip')"
expect_problem 'looks like a Mihomo' "$(make_bundle clash 'tools/Clash.Meta:x')"
elf_bundle="$(make_bundle elf "$(printf 'tools/helper:\177ELF....')")"
expect_problem 'native executable' "$elf_bundle"
macho_bundle="$(make_bundle macho "$(printf 'tools/helper:\317\372\355\376....')")"
expect_problem 'native executable' "$macho_bundle"
pe_bundle="$(make_bundle pe 'tools/helper.exe:MZ......')"
expect_problem 'native executable' "$pe_bundle"

# A saved container image, in any of its layouts, or an archive that could hold one.
expect_problem 'saved container image' "$(make_bundle oci 'oci-layout:{}')"
expect_problem 'saved container image' "$(make_bundle blobs 'image/blobs/sha256/abc123:x')"
expect_problem 'saved container image' "$(make_bundle layer 'abc/layer.tar:x')"
expect_problem 'nested archive' "$(make_bundle nested 'images/mihomo-image.tar:x')"

# The license files must be there.
bare="$TEST_ROOT/bare.stage"
mkdir -p "$bare/app"
make_jar "$bare/app/starry-api.jar" "${GOOD_JAR_ENTRIES[@]}"
tar -C "$bare" -czf "$TEST_ROOT/bare.tar.gz" .
expect_problem 'LICENSE is missing' "$TEST_ROOT/bare.tar.gz"
expect_problem 'NOTICE is missing' "$TEST_ROOT/bare.tar.gz"
expect_problem 'THIRD-PARTY-NOTICES.txt is missing' "$TEST_ROOT/bare.tar.gz"
expect_problem 'JAVA-THIRD-PARTY-NOTICES.txt is missing' "$TEST_ROOT/bare.tar.gz"
expect_problem 'licenses/LGPL-2.1.txt is missing' "$TEST_ROOT/bare.tar.gz"
expect_problem 'licenses/logback-NOTICE.txt is missing' "$TEST_ROOT/bare.tar.gz"

# The source code of the libraries used under a copyleft license has to be in the bundle: every one of them.
for source in "${SOURCE_JARS[@]}"; do
  library="${source%%-[0-9]*}"
  without="$TEST_ROOT/without-$library.stage"
  rm -rf "$without"
  cp -R "$TEST_ROOT/good.stage" "$without"
  rm "$without/third-party-sources/${source}-sources.jar"
  tar -C "$without" -czf "$TEST_ROOT/without-$library.tar.gz" .
  expect_problem "third-party-sources/${library}-<version>-sources.jar is missing" "$TEST_ROOT/without-$library.tar.gz"
done
expect_problem 'third-party-sources/logback-classic-<version>-sources.jar is missing' "$TEST_ROOT/bare.tar.gz"
# logback-core's source jar does not stand in for logback-classic's, nor a jar of another version scheme.
without="$TEST_ROOT/lookalike.stage"
rm -rf "$without"
cp -R "$TEST_ROOT/good.stage" "$without"
rm "$without/third-party-sources/logback-classic-1.5.38-sources.jar"
cp "$without/third-party-sources/logback-core-1.5.38-sources.jar" "$without/third-party-sources/logback-classicx-sources.jar"
tar -C "$without" -czf "$TEST_ROOT/lookalike.tar.gz" .
expect_problem 'third-party-sources/logback-classic-<version>-sources.jar is missing' "$TEST_ROOT/lookalike.tar.gz"

# The standalone console archive carries the license and the notice too, and the check looks inside it.
webui_files="$TEST_ROOT/webui-files"
mkdir -p "$webui_files"
printf x > "$webui_files/THIRD-PARTY-NOTICES.txt"
printf x > "$webui_files/index.html"
tar -C "$webui_files" -czf "$TEST_ROOT/webui-without-license.tar.gz" .
printf x > "$webui_files/LICENSE"
printf x > "$webui_files/NOTICE"
tar -C "$webui_files" -czf "$TEST_ROOT/webui-with-license.tar.gz" .
webui_stage="$TEST_ROOT/webui-bundle.stage"
rm -rf "$webui_stage"
cp -R "$TEST_ROOT/good.stage" "$webui_stage"
cp "$TEST_ROOT/webui-with-license.tar.gz" "$webui_stage/starry-api-webui.tar.gz"
tar -C "$webui_stage" -czf "$TEST_ROOT/webui-with-license-bundle.tar.gz" .
expect_ok "$TEST_ROOT/webui-with-license-bundle.tar.gz"
cp "$TEST_ROOT/webui-without-license.tar.gz" "$webui_stage/starry-api-webui.tar.gz"
tar -C "$webui_stage" -czf "$TEST_ROOT/webui-without-license-bundle.tar.gz" .
expect_problem 'starry-api-webui.tar.gz: LICENSE is missing' "$TEST_ROOT/webui-without-license-bundle.tar.gz"
expect_problem 'starry-api-webui.tar.gz: NOTICE is missing' "$TEST_ROOT/webui-without-license-bundle.tar.gz"

# The jar on its own, and the jar inside a bundle.
make_jar "$TEST_ROOT/good.jar" "${GOOD_JAR_ENTRIES[@]}"
expect_ok "$TEST_ROOT/good.jar"
make_jar "$TEST_ROOT/no-notices.jar" BOOT-INF/classes/static/index.html
expect_problem 'META-INF/LICENSE is missing' "$TEST_ROOT/no-notices.jar"
expect_problem 'THIRD-PARTY-NOTICES.txt is missing' "$TEST_ROOT/no-notices.jar"
expect_problem 'META-INF/JAVA-THIRD-PARTY-NOTICES.txt is missing' "$TEST_ROOT/no-notices.jar"
expect_problem 'META-INF/licenses/LGPL-2.1.txt is missing' "$TEST_ROOT/no-notices.jar"
expect_problem 'META-INF/licenses/logback-NOTICE.txt is missing' "$TEST_ROOT/no-notices.jar"
make_jar "$TEST_ROOT/mihomo.jar" "${GOOD_JAR_ENTRIES[@]}" BOOT-INF/classes/mihomo
expect_problem 'looks like a Mihomo' "$TEST_ROOT/mihomo.jar"
stage="$TEST_ROOT/bad-jar.stage"
mkdir -p "$stage/app"
cp "$TEST_ROOT/bare.stage/app/starry-api.jar" "$stage/app/starry-api.jar"
make_jar "$stage/app/starry-api.jar" "${GOOD_JAR_ENTRIES[@]}" BOOT-INF/lib/mihomo-linux-amd64
cp "$TEST_ROOT/good.stage/LICENSE" "$TEST_ROOT/good.stage/NOTICE" "$TEST_ROOT/good.stage/THIRD-PARTY-NOTICES.txt" \
  "$TEST_ROOT/good.stage/JAVA-THIRD-PARTY-NOTICES.txt" "$stage/"
cp -R "$TEST_ROOT/good.stage/licenses" "$TEST_ROOT/good.stage/third-party-sources" "$stage/"
tar -C "$stage" -czf "$TEST_ROOT/bad-jar.tar.gz" .
expect_problem 'bad-jar.tar.gz!app/starry-api.jar: BOOT-INF/lib/mihomo-linux-amd64' "$TEST_ROOT/bad-jar.tar.gz"

# Wrong input.
expect_problem 'no such file' "$TEST_ROOT/missing.tar.gz"
printf x > "$TEST_ROOT/notes.txt"
expect_problem 'expected a .tar.gz bundle or a .jar' "$TEST_ROOT/notes.txt"
if "$CHECK" > /dev/null 2>&1; then
  fail_test "running it with no file must fail"
fi

printf '[check-bundle-test] bundle and jar checks passed\n'
