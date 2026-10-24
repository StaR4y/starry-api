package tech.starryapi.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.assistant.AssistantDtos.ChatMessage;
import tech.starryapi.assistant.AssistantDtos.ChatRequest;
import tech.starryapi.assistant.AssistantDtos.ChatResponse;
import tech.starryapi.account.AccountPlatforms;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.assistant.AssistantDtos.Settings;
import tech.starryapi.common.ApiException;
import tech.starryapi.proxy.ProxyFacade;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class AssistantChatService {

    private static final int MAX_MESSAGES = 24;
    private static final int MAX_CONTENT = 4000;

    private final AssistantSettingsService settings;
    private final AssistantDocsIndex docs;
    private final ProxyFacade proxy;
    private final AccountPoolService accounts;
    private final ObjectMapper json;

    public AssistantChatService(
        AssistantSettingsService settings,
        AssistantDocsIndex docs,
        ProxyFacade proxy,
        AccountPoolService accounts,
        ObjectMapper json
    ) {
        this.settings = settings;
        this.docs = docs;
        this.proxy = proxy;
        this.accounts = accounts;
        this.json = json;
    }

    public Mono<ChatResponse> chat(ChatRequest request, ServerWebExchange exchange) {
        List<ChatMessage> history = normalize(request);
        String question = history.getLast().content();
        return settings.get().flatMap(config -> {
            if (!config.enabled()) {
                return Mono.error(new ApiException(
                    HttpStatus.CONFLICT,
                    "ASSISTANT_DISABLED",
                    "管理员尚未开放鲸鱼助手"
                ));
            }
            List<AssistantDocsIndex.Chunk> retrieved = docs.retrieve(question, 4);
            ObjectNode body = json.createObjectNode();
            body.put("model", resolveModel(config.modelId()));
            body.put("temperature", config.temperature());
            body.put("stream", false);
            body.put("max_tokens", 1024);
            body.set("messages", messages(config, history, retrieved));
            return proxy.completeAnonymousChat(body, exchange)
                .map(reply -> new ChatResponse(reply.trim(), retrieved.stream().map(AssistantDocsIndex.Chunk::source).distinct().toList()))
                .onErrorMap(AssistantChatService::mapChatError);
        });
    }

    private ArrayNode messages(Settings config, List<ChatMessage> history, List<AssistantDocsIndex.Chunk> retrieved) {
        ArrayNode messages = json.createArrayNode();
        ObjectNode system = json.createObjectNode();
        system.put("role", "system");
        system.put("content", systemPrompt(config, retrieved));
        messages.add(system);
        int keep = Math.max(1, config.maxHistory());
        List<ChatMessage> window = history.size() <= keep ? history : history.subList(history.size() - keep, history.size());
        for (ChatMessage message : window) {
            ObjectNode item = json.createObjectNode();
            item.put("role", message.role());
            item.put("content", message.content());
            messages.add(item);
        }
        return messages;
    }

    private static String systemPrompt(Settings config, List<AssistantDocsIndex.Chunk> retrieved) {
        if (retrieved.isEmpty()) {
            return config.systemPrompt();
        }
        StringBuilder docsBlock = new StringBuilder(config.systemPrompt());
        docsBlock.append("\n\n站点文档摘录：\n");
        for (AssistantDocsIndex.Chunk chunk : retrieved) {
            docsBlock.append("- ").append(chunk.source()).append(" / ").append(chunk.title()).append('\n');
            docsBlock.append(chunk.body()).append("\n\n");
        }
        return docsBlock.toString();
    }

    String resolveModel(String configured) {
        String model = configured == null ? "" : configured.trim();
        if (!model.isEmpty() && !"auto".equalsIgnoreCase(model)) {
            return model;
        }
        if (accounts.hasUsableAccount(AccountPlatforms::isKiroCompatible)) {
            return "auto";
        }
        if (accounts.hasUsableAccount(AccountPlatforms::isAnthropicApiKey)) {
            return "anthropic/claude-sonnet-4.5";
        }
        if (accounts.hasUsableAccount(AccountPlatforms::isDeepSeek)) {
            return "deepseek-chat";
        }
        if (accounts.hasUsableAccount(AccountPlatforms::isOpenAi)) {
            return "gpt-5.5";
        }
        if (accounts.hasUsableAccount(AccountPlatforms::isGoogleApiKey)) {
            return "gpt-5.5";
        }
        return "gpt-5.5";
    }

    static Throwable mapChatError(Throwable error) {
        if (error instanceof ApiException api) {
            String message = api.getMessage() == null ? "" : api.getMessage();
            if (message.contains("No available Kiro")) {
                return new ApiException(
                    api.getStatus(),
                    "NO_AVAILABLE_ACCOUNT",
                    "没有可用的 Kiro 账号。请在账号管理添加 Kiro 账号，或把鲸鱼助手的对话模型改成与现有账号匹配的模型（例如 gpt-5.5）。"
                ).initCause(api);
            }
            if (message.contains("No available Codex")) {
                return new ApiException(
                    api.getStatus(),
                    "NO_AVAILABLE_ACCOUNT",
                    "没有可用的 Codex 账号。请添加 Codex 账号，或把助手模型改成 gpt-5.5 并使用 OpenAI API Key。"
                ).initCause(api);
            }
            if (message.contains("No available")) {
                return new ApiException(
                    api.getStatus(),
                    "NO_AVAILABLE_ACCOUNT",
                    "没有可用的上游账号。请在账号管理添加可用账号，或在鲸鱼助手设置中选择匹配的对话模型。"
                ).initCause(api);
            }
            if ("MODEL_PRICE_UNAVAILABLE".equals(api.getCode()) || message.contains("Model price is unavailable")) {
                return new ApiException(
                    api.getStatus(),
                    "MODEL_PRICE_UNAVAILABLE",
                    "该模型还没有定价。请在系统设置 → 模型管理里为它填写价格，或换一个已同步官方价格的模型。"
                ).initCause(api);
            }
            return api;
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "ASSISTANT_UPSTREAM_FAILED", "助手暂时无法回答，请稍后重试");
    }

    static List<ChatMessage> normalize(ChatRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MESSAGE_REQUIRED", "请输入问题");
        }
        if (request.messages().size() > MAX_MESSAGES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_HISTORY_TOO_LONG", "对话轮次过多");
        }
        List<ChatMessage> cleaned = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if (message == null || message.content() == null) {
                continue;
            }
            String role = message.role() == null ? "" : message.role().trim().toLowerCase(Locale.ROOT);
            String content = message.content().trim();
            if (content.isEmpty() || content.length() > MAX_CONTENT) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MESSAGE_INVALID", "每条消息不能为空，且不超过 4000 字");
            }
            if (!role.equals("user") && !role.equals("assistant")) {
                continue;
            }
            cleaned.add(new ChatMessage(role, content));
        }
        if (cleaned.isEmpty() || !"user".equals(cleaned.getLast().role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MESSAGE_REQUIRED", "请输入问题");
        }
        return cleaned;
    }
}
