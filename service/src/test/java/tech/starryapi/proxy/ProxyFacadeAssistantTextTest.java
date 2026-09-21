package tech.starryapi.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tech.starryapi.common.ApiException;

class ProxyFacadeAssistantTextTest {

    @Test
    void readsChatCompletionContent() {
        ObjectNode body = JsonMapper.builder().build().createObjectNode();
        body.putArray("choices").addObject().putObject("message").put("content", "你好");
        assertThat(ProxyFacade.assistantText(ResponseEntity.ok(body))).isEqualTo("你好");
    }

    @Test
    void rejectsEmptyAssistantPayload() {
        assertThatThrownBy(() -> ProxyFacade.assistantText(ResponseEntity.ok(JsonMapper.builder().build().createObjectNode())))
            .isInstanceOf(ApiException.class);
    }
}
