package tech.starryapi.stats;

import io.r2dbc.spi.Row;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class StatsAnalyticsService {

    private static final Duration ACTIVE_USER_WINDOW = Duration.ofMinutes(5);
    private static final int ACTIVE_USER_LIMIT = 20;
    private static final int MODEL_USAGE_LIMIT = 20;
    private static final String USAGE_SEGMENTS_CTE = """
        WITH aggregation_boundary AS (
            SELECT (
                SELECT last_processed_at
                FROM aggregation_watermarks
                WHERE job_name = 'request_usage'
            ) AS watermark
        ),
        usage_segments AS (
            SELECT
                d.api_key_id,
                d.user_id,
                d.model,
                d.request_count,
                d.successful_requests,
                d.failed_requests,
                d.input_tokens,
                d.output_tokens,
                d.credits,
                d.last_request_at
            FROM request_usage_daily d
            CROSS JOIN aggregation_boundary b
            WHERE b.watermark IS NOT NULL
              AND d.usage_date < (b.watermark AT TIME ZONE 'UTC')::DATE

            UNION ALL

            SELECT
                h.api_key_id,
                h.user_id,
                h.model,
                h.request_count,
                h.successful_requests,
                h.failed_requests,
                h.input_tokens,
                h.output_tokens,
                h.credits,
                h.last_request_at
            FROM request_usage_hourly h
            CROSS JOIN aggregation_boundary b
            WHERE b.watermark IS NOT NULL
              AND h.bucket_start >= pg_catalog.date_trunc('day', b.watermark, 'UTC')
              AND h.bucket_start < b.watermark

            UNION ALL

            SELECT
                COALESCE(l.api_key_id, ''),
                COALESCE(l.user_id, ''),
                COALESCE(l.model, ''),
                1::BIGINT,
                CASE WHEN l.success THEN 1::BIGINT ELSE 0::BIGINT END,
                CASE WHEN l.success THEN 0::BIGINT ELSE 1::BIGINT END,
                l.input_tokens,
                l.output_tokens,
                l.credits,
                l.created_at
            FROM request_logs l
            CROSS JOIN aggregation_boundary b
            WHERE b.watermark IS NULL OR l.created_at >= b.watermark
        )
        """;

    private final DatabaseClient databaseClient;

    public StatsAnalyticsService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<StatsDtos.UsageSummaryResponse> usageSummary() {
        Instant activeSince = Instant.now().minus(ACTIVE_USER_WINDOW);
        return Mono.zip(totalUsage(), activeUsers(activeSince).collectList(), modelUsage().collectList())
            .map(tuple -> response(tuple.getT1(), activeSince, tuple.getT2(), tuple.getT3()));
    }

    public Mono<StatsDtos.UsageSummaryResponse> usageSummaryForUser(String userId) {
        Instant activeSince = Instant.now().minus(ACTIVE_USER_WINDOW);
        return Mono.zip(
                totalUsageForUser(userId),
                activeUsersForUser(activeSince, userId).collectList(),
                modelUsageForUser(userId).collectList()
            )
            .map(tuple -> response(tuple.getT1(), activeSince, tuple.getT2(), tuple.getT3()));
    }

    private Mono<UsageTotals> totalUsage() {
        String sql = USAGE_SEGMENTS_CTE + """
            SELECT
                COALESCE(SUM(request_count), 0) AS request_count,
                COALESCE(SUM(successful_requests), 0) AS successful_requests,
                COALESCE(SUM(failed_requests), 0) AS failed_requests,
                COALESCE(SUM(input_tokens), 0) AS input_tokens,
                COALESCE(SUM(output_tokens), 0) AS output_tokens,
                COALESCE(SUM(credits), 0) AS credits
            FROM usage_segments
            """;
        return databaseClient.sql(sql)
            .map((row, metadata) -> new UsageTotals(
                longValue(row, "request_count"),
                longValue(row, "successful_requests"),
                longValue(row, "failed_requests"),
                longValue(row, "input_tokens"),
                longValue(row, "output_tokens"),
                decimalValue(row, "credits")
            ))
            .one()
            .defaultIfEmpty(UsageTotals.empty());
    }

    private Mono<UsageTotals> totalUsageForUser(String userId) {
        String sql = USAGE_SEGMENTS_CTE + """
            SELECT
                COALESCE(SUM(s.request_count), 0) AS request_count,
                COALESCE(SUM(s.successful_requests), 0) AS successful_requests,
                COALESCE(SUM(s.failed_requests), 0) AS failed_requests,
                COALESCE(SUM(s.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(s.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(s.credits), 0) AS credits
            FROM usage_segments s
            WHERE s.user_id = :userId
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .map((row, metadata) -> new UsageTotals(
                longValue(row, "request_count"),
                longValue(row, "successful_requests"),
                longValue(row, "failed_requests"),
                longValue(row, "input_tokens"),
                longValue(row, "output_tokens"),
                decimalValue(row, "credits")
            ))
            .one()
            .defaultIfEmpty(UsageTotals.empty());
    }

    private Flux<StatsDtos.ActiveUserResponse> activeUsers(Instant activeSince) {
        String sql = """
            WITH recent_by_user_key AS (
                SELECT
                    COALESCE(user_id, '') AS user_id,
                    COALESCE(api_key_id, '') AS api_key_id,
                    COUNT(*) AS request_count,
                    COALESCE(SUM(input_tokens), 0) AS input_tokens,
                    COALESCE(SUM(output_tokens), 0) AS output_tokens,
                    COALESCE(SUM(credits), 0) AS credits,
                    MAX(created_at) AS last_active_at
                FROM request_logs
                WHERE created_at >= :activeSince
                GROUP BY COALESCE(user_id, ''), COALESCE(api_key_id, '')
            )
            SELECT
                COALESCE(NULLIF(r.user_id, ''), NULLIF(r.api_key_id, ''), 'anonymous') AS user_id,
                MAX(u.username) AS username,
                MAX(u.display_name) AS display_name,
                MAX(u.email) AS email,
                COALESCE(SUM(r.request_count), 0) AS request_count,
                COUNT(NULLIF(r.api_key_id, '')) AS api_key_count,
                COALESCE(SUM(r.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(r.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(r.credits), 0) AS credits,
                MAX(r.last_active_at) AS last_active_at
            FROM recent_by_user_key r
            LEFT JOIN admin_users u ON u.user_id = NULLIF(r.user_id, '')
            GROUP BY COALESCE(NULLIF(r.user_id, ''), NULLIF(r.api_key_id, ''), 'anonymous')
            ORDER BY last_active_at DESC, request_count DESC
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
            .bind("activeSince", activeSince)
            .bind("limit", ACTIVE_USER_LIMIT)
            .map((row, metadata) -> activeUser(row))
            .all();
    }

    private Flux<StatsDtos.ActiveUserResponse> activeUsersForUser(Instant activeSince, String userId) {
        String sql = """
            SELECT
                l.user_id AS user_id,
                MAX(u.username) AS username,
                MAX(u.display_name) AS display_name,
                MAX(u.email) AS email,
                COUNT(*) AS request_count,
                COUNT(DISTINCT l.api_key_id) AS api_key_count,
                COALESCE(SUM(l.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(l.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(l.credits), 0) AS credits,
                MAX(l.created_at) AS last_active_at
            FROM request_logs l
            LEFT JOIN admin_users u ON u.user_id = l.user_id
            WHERE l.user_id = :userId
              AND l.created_at >= :activeSince
            GROUP BY l.user_id
            ORDER BY last_active_at DESC, request_count DESC
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("activeSince", activeSince)
            .bind("limit", ACTIVE_USER_LIMIT)
            .map((row, metadata) -> activeUser(row))
            .all();
    }

    private Flux<StatsDtos.ModelUsageResponse> modelUsage() {
        String sql = USAGE_SEGMENTS_CTE + """
            SELECT
                COALESCE(NULLIF(model, ''), '未指定模型') AS model_name,
                COALESCE(SUM(request_count), 0) AS request_count,
                COALESCE(SUM(successful_requests), 0) AS successful_requests,
                COALESCE(SUM(failed_requests), 0) AS failed_requests,
                COALESCE(SUM(input_tokens), 0) AS input_tokens,
                COALESCE(SUM(output_tokens), 0) AS output_tokens,
                COALESCE(SUM(credits), 0) AS credits,
                MAX(last_request_at) AS last_used_at
            FROM usage_segments
            GROUP BY COALESCE(NULLIF(model, ''), '未指定模型')
            ORDER BY request_count DESC,
                (COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0)) DESC
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
            .bind("limit", MODEL_USAGE_LIMIT)
            .map((row, metadata) -> modelUsage(row))
            .all();
    }

    private Flux<StatsDtos.ModelUsageResponse> modelUsageForUser(String userId) {
        String sql = USAGE_SEGMENTS_CTE + """
            SELECT
                COALESCE(NULLIF(s.model, ''), '未指定模型') AS model_name,
                COALESCE(SUM(s.request_count), 0) AS request_count,
                COALESCE(SUM(s.successful_requests), 0) AS successful_requests,
                COALESCE(SUM(s.failed_requests), 0) AS failed_requests,
                COALESCE(SUM(s.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(s.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(s.credits), 0) AS credits,
                MAX(s.last_request_at) AS last_used_at
            FROM usage_segments s
            WHERE s.user_id = :userId
            GROUP BY COALESCE(NULLIF(s.model, ''), '未指定模型')
            ORDER BY request_count DESC,
                (COALESCE(SUM(s.input_tokens), 0) + COALESCE(SUM(s.output_tokens), 0)) DESC
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("limit", MODEL_USAGE_LIMIT)
            .map((row, metadata) -> modelUsage(row))
            .all();
    }

    private StatsDtos.UsageSummaryResponse response(
        UsageTotals totals,
        Instant activeSince,
        List<StatsDtos.ActiveUserResponse> activeUsers,
        List<StatsDtos.ModelUsageResponse> models
    ) {
        long inputTokens = totals.inputTokens();
        long outputTokens = totals.outputTokens();
        return new StatsDtos.UsageSummaryResponse(
            totals.requestCount(),
            totals.successfulRequests(),
            totals.failedRequests(),
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            totals.credits(),
            activeSince,
            activeUsers.size(),
            activeUsers,
            models
        );
    }

    private StatsDtos.ActiveUserResponse activeUser(Row row) {
        long inputTokens = longValue(row, "input_tokens");
        long outputTokens = longValue(row, "output_tokens");
        return new StatsDtos.ActiveUserResponse(
            stringValue(row, "user_id"),
            stringValue(row, "username"),
            stringValue(row, "display_name"),
            stringValue(row, "email"),
            longValue(row, "request_count"),
            longValue(row, "api_key_count"),
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            decimalValue(row, "credits"),
            instantValue(row, "last_active_at")
        );
    }

    private StatsDtos.ModelUsageResponse modelUsage(Row row) {
        long inputTokens = longValue(row, "input_tokens");
        long outputTokens = longValue(row, "output_tokens");
        return new StatsDtos.ModelUsageResponse(
            stringValue(row, "model_name"),
            longValue(row, "request_count"),
            longValue(row, "successful_requests"),
            longValue(row, "failed_requests"),
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            decimalValue(row, "credits"),
            instantValue(row, "last_used_at")
        );
    }

    private static long longValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return 0L;
    }

    private static BigDecimal decimalValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }
        return BigDecimal.ZERO;
    }

    private static String stringValue(Row row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    private static Instant instantValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        }
        return null;
    }

    private record UsageTotals(
        long requestCount,
        long successfulRequests,
        long failedRequests,
        long inputTokens,
        long outputTokens,
        BigDecimal credits
    ) {
        static UsageTotals empty() {
            return new UsageTotals(0, 0, 0, 0, 0, BigDecimal.ZERO);
        }
    }
}
