package tech.starryapi.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class JsonNodesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void countsMatchTheSerializedFormWithoutBuildingIt() throws Exception {
        JsonNode node = mapper.readTree("""
            {"model":"gpt-5.5","messages":[{"role":"user","content":"你好 \\"quoted\\" \\n line 😀 text"}],
             "n":1.5,"flag":true,"nothing":null,"nested":{"a":[1,2,3]}}
            """);

        assertThat(JsonNodes.utf8Length(node)).isEqualTo(node.toString().getBytes(StandardCharsets.UTF_8).length);
        assertThat(JsonNodes.charLength(node)).isEqualTo(node.toString().length());
        assertThat(JsonNodes.utf8Length(null)).isZero();
    }

    @Test
    void shallowCopySharesChildrenButLetsTheTopLevelDiverge() throws Exception {
        ObjectNode source = (ObjectNode) mapper.readTree("{\"model\":\"a\",\"messages\":[{\"role\":\"user\"}]}");

        ObjectNode copy = JsonNodes.shallowCopy(source);
        copy.put("model", "b");
        copy.put("max_tokens", 10);
        copy.remove("messages");

        assertThat(source.path("model").asString("")).isEqualTo("a");
        assertThat(source.has("max_tokens")).isFalse();
        assertThat(source.has("messages")).isTrue();
        assertThat(JsonNodes.shallowCopy(source).get("messages")).isSameAs(source.get("messages"));
    }

    @Test
    void utf8BytesAreIdenticalToSerialisingThroughAString() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            longText.append("段落 ").append(i).append(" \uD83D\uDE00 text \"q\" \\ / \n");
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("model", "claude-sonnet-4.5").put("text", longText.toString()).put("n", 1.25).putNull("x");
        node.putArray("items").add(1).add("é").addObject().put("k", "v");

        byte[] direct = JsonNodes.toUtf8Bytes(node);

        assertThat(direct).isEqualTo(node.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(mapper.readTree(direct)).isEqualTo(node);
        assertThat(JsonNodes.toUtf8Bytes(null)).isEmpty();
    }

    // Every base64 character that needs no JSON escaping, including the URL-safe pair.
    private static final String PAYLOAD = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5ejAxMjM0NTY3ODkrLz0-_"
        .repeat(60);

    /** Both texts are the same request, one with the payloads in place of "@@" and one without. */
    private JsonNodes.MediaStats statsOfRequestWithPayloads(String template) throws Exception {
        JsonNode full = mapper.readTree(template.replace("@@", PAYLOAD));
        String bare = mapper.readTree(template.replace("@@", "")).toString();

        JsonNodes.MediaStats stats = JsonNodes.mediaStats(full);

        assertThat(stats.charsWithoutBase64()).isEqualTo(bare.length());
        assertThat(stats.bytesWithoutBase64()).isEqualTo(bare.getBytes(StandardCharsets.UTF_8).length);
        assertThat(JsonNodes.charLength(full)).isGreaterThan(stats.charsWithoutBase64());
        return stats;
    }

    @Test
    void mediaStatsOfARequestWithoutMediaAreItsPlainLengths() throws Exception {
        JsonNode node = mapper.readTree("""
            {"model":"gpt-5.5","messages":[{"role":"user","content":"你好 😀 data:image/png;base64,QUJD"},
             {"role":"user","content":[{"type":"text","text":"see data:image/png;base64,QUJD"}]}],
             "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object","properties":{
               "image_url":{"type":"string"},"file_id":{"type":"string"},"file_data":{"type":"string"},
               "source":{"type":"string"},"inlineData":{"type":"string"}}}}}]}
            """);

        JsonNodes.MediaStats stats = JsonNodes.mediaStats(node);

        assertThat(stats).isEqualTo(new JsonNodes.MediaStats(JsonNodes.charLength(node), JsonNodes.utf8Length(node), 0, 0));
        assertThat(stats.bytesWithoutBase64()).isGreaterThan(stats.charsWithoutBase64());
        assertThat(JsonNodes.mediaStats(null)).isEqualTo(new JsonNodes.MediaStats(0, 0, 0, 0));
        assertThat(JsonNodes.mediaStats(mapper.readTree("[]"))).isEqualTo(new JsonNodes.MediaStats(2, 2, 0, 0));
    }

    @Test
    void chatDataUrisAreInlineImagesWhoseBodyIsLeftOut() throws Exception {
        JsonNodes.MediaStats stats = statsOfRequestWithPayloads("""
            {"model":"claude-sonnet-4.5","messages":[{"role":"user","content":[
              {"type":"text","text":"describe 😀 these"},
              {"type":"image_url","image_url":{"url":"data:image/png;base64,@@","detail":"high"}},
              {"type":"image_url","image_url":{"url":"DATA:image/jpeg;BASE64,@@"}}]}]}
            """);

        assertThat(stats.inlineImages()).isEqualTo(2);
        assertThat(stats.remoteMedia()).isZero();
    }

    @Test
    void aResponsesImageGivenAsAPlainStringCountsTheSame() throws Exception {
        JsonNodes.MediaStats stats = statsOfRequestWithPayloads("""
            {"model":"gpt-5.5","input":[{"role":"user","content":[
              {"type":"input_text","text":"look"},{"type":"input_image","image_url":"data:image/webp;base64,@@"}]}]}
            """);

        assertThat(stats.inlineImages()).isEqualTo(1);
        assertThat(stats.remoteMedia()).isZero();
    }

    @Test
    void httpImagesAndFileReferencesAreRemoteMediaAndStayInTheSize() throws Exception {
        JsonNode node = mapper.readTree("""
            {"messages":[{"role":"user","content":[
              {"type":"image_url","image_url":{"url":"https://example.com/a.png"}},
              {"type":"image_url","image_url":{"url":"http://example.com/b.png"}},
              {"type":"input_image","image_url":"https://example.com/c.png"},
              {"type":"input_image","file_id":"file-abc"},
              {"type":"input_file","file_url":"https://example.com/d.pdf"},
              {"type":"file","file":{"file_id":"file-xyz"}},
              {"type":"input_image","image_url":"https://example.com/e.png","file_id":"file-both"}]}]}
            """);

        JsonNodes.MediaStats stats = JsonNodes.mediaStats(node);

        assertThat(stats.remoteMedia()).isEqualTo(7);
        assertThat(stats.inlineImages()).isZero();
        assertThat(stats.charsWithoutBase64()).isEqualTo(JsonNodes.charLength(node));
        assertThat(stats.bytesWithoutBase64()).isEqualTo(JsonNodes.utf8Length(node));
    }

    @Test
    void anthropicSourcesAreCountedByTheirKind() throws Exception {
        JsonNodes.MediaStats stats = statsOfRequestWithPayloads("""
            {"model":"claude-sonnet-4.5","max_tokens":64,"messages":[{"role":"user","content":[
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"@@"}},
              {"type":"image","source":{"type":"url","url":"https://example.com/a.png"}},
              {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"@@"}},
              {"type":"document","source":{"type":"text","media_type":"text/plain","data":"plain words, not base64"}},
              {"type":"document","source":{"type":"file","file_id":"file_011CNha8iCJcU1wXNR6q4V8w"}},
              {"type":"tool_result","tool_use_id":"toolu_1","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"@@"}}]}]}]}
            """);

        assertThat(stats.inlineImages()).isEqualTo(2);
        // the url image, the PDF and the file id
        assertThat(stats.remoteMedia()).isEqualTo(3);
    }

    @Test
    void geminiInlineDataIsAnImageOnlyWhenItsMimeTypeIs() throws Exception {
        JsonNodes.MediaStats stats = statsOfRequestWithPayloads("""
            {"contents":[{"role":"user","parts":[
              {"text":"what is in these"},
              {"inlineData":{"mimeType":"image/png","data":"@@"}},
              {"inline_data":{"mime_type":"IMAGE/JPEG","data":"@@"}},
              {"inlineData":{"mimeType":"application/pdf","data":"@@"}},
              {"inlineData":{"data":"@@"}},
              {"fileData":{"mimeType":"video/mp4","fileUri":"gs://bucket/video.mp4"}},
              {"file_data":{"mime_type":"image/png","file_uri":"https://generativelanguage.googleapis.com/v1/files/abc"}}]}]}
            """);

        assertThat(stats.inlineImages()).isEqualTo(2);
        // the PDF, the part without a MIME type, and the two file URIs
        assertThat(stats.remoteMedia()).isEqualTo(4);
    }

    @Test
    void inlineAudioAndFilesAreLeftOutButCountedAsRemote() throws Exception {
        JsonNodes.MediaStats stats = statsOfRequestWithPayloads("""
            {"messages":[{"role":"user","content":[
              {"type":"input_audio","input_audio":{"data":"@@","format":"wav"}},
              {"type":"file","file":{"filename":"a.pdf","file_data":"data:application/pdf;base64,@@"}},
              {"type":"input_file","filename":"b.pdf","file_data":"@@"}]}]}
            """);

        assertThat(stats.inlineImages()).isZero();
        assertThat(stats.remoteMedia()).isEqualTo(3);
    }

    @Test
    void severalPayloadsAddUpAndNewLinesInsideOneAreCountedAsEscaped() throws Exception {
        JsonNode full = mapper.readTree("{\"a\":{\"image_url\":{\"url\":\"data:image/png;base64,QUJD\\nREVG\\r\\nR0hJ\"}},"
            + "\"b\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"data\":\"QUJD\\nREVG\"}}]}");
        String bare = mapper.readTree("{\"a\":{\"image_url\":{\"url\":\"data:image/png;base64,\"}},"
            + "\"b\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"data\":\"\"}}]}").toString();

        JsonNodes.MediaStats stats = JsonNodes.mediaStats(full);

        assertThat(stats.charsWithoutBase64()).isEqualTo(bare.length());
        assertThat(stats.bytesWithoutBase64()).isEqualTo(bare.getBytes(StandardCharsets.UTF_8).length);
        assertThat(stats.inlineImages()).isEqualTo(2);
    }

    @Test
    void textThatOnlyClaimsToBeBase64IsKeptInTheSize() throws Exception {
        JsonNode node = mapper.readTree("""
            {"messages":[{"role":"user","content":[
              {"type":"image_url","image_url":{"url":"data:image/png;base64,this is not base64!"}},
              {"type":"image_url","image_url":{"url":"data:image/svg+xml,%3Csvg%3E%3C/svg%3E"}},
              {"type":"image","source":{"type":"base64","media_type":"image/png","data":"héllo wörld"}}]}]}
            """);

        JsonNodes.MediaStats stats = JsonNodes.mediaStats(node);

        assertThat(stats.charsWithoutBase64()).isEqualTo(JsonNodes.charLength(node));
        assertThat(stats.bytesWithoutBase64()).isEqualTo(JsonNodes.utf8Length(node));
        // still images: the model sees them whatever the bytes are
        assertThat(stats.inlineImages()).isEqualTo(3);
        assertThat(stats.remoteMedia()).isZero();
    }

    @Test
    void aMegabytesLongPayloadIsOneSmallNumberAndTheTreeIsLeftAlone() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", "m");
        root.putArray("messages").addObject().put("role", "user").putArray("content").addObject()
            .put("type", "image_url").putObject("image_url").put("url", "data:image/png;base64," + "QUJD".repeat(1_000_000));
        JsonNode before = root.deepCopy();

        JsonNodes.MediaStats stats = JsonNodes.mediaStats(root);

        assertThat(stats.charsWithoutBase64()).isLessThan(200);
        assertThat(stats.bytesWithoutBase64()).isEqualTo(stats.charsWithoutBase64());
        assertThat(stats.inlineImages()).isEqualTo(1);
        assertThat(JsonNodes.charLength(root)).isGreaterThan(4_000_000);
        assertThat(root).isEqualTo(before);
    }
}
