# 预构建产物部署

Starry API 支持两种部署方式：**源码构建**（在服务器上用 Docker 构建镜像）和**预构建产物**（服务器只下载仓库里打好的包并运行）。

这份文档讲预构建产物的**发布流程**（给开发者 / CI）和**服务器部署**（给运维）。如果你只想把服务装起来，请直接看 [服务器一键安装](INSTALL_SERVER.md)。

## 两个脚本，两个角色

| 脚本 | 在哪里运行 | 作用 |
| --- | --- | --- |
| `scripts/publish-artifact.sh` | **开发机或 CI** | 构建控制台和 boot jar，把完整的部署包写到 `artifacts/` 目录 |
| `scripts/install-server.sh` | **Linux 服务器** | 下载 `artifacts/starry-api-bundle.tar.gz`，校验 SHA256，解压，启动 Docker Compose——不跑 Gradle / npm |

> 🚫 **服务器上不要运行 `publish-artifact.sh`。**

## 产物目录

仓库根目录的 `artifacts/` 存放可部署的产物：

| 文件 | 用途 |
| --- | --- |
| `artifacts/starry-api-bundle.tar.gz` | 完整的服务器部署包 |
| `artifacts/starry-api-bundle.tar.gz.sha256` | 服务器脚本用的校验和 |
| `artifacts/starry-api.jar` | 可执行的 Spring Boot jar（内嵌控制台） |
| `artifacts/starry-api.jar.sha256` | jar 的校验和（也包含在整包里） |
| `artifacts/starry-api-webui.tar.gz` | 单独的控制台静态文件包，用于检查或 CDN（带 `LICENSE` 和 `NOTICE`） |
| `artifacts/third-party-sources/*-sources.jar` | 按 copyleft 许可证使用的 5 个 Java 库（logback-classic、logback-core、jakarta.annotation-api、jakarta.mail-api、angus-mail）的源码 jar，和 jar、整包放在同一个位置 |
| `artifacts/docker-compose.artifact.yml` | 用 JRE 镜像运行 jar 的 Compose 文件 |
| `artifacts/runtime/recover-data-management.sh` | 双语交互恢复工具，会被复制进服务镜像的 `/app/bin/recover-data-management` |
| `artifacts/runtime/recover-data-management.sh.sha256` | 恢复工具校验和，安装时会校验 |
| `artifacts/starry-api.sh` | `starry-api` 管理命令（登录信息、重置 Root 密码），安装为 `${INSTALL_DIR}/starry-api` 并链接到 `/usr/local/bin/starry-api` |
| `artifacts/starry-api.sh.sha256` | 管理命令校验和，安装时会校验 |
| `artifacts/sha256sums.txt` | 所有可部署文件的校验和清单 |
| `artifacts/build-info.json` | 源码提交、分支、版本、构建时间、大小、校验和等元数据 |

整包还带有 `docker/egress/mihomo-entrypoint.sh`（可选 Mihomo 组件的启动脚本）、实验性的 `docker-compose.tls-bridge.yml`（TLS 指纹 bridge）和 `docs/ACCOUNT_EGRESS.md`。Compose 文件里的 `mihomo` 服务属于 `mihomo` profile：一键安装脚本在全新安装时默认选中它（空闲，由管理员在控制台打开），手动部署时 `.env` 没有 `COMPOSE_PROFILES=mihomo` 就不启动；整包不含 Mihomo 二进制，部署时拉取官方镜像。

