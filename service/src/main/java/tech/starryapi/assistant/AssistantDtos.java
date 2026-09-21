package tech.starryapi.assistant;

import java.util.List;

public final class AssistantDtos {
    private AssistantDtos() {
    }

    public record Settings(
        boolean enabled,
        String displayName,
        String greeting,
        String systemPrompt,
        String modelId,
        int maxHistory,
        double temperature
    ) {
    }

    public record SettingsView(
        Settings settings,
        List<ModelOption> models
    ) {
    }

    public record ModelOption(String id, String name, String provider) {
    }

    public record PublicSettings(
        boolean enabled,
        String displayName,
        String greeting
    ) {
    }

    public record ChatMessage(String role, String content) {
    }

    public record ChatRequest(List<ChatMessage> messages) {
    }

    public record ChatResponse(String reply, List<String> sources) {
    }
}
