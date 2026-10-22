#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  docker build -q -f docker/egress/Dockerfile -t starry-egress-test:local .
fi
runtime_id="$(docker run -d --rm --entrypoint /bin/sh \
  -e STARRY_EGRESS_CONTROLLER_SECRET=test-controller-secret \
  -v "$ROOT_DIR/docker/egress/mihomo-entrypoint.sh:/entrypoint.sh:ro" \
  metacubex/mihomo:v1.19.32 /entrypoint.sh)"
trap 'docker stop "$runtime_id" >/dev/null' EXIT
docker run --rm --network "container:$runtime_id" -e TEST_MIHOMO=true \
  -v "$ROOT_DIR/docker/egress:/tests:ro" -w /tests \
  starry-egress-test:local python -m unittest -v test_bridge
