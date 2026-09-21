package tech.starryapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class AppearanceDtosJsonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    @Test
    void jacksonReadsChromeFromPartialPatch() throws Exception {
        AppearanceDtos.UpdateAppearanceRequest request = mapper.readValue(
            """
            {"siteName":"Starry API","chrome":"editorial"}
            """,
            AppearanceDtos.UpdateAppearanceRequest.class
        );

        assertThat(request.chrome()).isEqualTo("editorial");
        assertThat(request.siteName()).isEqualTo("Starry API");
    }

    @Test
    void jacksonReadsChromeFromFrontendSavePayload() throws Exception {
        AppearanceDtos.UpdateAppearanceRequest request = mapper.readValue(
            """
            {
              "siteName": "Starry API",
              "primary": "#5b8def",
              "primaryMid": "#7b7cff",
              "accent": "#b387ff",
              "success": "#36c08a",
              "warning": "#f0a84d",
              "danger": "#ef5b6b",
              "header": "#1a1f36",
              "text": "#1f2a44",
              "textSecondary": "#54607a",
              "textMuted": "#8a93a8",
              "sidebarOpacity": 0.62,
              "surfaceOpacity": 0.62,
              "blurPx": 18,
              "backgroundBlurPx": 0,
              "chrome": "editorial"
            }
            """,
            AppearanceDtos.UpdateAppearanceRequest.class
        );

        assertThat(request.chrome()).isEqualTo("editorial");
    }

    @Test
    void jacksonWritesChromeEvenWhenCanonicalValueWouldHaveBeenNull() throws Exception {
        AppearanceDtos.AppearanceResponse appearance = new AppearanceDtos.AppearanceResponse(
            "Starry API",
            null,
            null,
            null,
            "default",
            "#5b8def",
            "#7b7cff",
            "#b387ff",
            "#36c08a",
            "#f0a84d",
            "#ef5b6b",
            "#1a1f36",
            "#1f2a44",
            "#54607a",
            "#8a93a8",
            0.62,
            0.62,
            18,
            0,
            null
        );

        assertThat(appearance.chrome()).isEqualTo(AppearanceDtos.CHROME_CLASSIC);
        assertThat(mapper.writeValueAsString(appearance)).contains("\"chrome\":\"classic\"");
    }

    @Test
    void jacksonWritesChromeOnAppearanceResponse() throws Exception {
        AppearanceDtos.AppearanceResponse appearance = new AppearanceDtos.AppearanceResponse(
            "Starry API",
            null,
            null,
            null,
            "default",
            "#5b8def",
            "#7b7cff",
            "#b387ff",
            "#36c08a",
            "#f0a84d",
            "#ef5b6b",
            "#1a1f36",
            "#1f2a44",
            "#54607a",
            "#8a93a8",
            0.62,
            0.62,
            18,
            0,
            "editorial"
        );

        assertThat(mapper.writeValueAsString(appearance)).contains("\"chrome\":\"editorial\"");
    }
}
