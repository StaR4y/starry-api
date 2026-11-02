#!/usr/bin/env bash
# Tests scripts/check-release-contents.sh with small made-up jars and images. A stand-in for docker serves an image's files
# from a directory, so no daemon is needed.
set -euo pipefail
export COPYFILE_DISABLE=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="${ROOT_DIR}/scripts/check-release-contents.sh"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/starry-api-release-check-test.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

fail_test() {
  printf '[check-release-test] ERROR: %s\n' "$*" >&2
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

# The stand-in: "create IMAGE" answers with the image name as the container id, and an image is the directory
# $FAKE_IMAGES/IMAGE holding the root file system.
FAKE_BIN="$TEST_ROOT/bin"
export FAKE_IMAGES="$TEST_ROOT/images"
mkdir -p "$FAKE_BIN" "$FAKE_IMAGES"
cat > "$FAKE_BIN/docker" <<'SH'
#!/usr/bin/env bash
case "$1" in
  create) [[ -d "$FAKE_IMAGES/$2" ]] || exit 1; printf '%s\n' "$2" ;;
  cp)
    id="${2%%:*}"
    [[ -d "$FAKE_IMAGES/$id/app" ]] || exit 1
    tar -C "$FAKE_IMAGES/$id" -cf - app
    ;;
  export) tar -C "$FAKE_IMAGES/$2" -cf - . ;;
  rm) ;;
  *) exit 2 ;;
esac
SH
chmod 0755 "$FAKE_BIN/docker"

# make_image NAME [EXTRA_FILE...]: an image with everything a release has, plus extra files (path:content), paths from /.
make_image() {
  local name="$1"
  shift
  local root="$FAKE_IMAGES/$name"
  local extra source
  mkdir -p "$root/app/bin" "$root/app/licenses" "$root/app/third-party-sources" "$root/usr/lib"
  printf 'license\n' > "$root/app/LICENSE"
  printf 'notice\n' > "$root/app/NOTICE"
  printf 'lgpl\n' > "$root/app/licenses/LGPL-2.1.txt"
  printf 'logback\n' > "$root/app/licenses/logback-NOTICE.txt"
  printf '#!/bin/sh\n' > "$root/app/bin/recover-data-management"
  for source in "${SOURCE_JARS[@]}"; do
    make_jar "$root/app/third-party-sources/${source}-sources.jar" "${source%%-[0-9]*}/Source.java"
  done
  make_jar "$root/app/app.jar" "${GOOD_JAR_ENTRIES[@]}"
  # The base image's own files, which are not ours to judge: a native binary and an archive outside /app.
  printf '\177ELF....' > "$root/usr/lib/libc.so"
  printf 'x' > "$root/usr/lib/fonts.tar.gz"
  for extra in "$@"; do
    mkdir -p "$root/$(dirname "${extra%%:*}")"
    printf '%s' "${extra#*:}" > "$root/${extra%%:*}"
  done
  printf '%s' "$name"
}

run_check() {
  PATH="$FAKE_BIN:$PATH" "$CHECK" "$@"
}

expect_ok() {
  run_check "$@" > "$TEST_ROOT/out.log" 2>&1 || fail_test "expected $* to pass: $(cat "$TEST_ROOT/out.log")"
}

expect_problem() {
  local pattern="$1"
  shift
  if run_check "$@" > "$TEST_ROOT/out.log" 2>&1; then
    fail_test "expected $* to fail on '${pattern}'"
  fi
  grep -q -- "$pattern" "$TEST_ROOT/out.log" || fail_test "expected '${pattern}' in: $(cat "$TEST_ROOT/out.log")"
}

# A complete image is fine: Mihomo-named Java classes in the jar are ours, and so is the base image's own native code.
expect_ok --image "$(make_image good)"
grep -q 'OK: no Mihomo binary' "$TEST_ROOT/out.log" || fail_test "the check must say what it verified"

