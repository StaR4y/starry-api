package tech.starryapi.account;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import tech.starryapi.common.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OpenAiOAuthTokenClaims {

    private static final String PROFILE_CLAIM = "https://api.openai.com/profile";
    private static final String AUTH_CLAIM = "https://api.openai.com/auth";

    private OpenAiOAuthTokenClaims() {
    }

    static Identity parse(
        ObjectMapper objectMapper,
        String idToken,
        String accessToken,
        Clock clock
    ) {
        JsonNode idClaims = jwtPayload(objectMapper, idToken, "id_token");
        JsonNode accessClaims = jwtPayload(objectMapper, accessToken, "access_token");
        Instant expiresAt = expiration(accessClaims);
        if (expiresAt == null) {
            expiresAt = expiration(idClaims);
        }
        if (expiresAt == null) {
            throw invalidToken("OAuth token response does not contain an expiration");
        }
        if (!expiresAt.isAfter(clock.instant().plusSeconds(30))) {
            throw invalidToken("OAuth token response is already expired");
        }
        return identity(idClaims, expiresAt);
    }

    static Identity parseStoredIdentity(ObjectMapper objectMapper, String idToken) {
        JsonNode idClaims = jwtPayload(objectMapper, idToken, "id_token");
        return identity(idClaims, expiration(idClaims));
    }

    static Instant tokenExpirationOrNull(ObjectMapper objectMapper, String token) {
        try {
            return expiration(jwtPayload(objectMapper, token, "access_token"));
        } catch (ApiException ignored) {
            return null;
        }
    }

    private static Identity identity(JsonNode idClaims, Instant expiresAt) {
        JsonNode profile = idClaims.path(PROFILE_CLAIM);
        JsonNode auth = idClaims.path(AUTH_CLAIM);
        String email = firstText(idClaims.path("email"), profile.path("email"));
        String userId = firstText(auth.path("chatgpt_user_id"), auth.path("user_id"));
        String accountId = text(auth.path("chatgpt_account_id"));
        if (userId == null && email == null) {
            throw invalidToken("OAuth ID token does not contain an account identity");
        }
        return new Identity(email, userId, accountId, expiresAt);
    }

    private static JsonNode jwtPayload(ObjectMapper objectMapper, String token, String field) {
        if (token == null || token.isBlank()) {
            throw invalidToken("OAuth token response does not contain " + field);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[1].isBlank()) {
            throw invalidToken("OAuth " + field + " is not a JWT");
        }
        try {
            return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception error) {
            throw invalidToken("OAuth " + field + " contains invalid claims");
        }
    }

    private static Instant expiration(JsonNode claims) {
        JsonNode value = claims.path("exp");
        if (!value.canConvertToLong()) {
            return null;
        }
        long epochSeconds = value.asLong(0L);
        try {
            return epochSeconds > 0 ? Instant.ofEpochSecond(epochSeconds) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String firstText(JsonNode... values) {
        for (JsonNode value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString("").trim();
        return text.isEmpty() ? null : text;
    }

    private static ApiException invalidToken(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "CHATGPT_OAUTH_INVALID_TOKEN", message);
    }

    record Identity(String email, String userId, String upstreamAccountId, Instant expiresAt) {
    }
}
