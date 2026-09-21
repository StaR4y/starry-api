package tech.starryapi.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tech.starryapi.common.ApiException;

class UsageQueryWindowTest {

    @Test
    void unboundedWindowWhenBothDatesMissing() {
        UsageQueryWindow window = UsageQueryWindow.parse(null, "  ");
        assertThat(window.fromInclusive()).isEqualTo(UsageQueryWindow.UNBOUNDED_FROM);
        assertThat(window.toExclusive()).isEqualTo(UsageQueryWindow.UNBOUNDED_TO);
    }

    @Test
    void shanghaiDayRangeIsHalfOpen() {
        UsageQueryWindow window = UsageQueryWindow.parse("2026-09-01", "2026-09-01");
        assertThat(window.fromInclusive().toString()).isEqualTo("2026-08-31T16:00:00Z");
        assertThat(window.toExclusive().toString()).isEqualTo("2026-09-01T16:00:00Z");
    }

    @Test
    void missingToUsesTodayInShanghai() {
        UsageQueryWindow window = UsageQueryWindow.parse("2026-01-01", null);
        LocalDate today = LocalDate.now(UsageQueryWindow.ZONE);
        assertThat(window.toExclusive())
            .isEqualTo(today.plusDays(1).atStartOfDay(UsageQueryWindow.ZONE).toInstant());
    }

    @Test
    void rejectsInvertedRangeAndBadInput() {
        assertThatThrownBy(() -> UsageQueryWindow.parse("2026-09-10", "2026-09-01"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("USAGE_DATE_RANGE_INVALID");
        assertThatThrownBy(() -> UsageQueryWindow.parse("09-01", null))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("USAGE_DATE_INVALID");
        assertThatThrownBy(() -> UsageQueryWindow.parse("2000-01-01", "2011-01-01"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("USAGE_DATE_RANGE_TOO_LONG");
        assertThatThrownBy(() -> UsageQueryWindow.pageLimit(0))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("USAGE_LOG_LIMIT_INVALID");
        assertThatThrownBy(() -> UsageQueryWindow.before("yesterday"))
            .isInstanceOf(ApiException.class)
            .extracting(error -> ((ApiException) error).getCode())
            .isEqualTo("USAGE_LOG_CURSOR_INVALID");
    }
}
