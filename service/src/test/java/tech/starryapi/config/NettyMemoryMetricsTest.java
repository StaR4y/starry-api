package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

class NettyMemoryMetricsTest {

    @Test
    void reportsTheDirectMemoryNettyActuallyHolds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyMemoryMetrics().bindTo(registry);
        double before = registry.get("netty.direct.memory.used").gauge().value();

        // The default allocator is the one Reactor Netty uses for request and response buffers.
        ByteBuf buffer = ByteBufAllocator.DEFAULT.directBuffer(8 * 1024 * 1024);
        try {
            double during = registry.get("netty.direct.memory.used").gauge().value();
            assertThat(during).isGreaterThanOrEqualTo(before + 8 * 1024 * 1024);
        } finally {
            buffer.release();
        }
        assertThat(registry.get("netty.direct.memory.max").gauge().value()).isPositive();
    }
}
