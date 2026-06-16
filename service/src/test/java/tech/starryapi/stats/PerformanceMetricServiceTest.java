package tech.starryapi.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PerformanceMetricServiceTest {

    @Test
    void parsesLinuxNetworkCountersAndSkipsLocalDockerInterfaces() {
        PerformanceMetricService.NetworkSnapshot snapshot = PerformanceMetricService.parseProcNetDev("""
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 1000 10 0 0 0 0 0 0 2000 20 0 0 0 0 0 0
              eth0: 3000 30 0 0 0 0 0 0 4000 40 0 0 0 0 0 0
            docker0: 5000 50 0 0 0 0 0 0 6000 60 0 0 0 0 0 0
            """, Instant.EPOCH);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.rxBytes()).isEqualTo(3000);
        assertThat(snapshot.txBytes()).isEqualTo(4000);
        assertThat(snapshot.sampledAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void parsesLinuxMemAvailableInsteadOfMemFree() {
        PerformanceMetricService.SystemMemorySnapshot snapshot = PerformanceMetricService.parseProcMemInfo("""
            MemTotal:       1000000 kB
            MemFree:          60000 kB
            MemAvailable:    760000 kB
            Buffers:          20000 kB
            Cached:          650000 kB
            SReclaimable:     30000 kB
            Shmem:            10000 kB
            """);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.totalBytes()).isEqualTo(1_000_000L * 1024);
        assertThat(snapshot.availableBytes()).isEqualTo(760_000L * 1024);
    }

    @Test
    void fallsBackToCacheAwareAvailableMemoryWhenMemAvailableIsMissing() {
        PerformanceMetricService.SystemMemorySnapshot snapshot = PerformanceMetricService.parseProcMemInfo("""
            MemTotal:       1000000 kB
            MemFree:          60000 kB
            Buffers:          20000 kB
            Cached:          650000 kB
            SReclaimable:     30000 kB
            Shmem:            10000 kB
            """);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.availableBytes()).isEqualTo(750_000L * 1024);
    }
}
