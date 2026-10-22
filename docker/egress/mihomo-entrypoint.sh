#!/bin/sh
set -eu
test -n "${STARRY_EGRESS_CONTROLLER_SECRET:-}" || { echo 'Controller secret is required' >&2; exit 1; }
case "$STARRY_EGRESS_CONTROLLER_SECRET" in *[!A-Za-z0-9_-]*) echo 'Use an alphanumeric controller secret' >&2; exit 1;; esac
umask 077
printf 'external-controller: 127.0.0.1:9090\nsecret: "%s"\nlog-level: silent\nmode: rule\nrules: ["MATCH,REJECT"]\n' "$STARRY_EGRESS_CONTROLLER_SECRET" > /tmp/config.yaml
exec /mihomo -d /tmp/mihomo -f /tmp/config.yaml
