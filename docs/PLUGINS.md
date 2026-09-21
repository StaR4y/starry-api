# Plugin Developer Mode

Implemented on 2026-09-12. This is an explicitly **full-trust**, in-process developer
plugin system. There is no marketplace, signing requirement, Security Manager,
network sandbox, or automatic inspection of arbitrary plugin behavior.

This version supports a single application instance. Installed metadata and consent
are persistent, but loaded classes, lifecycle serialization, logs and uploaded files
are local to that instance. Do not enable it across replicas sharing a database:
there is no cluster-wide installation, reload or stop coordination.

## Authorization

- Third-party plugins are disabled on a fresh database. No environment variable
  can enable developer mode in place of verification.
- The immutable `application_root_user_id` identifies the initial administrator.
  Other ADMIN accounts and `KIRO_ADMIN_TOKEN` cannot manage plugins.
- Only Root sees the Plugin Development navigation entry or can open its route.
  `GET /admin/plugins` also requires Root and returns `403 PLUGIN_ROOT_REQUIRED`
  to other administrators instead of a sanitized status page. Configuration,
  lifecycle actions, debugging, audits and logs retain their Root checks.
- Enabling requires the root account's **current** password, a six-digit code sent
  exclusively to its bound email, and explicit risk acceptance. The original
  bootstrap plaintext password is neither retained nor reused.
- Codes expire after 10 minutes, can be used once, and are bound to the requesting
  session and root credential/email fingerprint. Resending invalidates the old code.
- Requests have a 60-second send cooldown. Five verification failures lock further
  verification for 15 minutes. Database row locking protects concurrent attempts;
  disabling developer mode does not reset this lockout.
- SMTP failure does not permit activation. Registration-email settings that skip
  verification do not bypass this independent challenge.
- Codes are stored as salted PBKDF2 hashes. Passwords, codes, session credentials,
  configuration values and debug input/output are excluded from lifecycle auditing.

## User Interface

System Settings > Plugin Development (`/#/settings/plugins`) includes:

- Root verification and developer-mode switch.
- JAR upload/replacement, start, stop, reload and removal.
- JSON configuration editor and named debug-command console.
- Running state, version, publisher, SHA-256 and failure category.
- Concrete restart diagnostics (plugin, lifecycle phase, reason and timestamp), with
  start/reload/debug disabled for affected plugins and a stop/cleanup retry action.
- Live log polling, plugin filters, current-record export and paginated audit history.

An installation does **not** execute plugin code. Start is explicit; a successfully
started plugin is marked for startup on the next service boot, only while developer
mode remains enabled. Merely placing a JAR in a directory never installs it.
Replacing the same plugin ID stops the old version and preserves configuration and
data. The replacement stays stopped. Removing a plugin retains its data directory.

Disabling mode revokes new loading, clears automatic-start flags and attempts to stop
loaded plugins after root authorization succeeds. Normal installation, replacement,
start, stop, reload and removal do **not** require a host restart. Same-JVM cleanup
is cooperative, not a security boundary; see the hot-reload contract below.

### Hot Reload and Restart Diagnostics

On reload the host cancels managed tasks, calls `stop()`, revokes the context and its
temporary root sessions, closes registered resources, and waits for managed work to
drain. Only then does it unload the PF4J wrapper/classloader and start a new load with
a fresh configuration snapshot. Shutdown uses the same cleanup path without clearing
successful plugins' automatic-start settings. Replacement retains configuration/data
but stays stopped until explicitly started. Ordinary reload is not zero-downtime:
the plugin is unavailable between stop and successful start.

The post-stop cleanup grace period is 2 seconds, shared by executor termination,
concurrent resource disposal and SDK request termination. Synchronous callbacks may
exceed that grace period: the whole lifecycle/debug operation has a 60-second timeout,
including queue time. A cancelled Future alone is not proof that its task has exited.

`GET /admin/plugins` returns `restartRequired` and root-only `restartReasons`, each
containing `pluginId`, `operation`, `code`, `detail` and `at`:

| Code | Meaning |
| --- | --- |
| `INITIALIZATION_FAILED` | Construction failed before a usable host context existed; unmanaged startup effects are uncertain. |
| `STOP_FAILED` | The plugin's stop callback failed. |
| `RESOURCE_CLOSE_FAILED` | At least one registered resource close failed. |
| `RESOURCES_STILL_CLOSING` | Registered close callbacks have not finished. |
| `TASKS_STILL_RUNNING` | The managed executor did not terminate within the cleanup grace period. |
| `REQUESTS_STILL_ACTIVE` | SDK HTTP subscriptions have not terminated. |
| `CLEANUP_FAILED` / `UNLOAD_FAILED` | Cleanup or PF4J unloading raised an error. |
| `OPERATION_TIMEOUT` / `OPERATION_CANCELLED` | An operation was interrupted after entering plugin code, so complete termination cannot be confirmed. |