# A Mihomo binary, a release asset of it, or a native executable under any name.
expect_problem 'looks like a Mihomo' --image "$(make_image binary 'app/bin/mihomo:binary')"
expect_problem 'looks like a Mihomo' --image "$(make_image asset 'app/mihomo-linux-amd64-v1.19.32.gz:gzip')"
expect_problem 'looks like a Mihomo' --image "$(make_image clash 'app/tools/Clash.Meta:x')"
expect_problem 'looks like a Mihomo binary' --image "$(make_image elsewhere 'usr/local/bin/mihomo:x')"
expect_problem 'native executable' --image "$(make_image elf "$(printf 'app/tools/helper:\177ELF....')")"
expect_problem 'native executable' --image "$(make_image macho "$(printf 'app/tools/helper:\317\372\355\376....')")"
expect_problem 'native executable' --image "$(make_image pe 'app/tools/helper.exe:MZ......')"

# A saved container image, in any of its layouts, or an archive that could hold one.
expect_problem 'saved container image' --image "$(make_image oci 'app/oci-layout:{}')"
expect_problem 'saved container image' --image "$(make_image blobs 'app/image/blobs/sha256/abc123:x')"
expect_problem 'saved container image' --image "$(make_image layer 'app/abc/layer.tar:x')"
expect_problem 'nested archive' --image "$(make_image nested 'app/images/mihomo-image.tar:x')"

# The license files must be there.
bare="$FAKE_IMAGES/bare"
mkdir -p "$bare/app"
make_jar "$bare/app/app.jar" "${GOOD_JAR_ENTRIES[@]}"
expect_problem 'LICENSE is missing' --image bare
expect_problem 'NOTICE is missing' --image bare
expect_problem 'licenses/LGPL-2.1.txt is missing' --image bare
expect_problem 'licenses/logback-NOTICE.txt is missing' --image bare
expect_problem 'bin/recover-data-management is missing' --image bare

# The source code of the libraries used under a copyleft license has to be in the image: every one of them.
for source in "${SOURCE_JARS[@]}"; do
  library="${source%%-[0-9]*}"
  rm -rf "${FAKE_IMAGES:?}/without"
  cp -R "$FAKE_IMAGES/good" "$FAKE_IMAGES/without"
  rm "$FAKE_IMAGES/without/app/third-party-sources/${source}-sources.jar"
  expect_problem "third-party-sources/${library}-<version>-sources.jar is missing" --image without
done
# logback-core's source jar does not stand in for logback-classic's, nor a jar of another version scheme.
rm -rf "${FAKE_IMAGES:?}/lookalike"
cp -R "$FAKE_IMAGES/good" "$FAKE_IMAGES/lookalike"
rm "$FAKE_IMAGES/lookalike/app/third-party-sources/logback-classic-1.5.38-sources.jar"
cp "$FAKE_IMAGES/lookalike/app/third-party-sources/logback-core-1.5.38-sources.jar" \
  "$FAKE_IMAGES/lookalike/app/third-party-sources/logback-classicx-sources.jar"
expect_problem 'third-party-sources/logback-classic-<version>-sources.jar is missing' --image lookalike

# The jar inside an image.
rm -rf "${FAKE_IMAGES:?}/bad-jar"
cp -R "$FAKE_IMAGES/good" "$FAKE_IMAGES/bad-jar"
make_jar "$FAKE_IMAGES/bad-jar/app/app.jar" "${GOOD_JAR_ENTRIES[@]}" BOOT-INF/lib/mihomo-linux-amd64
expect_problem 'bad-jar!/app/app.jar: BOOT-INF/lib/mihomo-linux-amd64' --image bad-jar

# The jar on its own.
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

# Wrong input.
expect_problem 'no such file' "$TEST_ROOT/missing.jar"
expect_problem 'could not create a container' --image no-such-image
printf x > "$TEST_ROOT/notes.txt"
expect_problem 'expected --image IMAGE or a .jar' "$TEST_ROOT/notes.txt"
if "$CHECK" > /dev/null 2>&1; then
  fail_test "running it with no argument must fail"
fi

printf '[check-release-test] image and jar checks passed\n'
