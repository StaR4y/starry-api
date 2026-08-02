package tech.starryapi.stats;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");

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
        sample().subscribe(null, error -> log.warn("Failed to sample performance metrics", error));
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void scheduledCleanup() {
        repository.deleteBySampledAtBefore(Instant.now().minus(RETENTION))
            .subscribe(null, error -> log.warn("Failed to clean up performance metrics", error));
    }

    public Mono<Void> sample() {
        return Mono.fromSupplier(this::collect).flatMap(repository::save).then();
    }

    public Mono<List<StatsDtos.PerformanceMetricResponse>> history() {
        return repository.findTop120ByOrderBySampledAtDesc()
            .map(PerformanceMetricMapper::toResponse)
            .collectList()
            .map(List::reversed);
    }

    private PerformanceMetricEntity collect() {
        Instant now = Instant.now();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        NetworkSnapshot network = readNetworkSnapshot(now);
        NetworkRate rate = networkRate(network);
        SystemMemorySnapshot systemMemory = readSystemMemorySnapshot();
        long systemMemoryTotalBytes = systemMemory == null ? osBean.getTotalMemorySize() : systemMemory.totalBytes();
        long systemMemoryAvailableBytes = systemMemory == null ? osBean.getFreeMemorySize() : systemMemory.availableBytes();

        PerformanceMetricEntity entity = new PerformanceMetricEntity();
        entity.setSystemCpuLoadPercent(percent(osBean.getCpuLoad()));
        entity.setProcessCpuLoadPercent(percent(osBean.getProcessCpuLoad()));
        entity.setHeapUsedBytes(heap.getUsed());
        entity.setHeapCommittedBytes(heap.getCommitted());
        entity.setHeapMaxBytes(heap.getMax());
        entity.setNonHeapUsedBytes(nonHeap.getUsed());
        entity.setSystemMemoryTotalBytes(systemMemoryTotalBytes);
        entity.setSystemMemoryFreeBytes(systemMemoryAvailableBytes);
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

    static SystemMemorySnapshot parseProcMemInfo(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Long totalKb = null;
        Long availableKb = null;
        Long freeKb = null;
        Long buffersKb = null;
        Long cachedKb = null;
        Long reclaimableKb = null;
        Long sharedKb = null;
        for (String line : content.split("\\R")) {
            String[] nameAndValue = line.split(":", 2);
            if (nameAndValue.length != 2) {
                continue;
            }
            Long valueKb = memInfoKilobytes(nameAndValue[1]);
            if (valueKb == null) {
                continue;
            }
            switch (nameAndValue[0].trim()) {
                case "MemTotal" -> totalKb = valueKb;
                case "MemAvailable" -> availableKb = valueKb;
                case "MemFree" -> freeKb = valueKb;
                case "Buffers" -> buffersKb = valueKb;
                case "Cached" -> cachedKb = valueKb;
                case "SReclaimable" -> reclaimableKb = valueKb;
                case "Shmem" -> sharedKb = valueKb;
                default -> {
                }
            }
        }
        if (totalKb == null || totalKb <= 0) {
            return null;
        }
        long available = availableKb == null
            ? legacyAvailableKilobytes(freeKb, buffersKb, cachedKb, reclaimableKb, sharedKb)
            : availableKb;
        return new SystemMemorySnapshot(kibibytesToBytes(totalKb), kibibytesToBytes(Math.min(totalKb, Math.max(0, available))));
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

    private SystemMemorySnapshot readSystemMemorySnapshot() {
        try {
            if (!Files.isRegularFile(PROC_MEMINFO)) {
                return null;
            }
            return parseProcMemInfo(Files.readString(PROC_MEMINFO));
        } catch (Exception error) {
            log.debug("Unable to read memory counters from {}", PROC_MEMINFO, error);
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

    private static Long memInfoKilobytes(String rawValue) {
        String[] parts = rawValue.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(parts[0]);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long legacyAvailableKilobytes(
        Long freeKb,
        Long buffersKb,
        Long cachedKb,
        Long reclaimableKb,
        Long sharedKb
    ) {
        return Math.max(0, value(freeKb) + value(buffersKb) + value(cachedKb) + value(reclaimableKb) - value(sharedKb));
    }

    private static long kibibytesToBytes(long value) {
        return value * 1024;
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    record NetworkSnapshot(long rxBytes, long txBytes, Instant sampledAt) {
    }

    record SystemMemorySnapshot(long totalBytes, long availableBytes) {
    }

    private record NetworkRate(double rxBytesPerSecond, double txBytesPerSecond) {
    }
}
