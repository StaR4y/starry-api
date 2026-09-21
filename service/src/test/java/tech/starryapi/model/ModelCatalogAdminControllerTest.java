package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.common.ApiResponse;
import tech.starryapi.payment.PaymentSettingsService;

class ModelCatalogAdminControllerTest {

    @Test
    void allEndpointsRequireAdministratorRole() {
        ModelCatalogManagementService service = mock(ModelCatalogManagementService.class);
        ModelIconStorageService iconStorageService = mock(ModelIconStorageService.class);
        ModelPricingService modelPricingService = mock(ModelPricingService.class);
        PaymentSettingsService paymentSettingsService = mock(PaymentSettingsService.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/admin/models")
        );
        ModelCatalogAdminController controller = new ModelCatalogAdminController(
            service,
            iconStorageService,
            modelPricingService,
            paymentSettingsService,
            adminAuth
        );
        ApiException forbidden = new ApiException(
            HttpStatus.FORBIDDEN,
            "ADMIN_ROLE_REQUIRED",
            "Administrator role is required"
        );
        when(adminAuth.verifyAdmin(exchange)).thenThrow(forbidden);

        assertThatThrownBy(() -> controller.list(exchange)).isSameAs(forbidden);
        assertThatThrownBy(() -> controller.upsert(exchange, request())).isSameAs(forbidden);
        assertThatThrownBy(() -> controller.uploadIcon(exchange, reactor.core.publisher.Mono.empty()))
            .isSameAs(forbidden);
        assertThatThrownBy(() -> controller.delete(exchange, "team-model")).isSameAs(forbidden);
        assertThatThrownBy(() -> controller.deleteVendor(exchange, "OpenRouter")).isSameAs(forbidden);
        assertThatThrownBy(() -> controller.reorder(
            exchange,
            new ModelDtos.ReorderManagedModelsRequest(List.of("team-model"))
        )).isSameAs(forbidden);
        assertThatThrownBy(() -> controller.getDisplay(exchange)).isSameAs(forbidden);
        assertThatThrownBy(() -> controller.updateDisplay(
            exchange,
            new ModelDtos.UpdateModelCatalogDisplayRequest(
                "list", null, null, null, null, null, null, null, null, null, null
            )
        )).isSameAs(forbidden);
        verifyNoInteractions(service);
        verifyNoInteractions(iconStorageService);
        verifyNoInteractions(modelPricingService);
        verifyNoInteractions(paymentSettingsService);
    }

    @Test
    void listReturnsManagedCatalogWithEffectivePricing() {
        ModelCatalogManagementService service = mock(ModelCatalogManagementService.class);
        ModelIconStorageService iconStorageService = mock(ModelIconStorageService.class);
        ModelPricingService modelPricingService = mock(ModelPricingService.class);
        PaymentSettingsService paymentSettingsService = mock(PaymentSettingsService.class);
        PaymentSettingsService.PaymentConfig paymentConfig = mock(PaymentSettingsService.PaymentConfig.class);
        AdminAuth adminAuth = mock(AdminAuth.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/admin/models")
        );
        ModelCatalogAdminController controller = new ModelCatalogAdminController(
            service,
            iconStorageService,
            modelPricingService,
            paymentSettingsService,
            adminAuth
        );
        ModelDtos.ManagedModelResponse disabledModel = managedModel("disabled-model", false);
        ModelDtos.ManagedModelResponse pricedModel = disabledModel.withPricing(
            new ModelDtos.ModelPublicPricing("total", new BigDecimal("33333333"), null, null)
        );
        BigDecimal billingMultiplier = new BigDecimal("1.25");
        when(service.list()).thenReturn(Mono.just(List.of(disabledModel)));
        when(paymentSettingsService.config()).thenReturn(Mono.just(paymentConfig));
        when(paymentConfig.apiBillingMultiplier()).thenReturn(billingMultiplier);
        when(modelPricingService.applyManagedPricing(List.of(disabledModel), billingMultiplier))
            .thenReturn(Mono.just(List.of(pricedModel)));

        ApiResponse<List<ModelDtos.ManagedModelResponse>> response = controller.list(exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly(pricedModel);
        assertThat(response.data().getFirst().enabled()).isFalse();
        assertThat(response.data().getFirst().pricing()).isNotNull();
    }

    private ModelDtos.UpsertManagedModelRequest request() {
        return new ModelDtos.UpsertManagedModelRequest(
            "team-model",
            "Team Model",
            null,
            "Internal",
            "Team",
            "claude-sonnet-4.5",
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            200_000,
            null,
            true
        );
    }

    private ModelDtos.ManagedModelResponse managedModel(String id, boolean enabled) {
        return new ModelDtos.ManagedModelResponse(
            id,
            id,
            null,
            "Test",
            "Test",
            id,
            "OPENAI_CHAT_COMPLETIONS",
            "/v1/chat/completions",
            128_000,
            null,
            enabled,
            true
        );
    }
}