整包根目录还带有 `LICENSE`（AGPL-3.0-only）、`NOTICE`（依赖的许可证选择、源码和许可证文本在哪里、数据来源、商标声明）、`THIRD-PARTY-NOTICES.txt`（打进控制台的 npm 依赖的许可证，末尾是 logback 的版权声明和 LGPL-2.1-only 说明）、`JAVA-THIRD-PARTY-NOTICES.txt`（构建时根据运行时依赖的 POM 生成：每个 Java 库的版本和声明的许可证，以及许多 jar 自己不带的 Apache-2.0 / MIT 文本）、`licenses/`（logback 按 LGPL-2.1 使用，这里是 LGPL-2.1 全文和 logback 的版权声明）和 `third-party-sources/`（上面那 5 个库的源码 jar）；jar 的 `META-INF/` 里有 `LICENSE`、`NOTICE`、`JAVA-THIRD-PARTY-NOTICES.txt` 和 `licenses/`，控制台目录里有同一份 `THIRD-PARTY-NOTICES.txt`。一键安装脚本会把 `LICENSE`、`NOTICE` 和 `THIRD-PARTY-NOTICES.txt` 放进安装目录。发布脚本会检查这些都在，并在最后运行 `scripts/check-bundle-contents.sh`（CI 也会运行），它还确认整包和 jar 里没有 Mihomo 二进制或导出的容器镜像（Mihomo 是 GPL-3.0，只在部署时由 Docker 拉取，不随本项目分发）。源码 jar 由 `./gradlew :service:copyleftSources` 收集（第一次需要联网），`publish-artifact.sh` 的构建步骤会运行它；用 `SKIP_BUILD=true` 时要先自己运行。

---

## 每次提交后发布

每次想让某次源码提交**可被部署**，就在开发机 / CI 上（**不是服务器**）执行：

```bash
./scripts/publish-artifact.sh
git add artifacts
git commit -m "chore: publish prebuilt artifact"
git push
```

脚本会运行 `./gradlew test :service:bootJar`，确认 jar 里确实包含构建好的控制台，然后刷新 jar、控制台包、Compose 文件、恢复工具、整包、校验和和构建元数据。

**构建环境**：需要本机的 **Java 25**，或者 **Docker**。如果 `PATH` 里没有 `java`，脚本会自动退回用项目的 `Dockerfile` 在 Docker 里构建。

| 想要 | 命令 |
| --- | --- |
| 强制用 Docker 构建 | `BUILD_WITH_DOCKER=true ./scripts/publish-artifact.sh` |
| 强制用本机 Java / Gradle | `BUILD_WITH_DOCKER=false ./scripts/publish-artifact.sh` |
| 让脚本自动提交并推送「只含产物」的提交 | `COMMIT_ARTIFACTS=true PUSH_ARTIFACTS=true ./scripts/publish-artifact.sh` |
| 测试已经通过、只想快速刷新 | `RUN_TESTS=false ./scripts/publish-artifact.sh` |

自动提交模式在**已经有暂存的改动**时会拒绝运行，防止把源码改动一起提交进去。

---

## 在服务器上部署

在 Linux 服务器上执行一行命令（只下载仓库里已提交的整包，不运行 Gradle / npm）：

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh | sudo bash
```

带域名：

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env PUBLIC_BASE_URL=https://api.example.com bash
```

部署脚本会：

- 缺少时安装 `curl`、`openssl`、Docker 和 Docker Compose；
- 缺少时在 `/opt/starry-api/.env` 生成 PostgreSQL 凭据；
- 下载 `artifacts/starry-api-bundle.tar.gz` 及其校验和；
- 校验并解压到 `/opt/starry-api`；
- 仅当整包不可用时，才回退到单独的 `artifacts/starry-api.jar`；
- 安装经校验的恢复工具，并复制进服务镜像的 `/app/bin/recover-data-management`；
- 安装经校验的 `starry-api` 管理命令（整包自带；旧版本没有时单独下载，下载不到只提示并跳过）；
- 启动 PostgreSQL 17 和服务运行时（含 PostgreSQL 17 客户端工具和 `jq`）；
- 等待 `/actuator/health` 通过。

它**不会**克隆仓库，也**不会**在服务器上运行 Gradle 或 npm。

### 更新已有服务器

重新运行同一条命令即可。脚本会下载当前整包，原子地保留上一版的 `.env`、jar 及校验和、Compose 文件、PostgreSQL 引导脚本、恢复工具及其校验和；用新的部署单元重建服务；启动或健康检查失败时，**恢复整套旧部署并重建旧版本**后再报告失败。

### 部署某个分支的产物

