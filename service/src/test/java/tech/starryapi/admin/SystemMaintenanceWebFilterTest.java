package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class SystemMaintenanceWebFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void runsAtTheHighestFilterPriority() {
        assertThat(filter(mock(DataManagementSecurityService.class)).getOrder())
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void unlockedSystemPassesRequestsThrough() {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(false));
        SystemMaintenanceWebFilter filter = filter(securityService);
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/admin/accounts");
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void lockedSystemBlocksEveryApplicationServiceNamespace() throws Exception {
        for (String path : List.of(
            "/auth/login",
            "/admin/accounts",
            "/v1/chat/completions",
            "/payment/orders",
            "/models"
        )) {
            DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
            when(securityService.isLocked()).thenReturn(Mono.just(true));
            WebFilterChain chain = mock(WebFilterChain.class);
            MockServerWebExchange exchange = exchange(path);

            filter(securityService).filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode())
                .as(path)
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(errorCode(exchange)).isEqualTo("SYSTEM_MAINTENANCE");
            verify(chain, never()).filter(exchange);
        }
    }

    @Test
    void lockedSystemAllowsOnlyStatusHealthAndStaticResources() {
        for (String path : List.of(
            "/system/status",
            "/system/appearance",
            "/system/model-catalog-display",
            "/system/model-ranking",
            "/system/model-ranking/programbench",
            "/health",
            "/v1/health",
            "/actuator/health",
            "/actuator/health/readiness",
            "/",
            "/index.html",
            "/favicon.svg",
            "/assets/index.js",
            "/assets/index.css.br",
            "/plugin-assets/usage-maid/maid.png"
        )) {
            DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
            when(securityService.isLocked()).thenReturn(Mono.just(true));
            WebFilterChain chain = mock(WebFilterChain.class);
            MockServerWebExchange exchange = exchange(path);
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            filter(securityService).filter(exchange, chain).block();

            verify(chain).filter(exchange);
            assertThat(exchange.getResponse().getStatusCode()).as(path).isNull();
        }
    }

    @Test
    void lockSnapshotFailureAlsoBlocksRequests() throws Exception {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.error(new IllegalStateException("snapshot unavailable")));
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/auth/login");

        filter(securityService).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(exchange)).isEqualTo("SYSTEM_MAINTENANCE");
        verify(chain, never()).filter(exchange);
    }

    @Test
    void encodedTraversalCannotUseTheStaticResourceAllowlist() throws Exception {
        DataManagementSecurityService securityService = mock(DataManagementSecurityService.class);
        when(securityService.isLocked()).thenReturn(Mono.just(true));
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = exchange("/assets/%2e%2e/auth/login");

        filter(securityService).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(exchange)).isEqualTo("SYSTEM_MAINTENANCE");
        verify(chain, never()).filter(exchange);
    }

    private SystemMaintenanceWebFilter filter(DataManagementSecurityService securityService) {
        return new SystemMaintenanceWebFilter(securityService, objectMapper);
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private String errorCode(MockServerWebExchange exchange) throws Exception {
        String json = exchange.getResponse().getBody()
            .map(this::read)
            .collectList()
            .map(parts -> String.join("", parts))
            .block();
        JsonNode body = objectMapper.readTree(json);
        return body.path("error").path("code").asText();
    }

    private String read(DataBuffer buffer) {
        byte[] bytes = new byte[buffer.readableByteCount()];
        buffer.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
