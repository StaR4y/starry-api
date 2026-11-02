package tech.starryapi.stats;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Beijing peak hours used by the usage-maid calendar. */
final class PeakHours {

    static final Instant WEEKEND_FLAT_AT = Instant.parse("2026-08-22T16:00:00Z");

    private PeakHours() {
    }

    static boolean isPeakAt(Instant instant) {
        if (instant == null) {
            return false;
        }
        long epochMilli = instant.toEpochMilli();
        LocalDateTime beijing = LocalDateTime.ofEpochSecond(
            Math.floorDiv(epochMilli + 8 * 3600_000L, 1000L),
            Math.floorMod(epochMilli, 1000) * 1_000_000,
            ZoneOffset.UTC
        );
        DayOfWeek weekday = beijing.getDayOfWeek();
        if (epochMilli >= WEEKEND_FLAT_AT.toEpochMilli()
            && (weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY)) {
            return false;
        }
        int minutes = beijing.getHour() * 60 + beijing.getMinute();
        return (minutes >= 9 * 60 && minutes < 12 * 60) || (minutes >= 14 * 60 && minutes < 18 * 60);
    }
}
