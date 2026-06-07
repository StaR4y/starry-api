package xyz.star4y.api.stats;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class PerformanceMetricService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMetricService.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Path PROC_NET_DEV = Path.of("/proc/net/dev");

    private final PerformanceMetricRepository repository;
    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    private volatile NetworkSnapshot previousNetwork;

    public PerformanceMetricService(PerformanceMetricRepository repository) {
        this.repository = repository;
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sampleOnReady() {
        sample().subscribe(null, error -> log.warn("Failed to sample initial performance metrics", error));
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduledSample() {
        sample()
            .then(repository.deleteBySampledAtBefore(Instant.now().minus(RETENTION)))
            .subscribe(null, error -> log.warn("Failed to sample performance metrics", error));
    }

    public Mono<Void> sample() {
        return Mono.fromSupplier(this::collect).flatMap(repository::save).then();
    }

    public Mono<java.util.List<StatsDtos.PerformanceMetricResponse>> history() {
        return repository.findTop120ByOrderBySampledAtDesc()
            .map(PerformanceMetricMapper::toResponse)
            .collectList()
            .map(items -> items.stream()
                .sorted(Comparator.comparing(StatsDtos.PerformanceMetricResponse::sampledAt))
                .toList());
    }

    private PerformanceMetricEntity collect() {
        Instant now = Instant.now();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        NetworkSnapshot network = readNetworkSnapshot(now);
        NetworkRate rate = networkRate(network);

        PerformanceMetricEntity entity = new PerformanceMetricEntity();
        entity.setSystemCpuLoadPercent(percent(osBean.getCpuLoad()));
        entity.setProcessCpuLoadPercent(percent(osBean.getProcessCpuLoad()));
        entity.setHeapUsedBytes(heap.getUsed());
        entity.setHeapCommittedBytes(heap.getCommitted());
        entity.setHeapMaxBytes(heap.getMax());
        entity.setNonHeapUsedBytes(nonHeap.getUsed());
        entity.setSystemMemoryTotalBytes(osBean.getTotalMemorySize());
        entity.setSystemMemoryFreeBytes(osBean.getFreeMemorySize());
        entity.setSystemMemoryUsedPercent(memoryUsedPercent(entity.getSystemMemoryTotalBytes(), entity.getSystemMemoryFreeBytes()));
        if (network != null) {
            entity.setNetworkRxBytes(network.rxBytes());
            entity.setNetworkTxBytes(network.txBytes());
        }
        if (rate != null) {
            entity.setNetworkRxBytesPerSecond(rate.rxBytesPerSecond());
            entity.setNetworkTxBytesPerSecond(rate.txBytesPerSecond());
        }
        entity.setSampledAt(now);
        return entity;
    }

    private NetworkRate networkRate(NetworkSnapshot current) {
        NetworkSnapshot previous = previousNetwork;
        previousNetwork = current;
        if (current == null || previous == null) {
            return null;
        }
        double seconds = Math.max(1.0, Duration.between(previous.sampledAt(), current.sampledAt()).toMillis() / 1000.0);
        long rxDelta = Math.max(0, current.rxBytes() - previous.rxBytes());
        long txDelta = Math.max(0, current.txBytes() - previous.txBytes());
        return new NetworkRate(rxDelta / seconds, txDelta / seconds);
    }

    static NetworkSnapshot parseProcNetDev(String content, Instant sampledAt) {
        if (content == null || content.isBlank()) {
            return null;
        }
        long rxBytes = 0;
        long txBytes = 0;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.contains(":")) {
                continue;
            }
            String[] nameAndStats = trimmed.split(":", 2);
            String interfaceName = nameAndStats[0].trim();
            if (interfaceName.equals("lo") || interfaceName.startsWith("docker") || interfaceName.startsWith("br-")) {
                continue;
            }
            String[] fields = nameAndStats[1].trim().split("\\s+");
            if (fields.length < 16) {
                continue;
            }
            try {
                rxBytes += Long.parseLong(fields[0]);
                txBytes += Long.parseLong(fields[8]);
            } catch (NumberFormatException ignored) {
                // Ignore malformed interface counters.
            }
        }
        return rxBytes == 0 && txBytes == 0 ? null : new NetworkSnapshot(rxBytes, txBytes, sampledAt);
    }

    private NetworkSnapshot readNetworkSnapshot(Instant sampledAt) {
        try {
            if (!Files.isRegularFile(PROC_NET_DEV)) {
                return null;
            }
            return parseProcNetDev(Files.readString(PROC_NET_DEV), sampledAt);
        } catch (Exception error) {
            log.debug("Unable to read network counters from {}", PROC_NET_DEV, error);
            return null;
        }
    }

    private Double percent(double load) {
        return load < 0 || Double.isNaN(load) ? null : Math.max(0.0, Math.min(100.0, load * 100.0));
    }

    private Double memoryUsedPercent(Long total, Long free) {
        if (total == null || free == null || total <= 0) {
            return null;
        }
        return Math.max(0.0, Math.min(100.0, ((double) (total - free) / total) * 100.0));
    }

    record NetworkSnapshot(long rxBytes, long txBytes, Instant sampledAt) {
    }

    private record NetworkRate(double rxBytesPerSecond, double txBytesPerSecond) {
    }
}
