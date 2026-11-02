## What and why

<!-- What does this change, and why? Link the issue it closes: "Closes #123". -->

## How it was tested

<!-- Commands you ran and what you saw. For console changes, say which pages you opened. -->

## Checklist

- [ ] The change is focused, and the commit messages follow Conventional Commits.
- [ ] Tests cover the behavior change, and they ran **with Docker available** (PostgreSQL tests skip silently without it).
- [ ] `npm --prefix webui run test:i18n` passes: every new Chinese UI string has an English entry in `webui/src/i18n/en.json`.
- [ ] Documentation in `docs/` matches the new behavior.
- [ ] No Flyway migration that was already released is edited; schema changes are a new migration.
- [ ] No API keys, tokens, passwords, cookies or real e-mail addresses are in the code, tests, fixtures, logs or screenshots.
- [ ] Anything copied from another project is named here, and its license allows it.
- [ ] I license this contribution under AGPL-3.0-only together with the additional permission for plugins in section 2 of NOTICE (Apache-2.0 for changes to `plugin-api/`), as described in CONTRIBUTING.md.
