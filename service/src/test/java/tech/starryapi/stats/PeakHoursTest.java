package tech.starryapi.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PeakHoursTest {

    @Test
    void weekdayMorningAndAfternoonArePeak() {
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-09-14T01:00:00Z"))).isTrue();
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-09-14T06:30:00Z"))).isTrue();
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-09-14T04:00:00Z"))).isFalse();
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-09-14T10:00:00Z"))).isFalse();
    }

    @Test
    void weekendIsOffPeakAfterFlatRateDate() {
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-09-12T01:30:00Z"))).isFalse();
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-09-13T06:00:00Z"))).isFalse();
    }

    @Test
    void weekendStillSplitBeforeFlatRateDate() {
        assertThat(PeakHours.isPeakAt(Instant.parse("2026-08-22T01:30:00Z"))).isTrue();
    }
}