These reasons are sticky for the lifetime of the JVM, deduplicated per plugin/code,
and recorded in runtime logs plus durable audit events. Affected plugins report
`RESTART_REQUIRED`; start, reload, replacement and debug are rejected with HTTP 409
`PLUGIN_RESTART_REQUIRED`. Their frontend widget assets are no longer served. Already
executed browser scripts are not retroactively revoked; reload the browser page after
changing widget plugins. Root can
still edit configuration or retry stop/cleanup. Removal requires cleanup to succeed;
neither removal nor a later successful stop clears the historical restart flag.
Restart after investigating and correcting the plugin. Failed/uncertain starts clear
automatic-start flags; no plugin is automatically retried after a startup failure.

A partial `start()` failure that successfully stops and cleans up is just `FAILED`:
fix the configuration or replace the JAR, then start again without rebooting. A timeout
while still queued is audited but creates no false restart reason and never executes
later. A timed-out running callback may ignore interruption, but on returning it cannot
publish a successful start or re-enable automatic startup. Its SDK context is revoked
immediately. The single serialized lifecycle worker can remain occupied by code that
never returns; subsequent queued actions will time out until the host is restarted.

This detects **managed** cleanup failures, not all JVM leaks. Arbitrary host references,
unregistered listeners, raw threads, native code and already-dispatched side effects
cannot be forcibly undone or comprehensively detected. Closing a classloader does not
guarantee immediate JVM class unloading/GC, nor revoke a direct Spring bean reference.
Register resources/tasks through the SDK and make stop callbacks idempotent. Use process
isolation, not stronger claims about PF4J, if hard termination becomes a requirement.

## Emergency Recovery

Set `STARRY_PLUGINS_DISABLED=true` and restart. This is a one-way startup kill switch;
the UI cannot override it. It does not itself erase installed packages or the stored
developer-mode setting. Revoke developer mode through the root API before removing
the startup override if plugins should remain disabled.

`STARRY_PLUGIN_DIR` defaults to `${user.home}/.starry-api/plugins`:

```text
plugins/
  packages/<id>-<sha256>.jar
  data/<id>/
```

Keep this directory on a persistent, service-owned filesystem. Database backups
contain installation metadata, not the plugin JARs/data directory. Back these up
separately. Perform database restore with plugins forcibly disabled and the service
freshly restarted; no sandbox can stop full-trust code holding direct service handles.
Source Compose, the installer and the artifact-publishing template mount
`./data/plugins:/app/plugins`. Existing deployments must add that mount and the
`STARRY_PLUGIN_DIR=/app/plugins` environment variable before installing plugins.

## SDK

SDK 1.1 was added on 2026-09-13. The old Gradle module/directory `api` is now
`service-api`; Java packages and HTTP routes are unchanged. It contains business
contracts and DTOs (including plugin-management DTOs), whereas `plugin-api` contains
only the developer SDK. The two API modules do not depend on one another.

Build the SDK and the plugin currently included by `settings.gradle`:

```bash
./gradlew :service-api:jar :service-api:sourcesJar :plugin-api:jar :plugin-api:sourcesJar :plugin-whale-widget:jar
```

Outputs:

- `plugin-api/build/libs/plugin-api-0.1.0-SNAPSHOT.jar`
- `service-api/build/libs/service-api-0.1.0-SNAPSHOT.jar`
- `../whale-whidget-plugin/build/libs/starry-whale-widget-plugin.jar`

The widget plugin is a separate sibling project referenced by the current local
Gradle settings. The former `plugin-example` / `examples/plugin-demo` module has been
removed; it is not a build target in this worktree.

Previously compiled SDK 1.0 plugins still work on the updated host. Existing context
methods and the `Starry-Plugin-Api: 1` manifest remain unchanged; new interface methods
have defaults. Plugins using the new methods need an SDK 1.1 host. `sdkVersion()` and
`capabilities()` describe the current context, not Maven artifact versions.

