package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * The Gemini route hands the model of its path to the facade as it is written, so a {@code model@tier} selector in the
 * path (design 6.1) reaches the place that takes it off, however the client spells the {@code @}.
 */
class ProxyControllerGeminiPathTest {

    private final ProxyFacade facade = mock(ProxyFacade.class);
    private final WebTestClient client = WebTestClient
        .bindToController(new ProxyController(facade, mock(ProxyModelService.class)))
        .build();

    @Test
    void aSelectorInThePathReachesTheFacadeWithTheModel() {
        when(facade.geminiGenerateContent(anyString(), any(), any())).thenReturn(Mono.<ResponseEntity<?>>just(ResponseEntity.ok("{}")));

        for (String path : new String[] {
            "/v1beta/models/gemini-2.5-pro@kiro:generateContent",
            "/v1beta/models/gemini-2.5-pro%40kiro:generateContent",
            "/v1/models/gemini-2.5-pro@L2:generateContent",
            "/models/gemini-2.5-pro@kiro+:generateContent"
        }) {
            client.post().uri(URI.create(path)).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        }

        ArgumentCaptor<String> models = ArgumentCaptor.forClass(String.class);
        verify(facade, times(4)).geminiGenerateContent(models.capture(), any(JsonNode.class), any());
        assertThat(models.getAllValues())
            .containsExactly("gemini-2.5-pro@kiro", "gemini-2.5-pro@kiro", "gemini-2.5-pro@L2", "gemini-2.5-pro@kiro+");
    }
}
