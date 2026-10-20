package tech.starryapi.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tech.starryapi.news.NewsDtos.DailyIndex;
import tech.starryapi.news.NewsDtos.DailyReport;
import tech.starryapi.news.NewsDtos.HotTopics;

@Service
public class NewsService {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final WebClient client;
    /**
     * Bounded and self-expiring. The previous map only overwrote an entry when the same page was requested again, so
     * every daily report anyone ever opened stayed in memory as a parsed JSON tree for the life of the process.
     */
    private final Cache<String, JsonNode> cache = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL)
        .maximumSize(64)
        .build();

    public NewsService(WebClient.Builder builder) {
        this.client = builder.baseUrl("https://aihot.news").build();
    }

    public Mono<DailyIndex> dailies(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 60 : requestedLimit, 180));
        return get("/api/v1/dailies?limit=" + limit).map(NewsMapper::dailies);
    }

    public Mono<DailyReport> daily(String date) {
        String normalized = date == null ? "" : date.trim();
        if (!DATE.matcher(normalized).matches()) {
            return Mono.error(new ApiException(HttpStatus.BAD_REQUEST, "NEWS_DATE_INVALID", "日期需要是 YYYY-MM-DD"));
        }
        return get("/api/v1/dailies/" + normalized).map(NewsMapper::daily);
    }

    public Mono<HotTopics> hotTopics() {
        return get("/api/v1/hot-topics").map(NewsMapper::hotTopics);
    }

    /** Number of pages currently cached, for tests. */
    long cachedPages() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private Mono<JsonNode> get(String path) {
        JsonNode cached = cache.getIfPresent(path);
        if (cached != null) {
            return Mono.just(cached);
        }
        return client.get()
            .uri(path)
            .retrieve()
            .onStatus(status -> status.value() == 404, response -> Mono.error(new ApiException(
                HttpStatus.NOT_FOUND,
                "NEWS_NOT_FOUND",
                "这一天还没有日报"
            )))
            .onStatus(HttpStatusCode::isError, response -> Mono.error(new ApiException(
                HttpStatus.BAD_GATEWAY,
                "NEWS_UPSTREAM_FAILED",
                "新闻源暂时不可用"
            )))
            .bodyToMono(JsonNode.class)
            .doOnNext(body -> cache.put(path, body));
    }
}