Compile against Java 25. Use `compileOnly` for the SDK, PF4J, Reactor and any host API
dependencies. Do not include them in the plugin JAR. Third-party dependencies must
be contained in one ordinary JAR, relocating conflicting libraries where needed.
Boot `BOOT-INF` archives, external manifest `Class-Path`, and cross-plugin dependencies
are not supported in this first version. Maximum upload is 32 MiB, maximum expanded
contents 128 MiB and maximum installed count 100.

Required manifest:

```text
Manifest-Version: 1.0
Plugin-Id: my-plugin
Plugin-Version: 1.0.0
Plugin-Class: example.MyPlugin
Plugin-Provider: Example
Plugin-Description: Example integration
Starry-Plugin-Api: 1
```

Extend `tech.starryapi.plugin.sdk.StarryPlugin`, taking `PluginWrapper` in the public
constructor. The host initializes `context()` before calling `start()`. Constructors
must not use it. Release unmanaged subscriptions, clients, files and executors in
`stop()`, or register them with `manage(...)` for automatic cleanup.

`PluginContext` exposes:

| Method | Contract |
| --- | --- |
| `service(Class<T>)` | Full access to registered host Spring services and implemented API contracts. No business-domain allowlist. Internal services can change between host versions. |
| `service(name, Class<T>)` / `services(Class<T>)` | Select a named Spring bean or discover all implementations as an immutable map. Prefer contracts from `service-api` when available. |
| `request(method, path, headers, bytes)` | Calls an existing same-host HTTP API with a short-lived root session. Existing endpoint validation, billing and additional verification still apply. Explicit Authorization can supply a normal proxy API key instead. |
| `requestStream(method, path, headers, bytes)` | Emits one `PluginHttpEvent.Head`, followed by raw `Data` chunks, without buffering the complete response. Empty and non-2xx responses preserve their HTTP status. |
| `configJson()` | Immutable configuration snapshot for this load; reload to apply edits. |
| `config(Class<T>)` / `json()` | Decode configuration into a record/DTO and convert JSON without exposing a mutable host mapper. Unknown DTO fields are ignored; malformed/trailing JSON is rejected; input/output is limited to 64 KiB of UTF-8. |
| `dataDirectory()` | Plugin-specific persistent data directory. |
| `manage(AutoCloseable)` | Register a resource, returning a disposable handle that closes it exactly once. Reactive subscriptions can register `subscription::dispose`. |
| `tasks()` | Named one-shot and fixed-delay tasks on a lazily created per-plugin worker. |
| `sdkVersion()` / `capabilities()` | SDK 1.1 advertises `json`, `service-discovery`, `managed-resources`, `managed-tasks` and `http-stream`. |
| `log(level, message)` | Tagged runtime logs with bounded UI buffering and common-secret redaction. |

The HTTP helper rejects external URLs and omits sensitive content from logs. It
buffers up to 8 MiB of response bytes and has a 60-second total response timeout in
`request`. `requestStream` has a 60-second idle timeout between emitted signals, not
a total-duration or total-body-size limit. Apply backpressure and process chunks
incrementally; chunk boundaries are neither SSE message boundaries nor UTF-8 character
boundaries. Slow downstream consumption can also trigger the idle timeout. Both helpers
revoke their temporary root session on completion, cancellation, failure and timeout.
Context revocation cancels in-flight calls and reports an error, not successful partial
output. Direct service/client calls do not automatically acquire this audit/cleanup
coverage, and ordinary endpoint authorization/billing/verification is not bypassed.

### Managed Lifecycle

- `manage` allows at most 256 live resources per plugin load. Disposal removes a
  registration; cleanup runs in reverse registration order. Rejected registrations
  close the offered resource immediately, including races with unload.
- `tasks().schedule(name, delay, callback)` runs once. `tasks().repeat(name, initial,
  delay, callback)` measures delay from completion, so recurring calls do not overlap.
  At most 64 tasks may be registered per plugin load. Names use letters, digits,
  underscore, dot or hyphen, with a maximum length of 64.
- Task workers are created only when used, with one daemon thread per plugin. They
  are independent of Netty event threads and the serialized plugin lifecycle worker.
  Tasks are cancelled before `stop`, and registered resources close afterwards, even
  if `stop` throws. Failed startup cleans resources and cancels tasks too.
- Cancellation interrupts cooperative tasks but cannot kill code that ignores
  interruption. Resource cleanup callbacks may also hang; restart remains the final
  recovery mechanism. Keeping raw host-service references remains full-trust access.
- Tasks are not persistent jobs. Re-register them in `start` after reload or restart;
  changing JSON configuration still requires reload. During developer-mode revocation,
  `stop` should use already-owned handles and `log`, not request new context services.
