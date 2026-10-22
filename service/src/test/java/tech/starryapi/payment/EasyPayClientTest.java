package tech.starryapi.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EasyPayClientTest {

    @Test
    void signsParamsBySortedKeysAndIgnoresExistingSignatureFields() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "alipay");
        params.put("sign", "old-signature");
        params.put("name", "Starry API");
        params.put("money", "10.00");
        params.put("sign_type", "MD5");

        assertThat(EasyPayClient.sign("secret-key", params))
            .isEqualTo("770751cbf04e0c39304d670e1ed9002f");
    }

    @Test
    void normalizesGatewayBaseUrlFromCommonEndpointInputs() {
        assertThat(EasyPayClient.apiBase("https://pay.example.com/")).isEqualTo("https://pay.example.com");
        assertThat(EasyPayClient.apiBase("https://pay.example.com/submit.php")).isEqualTo("https://pay.example.com");
        assertThat(EasyPayClient.apiBase("https://pay.example.com/mapi.php")).isEqualTo("https://pay.example.com");
        assertThat(EasyPayClient.apiBase("https://pay.example.com/gateway/api.php"))
            .isEqualTo("https://pay.example.com/gateway");
    }

    @Test
    void parsesPaidOrderQueryResponse() {
        EasyPayClient client = new EasyPayClient(null, new ObjectMapper());

        EasyPayClient.OrderQueryResult result = client.parseOrderQueryResponse("SA123", """
            {"code":1,"status":1,"out_trade_no":"SA123","trade_no":"T123","money":"1.00"}
            """);

        assertThat(result.paid()).isTrue();
        assertThat(result.outTradeNo()).isEqualTo("SA123");
        assertThat(result.tradeNo()).isEqualTo("T123");
        assertThat(result.amount()).isEqualByComparingTo("1.00");
    }

    @Test
    void treatsMissingOrderQueryResponseAsUnpaid() {
        EasyPayClient client = new EasyPayClient(null, new ObjectMapper());

        EasyPayClient.OrderQueryResult result = client.parseOrderQueryResponse("SA404", """
            {"code":-1,"msg":"order not found"}
            """);

        assertThat(result.paid()).isFalse();
        assertThat(result.outTradeNo()).isEqualTo("SA404");
    }
}
