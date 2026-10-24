package tech.starryapi.payment;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.payment.PaymentSettingsService.PaymentConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class EasyPayClient {

    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public EasyPayClient(@Qualifier("upstreamWebClient") WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public Mono<CreatePaymentResult> createPayment(
        PaymentConfig config,
        PaymentOrderEntity order,
        String notifyUrl,
        String returnUrl,
        String clientIp,
        boolean mobile
    ) {
        Map<String, String> params = signedCreateParams(config, order, notifyUrl, returnUrl, clientIp, mobile);
        if ("qrcode".equals(config.easypayPaymentMode())) {
            return createApiPayment(config, params);
        }
        return Mono.just(new CreatePaymentResult(redirectUrl(config, params), null, null));
    }

    public Mono<OrderQueryResult> queryOrder(PaymentConfig config, PaymentOrderEntity order) {
        String queryUrl = apiBase(config.easypayApiBase())
            + "/api.php?act=order"
            + "&pid=" + encode(config.easypayPid())
            + "&key=" + encode(config.easypayPkey())
            + "&out_trade_no=" + encode(order.getOutTradeNo());
        return webClient.get()
            .uri(URI.create(queryUrl))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(REQUEST_TIMEOUT)
            .map(body -> parseOrderQueryResponse(order.getOutTradeNo(), body))
            // The merchant key travels in this query string, and Spring puts the request URI into its exception
            // messages. Replace the error so the key never reaches a log line.
            .onErrorMap(error -> !(error instanceof IllegalStateException),
                error -> new IllegalStateException("EasyPay order query failed: " + describeFailure(error)));
    }

    static String describeFailure(Throwable error) {
        if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return redact(error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
    }

    static String redact(String text) {
        return text == null ? null : text.replaceAll("(?i)([?&]key=)[^&\\s\"']*", "$1***");
    }

    public Notification verifyNotification(PaymentConfig config, Map<String, String> params, String rawBody) {
        String sign = params.get("sign");
        if (sign == null || sign.isBlank()) {
            throw new IllegalArgumentException("EasyPay notification signature is missing");
        }
        byte[] expected = sign(config.easypayPkey(), params).toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = sign.trim().toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("EasyPay notification signature is invalid");
        }
        String status = params.getOrDefault("trade_status", "");
        return new Notification(
            params.getOrDefault("out_trade_no", ""),
            params.getOrDefault("trade_no", ""),
            parseAmount(params.get("money")),
            TRADE_SUCCESS.equalsIgnoreCase(status),
            rawBody
        );
    }

    private Mono<CreatePaymentResult> createApiPayment(PaymentConfig config, Map<String, String> params) {
        var form = BodyInserters.fromFormData("pid", params.get("pid"));
        params.forEach((key, value) -> {
            if (!"pid".equals(key)) {
                form.with(key, value);
            }
        });
        return webClient.post()
            .uri(apiBase(config.easypayApiBase()) + "/mapi.php")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(REQUEST_TIMEOUT)
            .map(this::parseCreateResponse);
    }

    private CreatePaymentResult parseCreateResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(0);
            if (code != 1) {
                throw new IllegalStateException("EasyPay create payment failed: " + root.path("msg").asString(body));
            }
            return new CreatePaymentResult(
                firstNonBlank(root.path("payurl").asString(null), root.path("payurl2").asString(null)),
                blankToNull(root.path("qrcode").asString(null)),
                blankToNull(root.path("trade_no").asString(null))
            );
        } catch (Exception error) {
            throw new IllegalStateException("EasyPay create payment response is invalid: " + error.getMessage(), error);
        }
    }

    OrderQueryResult parseOrderQueryResponse(String expectedOutTradeNo, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(0);
            if (code != 1) {
                return new OrderQueryResult(expectedOutTradeNo, null, BigDecimal.ZERO, false, body);
            }
            String outTradeNo = firstNonBlank(root.path("out_trade_no").asString(null), expectedOutTradeNo);
            String status = firstNonBlank(root.path("trade_status").asString(null), root.path("status").asString(null));
            boolean paid = "1".equals(status) || TRADE_SUCCESS.equalsIgnoreCase(status);
            BigDecimal amount = parseAmount(firstNonBlank(root.path("money").asString(null), root.path("amount").asString(null)));
            return new OrderQueryResult(outTradeNo, blankToNull(root.path("trade_no").asString(null)), amount, paid, body);
        } catch (Exception error) {
            throw new IllegalStateException("EasyPay order query response is invalid: " + error.getMessage(), error);
        }
    }

    private Map<String, String> signedCreateParams(
        PaymentConfig config,
        PaymentOrderEntity order,
        String notifyUrl,
        String returnUrl,
        String clientIp,
        boolean mobile
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", config.easypayPid());
        params.put("type", order.getPaymentType());
        params.put("out_trade_no", order.getOutTradeNo());
        params.put("notify_url", notifyUrl);
        params.put("return_url", returnUrl);
        params.put("name", subject(config, order));
        params.put("money", order.getPayAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        if (clientIp != null && !clientIp.isBlank() && "qrcode".equals(config.easypayPaymentMode())) {
            params.put("clientip", clientIp);
        }
        if (mobile) {
            params.put("device", "mobile");
        }
        params.put("sign", sign(config.easypayPkey(), params));
        params.put("sign_type", "MD5");
        return params;
    }

    private String subject(PaymentConfig config, PaymentOrderEntity order) {
        String prefix = config.productNamePrefix().isBlank() ? "Starry API 余额充值" : config.productNamePrefix();
        String suffix = config.productNameSuffix().isBlank() ? "" : config.productNameSuffix();
        return prefix + " " + order.getAmount().stripTrailingZeros().toPlainString() + suffix;
    }

    private String redirectUrl(PaymentConfig config, Map<String, String> params) {
        String query = params.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        return apiBase(config.easypayApiBase()) + "/submit.php?" + query;
    }

    static String sign(String pkey, Map<String, String> params) {
        String canonical = params.entrySet().stream()
            .filter(entry -> !"sign".equals(entry.getKey()))
            .filter(entry -> !"sign_type".equals(entry.getKey()))
            .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("&")) + pkey;
        return md5(canonical);
    }

    static String apiBase(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        try {
            URI uri = URI.create(trimmed);
            String path = trimEndpointPath(uri.getPath());
            URI normalized = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), path, null, null);
            return stripTrailingSlash(normalized.toString());
        } catch (Exception ignored) {
            return stripTrailingSlash(trimEndpointPath(trimmed));
        }
    }

    private static String trimEndpointPath(String path) {
        String value = stripTrailingSlash(path == null ? "" : path.trim());
        String lower = value.toLowerCase();
        for (String endpoint : new String[]{"/submit.php", "/mapi.php", "/api.php"}) {
            if (lower.endsWith(endpoint)) {
                return stripTrailingSlash(value.substring(0, value.length() - endpoint.length()));
            }
        }
        return value;
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("MD5 is not available", error);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static BigDecimal parseAmount(String value) {
        try {
            return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value.trim());
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CreatePaymentResult(String payUrl, String qrCode, String tradeNo) {
    }

    public record Notification(
        String outTradeNo,
        String tradeNo,
        BigDecimal amount,
        boolean paid,
        String rawBody
    ) {
    }

    public record OrderQueryResult(
        String outTradeNo,
        String tradeNo,
        BigDecimal amount,
        boolean paid,
        String rawBody
    ) {
    }
}
