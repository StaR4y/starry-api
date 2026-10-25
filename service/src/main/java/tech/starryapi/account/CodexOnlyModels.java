package tech.starryapi.account;

import java.util.Locale;

/**
 * The models that only Codex accounts can serve on the Responses route. The proxy facade sends a request for one to the
 * Codex pool, and the model listing prices it by the tier that holds Codex accounts, so both ask here.
 */
public final class CodexOnlyModels {

    private CodexOnlyModels() {
    }

    public static boolean isCodexOnly(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("starry/")) {
            normalized = normalized.substring("starry/".length());
        }
        if (normalized.startsWith("openai/")) {
            normalized = normalized.substring("openai/".length());
        } else if (normalized.startsWith("openai-compatible/")) {
            normalized = normalized.substring("openai-compatible/".length());
        }
        return normalized.contains("codex")
            || "codex-mini-latest".equals(normalized)
            || normalized.matches("gpt-5\\.5.*");
    }
}
