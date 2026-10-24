package tech.starryapi.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;

class EasyPayClientHardeningTest {

    @Test
    void theMerchantKeyIsStrippedFromAnyErrorText() {
        String leaked = "404 Not Found from GET https://pay.example.com/api.php?act=order&pid=1001&key=s3cr3t-merchant-key&out_trade_no=T1";

        assertThat(EasyPayClient.redact(leaked)).doesNotContain("s3cr3t-merchant-key").contains("key=***&out_trade_no=T1");
        WebClientResponseException http = WebClientResponseException.create(
            404, "Not Found", org.springframework.http.HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8
        );
        assertThat(EasyPayClient.describeFailure(http)).isEqualTo("HTTP 404");
    }

    @Test
    void aNotificationWithAWrongSignatureIsRejected() {
        EasyPayClient client = new EasyPayClient(WebClient.builder().build(), new ObjectMapper());
        PaymentSettingsService.PaymentConfig config = new PaymentSettingsService.PaymentConfig(
            true, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, 30, 3, java.util.List.of("alipay"), BigDecimal.ONE,
            BigDecimal.ONE, BigDecimal.ZERO, "", "", "", "", "https://pay.example.com", "1001", "secret", "popup", false,
            BigDecimal.ZERO, 10, null, null
        );

        assertThatThrownBy(() -> client.verifyNotification(config, Map.of("out_trade_no", "T1", "sign", "0123456789abcdef"), "raw"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid");
    }

    @Test
    void orderAmountsAreBoundedBeforeAnyArithmeticHappens() {
        Validator validator;
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
        java.util.function.Function<BigDecimal, Set<?>> violations = amount -> validator.validate(
            new PaymentDtos.CreatePaymentOrderRequest(amount, "alipay", null, false));

        assertThat(violations.apply(new BigDecimal("10.00"))).isEmpty();
        assertThat(violations.apply(new BigDecimal("1e999999999"))).isNotEmpty();
        assertThat(violations.apply(new BigDecimal("100000001"))).isNotEmpty();
        assertThat(violations.apply(new BigDecimal("1.234"))).isNotEmpty();
        assertThat(violations.apply(new BigDecimal("0.00"))).isNotEmpty();
    }
}
