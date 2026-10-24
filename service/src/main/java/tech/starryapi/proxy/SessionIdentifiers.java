package tech.starryapi.proxy;

import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class SessionIdentifiers {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LEGACY = Pattern.compile("^user_.+_account_.*_session_([A-Za-z0-9-]{1,128})$");

    private SessionIdentifiers() {}

    static String anthropic(JsonNode metadata) {
        String explicit = metadata.path("session_id").asString("");
        if (!explicit.isBlank() && explicit.length() <= 256) return explicit;
        String userId = metadata.path("user_id").asString("");
        if (userId.length() > 4096) return null;
        try {
            JsonNode value = JSON.readTree(userId);
            String session = value == null ? "" : value.path("session_id").asString("");
            if (!session.isBlank() && session.length() <= 256) return session;
        } catch (Exception ignored) {
            // Older Claude Code clients encode session metadata in a structured user_id string.
        }
        var match = LEGACY.matcher(userId);
        return match.matches() ? match.group(1) : null;
    }
}
