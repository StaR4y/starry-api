package tech.starryapi.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.util.internal.PlatformDependent;
import org.springframework.stereotype.Component;

/**
 * Netty's own direct-memory accounting, which is what {@code -XX:MaxDirectMemorySize} effectively limits.
 *
 * <p>Since Netty 4.2 the server and client buffers are no longer allocated through {@code ByteBuffer.allocateDirect},
 * so the JVM's {@code jvm.buffer.memory.used{id=direct}} stays near zero while Netty holds tens of megabytes. Alert on
 * {@code netty.direct.memory.used / netty.direct.memory.max} instead. Netty fails with OutOfDirectMemoryError when the
 * two meet.
 */
@Component
public class NettyMemoryMetrics implements MeterBinder {

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("netty.direct.memory.used", NettyMemoryMetrics::used)
            .description("Direct memory held by Netty buffers")
            .baseUnit(BaseUnits.BYTES)
            .register(registry);
        Gauge.builder("netty.direct.memory.max", NettyMemoryMetrics::max)
            .description("Direct memory Netty may allocate before failing with OutOfDirectMemoryError")
            .baseUnit(BaseUnits.BYTES)
            .register(registry);
    }

    static double used() {
        // -1 means Netty is not counting (it then relies on the JVM's own direct-buffer accounting).
        long used = PlatformDependent.usedDirectMemory();
        return used < 0 ? Double.NaN : used;
    }

    static double max() {
        return PlatformDependent.maxDirectMemory();
    }
}
