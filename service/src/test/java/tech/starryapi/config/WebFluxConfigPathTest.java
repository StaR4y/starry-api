package tech.starryapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WebFluxConfigPathTest {

    @Test
    void attachmentPathCheckAgreesWithTheRegexItReplaced() {
        List<String> paths = List.of(
            "/admin/documentation/pages/abc/attachments",
            "/admin/documentation/pages/9f1c2c3e-aaaa-bbbb-cccc-000000000001/attachments",
            "/admin/documentation/pages//attachments",
            "/admin/documentation/pages/attachments",
            "/admin/documentation/pages/a/b/attachments",
            "/admin/documentation/pages/abc/attachments/",
            "/admin/documentation/pages/abc/attachments/x",
            "/admin/documentation/pages/abc",
            "/v1/chat/completions",
            ""
        );
        for (String path : paths) {
            assertThat(WebFluxConfig.isDocumentationAttachmentPath(path))
                .as(path)
                .isEqualTo(path.matches("/admin/documentation/pages/[^/]+/attachments"));
        }
    }
}
