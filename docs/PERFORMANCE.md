# 性能与内存基线

这份文档记录了 Starry API 在某一时间点的**运行时基线**，用于容量规划和回归对比，**不是**生产吞吐量的保证。上游模型的延迟、PostgreSQL 查询负载、TLS、网络距离、日志量和宿主机竞争，都没有体现在这次本地 HTTP 压测里。

> **一句话结论**：这次基线下，没有发现需要优化应用内存或更换 GC 的理由；但**生产环境必须设置明确的内存边界**——推荐的起步配置见 [生产建议](#生产建议内存边界)。

## 基线环境

测量日期 2026-08-28：

- Apple Silicon 宿主机，32 GiB 内存
- Java 25，G1 GC
- 通过 `./gradlew :service:bootRun` 启动 Spring Boot
- 本地 PostgreSQL 17
- 回环 HTTP 流量，没有外部反向代理
- 196 个已启用模型；`/v1/models` 返回约 345 KiB
- 测试前进程已运行约 4 小时 48 分钟

`bootRun` 是开发配置：它在 Gradle Daemon 之下启动服务，并使用 `-XX:TieredStopAtLevel=1`；生产 JAR 的内存和编译代码行为可能不同。生产 JAR 里内嵌的控制台是静态文件，**不需要 Node.js 进程**。

## 内存基线

| 指标 | 观测值 |
| --- | ---: |
| 压测前后端 RSS | 约 354 MiB |
| 压测前堆已用 | 约 159 MiB |
| 诊断性 Full GC 之后的存活堆 | 约 116 MiB |
| 非堆内存 | 约 94 MiB |
| Netty 直接缓冲内存 | 80 MiB（23 个缓冲；Spring Boot 3.5 / Netty 4.1 时测得） |
| 存活 JVM 线程 | 45 |
| JVM 自动选择的最大堆 | 8 GiB |
| 短压测期间 RSS 峰值 | 约 570 MiB |
| 压测后堆已提交 | 约 408 MiB |

**怎么理解这些数字**：

- 8 GiB 的最大堆是 JVM 从 32 GiB 宿主机**自动**选的（开发进程没设显式堆上限），它只是**预留的地址空间**，不是立刻提交的物理内存。
- 压测之后，已用堆达到约 216 MiB。一次诊断性 Full GC 把它降回约 116 MiB，与压测前的存活数据水平一致。RSS 仍接近 574 MiB，是因为 G1 保留了已提交的堆页供后续请求使用。**存活堆稳定、但已提交页保留**，是正常的 JVM 行为，**本身不是内存泄漏的证据**。
- 压测结束时，完整的开发 Java 进程树用了约 1.32 GiB RSS，其中约 745 MiB 属于两个 Gradle Daemon，**不是** Starry API 运行时。测部署内存时，请直接运行生产 JAR。

## 短压测

本地用 ApacheBench 发了 5,500 个请求：

| 端点 | 请求数 / 并发 | 失败 | 吞吐 | P99 延迟 |
| --- | ---: | ---: | ---: | ---: |
| `GET /health` | 5,000 / 50 | 0 | 约 34,500 req/s | 5 ms |
| `GET /v1/models` | 500 / 20 | 0 | 约 3,030 req/s | 18 ms |

模型列表的结果衡量的是本地目录序列化和回环传输，**不代表**补全代理的吞吐——后者通常受所选上游厂商和流式连接寿命限制。

整个观测窗口内，G1 共记录 19 次暂停，合计 112 ms。普通年轻代暂停峰值 3 ms；最大的一次 29 ms 暂停是手动触发的诊断性 Full GC，不属于正常请求处理。没有请求失败，采样时的 GC 开销为 0%。

## 评估

这份基线**不足以**证明需要做应用代码的内存优化或更换 GC：

- 压测后存活堆回到了之前的水平；
- 直接缓冲内存和线程数保持有界；
- 年轻代暂停很短；
- 被测端点全部无失败地完成；
- R2DBC 连接池默认的 4 初始 / 20 最大连接，在这次测试中不是内存压力来源。

保持 G1 和当前的 R2DBC 连接池配置，直到生产指标显示出具体的瓶颈。**不要**用定时 Full GC 或调用 `System.gc()` 当作内存管理策略。

## 生产建议：内存边界

生产部署**必须**有显式的内存边界。**不要**用 `-XX:MaxRAMPercentage=75.0`（旧版 `.env.example` 和安装脚本曾经默认这个值）：在没有内存上限的宿主机上它会把堆放大到宿主机内存的四分之三；放进 2 GiB 容器里，实测 1 MiB 请求体 × 200 并发时常驻内存会顶到约 **2.0 GiB**，几乎没有余量，很快被容器杀掉。

现在 Compose 文件默认就带这套配置：

```bash
JAVA_OPTS="-Xms64m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+ExitOnOutOfMemoryError -XX:G1PeriodicGCInterval=300000 -XX:-G1PeriodicGCInvokesConcurrent -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
```

- 服务容器同时有 `mem_limit: 2g`（`SERVICE_MEM_LIMIT` 可改）和 `/health` 健康检查，并设了 `MALLOC_ARENA_MAX=2`。
- **`-Xms64m` 加周期回收，是为了把内存还回去。**同一个构建、同样的突发流量（200 KiB × 100 并发 20 秒，再 2 KiB × 500 并发 20 秒），实测常驻内存：

  | JVM 参数 | 刚启动 | 突发刚结束 | 空闲 3 分钟后 |
  | --- | ---: | ---: | ---: |
  | `-Xms256m`，无周期回收（旧默认） | 527 MiB | 650 MiB | 649 MiB |
  | `-Xms64m` + 并发式周期回收 | 453 MiB | 647 MiB | 550 MiB |
  | `-Xms64m` + **完整**周期回收（现默认） | 478 MiB | 609 MiB | **418 MiB** |
  | Serial GC | 390 MiB | 1098 MiB | 1074 MiB |

  吞吐没有可见差别（200 KiB 场景四组都是约 289 次/秒）。测试时把周期设成 60 秒以便观察，默认值是 5 分钟。周期回收只在这段时间内**完全没有发生过 GC** 时才触发，也就是服务空闲的时候，代价是空闲时偶尔一次几十毫秒的停顿。Serial GC 启动最省，但堆涨上去后不会回落，不建议。
- **`-XX:+ExitOnOutOfMemoryError` 不能省。**实测中，堆被撑满后进程会继续运行但不再响应，`/health` 一直超时，几分钟都不会恢复；Docker 只在进程**退出**时才按 `restart: unless-stopped` 重启，所以没有这个参数就没有自动恢复。
- 并发更高或有很大的上下文时，把 `SERVICE_MEM_LIMIT` 调到 `3g`，并把 `-Xmx` 调到 1–1.5 GiB。
- 没做过针对你工作负载的测试，**不要部署在 1 GiB 以下**。

## 大请求与并发：容量规则

代理对每个请求都会：读完整个请求体 → 解析成 JSON 树 → 序列化后发给上游 → **等上游返回完整响应** → 再解析和回放。所以内存开销主要由「**同时在途的请求体总字节数**」决定，而不是请求速率。普通的聊天请求（几 KB 到几百 KB）完全不是问题；风险来自**很大的上下文 × 很高的并发**。

2026-10-10 在本地用生产 JAR、`-Xmx768m` 实测（模拟上游，上游延迟 20 秒让请求一直挂着）：

| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| 500 个 1 MiB 请求同时在途 | **OutOfMemoryError**，进程不再响应 | 同时在途被限制在约 190 个，其余立刻得到 `503 PROXY_OVERLOADED`（带 `Retry-After`），无 OOM，服务正常 |
| `/v1/responses`，每个请求 1 MiB 上下文，20 并发持续发送 | 为 `previous_response_id` 保存的历史让存活堆涨到 **736 MiB** 后 OOM | 存活堆约 60 MiB（历史压缩保存，并有 64 MiB 总上限） |
| 1 MiB × 200 并发，上游 300 ms | 全部成功，但 Full GC 82 次、GC 占 25% 时间 | 约 28% 的请求被限流为 503，Full GC 28 次、GC 约 11% |
| 2 KiB × 500 并发 | 约 850 次/秒 | 约 830 次/秒（持平） |
| 200 KiB × 200 并发 | 约 600 次/秒 | 约 555 次/秒（持平） |

对应的改动：

- 请求体不再被多次深拷贝或整体序列化成 String，只在改顶层字段时做浅拷贝。
- **`STARRY_PROXY_MAX_INFLIGHT_BODY_BYTES`**：同时在途请求体的总预算，默认是最大堆的 1/4。超出时立刻 `503` + `Retry-After: 1`。
- `/v1/responses` 的对话历史改为压缩字节保存，总量和单条都有上限（`STARRY_RESPONSES_STATE_MAX_TOTAL_BYTES`、`STARRY_RESPONSES_STATE_MAX_ENTRY_BYTES`）。

**怎么估算你的上限**：并发上限 ≈ `预算 ÷ 平均请求体大小`。预算默认 192 MiB（768 MiB 堆的 1/4），请求体平均 1 MiB 时大约允许 190 个同时在途。不够用就同时调大 `-Xmx`、`SERVICE_MEM_LIMIT` 和这个预算。

另外，每个上游账号默认最多同时处理 4 个请求、每分钟 60 个（可在账号分配策略里改），所以真实部署的并发先被**账号池容量**限制住；只有账号很多、或把限制调得很高时才会碰到上面的内存问题。

## 每个请求的固定开销

除了请求体本身，每个代理请求还有一份和大小无关的固定开销，主要来自数据库访问。2026-10-11 用 GC 日志实测（按「两次 GC 之间堆的增长」累加，再除以请求数）：

| 指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 2 KiB 请求，每次分配的内存 | 641 KiB | 约 470 KiB |
| 200 KiB 请求，每次分配的内存 | 1661 KiB | 约 1540 KiB |
| 每个请求发出的 SQL 语句（缓存已热） | 13 条 | 11 条 |
| 每个成功请求构造的异常对象 | 约 10 个 | 0 个 |

做了什么：

- API Key 查询从 Spring Data 的「按方法名派生」改成显式 SQL。派生查询每次执行都要重建 SELECT 列表，并对每个多词列名在内部抛出再吞掉一个带完整堆栈的 `PropertyReferenceException`（每次查询约 8 个）。**热路径上的仓库方法请一律写 `@Query`。**
- `switchIfEmpty(Mono.error(new XxxException(...)))` 会在**每次**组装链路时就把异常造出来（含堆栈），哪怕最后没用上。统一改成 `Mono.error(() -> new XxxException(...))`。
- 报价不再每个请求都查库并重新解析价格规则，改用已有的规则快照（本机修改立即失效，另有 15 秒过期兜底）。
- 结算成功后不再多调一次「释放预留」。
- 选账号时对每个候选账号做的小写转换、URL 解析改为记忆结果；否则账号越多，每个请求的分配越多。

大请求剩下的开销（200 KiB 请求约 1.5 MiB，约为请求体的 7 倍）基本都在「把请求体解析成 JSON 树，再序列化发给上游」上，这是当前「先完整解析、再整体转发」设计本身的成本，要再降只能改成流式透传。

空闲时进程里真正存活的对象只有约 40 MiB，应用自己的静态数据（模型目录、账号缓存等）不到 1 MiB，没有可优化的常驻数据。PostgreSQL 一侧，连接池开满 20 个连接时每个后端约 7.6 MiB 私有内存（合计约 160 MiB），加上 256 MiB 的 `shared_buffers`。

> 这份数据来自 macOS 上的生产 JAR，不是 Linux 容器；模拟上游和压测脚本跑在同一台机器上，没有 TLS 和真实网络延迟。Linux 上 glibc 的内存碎片可能让常驻内存更高，所以容器上限要留余量。

## Spring Boot 4 升级前后对比（2026-10-24）

同一台机器（Apple M5，10 核）、同样的生产 `JAVA_OPTS`，分别运行 Spring Boot 3.5.16（Netty 4.1）和 4.1.2（Netty 4.2）的 jar，压测对象是本地假上游：

| 场景 | 吞吐与延迟 | RSS 峰值（旧 → 新） | 说明 |
| --- | --- | ---: | --- |
| 300 路并发 SSE 流，持续 3 分钟 | 相同（约 47 req/s，P99 约 6.2 s） | 523 → 549 MiB | 新版常驻多约 25–50 MiB，线程多 9 个 |
| 40 路并发 2 MiB 请求体，另加 120 路突发 | 相同（P99 约 2.1 s；突发均为 96 成功 + 24 个 `503 PROXY_OVERLOADED`） | 972 → 960 MiB | 新版堆峰值更低（524 → 460 MiB） |
| 压测后空闲 6 分钟 | — | — | 两版的堆都会回落，没有泄漏 |

两版都没有出现错误，流式响应完整。新版在 2 GiB 容器上限下仍有约 40% 余量，现有的 768 MiB 堆、256 MiB 直接内存设置不需要调整；改回 Netty 旧的池化分配器（`-Dio.netty.allocator.type=pooled`）不会降低内存，不建议使用。

## 监控阈值

用 `/actuator/prometheus` 或管理端的性能页建立更长期的生产基线。建议的初始告警阈值：

| 信号 | 初始告警阈值 |
| --- | --- |
| 容器 RSS | 超过上限的 80%，持续 10 分钟 |
| GC 后堆 | 超过 `-Xmx` 的 70%，持续 15 分钟 |
| Netty 直接内存 | `netty.direct.memory.used` 超过 `netty.direct.memory.max` 的 70% |
| GC 暂停 P99 | 超过 100 ms |
| GC 时间 | 超过墙钟时间的 2%，持续 10 分钟 |
| 存活线程 | 持续增长到既定基线的 2 倍以上 |

从 Spring Boot 4 / Netty 4.2 起，Netty 的缓冲不再经过 `ByteBuffer.allocateDirect`，JVM 自带的 `jvm.buffer.memory.used{id=direct}` 会一直接近 0，不能再用来判断直接内存；改看服务导出的 `netty.direct.memory.used` 和 `netty.direct.memory.max`（Netty 在两者相等时抛出 `OutOfDirectMemoryError`）。

**只有当 GC 后的堆、或原生 / 直接内存，在多个可比的负载窗口里持续增长时，才需要调查泄漏。**单看 RSS 是不够的，因为 JVM 可能在对象已回收之后继续保留已提交的页。

## 复现这份基线

找到后端进程的 PID，查看常驻内存：

```bash
PID="$(pgrep -f 'tech.starryapi.StarryApiApplication' | head -n 1)"
ps -p "$PID" -o pid=,rss=,%mem=,%cpu=,etime=
jcmd "$PID" GC.heap_info
```

读取 Actuator 暴露的同样的运行时指标：

```bash
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.memory.used?tag=area:heap'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.memory.used?tag=area:nonheap'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/netty.direct.memory.used'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/netty.direct.memory.max'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.gc.pause'
```

有 ApacheBench 的话，可以重跑同样的短压测：

```bash
ab -n 5000 -c 50 -q http://127.0.0.1:8080/health
ab -n 500 -c 20 -q http://127.0.0.1:8080/v1/models
```

> ⚠️ **不要把 `jcmd <pid> GC.run` 当作日常的生产检查。**它会触发 stop-the-world 的 Full GC；这里只用过一次，是为了测出存活数据的下限。请优先看 `jvm.memory.usage.after.gc` 和正常的生产 GC 遥测。
