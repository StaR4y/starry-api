package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;

class ModelMappingAdminControllerTest {

    @Test
    void allEndpointsRequireAdministratorRole() {
        ModelMappingService service = mock(ModelMappingService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        MockServerWebExchange exchange = exchange();
        ModelMappingAdminController controller = new ModelMappingAdminController(service, adminAuth);
        ApiException forbidden = new ApiException(
            HttpStatus.FORBIDDEN,
            "ADMIN_ROLE_REQUIRED",
            "Administrator role is required"
        );

        when(adminAuth.verifyAdmin(exchange)).thenThrow(forbidden);

        assertThatThrownBy(() -> controller.list(exchange))
            .isSameAs(forbidden);
        assertThatThrownBy(() -> controller.create(exchange, createRequest()))
            .isSameAs(forbidden);
        assertThatThrownBy(() -> controller.update(exchange, "mapping-1", updateRequest()))
            .isSameAs(forbidden);
        assertThatThrownBy(() -> controller.delete(exchange, "mapping-1"))
            .isSameAs(forbidden);
        verifyNoInteractions(service);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/admin/model-mappings"));
    }

    private ModelDtos.CreateMappingRequest createRequest() {
        return new ModelDtos.CreateMappingRequest(
            "mapping",
            "replace",
            "gpt-4",
            List.of("claude-sonnet-4.5"),
            null,
            null,
            null
        );
    }

    private ModelDtos.UpdateMappingRequest updateRequest() {
        return new ModelDtos.UpdateMappingRequest(
            "mapping",
            true,
            "replace",
            "gpt-4",
            List.of("claude-sonnet-4.5"),
            null,
            100,
            null
        );
    }
}
