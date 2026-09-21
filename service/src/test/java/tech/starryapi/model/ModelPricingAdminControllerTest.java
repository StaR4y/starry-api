package tech.starryapi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiException;
import tech.starryapi.payment.PaymentSettingsService;

class ModelPricingAdminControllerTest {
    private final ModelPricingService pricing = mock(ModelPricingService.class);
    private final OfficialModelPricingService official = mock(OfficialModelPricingService.class);
    private final AdminAuth auth = mock(AdminAuth.class);
    private final PaymentSettingsService payment = mock(PaymentSettingsService.class);
    private final ModelPricingAdminController controller = new ModelPricingAdminController(pricing, official, auth, payment);
    private final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/admin/model-pricing/preview"));

    @Test
    void previewRejectsNonAdministratorsBeforeReadingPrices() {
        var error = new ApiException(HttpStatus.FORBIDDEN, "ADMIN_ROLE_REQUIRED", "Administrator required");
        when(auth.verifyAdmin(exchange)).thenThrow(error);
        assertThatThrownBy(() -> controller.preview(exchange, new ModelDtos.ModelPricingPreviewRequest("gpt-5.5", List.of())))
            .isSameAs(error);
        verifyNoInteractions(pricing, official, payment);
    }

    @Test
    void previewUsesCurrentPaymentMultiplierWithSubmittedDraft() {
        var config = mock(PaymentSettingsService.PaymentConfig.class);
        when(payment.config()).thenReturn(Mono.just(config));
        when(config.apiBillingMultiplier()).thenReturn(new BigDecimal("2"));
        var request = new ModelDtos.ModelPricingPreviewRequest("gpt-5.5", List.of());
        var result = new ModelDtos.ModelPricingPreview("gpt-5.5", "gpt-5.5", "official", "gpt-5.5",
            new BigDecimal("2"), List.of(), null, null);
        when(pricing.preview(request, new BigDecimal("2"))).thenReturn(result);
        assertThat(controller.preview(exchange, request).block().data()).isSameAs(result);
        verify(auth).verifyAdmin(exchange);
        verifyNoInteractions(official);
    }

    @Test
    void httpPreviewValidatesBlankModel() {
        WebTestClient.bindToController(controller).build().post().uri("/admin/model-pricing/preview")
            .header("Content-Type", "application/json")
            .bodyValue(new ModelDtos.ModelPricingPreviewRequest(" ", List.of()))
            .exchange().expectStatus().isBadRequest();
        verifyNoInteractions(pricing, payment);
    }
}
