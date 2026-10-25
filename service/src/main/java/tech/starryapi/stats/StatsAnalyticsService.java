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
    private static final int TRACKER_MODEL_LIMIT = 200;
    private static final String TRACKER_SEGMENTS_CTE = trackerSegmentsCte();

    private static String trackerSegmentsCte() {
        String hourlyPeak = peakAtSql("h.bucket_start");
        String rawPeak = peakAtSql("l.created_at");
        return """
            WITH aggregation_boundary AS (
                SELECT (
                    SELECT last_processed_at
                    FROM aggregation_watermarks
                    WHERE job_name = 'request_usage'
                ) AS watermark
            ),
            usage_segments AS (
                SELECT
                    d.user_id,
                    d.model,
                    d.request_count,
                    d.successful_requests,
                    d.failed_requests,
                    d.input_tokens,
                    d.output_tokens,
                    d.credits,
                    COALESCE(d.last_request_at, (d.usage_date::timestamp AT TIME ZONE 'UTC')) AS segment_at,
                    to_char(
                        (COALESCE(d.last_request_at, (d.usage_date::timestamp AT TIME ZONE 'UTC'))
                            AT TIME ZONE 'Asia/Shanghai')::date,
                        'YYYY-MM-DD'
                    ) AS usage_day,
                    0::BIGINT AS peak_requests,
                    0::BIGINT AS off_peak_requests,
                    0::NUMERIC AS peak_credits,
                    0::NUMERIC AS off_peak_credits
                FROM request_usage_daily d
                CROSS JOIN aggregation_boundary b
                WHERE b.watermark IS NOT NULL
                  AND d.usage_date < (b.watermark AT TIME ZONE 'UTC')::DATE

                UNION ALL

                SELECT
                    h.user_id,
                    h.model,
                    h.request_count,
                    h.successful_requests,
                    h.failed_requests,
                    h.input_tokens,
                    h.output_tokens,
                    h.credits,
                    h.bucket_start AS segment_at,
                    to_char((h.bucket_start AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM-DD') AS usage_day,
                    CASE WHEN %1$s THEN h.request_count ELSE 0 END,
                    CASE WHEN %1$s THEN 0 ELSE h.request_count END,
                    CASE WHEN %1$s THEN h.credits ELSE 0 END,
                    CASE WHEN %1$s THEN 0 ELSE h.credits END
                FROM request_usage_hourly h
                CROSS JOIN aggregation_boundary b
                WHERE b.watermark IS NOT NULL
                  AND h.bucket_start >= pg_catalog.date_trunc('day', b.watermark, 'UTC')
                  AND h.bucket_start < b.watermark

                UNION ALL

                SELECT
                    COALESCE(l.user_id, ''),
                    COALESCE(l.model, ''),
                    1::BIGINT,
                    CASE WHEN l.success THEN 1::BIGINT ELSE 0::BIGINT END,
                    CASE WHEN l.success THEN 0::BIGINT ELSE 1::BIGINT END,
                    l.input_tokens,
                    l.output_tokens,
                    l.credits,
                    l.created_at AS segment_at,
                    to_char((l.created_at AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM-DD') AS usage_day,
                    CASE WHEN %2$s THEN 1::BIGINT ELSE 0::BIGINT END,
                    CASE WHEN %2$s THEN 0::BIGINT ELSE 1::BIGINT END,
                    CASE WHEN %2$s THEN l.credits ELSE 0 END,
                    CASE WHEN %2$s THEN 0 ELSE l.credits END
                FROM request_logs l
                CROSS JOIN aggregation_boundary b
                WHERE b.watermark IS NULL OR l.created_at >= b.watermark
            )
            """.formatted(hourlyPeak, rawPeak);
    }
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

    public Mono<StatsDtos.UsageTrackerResponse> usageTrackerForUser(String userId, String from, String to) {
        UsageQueryWindow window = UsageQueryWindow.parse(from, to);
        return Mono.zip(
                trackerTotals(userId, window),
                trackerModels(userId, window).collectList(),
                trackerDays(userId, window).collectList()
            )
            .map(tuple -> trackerResponse(window, tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    public Mono<StatsDtos.RequestLogPageResponse> logsForUser(
        String userId,
        String from,
        String to,
        String before,
        Integer limit
    ) {
        UsageQueryWindow window = UsageQueryWindow.parse(from, to);
        Instant cursor = UsageQueryWindow.before(before);
        int pageSize = UsageQueryWindow.pageLimit(limit);
        String sql = """
            SELECT
                request_id,
                api_key_id,
                account_id,
                path,
                model,
                status_code,
                success,
                input_tokens,
                output_tokens,
                credits,
                latency_ms,
                error_message,
                created_at,
                source_tier_level,
                source_tier_name,
                price_factor
            FROM request_logs
            WHERE user_id = :userId
              AND created_at >= :fromInclusive
              AND created_at < :toExclusive
              AND created_at < :before
            ORDER BY created_at DESC
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("fromInclusive", window.fromInclusive())
            .bind("toExclusive", window.toExclusive())
            .bind("before", cursor)
            .bind("limit", pageSize + 1)
            .map((row, metadata) -> requestLog(row))
            .all()
            .collectList()
            .map(rows -> {
                boolean hasMore = rows.size() > pageSize;
                List<StatsDtos.RequestLogResponse> items = hasMore ? rows.subList(0, pageSize) : rows;
                Instant nextBefore = items.isEmpty() ? null : items.getLast().createdAt();
                return new StatsDtos.RequestLogPageResponse(List.copyOf(items), hasMore, nextBefore);
            });
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

    private Mono<TrackerTotals> trackerTotals(String userId, UsageQueryWindow window) {
        String sql = TRACKER_SEGMENTS_CTE + """
            SELECT
                COALESCE(SUM(s.request_count), 0) AS request_count,
                COALESCE(SUM(s.successful_requests), 0) AS successful_requests,
                COALESCE(SUM(s.failed_requests), 0) AS failed_requests,
                COALESCE(SUM(s.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(s.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(s.credits), 0) AS credits,
                COALESCE(SUM(s.peak_requests), 0) AS peak_requests,
                COALESCE(SUM(s.off_peak_requests), 0) AS off_peak_requests,
                COALESCE(SUM(s.peak_credits), 0) AS peak_credits,
                COALESCE(SUM(s.off_peak_credits), 0) AS off_peak_credits
            FROM usage_segments s
            WHERE s.user_id = :userId
              AND s.segment_at >= :fromInclusive
              AND s.segment_at < :toExclusive
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("fromInclusive", window.fromInclusive())
            .bind("toExclusive", window.toExclusive())
            .map((row, metadata) -> new TrackerTotals(
                longValue(row, "request_count"),
                longValue(row, "successful_requests"),
                longValue(row, "failed_requests"),
                longValue(row, "input_tokens"),
                longValue(row, "output_tokens"),
                decimalValue(row, "credits"),
                longValue(row, "peak_requests"),
                longValue(row, "off_peak_requests"),
                decimalValue(row, "peak_credits"),
                decimalValue(row, "off_peak_credits")
            ))
            .one()
            .defaultIfEmpty(TrackerTotals.empty());
    }

    private Flux<StatsDtos.ModelUsageResponse> trackerModels(String userId, UsageQueryWindow window) {
        String sql = TRACKER_SEGMENTS_CTE + """
            SELECT
                COALESCE(NULLIF(s.model, ''), '未指定模型') AS model_name,
                COALESCE(SUM(s.request_count), 0) AS request_count,
                COALESCE(SUM(s.successful_requests), 0) AS successful_requests,
                COALESCE(SUM(s.failed_requests), 0) AS failed_requests,
                COALESCE(SUM(s.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(s.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(s.credits), 0) AS credits,
                MAX(s.segment_at) AS last_used_at
            FROM usage_segments s
            WHERE s.user_id = :userId
              AND s.segment_at >= :fromInclusive
              AND s.segment_at < :toExclusive
            GROUP BY COALESCE(NULLIF(s.model, ''), '未指定模型')
            ORDER BY request_count DESC,
                (COALESCE(SUM(s.input_tokens), 0) + COALESCE(SUM(s.output_tokens), 0)) DESC
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("fromInclusive", window.fromInclusive())
            .bind("toExclusive", window.toExclusive())
            .bind("limit", TRACKER_MODEL_LIMIT)
            .map((row, metadata) -> modelUsage(row))
            .all();
    }

    private Flux<StatsDtos.UsageDayResponse> trackerDays(String userId, UsageQueryWindow window) {
        String sql = TRACKER_SEGMENTS_CTE + """
            SELECT
                s.usage_day AS usage_day,
                COALESCE(SUM(s.request_count), 0) AS request_count,
                COALESCE(SUM(s.successful_requests), 0) AS successful_requests,
                COALESCE(SUM(s.failed_requests), 0) AS failed_requests,
                COALESCE(SUM(s.input_tokens), 0) AS input_tokens,
                COALESCE(SUM(s.output_tokens), 0) AS output_tokens,
                COALESCE(SUM(s.credits), 0) AS credits,
                COALESCE(SUM(s.peak_requests), 0) AS peak_requests,
                COALESCE(SUM(s.off_peak_requests), 0) AS off_peak_requests,
                COALESCE(SUM(s.peak_credits), 0) AS peak_credits,
                COALESCE(SUM(s.off_peak_credits), 0) AS off_peak_credits
            FROM usage_segments s
            WHERE s.user_id = :userId
              AND s.segment_at >= :fromInclusive
              AND s.segment_at < :toExclusive
            GROUP BY s.usage_day
            ORDER BY s.usage_day
            """;
        return databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("fromInclusive", window.fromInclusive())
            .bind("toExclusive", window.toExclusive())
            .map((row, metadata) -> usageDay(row))
            .all();
    }

    private StatsDtos.UsageTrackerResponse trackerResponse(
        UsageQueryWindow window,
        TrackerTotals totals,
        List<StatsDtos.ModelUsageResponse> models,
        List<StatsDtos.UsageDayResponse> days
    ) {
        long inputTokens = totals.inputTokens();
        long outputTokens = totals.outputTokens();
        return new StatsDtos.UsageTrackerResponse(
            window.fromInclusive(),
            window.toExclusive(),
            totals.requestCount(),
            totals.successfulRequests(),
            totals.failedRequests(),
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            totals.credits(),
            totals.peakRequests(),
            totals.offPeakRequests(),
            totals.peakCredits(),
            totals.offPeakCredits(),
            models,
            days
        );
    }

    private StatsDtos.UsageDayResponse usageDay(Row row) {
        long inputTokens = longValue(row, "input_tokens");
        long outputTokens = longValue(row, "output_tokens");
        return new StatsDtos.UsageDayResponse(
            stringValue(row, "usage_day"),
            longValue(row, "request_count"),
            longValue(row, "successful_requests"),
            longValue(row, "failed_requests"),
            inputTokens,
            outputTokens,
            inputTokens + outputTokens,
            decimalValue(row, "credits"),
            longValue(row, "peak_requests"),
            longValue(row, "off_peak_requests"),
            decimalValue(row, "peak_credits"),
            decimalValue(row, "off_peak_credits")
        );
    }

    // A user sees the name, level and factor of the tier that served a request, and nothing else about its source
    // (design 5.7): the other source columns of the row are neither selected nor returned.
    private StatsDtos.RequestLogResponse requestLog(Row row) {
        return new StatsDtos.RequestLogResponse(
            stringValue(row, "request_id"),
            stringValue(row, "api_key_id"),
            stringValue(row, "account_id"),
            stringValue(row, "path"),
            stringValue(row, "model"),
            integerValue(row, "status_code"),
            booleanValue(row, "success"),
            longValue(row, "input_tokens"),
            longValue(row, "output_tokens"),
            decimalValue(row, "credits"),
            longValue(row, "latency_ms"),
            stringValue(row, "error_message"),
            instantValue(row, "created_at"),
            null, // source_tier_ref
            integerValue(row, "source_tier_level"),
            stringValue(row, "source_tier_name"),
            row.get("price_factor") == null ? null : decimalValue(row, "price_factor"),
            null, // tier_selection, source_dispatch, wire_model, usage_source, stop_reason, degraded_features
            null,
            null,
            null,
            null,
            null
        );
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

    private static String peakAtSql(String timestampExpression) {
        return """
            (
              CASE
                WHEN (%1$s AT TIME ZONE 'Asia/Shanghai') >= TIMESTAMP '2026-08-23'
                     AND EXTRACT(DOW FROM (%1$s AT TIME ZONE 'Asia/Shanghai')) IN (0, 6)
                  THEN FALSE
                WHEN (EXTRACT(HOUR FROM (%1$s AT TIME ZONE 'Asia/Shanghai')) * 60
                    + EXTRACT(MINUTE FROM (%1$s AT TIME ZONE 'Asia/Shanghai'))) >= 540
                 AND (EXTRACT(HOUR FROM (%1$s AT TIME ZONE 'Asia/Shanghai')) * 60
                    + EXTRACT(MINUTE FROM (%1$s AT TIME ZONE 'Asia/Shanghai'))) < 720
                  THEN TRUE
                WHEN (EXTRACT(HOUR FROM (%1$s AT TIME ZONE 'Asia/Shanghai')) * 60
                    + EXTRACT(MINUTE FROM (%1$s AT TIME ZONE 'Asia/Shanghai'))) >= 840
                 AND (EXTRACT(HOUR FROM (%1$s AT TIME ZONE 'Asia/Shanghai')) * 60
                    + EXTRACT(MINUTE FROM (%1$s AT TIME ZONE 'Asia/Shanghai'))) < 1080
                  THEN TRUE
                ELSE FALSE
              END
            )
            """.formatted(timestampExpression);
    }

    private static Integer integerValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return null;
    }

    private static Boolean booleanValue(Row row, String column) {
        Object value = row.get(column);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.FALSE;
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

    private record TrackerTotals(
        long requestCount,
        long successfulRequests,
        long failedRequests,
        long inputTokens,
        long outputTokens,
        BigDecimal credits,
        long peakRequests,
        long offPeakRequests,
        BigDecimal peakCredits,
        BigDecimal offPeakCredits
    ) {
        static TrackerTotals empty() {
            return new TrackerTotals(0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
