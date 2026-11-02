# CI 故障修复报告（2026-09-22）

## 对应运行

- [GitHub Actions #35645807212](https://github.com/StaR4y/starry-api/actions/runs/35645807212)
- 提交：`541f6ea8515aa720d768c8e19405cba29de38aed`，分支：`main`。
- `Java 25, WebUI, PostgreSQL 17` 在恢复工具测试退出，尚未执行 Java 测试及前端构建。
- `PostgreSQL 18 compatibility` 报告 96 项测试，58 项失败、19 项跳过；主要错误为 `relation "system_settings" does not exist`。

## 根因与修复

### 恢复脚本的 GNU/BSD 差异

测试辅助函数优先调用 BSD 形式的 `stat -f '%Lp'`。GNU stat 将 `-f` 解释为文件系统信息查询，失败前已经输出的内容污染了后续权限结果，导致将正常的 `0600` 文件误报为权限被修改。

修复为优先调用 GNU 形式，并仅在调用成功时输出捕获值；BSD 系统使用相应后备命令。继续执行后还发现 Ubuntu 的 `mv -n` 在拒绝覆盖时返回非零，而 macOS 返回零。恢复脚本现在在两种返回方式下都能识别目标已存在，保留目标文件并清理暂存密钥。

### R2DBC 未等待 Flyway

集成测试启用了 `spring.main.lazy-initialization=true`，Flyway 初始化 Bean 因而没有在首次业务查询前运行。启动管理员初始化、账号池和其他数据库消费者会访问尚未创建的表。这不是 PostgreSQL 18 的 SQL 兼容性问题。

通过 Spring Boot 的 `DependsOnDatabaseInitializationDetector` 扩展点，将 `DatabaseClient` 注册为数据库初始化的消费者。正常和懒加载模式均先完成迁移再创建客户端；迁移失败时阻止客户端创建，不吞掉迁移错误。三个独立回归测试覆盖迁移顺序、迁移失败和未配置迁移器的场景。

### 容器生命周期与测试契约滞后

- 每个集成测试类结束时关闭 Spring 测试上下文，避免下一类复用指向已停止 Testcontainers 容器的连接池。
- 修正 Testcontainers JDBC URL 已有查询参数时重复使用 `?` 的问题。
- 缩短两组超过 `affiliate_code VARCHAR(32)` 的测试数据。
- 聚合表测试数据由数据库 owner 写入，保留运行账号对派生表的只读边界。
- 分区清理测试遵循现行至少保留四个月的约束，仍验证过期分区被删除、当前分区被保留。
- 聚合幂等测试先完成历史积压，再验证同一边界不会重复重建；独立测试继续验证每批一小时的推进上限。
- 并发扣费测试在直接修改余额的测试充值前检查账本连续性，充值后验证第三次完整扣费和账本总额。

### 函数默认执行权限

恢复数据库测试后，权限断言发现 `noPublicFunctionExecute=false`。旧 V1 使用 schema 局部默认权限撤销，不能覆盖 PostgreSQL 全局默认授予 `PUBLIC` 的函数执行权限。

新增 `V20__function_execute_privileges.sql`：

1. 对当前数据库中 `starry_api_owner` 创建的未来函数，在全局默认权限层撤销 `PUBLIC EXECUTE`。
2. 撤销 `starry_api` 中现有函数的 `PUBLIC EXECUTE`，保留显式授予运行角色的权限。

集成测试验证现有函数权限，并创建一个新函数，验证其没有公共执行授权且运行角色不能直接调用。全部计费、账号调度测试继续通过。

原 V1–V19 未修改。备份恢复要求同步升级至 V1–V20，保留完整历史及校验和检查；V19 备份不能直接通过新版的严格恢复校验，应先按旧版本恢复并升级，再导出新版备份。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 修复前 Ubuntu 24.04 恢复脚本 | 复现权限误报 |
| 修复前 PostgreSQL 18 容器测试 | 复现 58 项失败 |
| Ubuntu 24.04 安装回滚、恢复工具 | 全部通过，包括拒绝覆盖及暂存清理 |
| macOS 安装回滚、恢复工具 | 全部通过 |
| PostgreSQL 18 容器集成测试 | 78 项通过，0 失败，0 跳过 |
| PostgreSQL 18 同次运行的初始化/备份恢复回归 | 17 项通过 |
| PostgreSQL 17 完整构建 | service 858 项、plugin-api 3 项通过，共 861 项，0 失败 |
| PostgreSQL 17 容器集成测试（包含在完整构建中） | 78 项通过，0 跳过 |
| WebUI 类型检查、Vite 构建、bootJar | 通过 |
| 同一临时目录重复发布产物两次 | 通过，恢复工具可执行、内容一致、全部清单校验和正确 |

完整运行共发现 902 项测试，另有 41 项按原有环境开关跳过：外部 Coding Plan 数据库 19 项、工单数据库 10 项、插件安全集成 12 项。上述跳过项未记为通过；Coding Plan 的同一组 19 项契约另通过容器版本实际执行。

Java 测试运行于本机 JDK 25.0.3，PostgreSQL 17/18 使用临时 Docker 容器；Linux shell 测试运行于 Ubuntu 24.04 容器。前端使用本机 Node 26.0.0，远端 CI 配置为 Node 22。这里记录的是本地验证，尚未推送或触发新的 GitHub Actions 运行。

主要命令：

```bash
TEST_POSTGRES_IMAGE=postgres:18-alpine ./gradlew --no-daemon :service:test \
  --tests 'tech.starryapi.postgresql.*' \
  --tests '*R2dbcDatabaseInitializationDependencyDetectorTest' \
  --tests '*DataTransferServiceTest' --tests '*DataTransferMigrationContractTest'

TEST_POSTGRES_IMAGE=postgres:17-alpine ./gradlew --no-daemon clean test :webui:npmBuild :service:bootJar
./scripts/test-install-server.sh
./scripts/test-recover-data-management.sh
```

使用 Colima 时需另外设置 `DOCKER_HOST` 指向当前 Docker socket，并设置 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`。确认测试 XML 的 `skipped="0"`，避免将缺少 Docker 时的跳过误认为数据库验证成功。