- Task and resource-cleanup failures include exception categories and stack frames,
  never arbitrary exception messages. Recurring tasks continue after an ordinary
  callback failure. Lifecycle DEBUG logs identify task scheduling/cancellation.

### Usage

Compile against the APIs without bundling them:

```groovy
dependencies {
    compileOnly project(':plugin-api')
    compileOnly project(':service-api') // Only when using host business contracts.
}
```

For example, inside a `StarryPlugin` implementation:

```java
public record Settings(int heartbeatSeconds) {}

@Override public void start() {
    var settings = context().config(Settings.class);
    context().manage(() -> context().log(PluginContext.Level.INFO, "Resources closed"));
    if (settings.heartbeatSeconds() > 0) {
        context().tasks().repeat("heartbeat", Duration.ZERO,
            Duration.ofSeconds(settings.heartbeatSeconds()),
            () -> context().log(PluginContext.Level.DEBUG, "Heartbeat"));
    }
}
```

This example needs the standard `java.time.Duration` and SDK imports. The currently
included buildable plugin lives in `../whale-whidget-plugin`.

`debug(command, json)` is an optional plugin-defined synchronous operation with JSON
input/output (64 KiB maximum). Lifecycle and debug callbacks run outside Netty event
threads on a serialized bounded scheduler. Never block Reactor/Netty event threads in
plugin callbacks scheduled by your own code.

The current widget plugin implements `health` and `capabilities`; the latter describes
its frontend assets and data APIs. Managed tasks, service discovery and streaming HTTP
are covered by the host/SDK tests rather than this plugin's debug commands.

## Logs and Persistence

- Flyway V16 creates `plugin_security_state`, `installed_plugins` and `plugin_audit`.
- Verification, mode changes, install, lifecycle, configuration and debug operations
  have durable audit events. Failed plugin callbacks record the exception category,
  not arbitrary exception messages that might contain secrets. Lifecycle and debug
  failures also record up to 12 stack frames in the runtime log for diagnosis.
- Package SHA-256 detects accidental on-disk replacement; it is **not a signature**.
- Runtime logs use `tech.starryapi.plugins`, tag plugin IDs, and keep at most 2,000
  entries in memory (500 per HTTP response). Persist server logs using deployment
  logging. DEBUG output follows the server logger level.
- Lifecycle audits retain 90 days. Hourly cleanup removes at most 10,000 old rows
  using a narrowly scoped database function. The runtime database role has no direct
  UPDATE/DELETE permission on the audit table.
- This is not comprehensive network monitoring, nor tamper-proof auditing against a
  malicious same-process plugin. A plugin may log secrets in arbitrary forms or bypass
  the helper entirely. Do not grant full trust to code you do not trust.

## HTTP Endpoints

