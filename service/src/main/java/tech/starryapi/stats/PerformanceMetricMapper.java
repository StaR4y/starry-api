package tech.starryapi.stats;

public final class PerformanceMetricMapper {

    private PerformanceMetricMapper() {
    }

    public static StatsDtos.PerformanceMetricResponse toResponse(PerformanceMetricEntity entity) {
        return new StatsDtos.PerformanceMetricResponse(
            entity.getSystemCpuLoadPercent(),
            entity.getProcessCpuLoadPercent(),
            entity.getHeapUsedBytes(),
            entity.getHeapCommittedBytes(),
            entity.getHeapMaxBytes(),
            entity.getNonHeapUsedBytes(),
            entity.getSystemMemoryTotalBytes(),
            entity.getSystemMemoryFreeBytes(),
            entity.getSystemMemoryUsedPercent(),
            entity.getNetworkRxBytes(),
            entity.getNetworkTxBytes(),
            entity.getNetworkRxBytesPerSecond(),
            entity.getNetworkTxBytesPerSecond(),
            entity.getSampledAt()
        );
    }
}
