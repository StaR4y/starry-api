package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Settings;
import tech.starryapi.platform.xproto.ChatWireSanitizer.Wire;
import tech.starryapi.platform.xproto.PairRegistry.Entry;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Detected;
import tech.starryapi.platform.xproto.RequestFeatureDetector.Feature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The two spellings of a {@code generateContent} request (design 7.6, GEMINI): what is read of a request in snake_case, which the
 * Python SDK writes inside the messages of the request and the REST reference writes throughout, is what is read of the same
 * request in camelCase. The bodies are the shapes the SDKs produce (authored from them, with placeholder values).
 */
class GeminiRequestNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final GeminiChatConverter CONVERTER = new GeminiChatConverter(JSON);
    private static final Settings GEMINI = new Settings(true, true, false, true, false, true, false, false, false, true, true);

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    private static JsonNode normalized(String json) {
        return GeminiRequestNormalizer.normalize(tree(json));
    }

    /** The request of the Python SDK (google-genai) with a system prompt, a tool, a thinking budget, an image answer to a tool. */
    private static final String PYTHON_SDK = """
        {"contents":[
          {"role":"user","parts":[{"text":"look at the screenshot"}]},
          {"role":"model","parts":[{"function_call":{"name":"screenshot","args":{"min_items":1,"inline_data":{"x":1}}},"thought_signature":"SIG-1"}]},
          {"role":"user","parts":[{"function_response":{"name":"screenshot","response":{"output":"ok","file_uri":"kept"},
            "parts":[{"inline_data":{"mime_type":"image/png","data":"8PHy8_T19vf4-fr7_P3-__Dx"}}]}}]},
          {"role":"user","parts":[{"inline_data":{"mime_type":"image/jpeg","data":"AAAA"}},{"file_data":{"mime_type":"image/png","file_uri":"https://example.com/a.png"}}]}],
         "systemInstruction":{"parts":[{"text":"be brief"}],"role":"user"},
         "tools":[{"functionDeclarations":[{"name":"other","description":"d",
           "parameters_json_schema":{"type":"object","properties":{"min_items":{"type":"integer"},"inline_data":{"type":"string"}}}}]}],
         "toolConfig":{"function_calling_config":{"mode":"ANY","allowed_function_names":["other"]}},
         "generationConfig":{"max_output_tokens":2000,"stop_sequences":["END"],"top_p":0.9,"response_mime_type":"application/json",
           "response_json_schema":{"type":"object","properties":{"a_b":{"type":"string"}}},
           "thinkingConfig":{"include_thoughts":true,"thinking_level":"HIGH","thinking_budget":20000}},
         "cached_content":"cachedContents/abc","safety_settings":[{"category":"X","threshold":"Y"}]}""";

    @Test
    void theFieldsOfTheApiAreWrittenInCamelCaseWhereverTheyAreInAMessage() {
        JsonNode request = normalized(PYTHON_SDK);

        assertThat(request.path("systemInstruction").path("parts").get(0).path("text").stringValue()).isEqualTo("be brief");
        JsonNode contents = request.path("contents");
        JsonNode call = contents.get(1).path("parts").get(0);
        assertThat(call.path("functionCall").path("name").stringValue()).isEqualTo("screenshot");
        assertThat(call.path("thoughtSignature").stringValue()).isEqualTo("SIG-1");
        JsonNode answer = contents.get(2).path("parts").get(0).path("functionResponse");
        assertThat(answer.path("parts").get(0).path("inlineData").path("mimeType").stringValue()).isEqualTo("image/png");
        assertThat(contents.get(3).path("parts").get(0).path("inlineData").path("mimeType").stringValue()).isEqualTo("image/jpeg");
        assertThat(contents.get(3).path("parts").get(1).path("fileData").path("fileUri").stringValue()).isEqualTo("https://example.com/a.png");
        assertThat(request.path("tools").get(0).path("functionDeclarations").get(0).has("parametersJsonSchema")).isTrue();
        assertThat(request.path("toolConfig").path("functionCallingConfig").path("allowedFunctionNames").get(0).stringValue()).isEqualTo("other");
        JsonNode config = request.path("generationConfig");
        assertThat(config.path("maxOutputTokens").intValue()).isEqualTo(2000);
        assertThat(config.path("stopSequences").get(0).stringValue()).isEqualTo("END");
        assertThat(config.path("topP").doubleValue()).isEqualTo(0.9);
        assertThat(config.path("responseMimeType").stringValue()).isEqualTo("application/json");
        assertThat(config.path("thinkingConfig").path("includeThoughts").booleanValue()).isTrue();
        assertThat(config.path("thinkingConfig").path("thinkingLevel").stringValue()).isEqualTo("HIGH");
        assertThat(config.path("thinkingConfig").path("thinkingBudget").intValue()).isEqualTo(20000);
        assertThat(request.path("cachedContent").stringValue()).isEqualTo("cachedContents/abc");
        assertThat(request.has("safetySettings")).isTrue();
        assertThat(request.propertyNames()).noneMatch(name -> name.contains("_"));
    }

    @Test
    void whatTheClientPutInTheValuesIsNotRenamedBecauseItIsNotTheApisOwn() {
        JsonNode request = normalized(PYTHON_SDK);

        JsonNode call = request.path("contents").get(1).path("parts").get(0).path("functionCall");
        assertThat(call.path("args")).isEqualTo(tree("{\"min_items\":1,\"inline_data\":{\"x\":1}}"));
        assertThat(request.path("contents").get(2).path("parts").get(0).path("functionResponse").path("response"))
            .isEqualTo(tree("{\"output\":\"ok\",\"file_uri\":\"kept\"}"));
        assertThat(request.path("tools").get(0).path("functionDeclarations").get(0).path("parametersJsonSchema"))
            .isEqualTo(tree("{\"type\":\"object\",\"properties\":{\"min_items\":{\"type\":\"integer\"},\"inline_data\":{\"type\":\"string\"}}}"));
        assertThat(request.path("generationConfig").path("responseJsonSchema").path("properties").has("a_b")).isTrue();
    }

    @Test
    void theRequestIsNotChangedAndCamelCaseIsReadAsItIs() {
        JsonNode original = tree(PYTHON_SDK);
        GeminiRequestNormalizer.normalize(original);
        assertThat(original).isEqualTo(tree(PYTHON_SDK));

        String camel = "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"maxOutputTokens\":5,\"unknown_key\":1},\"x_y\":2}";
        assertThat(normalized(camel)).isEqualTo(tree(camel));
    }

    @Test
    void theSpellingOfTheApiWinsWhenBothAreWritten() {
        JsonNode request = normalized("{\"system_instruction\":{\"parts\":[{\"text\":\"snake\"}]},\"systemInstruction\":{\"parts\":[{\"text\":\"camel\"}]}}");

        assertThat(request.propertyNames()).containsExactly("systemInstruction");
        assertThat(request.path("systemInstruction").path("parts").get(0).path("text").stringValue()).isEqualTo("camel");
        JsonNode other = normalized("{\"systemInstruction\":{\"parts\":[{\"text\":\"camel\"}]},\"system_instruction\":{\"parts\":[{\"text\":\"snake\"}]}}");
        assertThat(other.path("systemInstruction").path("parts").get(0).path("text").stringValue()).isEqualTo("camel");
    }

    @Test
    void oneValueWhereTheApiHasARepeatedFieldIsAListOfOne() {
        JsonNode request = normalized("""
            {"contents":{"role":"user","parts":{"text":"hi"}},
             "tools":{"function_declarations":{"name":"f"}},
             "generationConfig":{"stop_sequences":"END","responseModalities":"TEXT"},
             "tool_config":{"function_calling_config":{"mode":"ANY","allowed_function_names":"f"}}}""");

        assertThat(request.path("contents").isArray()).isTrue();
        assertThat(request.path("contents").get(0).path("parts").get(0).path("text").stringValue()).isEqualTo("hi");
        assertThat(request.path("tools").get(0).path("functionDeclarations").get(0).path("name").stringValue()).isEqualTo("f");
        assertThat(request.path("generationConfig").path("stopSequences")).isEqualTo(tree("[\"END\"]"));
        assertThat(request.path("generationConfig").path("responseModalities")).isEqualTo(tree("[\"TEXT\"]"));
        assertThat(request.path("toolConfig").path("functionCallingConfig").path("allowedFunctionNames")).isEqualTo(tree("[\"f\"]"));
    }

    @Test
    void aRequestThatIsNotAnObjectAndValuesOfTheWrongShapeAreLeftToTheReaders() {
        assertThat(GeminiRequestNormalizer.normalize(null)).isNull();
        assertThat(normalized("[1]")).isEqualTo(tree("[1]"));
        JsonNode odd = normalized("{\"contents\":\"text\",\"generation_config\":7,\"tools\":[1,{\"function_declarations\":\"x\"}]}");
        assertThat(odd.path("contents").stringValue()).isEqualTo("text");
        assertThat(odd.path("generationConfig").intValue()).isEqualTo(7);
        assertThat(odd.path("tools").get(0).intValue()).isEqualTo(1);
        assertThat(odd.path("tools").get(1).path("functionDeclarations").stringValue()).isEqualTo("x");
    }

    // ---- what the readers make of a snake_case request: the same as of the camelCase one ----

    /** What the detector, the digest and the converter make of a request, as the facade reads it. */
    private record Read(Detected detected, JsonNode digest, ObjectNode chat, Wire wire) {
    }

    private static Read read(String json) {
        JsonNode request = GeminiRequestNormalizer.normalize(tree(json));
        Detected detected = RequestFeatureDetector.detect(Entry.GEMINI, request);
        ObjectNode chat = CONVERTER.toChat("gemini-2.5-pro", request);
        Wire wire = ChatCanonicalizer.canonicalize(
            ChatWireSanitizer.sanitize(chat, ChatWireSanitizer.entryDigest(request), GEMINI, "relay-model"), GEMINI);
        return new Read(detected, ChatWireSanitizer.entryDigest(request), chat, wire);
    }

    /** The same request as {@link #PYTHON_SDK}, as the JavaScript SDK writes it. */
    private static final String JAVASCRIPT_SDK = """
        {"contents":[
          {"role":"user","parts":[{"text":"look at the screenshot"}]},
          {"role":"model","parts":[{"functionCall":{"name":"screenshot","args":{"min_items":1,"inline_data":{"x":1}}},"thoughtSignature":"SIG-1"}]},
          {"role":"user","parts":[{"functionResponse":{"name":"screenshot","response":{"output":"ok","file_uri":"kept"},
            "parts":[{"inlineData":{"mimeType":"image/png","data":"8PHy8/T19vf4+fr7/P3+/"}}]}}]},
          {"role":"user","parts":[{"inlineData":{"mimeType":"image/jpeg","data":"AAAA"}},{"fileData":{"mimeType":"image/png","fileUri":"https://example.com/a.png"}}]}],
         "systemInstruction":{"parts":[{"text":"be brief"}],"role":"user"},
         "tools":[{"functionDeclarations":[{"name":"other","description":"d",
           "parametersJsonSchema":{"type":"object","properties":{"min_items":{"type":"integer"},"inline_data":{"type":"string"}}}}]}],
         "toolConfig":{"functionCallingConfig":{"mode":"ANY","allowedFunctionNames":["other"]}},
         "generationConfig":{"maxOutputTokens":2000,"stopSequences":["END"],"topP":0.9,"responseMimeType":"application/json",
           "responseJsonSchema":{"type":"object","properties":{"a_b":{"type":"string"}}},
           "thinkingConfig":{"includeThoughts":true,"thinkingLevel":"HIGH","thinkingBudget":20000}},
         "cachedContent":"cachedContents/abc","safetySettings":[{"category":"X","threshold":"Y"}]}""";

    @Test
    void aRequestOfThePythonSdkIsReadLikeTheSameRequestOfTheJavaScriptOne() {
        Read snake = read(PYTHON_SDK);
        Read javaScript = read(JAVASCRIPT_SDK);

        assertThat(snake.detected().features()).isEqualTo(javaScript.detected().features());
        assertThat(snake.detected().features()).containsExactlyInAnyOrder(Feature.THINKING, Feature.REASONING_SUMMARY,
            Feature.SERVER_HELD_CONTEXT, Feature.TOOL_OUTPUT_IMAGE, Feature.IMAGE, Feature.JSON_SCHEMA, Feature.SAFETY_SETTINGS);
        assertThat(snake.digest()).isEqualTo(javaScript.digest());
        assertThat(snake.chat().path("messages").size()).isEqualTo(javaScript.chat().path("messages").size());
        assertThat(snake.wire().body().path("tools")).isEqualTo(javaScript.wire().body().path("tools"));
        assertThat(snake.wire().body().path("reasoning_effort")).isEqualTo(javaScript.wire().body().path("reasoning_effort"));
    }

    @Test
    void theSystemPromptTheImagesTheSchemaAndTheThinkingOfThePythonSdkReachTheRelay() {
        Read read = read(PYTHON_SDK);

        assertThat(read.wire().body().path("reasoning_effort").stringValue()).isEqualTo("high");
        JsonNode messages = read.wire().body().path("messages");
        assertThat(messages.get(0).path("role").stringValue()).isEqualTo("system");
        assertThat(messages.get(0).path("content").stringValue()).isEqualTo("be brief");
        JsonNode tools = read.wire().body().path("tools");
        assertThat(tools.get(0).path("function").path("parameters").path("properties").has("min_items")).isTrue();
        assertThat(read.wire().body().path("max_tokens").intValue()).isEqualTo(2000);
        assertThat(read.wire().body().path("stop")).isEqualTo(tree("[\"END\"]"));
        assertThat(read.wire().body().path("response_format").path("type").stringValue()).isEqualTo("json_schema");
        assertThat(read.wire().body().path("tool_choice")).isEqualTo(tree("{\"type\":\"function\",\"function\":{\"name\":\"other\"}}"));
        // The image the user sent is carried (data URI in the standard alphabet), the answer's image is told as present.
        String body = read.wire().body().toString();
        assertThat(body).contains("data:image/jpeg;base64,AAAA", "https://example.com/a.png", "[image omitted]");
        assertThat(read.wire().degraded()).doesNotContain("thinking", "image");
    }

    @Test
    void theBudgetAndTheLevelOfThePythonSdkAreTheReasoningThatTheJavaScriptOneAsksFor() {
        for (String config : List.of("{\"thinking_config\":{\"thinking_budget\":20000}}", "{\"thinkingConfig\":{\"thinking_budget\":20000}}",
            "{\"thinking_config\":{\"include_thoughts\":true,\"thinking_level\":\"HIGH\"}}")) {
            Read read = read("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generation_config\":" + config + "}");

            assertThat(read.detected().features()).as(config)
                .contains(Feature.THINKING).isSubsetOf(Feature.THINKING, Feature.REASONING_SUMMARY);
            assertThat(read.wire().body().path("reasoning_effort").stringValue()).as(config).isEqualTo("high");
        }
    }

    @Test
    void theServiceTierAndTheSummariesOfThoughtsAreNamedWhicheverSpellingTheyAreWrittenIn() {
        for (String request : List.of(
            "{\"serviceTier\":\"priority\",\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true}}}",
            "{\"service_tier\":\"priority\",\"generation_config\":{\"thinking_config\":{\"include_thoughts\":true}}}")) {
            Read read = read("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]," + request.substring(1));

            assertThat(read.detected().features()).as(request).containsExactlyInAnyOrder(Feature.SERVICE_TIER, Feature.REASONING_SUMMARY);
            assertThat(read.wire().body().propertyNames()).as(request).doesNotContain("service_tier", "serviceTier");
        }
    }

    @Test
    void theRestReferencesSamplesAreReadWhole() {
        Read image = read("""
            {"system_instruction":{"parts":[{"text":"You are a musician."}]},
             "contents":[{"parts":[{"text":"Tell me about this instrument"},{"inline_data":{"mime_type":"image/jpeg","data":"AAAA"}}]}]}""");

        JsonNode messages = image.chat().path("messages");
        assertThat(messages.get(0)).isEqualTo(tree("{\"role\":\"system\",\"content\":\"You are a musician.\"}"));
        assertThat(messages.get(1).path("content").get(1).path("image_url").path("url").stringValue()).isEqualTo("data:image/jpeg;base64,AAAA");
        assertThat(image.detected().features()).containsExactly(Feature.IMAGE);

        Read cached = read("{\"cached_content\":\"cachedContents/abc\",\"contents\":[{\"parts\":[{\"text\":\"hi\"}]}]}");
        assertThat(cached.detected().features()).containsExactly(Feature.SERVER_HELD_CONTEXT);

        Read stops = read("{\"generation_config\":{\"stop_sequences\":[\"a\",\"b\",\"c\",\"d\",\"e\"]},\"contents\":[{\"parts\":[{\"text\":\"hi\"}]}]}");
        assertThat(stops.detected().features()).containsExactly(Feature.STOP_SEQUENCES_OVER_4);
    }

    @Test
    void turnsWhosePartsAreOneObjectAreNotLeftOutSoThatTheRelayAnswersTheLastOne() {
        Read read = read("""
            {"contents":[{"role":"user","parts":[{"text":"first"}]},{"role":"model","parts":{"text":"second"}},{"role":"user","parts":{"text":"third"}}]}""");

        assertThat(read.chat().path("messages")).hasSize(3);
        assertThat(read.chat().path("messages").get(2).path("content").stringValue()).isEqualTo("third");
    }
}
