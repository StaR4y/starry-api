package tech.starryapi.assistant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.assistant.AssistantDtos.ChatRequest;
import tech.starryapi.assistant.AssistantDtos.ChatResponse;
import tech.starryapi.assistant.AssistantDtos.PublicSettings;
import tech.starryapi.common.AdminAuth;
import tech.starryapi.common.ApiResponse;

@RestController
@RequestMapping("/assistant")
public class AssistantChatController {

    private final AssistantSettingsService settings;
    private final AssistantChatService chat;
    private final AdminAuth auth;

    public AssistantChatController(
        AssistantSettingsService settings,
        AssistantChatService chat,
        AdminAuth auth
    ) {
        this.settings = settings;
        this.chat = chat;
        this.auth = auth;
    }

    @GetMapping("/settings")
    public Mono<ApiResponse<PublicSettings>> settings(ServerWebExchange exchange) {
        auth.verify(exchange);
        return settings.publicSettings().map(ApiResponse::ok);
    }

    @PostMapping("/chat")
    public Mono<ApiResponse<ChatResponse>> chat(ServerWebExchange exchange, @RequestBody ChatRequest request) {
        auth.verify(exchange);
        return chat.chat(request, exchange).map(ApiResponse::ok);
    }
}
