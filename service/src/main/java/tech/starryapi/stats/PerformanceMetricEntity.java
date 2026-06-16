package tech.starryapi.stats;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("performance_metrics")
public class PerformanceMetricEntity {

    @Id
    private Long id;
    private Double systemCpuLoadPercent;
    private Double processCpuLoadPercent;
    private Long heapUsedBytes;
    private Long heapCommittedBytes;
    private Long heapMaxBytes;
    private Long nonHeapUsedBytes;
    private Long systemMemoryTotalBytes;
    private Long systemMemoryFreeBytes;
    private Double systemMemoryUsedPercent;
    private Long networkRxBytes;
    private Long networkTxBytes;
    private Double networkRxBytesPerSecond;
    private Double networkTxBytesPerSecond;
    private Instant sampledAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Double getSystemCpuLoadPercent() {
        return systemCpuLoadPercent;
    }

    public void setSystemCpuLoadPercent(Double systemCpuLoadPercent) {
        this.systemCpuLoadPercent = systemCpuLoadPercent;
    }

    public Double getProcessCpuLoadPercent() {
        return processCpuLoadPercent;
    }

    public void setProcessCpuLoadPercent(Double processCpuLoadPercent) {
        this.processCpuLoadPercent = processCpuLoadPercent;
    }

    public Long getHeapUsedBytes() {
        return heapUsedBytes;
    }

    public void setHeapUsedBytes(Long heapUsedBytes) {
        this.heapUsedBytes = heapUsedBytes;
    }

    public Long getHeapCommittedBytes() {
        return heapCommittedBytes;
    }

    public void setHeapCommittedBytes(Long heapCommittedBytes) {
        this.heapCommittedBytes = heapCommittedBytes;
    }

    public Long getHeapMaxBytes() {
        return heapMaxBytes;
    }

    public void setHeapMaxBytes(Long heapMaxBytes) {
        this.heapMaxBytes = heapMaxBytes;
    }

    public Long getNonHeapUsedBytes() {
        return nonHeapUsedBytes;
    }

    public void setNonHeapUsedBytes(Long nonHeapUsedBytes) {
        this.nonHeapUsedBytes = nonHeapUsedBytes;
    }

    public Long getSystemMemoryTotalBytes() {
        return systemMemoryTotalBytes;
    }

    public void setSystemMemoryTotalBytes(Long systemMemoryTotalBytes) {
        this.systemMemoryTotalBytes = systemMemoryTotalBytes;
    }

    public Long getSystemMemoryFreeBytes() {
        return systemMemoryFreeBytes;
    }

    public void setSystemMemoryFreeBytes(Long systemMemoryFreeBytes) {
        this.systemMemoryFreeBytes = systemMemoryFreeBytes;
    }

    public Double getSystemMemoryUsedPercent() {
        return systemMemoryUsedPercent;
    }

    public void setSystemMemoryUsedPercent(Double systemMemoryUsedPercent) {
        this.systemMemoryUsedPercent = systemMemoryUsedPercent;
    }

    public Long getNetworkRxBytes() {
        return networkRxBytes;
    }

    public void setNetworkRxBytes(Long networkRxBytes) {
        this.networkRxBytes = networkRxBytes;
    }

    public Long getNetworkTxBytes() {
        return networkTxBytes;
    }

    public void setNetworkTxBytes(Long networkTxBytes) {
        this.networkTxBytes = networkTxBytes;
    }

    public Double getNetworkRxBytesPerSecond() {
        return networkRxBytesPerSecond;
    }

    public void setNetworkRxBytesPerSecond(Double networkRxBytesPerSecond) {
        this.networkRxBytesPerSecond = networkRxBytesPerSecond;
    }

    public Double getNetworkTxBytesPerSecond() {
        return networkTxBytesPerSecond;
    }

    public void setNetworkTxBytesPerSecond(Double networkTxBytesPerSecond) {
        this.networkTxBytesPerSecond = networkTxBytesPerSecond;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Instant sampledAt) {
        this.sampledAt = sampledAt;
    }
}
