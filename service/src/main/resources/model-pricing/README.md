# Bundled model pricing snapshot

`model_prices_and_context_window.json` is copied from
[`Wei-Shaw/model-price-repo`](https://github.com/Wei-Shaw/model-price-repo) and is used as the
offline startup snapshot of vendor list prices (USD per token) from that community-maintained catalog. The
catalog is not published by the vendors and does not guarantee that a price is current.

- SHA-256 of `model_prices_and_context_window.json` as shipped here: `7d216ca09f4f4c18f158cbdd4e124ae92d8e69abbbe33346a89141ab1f5e9a16`
- Source commit: `5b357a2cfd68980c7ebdd631e941b9d927b1a93f` (recorded earlier; not yet checked against the upstream
  repository, because the SHA-256 recorded with it did not match this file, so the file may be a later or edited copy)
- Source timestamp: `2026-08-16T01:21:03Z` (same caveat)
- License: MIT, as stated by the upstream repository (credited in the top-level `NOTICE`). Its copyright notice and
  license text still have to be fetched from the upstream repository and added here as `LICENSE`.

At runtime the service periodically downloads the current JSON and checksum from the same
repository. A failed download or checksum validation leaves the last verified catalog active.