```bash
curl -fsSL https://raw.githubusercontent.com/StaR4y/starry-api/main/scripts/install-server.sh \
  | sudo env BRANCH=my-branch bash
```

---

## 变量

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `REPO_URL` | `https://github.com/StaR4y/starry-api.git` | 用来推导 raw 下载地址的 GitHub 仓库 |
| `BRANCH` | `main` | 用来推导 raw 下载地址的分支 |
| `REPO_RAW_BASE` | 由 `REPO_URL` 和 `BRANCH` 推导 | 下载脚本和产物的 raw 根地址 |
| `BUNDLE_URL` / `BUNDLE_CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api-bundle.tar.gz` / `${BUNDLE_URL}.sha256` | 完整整包及校验和地址 |
| `ARTIFACT_URL` / `CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.jar` / `….jar.sha256` | 单独 jar 及校验和地址 |
| `BUILD_INFO_URL` | `${REPO_RAW_BASE}/artifacts/build-info.json` | 可选的构建元数据地址 |
| `RECOVERY_TOOL_URL` / `RECOVERY_TOOL_CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/runtime/recover-data-management.sh` / `${RECOVERY_TOOL_URL}.sha256` | 恢复工具及校验和地址，**仅在回退路径使用** |
| `CLI_TOOL_URL` / `CLI_TOOL_CHECKSUM_URL` | `${REPO_RAW_BASE}/artifacts/starry-api.sh` / `${CLI_TOOL_URL}.sha256` | `starry-api` 管理命令及校验和地址，**仅在整包里没有它时使用** |
| `INSTALL_DIR` | `/opt/starry-api` | 服务器安装目录 |
| `HOST_BIND_ADDR` | `0.0.0.0` | Compose 端口绑定的宿主机地址 |
| `HOST_HTTP_PORT` | `18080` | Compose 暴露的宿主机端口 |
| `SERVER_PORT` | `8080` | 容器内 Spring Boot 端口 |
| `PUBLIC_BASE_URL` | 空 | 用于启动日志的公网 HTTPS 地址 |
| `FORCE_ENV` | `false` | 仅在 PostgreSQL 初始化**之前**重新生成 `.env`；已初始化的集群需要先在数据库里轮换角色密码，再手动更新 `.env` |
| `INSTALL_DOCKER` | `true` | 缺少 Docker 时是否自动安装 |
| `ENABLE_MIHOMO` | `auto` | `auto`：全新安装时部署可选的 Mihomo 出站组件（`.env` 里的 `COMPOSE_PROFILES=mihomo` 和随机的 `STARRY_EGRESS_CONTROLLER_SECRET`），已有 `.env` 不改动；`true`：已有 `.env` 也追加；`false`：不部署，已有的保持不变。部署后由管理员在「系统设置 → 出站代理」打开。整包只含启动脚本，不含 Mihomo 二进制，镜像部署时拉取官方 `metacubex/mihomo`。见 [账号出站代理](ACCOUNT_EGRESS.md#部署) |

---

## 运维

```bash
cd /opt/starry-api
docker compose -f docker-compose.artifact.yml logs -f service
docker compose -f docker-compose.artifact.yml restart service
docker compose -f docker-compose.artifact.yml down
```

生成的环境使用数据库 `starry_api`、角色 `starry_api`、schema `starry_api`，同时安装一份可配置的 PostgreSQL OLTP 基线（连接数、内存、WAL / 检查点、规划器统计、autovacuum），默认值假定至少 2 GiB 可用内存。数据库文件在 `./data/postgresql` 下持久化，**除非这是可丢弃的部署，否则不要删除该目录**。

**数据管理被硬锁**时的恢复步骤：保持恰好一个服务实例运行，用交互式终端执行镜像里自带的双语工具，然后重启 `service` 并验证。完整流程见 [运维手册：恢复被锁定的数据管理](OPERATIONS.md#恢复被锁定的数据管理)。关键规则：不要加 `exec -T`、不要通过参数或管道传密钥、不要用 `curl ... | sh` 下载恢复脚本。
