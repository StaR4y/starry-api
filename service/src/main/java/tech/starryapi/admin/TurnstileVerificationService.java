package tech.starryapi.admin;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

@Service
public class TurnstileVerificationService {

    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final RegistrationSettingsService registrationSettingsService;
    private final WebClient webClient;

    public TurnstileVerificationService(
        RegistrationSettingsService registrationSettingsService,
        @Qualifier("upstreamWebClient") WebClient webClient
    ) {
        this.registrationSettingsService = registrationSettingsService;
        this.webClient = webClient;
    }

    public Mono<Void> verifyIfRequired(String token, String remoteIp) {
        return registrationSettingsService.turnstileConfig()
            .flatMap(config -> {
                if (!config.enabled()) {
                    return Mono.empty();
                }
                return verifyEnabled(config, token, remoteIp);
            });
    }

    public Mono<Void> verifyRequired(String token, String remoteIp) {
        return registrationSettingsService.turnstileConfig()
            .flatMap(config -> {
                if (!config.enabled()) {
                    return Mono.error(new ApiException(
                        HttpStatus.CONFLICT,
                        "REGISTRATION_POLICY_CHANGED",
                        "Registration policy changed during verification; restart registration"
                    ));
                }
                return verifyEnabled(config, token, remoteIp);
            });
    }

    private Mono<Void> verifyEnabled(
        RegistrationSettingsService.TurnstileConfig config,
        String token,
        String remoteIp
    ) {
        if (!config.configured()) {
            return Mono.error(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TURNSTILE_NOT_CONFIGURED",
                "Cloudflare Turnstile is enabled but site key or secret key is missing"
            ));
        }
        String cleanedToken = clean(token);
        if (cleanedToken.isBlank()) {
            return Mono.error(new ApiException(
                HttpStatus.BAD_REQUEST,
                "TURNSTILE_REQUIRED",
                "Cloudflare Turnstile verification is required"
            ));
        }
        return verify(config, cleanedToken, remoteIp);
    }

    private Mono<Void> verify(
        RegistrationSettingsService.TurnstileConfig config,
        String token,
        String remoteIp
    ) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", config.secretKey());
        form.add("response", token);
        String cleanedIp = clean(remoteIp);
        if (!cleanedIp.isBlank()) {
            form.add("remoteip", cleanedIp);
        }
        return webClient.post()
            .uri(VERIFY_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .exchangeToMono(response -> response.bodyToMono(JsonNode.class)
                .defaultIfEmpty(NullNode.getInstance())
                .flatMap(body -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return Mono.error(new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "TURNSTILE_VERIFY_FAILED",
                            "Cloudflare Turnstile verification failed with status " + response.statusCode().value()
                        ));
                    }
                    if (body != null && body.path("success").asBoolean(false)) {
                        return Mono.empty();
                    }
                    return Mono.error(new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "TURNSTILE_INVALID",
                        "Cloudflare Turnstile verification failed" + errorCodes(body)
                    ));
                }));
    }

    private String errorCodes(JsonNode body) {
        if (body == null || !body.has("error-codes")) {
            return "";
        }
        return ": " + body.path("error-codes");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
