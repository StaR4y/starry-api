package tech.starryapi.admin;

import java.time.Instant;

public final class SystemStatusDtos {

    private SystemStatusDtos() {
    }

    public record SystemStatusResponse(
        String status,
        boolean maintenance,
        String code,
        String message,
        Instant checkedAt
    ) {
    }
}