All paths are under `/admin/plugins`. Management requires a normal root session.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/` | State and installed packages; other admins only see root-required state |
| POST | `/verification` | Password check and root email challenge |
| POST | `/developer-mode` | Verify password/code/risk acceptance and enable |
| DELETE | `/developer-mode` | Disable, invalidate challenge and attempt stop |
| POST | `/` | Multipart `file` JAR upload |
| POST | `/{id}/actions/{action}` | `start`, `stop`, `reload`, `remove` |
| GET / PUT | `/{id}/config` | Read/write JSON config |
| POST | `/{id}/debug` | Execute `command` with JSON string `json` |
| GET | `/logs?plugin=&after=0` | Bounded incremental runtime logs |
| GET | `/audit?plugin=&before=0` | 100-row audit cursor page |

## Verification

Hot-reload validation on 2026-09-13 (current worktree):

- Full Gradle run: 819 discovered, 723 passed, 96 environment-dependent tests skipped,
  zero failures/errors. All 86 plugin host/SDK tests passed, including the 12 opt-in
  real PostgreSQL verification cases. The existing skipped tests are not counted as
  successful tests.
- Added real PF4J JAR regression cases for fresh classloaders on reload/replacement,
  configuration preservation, clean partial-start recovery, sticky stop/close failure
  diagnostics, cleanup retries, widget-asset denial, interrupt-ignoring tasks, running
  timeouts/cancellation and queued-timeout non-execution.
- Added HTTP subscription-drain and resource-disposal reporting checks, root-only
  diagnostic visibility, and a regression ensuring mode cleanup cannot execute before
  the authorization publisher is subscribed successfully.
- WebUI type checking, production build and targeted ESLint passed. Both API source
  JARs, the current sibling widget plugin and the executable server build successfully.
- Disposable real-browser flow passed with root password plus SMTP verification,
  current widget JAR install/start/configuration/reload/debug/assets, logs/audit and
  mode revocation. Healthy reload and disable both leave `restartRequired=false` and
  an empty reason list. Two clearly identified mocked diagnostic views verify blocked
  buttons and warning layout. All 13 desktop/tablet/mobile screenshots had no page
  errors or horizontal overflow. Developer mode was disabled again after testing.
- Existing services on 8080/5173 were untouched; only the disposable preview on
  8082/5175 was restarted. Docker execution remains unverified because Docker is not
  available locally. Hard JVM/class-GC reclamation is not claimed by these tests.

Earlier SDK 1.1 validation on 2026-09-13 (before removal of the demo module):

- Full Gradle run across modules: 797 discovered, 701 passed, 96 environment-dependent
  tests skipped, no failures or errors. The plugin runtime and SDK account for 73
  passing tests, including all 12 real PostgreSQL verification cases.
- Added tests for source-compatible legacy contexts, immutable stream metadata and
  buffers, typed JSON and UTF-8 limits, named services, task limits/cancellation,
  resource-close races and failure isolation, and cleanup across real PF4J
  stop/reload/remove/disable/start-failure/stop-failure callbacks.
- Real Netty HTTP tests verify early streaming delivery, a 9 MiB response without
  aggregation, empty/non-2xx responses, explicit API-key authorization, subscriber
  cancellation, idle timeout, synchronous request-construction failure and revocation
  of both streaming and buffered calls. These tests caught and fixed a revocation
  path that signalled an error without cancelling the upstream subscription.
- Both API source JARs, the SDK 1.1 example and the executable server build successfully.
  The executable includes `service-api` and `plugin-api`, not the old `api` JAR.
  Docker image execution remains unverified because Docker is unavailable locally.
- The updated real-browser harness also passed against the rebuilt executable and
  example JAR: typed configuration, heartbeat execution, model-contract access and
  HTTP streaming were exercised through the plugin debug API. All 11 viewport
  screenshots completed without page errors/overflow; developer mode was disabled
  again after the run. Existing 8080/5173 services were not restarted.

Unit tests cover archive validation without class loading, lifecycle, configuration,
debug/reload/removal, startup failure, disabled-mode gating, checksum validation,
stop callbacks, log redaction, cursor behavior and buffer limits.

Validation on 2026-09-12:

- Full backend run: 759 discovered, 663 passed, 96 skipped, no failures or errors.
  Skips are existing Docker-dependent PostgreSQL suites and the separate opt-in
  Coding Plan database suite; they are not counted as successful tests.
- Plugin-specific coverage: all 39 passed, including 12 real PostgreSQL integration
  cases. Also covers temporary root-session cleanup after synchronous HTTP request
  construction failure, and debug stack traces without recording debug input.
- WebUI type checking and production build passed; targeted ESLint passed.
- Real browser smoke: root password plus SMTP code, JAR install/start/reload/config,
  debug and host API calls, logs/audit, and mode revocation; 11 screenshots at desktop,
  390px mobile and 768px tablet sizes, no page errors or horizontal overflow.
- Installer rollback/bundle/fallback tests passed. Source and generated Compose
  definitions passed structured YAML checks for plugin persistence and the kill
  switch; temporary release-bundle checksums passed. Docker is unavailable locally,
  so a container image build/launch was not executed.

The disposable browser harness is `webui/scripts/plugin-browser-smoke.cjs`. It uses
Chrome/Playwright, a loopback-only SMTP fixture, a dedicated backend on 8082 and Vite
on 5175. `scripts/start-plugin-preview.cjs` refuses to launch unless explicitly opted
in and pointed at a disposable PostgreSQL cluster on 55438; it does not alter existing
8080/5173 services. These local scripts are test utilities, not production launchers.

The opt-in PostgreSQL suite uses a **disposable database** whose name starts with
`starry_plugin_test`. It covers row-lock concurrency, both authentication factors,
expiration, replay, resend, identity changes, mail failure and lockout persistence:

```bash
PLUGIN_DATABASE_TESTS=true POSTGRES_DATABASE=starry_plugin_test_local \
  ./gradlew :service:test --tests 'tech.starryapi.plugin.*' --rerun-tasks
```

Set the usual `POSTGRES_*` connection variables and initialize the project's owner,
migration and runtime roles/schema first. Never point this suite at a live database.
