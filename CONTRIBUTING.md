# Contributing to Starry API

Thanks for wanting to help. Bug reports, documentation fixes and pull requests are all welcome. For anything bigger than a small fix, please open an issue first so that we can agree on the approach before you spend time on it.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security problems do not go into public issues; see [SECURITY.md](SECURITY.md).

## What you need

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 25 | The Gradle build uses a Java 25 toolchain (`options.release = 25`). |
| Node.js and npm | 22 | Builds and tests the Vue 3 console in `webui/` (CI uses Node 22). |
| Docker (Colima on macOS works) | any recent | The PostgreSQL tests start PostgreSQL 17 with Testcontainers. |

**Docker matters more than it looks.** The PostgreSQL integration tests (`tech.starryapi.postgresql.*`, which cover the schema, billing, allocation and the admin command) are skipped **silently** when no Docker daemon is reachable, and the rest of the suite still passes. A green run without Docker did not run them. CI checks that they ran (the reports under `service/build/test-results/test/` must show `skipped="0"`), and you should too. With Colima on macOS:

```bash
colima start
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

`TEST_POSTGRES_IMAGE` (default `postgres:17-alpine`) selects the image; CI also runs the PostgreSQL tests on `postgres:18-alpine`.

## Building and testing

```bash
./gradlew test                                   # all backend tests
./gradlew :service:test --tests 'tech.starryapi.admin.*' -PskipWebuiBuild=true   # a part of them, without building the console
npm --prefix webui ci                            # install the console's dependencies once
npm --prefix webui run build                     # type-check and build the console
npm --prefix webui run lint
npm --prefix webui run test:i18n                 # every Chinese UI string needs an English entry
npm --prefix webui run test:documentation
npm --prefix webui run test:source-tiers
npm --prefix webui run test:legal                # the license footer and THIRD-PARTY-NOTICES.txt
./scripts/test-install.sh                        # when you touch scripts/install.sh
```

The test JVMs get a throwaway home directory, so running the tests never touches your own `~/.starry-api`. [docs/PACKAGE_GUIDE.md](docs/PACKAGE_GUIDE.md) explains where code belongs and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) the request flow.

## Conventions

- **Commits follow [Conventional Commits](https://www.conventionalcommits.org/)**: `feat(scope): ...`, `fix(scope): ...`, `docs: ...`, `refactor: ...`, `test: ...`, `chore: ...`. Use the imperative mood, keep the subject short, and explain *why* in the body when it is not obvious. One logical change per commit.
- **Never edit a Flyway migration that has been released.** Flyway stores a checksum for every applied migration, so changing `service/src/main/resources/db/migration/V*__*.sql` breaks every existing deployment at startup. Add a new migration with the next version number instead.
- **The console is bilingual.** Chinese source strings are the translation keys, and the English text lives in `webui/src/i18n/en.json`. Add both, and run `test:i18n`.
- **Documents are Chinese-first** and live in `docs/`. Change the documents together with the behavior they describe, and keep commands copy-and-paste ready.
- **Write tests for behavior changes.** Test fixtures must not contain real API keys, tokens, cookies or e-mail addresses.
- Keep changes focused. Do not reformat files you are not changing.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make the change, with tests and documentation.
3. Run the checks above that apply to it, with Docker running.
4. Open the pull request and fill in the template. CI must pass.

## Licensing of contributions

Starry API is licensed under the [GNU Affero General Public License, version 3 only](LICENSE) (`AGPL-3.0-only`) together with the additional permission for plugins under section 7 of that license, which is section 2 of [NOTICE](NOTICE); `plugin-api/` is Apache-2.0. **By submitting a contribution you license it on those terms**: a contribution outside `plugin-api/` under `AGPL-3.0-only` together with the additional permission in section 2 of NOTICE, and a change to `plugin-api/` under the Apache License 2.0. The permission is part of the promise to plugin authors, so a contribution cannot be accepted without it. There is no contributor license agreement. You confirm that you wrote the contribution or have the right to submit it on those terms.

Do not copy code, prompts, documentation or data from other projects unless its license allows it. If you do reuse something, say where it comes from in the pull request, and keep its notices. New dependencies must have a license compatible with AGPL-3.0; mention it in the pull request.

## 中文说明

欢迎提 issue 和 Pull Request。较大的改动请先开 issue 讨论。需要 JDK 25、Node.js 22，以及一个**正在运行的 Docker**（macOS 可用 Colima）：PostgreSQL 集成测试在没有 Docker 时会**静默跳过**，其余测试照样通过，所以请确认它们真的跑了（报告里 `skipped="0"`）。提交信息使用 Conventional Commits（`feat(...)`、`fix(...)`、`docs: ...`）。**已发布的 Flyway 迁移文件不能修改**，请新增更高版本号的迁移。控制台的中文源字符串是翻译键，英文写在 `webui/src/i18n/en.json`。你提交的贡献按 AGPL-3.0-only 授权，并附带 NOTICE 第 2 节的插件附加许可（AGPL 第 7 条下的附加许可）；`plugin-api/` 的改动按 Apache-2.0。没有额外的贡献者协议。
