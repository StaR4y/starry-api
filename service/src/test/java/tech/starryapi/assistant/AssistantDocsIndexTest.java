package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssistantDocsIndexTest {

    @Test
    void retrievesChineseDocChunks(@TempDir Path root) throws Exception {
        Path docs = root.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("API.md"), """
            # API Reference
            ## 模型清单
            GET /v1/models 返回当前启用的模型 ID、厂商和价格。
            ## API Key
            登录用户可以在控制台创建 sk- 开头的密钥，并绑定一个模型分类。
            """);
        AssistantDocsIndex index = new AssistantDocsIndex(root);

        List<AssistantDocsIndex.Chunk> hits = index.retrieve("怎么创建 API Key", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().body()).contains("sk-");
    }

    @Test
    void splitsMarkdownSections() {
        List<AssistantDocsIndex.Chunk> chunks = AssistantDocsIndex.split("docs/API.md", """
            # Title
            ## First
            This section is long enough to be indexed as a document chunk for retrieval.
            ## Second
            Another sufficiently long paragraph about billing multipliers and official prices.
            """);
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
    }
}
