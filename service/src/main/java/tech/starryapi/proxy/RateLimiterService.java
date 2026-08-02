package tech.starryapi.proxy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tech.starryapi.config.ProxyProperties;

@Service
public class RateLimiterService {

    private final ProxyProperties properties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiterService(ProxyProperties properties) {
        this.properties = properties;
    }

    public RateLimitResult check(String key) {
        int limit = properties.getRateLimitPerMinute();
        if (limit <= 0) {
            return new RateLimitResult(true, 0, 0, 0);
        }
        long now = Instant.now().toEpochMilli();
        Bucket bucket = buckets.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStartMillis >= 60_000) {
                return new Bucket(now, new AtomicInteger(0));
            }
            return current;
        });
        int used = bucket.count.incrementAndGet();
        if (used <= limit) {
            return new RateLimitResult(true, limit, Math.max(0, limit - used), 0);
        }
        long retryAfter = Math.max(1, 60_000 - (now - bucket.windowStartMillis));
        return new RateLimitResult(false, limit, 0, retryAfter);
    }

    public void invalidateAllBuckets() {
        buckets.clear();
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        long now = Instant.now().toEpochMilli();
        buckets.entrySet().removeIf(entry -> now - entry.getValue().windowStartMillis > 120_000);
    }

    public record RateLimitResult(boolean allowed, int limit, int remaining, long retryAfterMillis) {
    }

    private record Bucket(long windowStartMillis, AtomicInteger count) {
    }
}
