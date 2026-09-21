# Performance and Memory Baseline

This document records a point-in-time runtime baseline for Starry API. It is
intended for capacity planning and regression comparison, not as a production
throughput guarantee. Upstream model latency, PostgreSQL query load, TLS,
network distance, logging volume, and host contention were not represented by
the local HTTP benchmark.

## Baseline Environment

Measured on 2026-08-28 with:

- Apple Silicon host with 32 GiB memory
- Java 25 and G1 GC
- Spring Boot started through `./gradlew :service:bootRun`
- Local PostgreSQL 17
- Loopback HTTP traffic without an external reverse proxy
- 196 enabled models; `/v1/models` returned approximately 345 KiB
- Approximately 4 hours and 48 minutes of process uptime before the test

`bootRun` is a development configuration. It starts the service below a Gradle
Daemon and uses `-XX:TieredStopAtLevel=1`; production JAR memory and compiled
code behavior can differ. The embedded WebUI is static in the production JAR
and does not require a Node.js process.

## Memory Baseline

| Metric | Observed value |
| --- | ---: |
| Backend RSS before load | approximately 354 MiB |
| Heap used before load | approximately 159 MiB |
| Live heap after diagnostic Full GC | approximately 116 MiB |
| Non-heap memory | approximately 94 MiB |
| Netty direct-buffer memory | 80 MiB across 23 buffers |
| Live JVM threads | 45 |
| JVM-selected maximum heap | 8 GiB |
| RSS peak during the short load test | approximately 570 MiB |
| Heap committed after load | approximately 408 MiB |

The 8 GiB maximum heap was selected automatically from the 32 GiB host because
the development process had no explicit heap limit. It was reserved address
space, not immediately committed physical memory.

After the request load, used heap reached approximately 216 MiB. A diagnostic
Full GC returned it to approximately 116 MiB, matching the pre-load live-data
level. RSS remained close to 574 MiB because G1 retained committed heap pages
for later requests. A stable live heap with retained committed pages is normal
JVM behavior and is not, by itself, evidence of a memory leak.

The complete development Java process tree used approximately 1.32 GiB RSS at
the end of the test. Approximately 745 MiB belonged to two Gradle Daemons, not
the Starry API runtime. Run the production JAR directly when measuring
deployment memory.

## Short Load Test

The local test issued 5,500 requests with ApacheBench:

| Endpoint | Requests / concurrency | Failures | Throughput | P99 latency |
| --- | ---: | ---: | ---: | ---: |
| `GET /health` | 5,000 / 50 | 0 | approximately 34,500 req/s | 5 ms |
| `GET /v1/models` | 500 / 20 | 0 | approximately 3,030 req/s | 18 ms |

The model-list result measures local catalog serialization and loopback
transfer. It does not represent completion-proxy throughput, which is normally
bounded by the selected upstream provider and streaming connection lifetime.

During the complete observation window, G1 recorded 19 pauses totaling 112 ms.
Ordinary young-generation pauses peaked at 3 ms. The largest 29 ms pause was
the manually requested diagnostic Full GC and is not part of normal request
processing. No request failed and the reported GC overhead was 0% at sampling
time.

## Assessment

No application-code memory optimization or GC replacement is currently
justified by this baseline:

- live heap returned to its previous level after load;
- direct-buffer memory and thread count remained bounded;
- young-generation pauses were short;
- the tested endpoints completed without failures;
- the R2DBC pool defaults of 4 initial and 20 maximum connections were not a
  memory-pressure source in this test.

The production deployment does need an explicit memory boundary. The example
`-XX:MaxRAMPercentage=75.0` can leave too little headroom for Netty direct
buffers, Metaspace, code cache, thread stacks, and native libraries. It is
especially risky when the container itself has no memory limit.

For a normal single-instance deployment, start with a 1.5 GiB container limit
and the following fixed JVM budget:

```bash
JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxDirectMemorySize=256m -XX:+HeapDumpOnOutOfMemoryError"
```

Use a 2 GiB container limit for higher concurrency or many long-lived streaming
responses. Do not deploy below 1 GiB without a workload-specific test. Fixed
heap limits are preferred for the initial deployment because they remain
predictable when the same image runs on hosts with different memory sizes.

Keep G1 and the current R2DBC pool settings until production metrics show a
specific bottleneck. Do not add scheduled Full GC or call `System.gc()` as a
memory-management strategy.

## Monitoring Thresholds

Use `/actuator/prometheus` or the administration performance page to establish
a longer production baseline. Suggested initial warning thresholds are:

| Signal | Initial warning threshold |
| --- | --- |
| Container RSS | above 80% of its limit for 10 minutes |
| Heap after GC | above 70% of `-Xmx` for 15 minutes |
| Direct-buffer memory | above 70% of `MaxDirectMemorySize` |
| GC pause P99 | above 100 ms |
| GC time | above 2% of wall time for 10 minutes |
| Live threads | sustained growth above twice the established baseline |

Investigate a leak only when after-GC heap or native/direct memory grows across
multiple comparable workload windows. RSS alone is insufficient because the
JVM may retain committed pages after objects have been reclaimed.

## Reproducing the Baseline

Find the backend PID and inspect its resident memory:

```bash
PID="$(pgrep -f 'tech.starryapi.StarryApiApplication' | head -n 1)"
ps -p "$PID" -o pid=,rss=,%mem=,%cpu=,etime=
jcmd "$PID" GC.heap_info
```

Read the same runtime metrics exposed through Actuator:

```bash
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.memory.used?tag=area:heap'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.memory.used?tag=area:nonheap'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.buffer.memory.used?tag=id:direct'
curl -sS 'http://127.0.0.1:8080/actuator/metrics/jvm.gc.pause'
```

Run the same short local request load when ApacheBench is available:

```bash
ab -n 5000 -c 50 -q http://127.0.0.1:8080/health
ab -n 500 -c 20 -q http://127.0.0.1:8080/v1/models
```

Do not run `jcmd <pid> GC.run` as a routine production check. It triggers a
stop-the-world Full GC and was used once here only to measure the live-data
floor. Prefer `jvm.memory.usage.after.gc` and normal production GC telemetry.
