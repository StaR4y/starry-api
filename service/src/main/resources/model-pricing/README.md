# Bundled model pricing snapshot

`model_prices_and_context_window.json` is copied from
[`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) and is used as the
offline startup snapshot for official USD token prices.

- Source commit: `5b357a2cfd68980c7ebdd631e941b9d927b1a93f`
- Source timestamp: `2026-08-16T01:21:03Z`
- SHA-256: `ae8eb08c1524ebf82fb020a951a7d4a99d62d0c2c8b7d008f76b01bf261123c1`
- License: MIT

At runtime the service periodically downloads the current JSON and checksum from the same
repository. A failed download or checksum validation leaves the last verified catalog active.
