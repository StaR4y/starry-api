package tech.starryapi.stats;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.GlobalExceptionHandler;

class AdminStatsControllerTest {

    private StatsAnalyticsService analytics;
    private AdminAuth adminAuth;
    private WebTestClient client;

    @BeforeEach
    void setup() {
        RequestLogRepository logs = mock(RequestLogRepository.class);
        PerformanceMetricService performance = mock(PerformanceMetricService.class);
        analytics = mock(StatsAnalyticsService.class);
        adminAuth = mock(AdminAuth.class);
        when(adminAuth.verify(any())).thenReturn(new AdminPrincipal("user-1", "starry", "USER", null, false));
        client = WebTestClient.bindToController(
                new AdminStatsController(logs, performance, analytics, adminAuth)
            )
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void usageTrackerIsScopedToCurrentUser() {
        StatsDtos.UsageTrackerResponse payload = new StatsDtos.UsageTrackerResponse(
            Instant.parse("2026-09-01T16:00:00Z"),
            Instant.parse("2026-09-02T16:00:00Z"),
            3L, 2L, 1L, 30L, 12L, 42L, new BigDecimal("1.25"),
            1L, 2L, new BigDecimal("0.40"), new BigDecimal("0.85"),
            List.of(),
            List.of(new StatsDtos.UsageDayResponse(
                "2026-09-02", 3L, 2L, 1L, 30L, 12L, 42L, new BigDecimal("1.25"),
                1L, 2L, new BigDecimal("0.40"), new BigDecimal("0.85")
            ))
        );
        when(analytics.usageTrackerForUser("user-1", "2026-09-02", "2026-09-02"))
            .thenReturn(Mono.just(payload));

        client.get()
            .uri("/admin/usage-tracker/me?from=2026-09-02&to=2026-09-02")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.data.totalRequests").isEqualTo(3)
            .jsonPath("$.data.days[0].day").isEqualTo("2026-09-02");
        verify(adminAuth).verify(any());
    }

    @Test
    void myLogsRejectsInvalidLimitThroughServiceContract() {
        when(analytics.logsForUser(eq("user-1"), isNull(), isNull(), isNull(), eq(0)))
            .thenThrow(new tech.starryapi.common.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "USAGE_LOG_LIMIT_INVALID",
                "每页条数必须在 1 到 200 之间"
            ));

        client.get()
            .uri("/admin/logs/me?limit=0")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error.code").isEqualTo("USAGE_LOG_LIMIT_INVALID");
    }
}
