package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import tech.starryapi.assistant.AssistantDtos.ChatMessage;
import tech.starryapi.assistant.AssistantDtos.ChatRequest;
import tech.starryapi.assistant.AssistantDtos.ChatResponse;
import org.springframework.http.HttpStatus;
import tech.starryapi.account.AccountPoolService;
import tech.starryapi.assistant.AssistantDtos.Settings;
import tech.starryapi.common.ApiException;
import tech.starryapi.proxy.ProxyFacade;
import tools.jackson.databind.json.JsonMapper;

class AssistantChatServiceTest {

    @Test
    void requiresAUserQuestion() {
        assertThatThrownBy(() -> AssistantChatService.normalize(new ChatRequest(List.of(
            new ChatMessage("assistant", "先问我")
        )))).isInstanceOf(ApiException.class);
    }

    @Test
    void repliesThroughConfiguredModel() {
        AssistantSettingsService settings = mock(AssistantSettingsService.class);
        when(settings.get()).thenReturn(Mono.just(new Settings(
            true, "鲸鱼助手", "你好", "你是助手", "auto", 8, 0.2
        )));
        ProxyFacade proxy = mock(ProxyFacade.class);
        when(proxy.completeAnonymousChat(any(), any())).thenReturn(Mono.just("请到系统设置创建 API Key。"));
        AccountPoolService accounts = mock(AccountPoolService.class);
        when(accounts.hasUsableAccount(any())).thenReturn(false);
        AssistantChatService service = new AssistantChatService(
            settings,
            new AssistantDocsIndex(java.nio.file.Path.of("does-not-exist")),
            proxy,
            accounts,
            JsonMapper.builder().build()
        );

        ChatResponse response = service.chat(
            new ChatRequest(List.of(new ChatMessage("user", "如何创建密钥"))),
            MockServerWebExchange.from(MockServerHttpRequest.post("/assistant/chat"))
        ).block();

        assertThat(response.reply()).contains("API Key");
    }

    @Test
    void rejectsWhenDisabled() {
        AssistantSettingsService settings = mock(AssistantSettingsService.class);
        when(settings.get()).thenReturn(Mono.just(new Settings(false, "鲸鱼助手", "hi", "p", "auto", 8, 0.2)));
        AssistantChatService service = new AssistantChatService(
            settings,
            new AssistantDocsIndex(java.nio.file.Path.of("does-not-exist")),
            mock(ProxyFacade.class),
            mock(AccountPoolService.class),
            JsonMapper.builder().build()
        );

        assertThatThrownBy(() -> service.chat(
            new ChatRequest(List.of(new ChatMessage("user", "你好"))),
            MockServerWebExchange.from(MockServerHttpRequest.post("/assistant/chat"))
        ).block()).isInstanceOf(ApiException.class);
    }

    @Test
    void autoModelUsesOpenAiWhenKiroPoolIsEmpty() {
        AccountPoolService accounts = mock(AccountPoolService.class);
        when(accounts.hasUsableAccount(any())).thenAnswer(invocation -> {
            java.util.function.Predicate<?> filter = invocation.getArgument(0);
            tech.starryapi.account.ProxyAccountEntity openai = new tech.starryapi.account.ProxyAccountEntity();
            openai.setProvider("OpenAI");
            openai.setAuthMethod("api-key");
            tech.starryapi.account.ProxyAccountEntity kiro = new tech.starryapi.account.ProxyAccountEntity();
            kiro.setProvider("BuilderID");
            kiro.setAuthMethod("social");
            @SuppressWarnings("unchecked")
            java.util.function.Predicate<tech.starryapi.account.ProxyAccountEntity> typed =
                (java.util.function.Predicate<tech.starryapi.account.ProxyAccountEntity>) filter;
            return typed.test(openai) && !typed.test(kiro);
        });
        AssistantChatService service = new AssistantChatService(
            mock(AssistantSettingsService.class),
            new AssistantDocsIndex(java.nio.file.Path.of("does-not-exist")),
            mock(ProxyFacade.class),
            accounts,
            JsonMapper.builder().build()
        );
        assertThat(service.resolveModel("auto")).isEqualTo("gpt-5.5");
    }

    @Test
    void mapsMissingKiroPoolToChineseGuidance() {
        Throwable mapped = AssistantChatService.mapChatError(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "NO_AVAILABLE_ACCOUNT",
            "No available Kiro upstream account"
        ));
        assertThat(mapped).isInstanceOf(ApiException.class);
        assertThat(((ApiException) mapped).getMessage()).contains("Kiro").contains("gpt-5.5");
    }

    @Test
    void mapsMissingModelPriceToChineseGuidance() {
        Throwable mapped = AssistantChatService.mapChatError(new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MODEL_PRICE_UNAVAILABLE",
            "Model price is unavailable; configure an explicit price or synchronize the official catalog"
        ));
        assertThat(((ApiException) mapped).getMessage()).contains("定价");
    }
}
