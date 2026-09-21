package tech.starryapi.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import tech.starryapi.common.ApiException;

final class UsageQueryWindow {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final Instant UNBOUNDED_FROM = Instant.parse("1970-01-01T00:00:00Z");
    static final Instant UNBOUNDED_TO = Instant.parse("9999-01-01T00:00:00Z");
    static final int MAX_SPAN_DAYS = 3660;
    static final int DEFAULT_LOG_LIMIT = 100;
    static final int MAX_LOG_LIMIT = 200;

    private final Instant fromInclusive;
    private final Instant toExclusive;

    private UsageQueryWindow(Instant fromInclusive, Instant toExclusive) {
        this.fromInclusive = fromInclusive;
        this.toExclusive = toExclusive;
    }

    Instant fromInclusive() {
        return fromInclusive;
    }

    Instant toExclusive() {
        return toExclusive;
    }

    static UsageQueryWindow parse(String from, String to) {
        boolean fromBlank = from == null || from.isBlank();
        boolean toBlank = to == null || to.isBlank();
        if (fromBlank && toBlank) {
            return new UsageQueryWindow(UNBOUNDED_FROM, UNBOUNDED_TO);
        }
        LocalDate fromDate = fromBlank ? LocalDate.ofInstant(UNBOUNDED_FROM, ZONE) : day(from);
        LocalDate toDate = toBlank ? LocalDate.now(ZONE) : day(to);
        if (fromDate.isAfter(toDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USAGE_DATE_RANGE_INVALID", "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(fromDate, toDate) > MAX_SPAN_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USAGE_DATE_RANGE_TOO_LONG", "查询区间过长");
        }
        return new UsageQueryWindow(
            fromDate.atStartOfDay(ZONE).toInstant(),
            toDate.plusDays(1).atStartOfDay(ZONE).toInstant()
        );
    }

    static int pageLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LOG_LIMIT;
        }
        if (limit < 1 || limit > MAX_LOG_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USAGE_LOG_LIMIT_INVALID", "每页条数必须在 1 到 200 之间");
        }
        return limit;
    }

    static Instant before(String before) {
        if (before == null || before.isBlank()) {
            return UNBOUNDED_TO;
        }
        try {
            return Instant.parse(before.trim());
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USAGE_LOG_CURSOR_INVALID", "分页游标无效");
        }
    }

    private static LocalDate day(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USAGE_DATE_INVALID", "日期格式必须是 YYYY-MM-DD");
        }
    }
}
