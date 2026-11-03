# Security Policy

Starry API stores upstream credentials, API keys, balances and payment records, and it can run full-trust plugins, so we want to hear about security problems and to handle them privately first.

## Reporting a vulnerability

Please report it through **GitHub private vulnerability reporting**: open the repository's **Security** tab and choose **Report a vulnerability** (https://github.com/StaR4y/starry-api/security/advisories/new). Only the maintainers can see the report.

- **Do not** open a public issue, pull request or discussion for a vulnerability, and do not post it anywhere else before it is fixed.
- Say what you found and what it allows, which version or commit and which deployment (installer, Docker Compose, plain jar) you tested, and how to reproduce it. A minimal proof of concept helps.
- **Never include real credentials.** Use a throwaway deployment and test keys. If a real API key, token or password was exposed while you were investigating, tell us so that it can be revoked.
- Test only against deployments you own or are allowed to test. Do not read, change or delete other people's data.

This is a volunteer project. We aim to acknowledge a report within 7 days and to tell you what we decided, or when a fix will be released, within 30 days. These are goals, not guarantees. We will credit you in the release notes if you want us to.

## Supported versions

Security fixes go into the latest release and the `main` branch. Older releases are not patched; upgrade to the latest release (see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for upgrading and rolling back).

## In scope

- The service: sign-in, sessions, roles and the Root account, API keys, rate limiting, billing, balances, redemption codes and payments, the admin API, data management (backup, export, restore and its lock), the proxy endpoints (`/v1/...`), and how upstream credentials are stored and sent.
- The web console: cross-site scripting, request forgery and anything that exposes credentials to the browser.
- The installer, the update and recovery scripts, the Docker Compose files and their defaults, when they leave a deployment less safe than the documentation says.
- Documentation that tells operators to do something unsafe.

## Out of scope

- Vulnerabilities in third-party dependencies. Report those to the dependency. If a dependency version in our lockfiles is exploitable through Starry API, that is in scope; tell us.
- Findings that need an attacker who already is the Root administrator, or who already controls the server, the database or the container.
- Plugins an administrator chose to install (see below), and any harm done by an upstream provider to an account you connected to this software. Whether a configuration is allowed by a provider's terms is your responsibility (see the Disclaimer in the [README](README.md)); it is not a vulnerability.
- Denial of service that needs more traffic than the documented limits, missing hardening headers without a demonstrated impact, and reports produced by scanners without a working proof.

## Plugins and the trust model

Plugins are full-trust code. A plugin runs inside the service process and can read the database, the files and the network that the service can. There is no sandbox, no permission boundary, no signature requirement and no plugin marketplace. Read [docs/PLUGINS.md](docs/PLUGINS.md) before you install one, and run only code you trust.

Because of that:

- A malicious or buggy plugin that a Root administrator installed and enabled is **not** a vulnerability in Starry API.
- Anything that lets a plugin load, start or stay enabled **without** the documented gate is a vulnerability: developer mode is off on a fresh database, turning it on needs the Root password and an email verification code, and `STARRY_PLUGINS_DISABLED=true` must stop all plugin loading.
- A way for a user without the Root role to install, start or change a plugin, or to read what a plugin stores, is a vulnerability.

## Operating your own deployment safely

Set strong passwords in `.env`, keep `STARRY_ALLOW_ANONYMOUS_PROXY=false` on any public service, put TLS in front of it, restrict who can reach the database and the `.env` file, and keep the service updated. [docs/CONFIGURATION.md](docs/CONFIGURATION.md) and [docs/OPERATIONS.md](docs/OPERATIONS.md) describe each setting.

## 中文摘要

发现安全漏洞时，请使用 GitHub 的**私密漏洞报告**（仓库 **Security** 页签 → **Report a vulnerability**），**不要**开公开的 issue。报告里请写清影响、版本和部署方式、复现步骤，**不要附带真实的密钥或密码**。这是志愿者维护的项目，我们的目标是 7 天内确认收到、30 天内给出处理结论或修复时间，但这不是承诺。只有最新发布版和 `main` 分支会收到安全修复。插件是「完全信任」的进程内代码：Root 管理员自己装的恶意插件不算本项目的漏洞，但绕过开发者模式（Root 密码 + 邮箱验证）或 `STARRY_PLUGINS_DISABLED` 去加载插件算。
