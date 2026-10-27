package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.assistant.AssistantDocsChunker.Parsed;
import tech.starryapi.assistant.AssistantDocsChunker.Section;

class AssistantDocsChunkerTest {

    @Test
    void splitsAtHeadingsOfLevelOneToThreeAndKeepsDeeperHeadingsInTheText() {
        String about = "这一节先交代背景和适用范围，再说明后面各小节分别讲什么，读完这段再往下看。".repeat(3);
        List<Section> sections = AssistantDocsChunker.split("""
            开头的说明。

            # 第一章
            %s

            ## 1. 登录并修改密码
            %s

            ### 忘记密码
            联系管理员重置。

            #### 更深一层
            这一层不再切开。
            """.formatted(about, about), 1);

        assertThat(sections).extracting(Section::heading)
            .containsExactly("", "第一章", "第一章 › 1. 登录并修改密码", "第一章 › 1. 登录并修改密码 › 忘记密码");
        assertThat(sections.getLast().body()).contains("联系管理员重置", "#### 更深一层", "这一层不再切开");
    }

    @Test
    void neverSplitsInsideFencedCodeBlocks() {
        List<Section> sections = AssistantDocsChunker.split("""
            ## 配置

            ```bash
            # 这是 shell 注释，不是标题
            ## 也不是
            export ANTHROPIC_BASE_URL=http://localhost:8080

            claude
            ```

            ~~~text
            # 波浪线围栏里的注释
            ~~~

            ## 下一节
            后面的内容。
            """, 1);

        assertThat(sections).extracting(Section::heading).containsExactly("配置", "下一节");
        assertThat(sections.getFirst().body())
            .contains("# 这是 shell 注释，不是标题", "## 也不是", "export ANTHROPIC_BASE_URL", "# 波浪线围栏里的注释");
    }

    @Test
    void aLongerFenceIsClosedOnlyByAFenceAsLongAsItself() {
        List<Section> sections = AssistantDocsChunker.split("""
            ## 示例

            ````markdown
            ```
            ## 里面的标题
            ```
            ````

            ## 结尾
            结束语。
            """, 1);

        assertThat(sections).extracting(Section::heading).containsExactly("示例", "结尾");
        assertThat(sections.getFirst().body()).contains("## 里面的标题");
    }

    @Test
    void removesImagesAttachmentReferencesAndSignedUrlsButKeepsLinkText() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        List<Section> sections = AssistantDocsChunker.split("""
            ## 附件

            ![创建密钥的截图](attachment:%s)
            ![外链图片](https://example.com/a.png)
            [下载说明书](attachment:%s) 或者看 [API Key](#/api-keys)。
            裸引用 attachment:%s 也要去掉。
            临时地址 https://host/documentation/media/%s?expires=1&admin=false&signature=abc 不能出现。
            外部链接 [CC Switch](https://ccswitch.io) 保留。
            """.formatted(id, id, id, id), 1);

        String body = sections.getFirst().body();
        assertThat(body).doesNotContain("attachment:", id, "signature", "expires", "截图", "a.png", "(#/api-keys)");
        assertThat(body).contains("下载说明书", "API Key", "[CC Switch](https://ccswitch.io)");
    }

    @Test
    void removesHtmlOutsideCodeButKeepsPlaceholders() {
        List<Section> sections = AssistantDocsChunker.split("""
            ## HTML

            <div class="note">注意<br>换行 <b>加粗</b></div>
            <img src="x.png" alt="y">
            <!-- 单行注释 -->
            <!-- 多行
            注释 -->可见文字

            填写 `<model>` 和 <UUID> 占位符。

            ```html
            <div>代码里的标签保留</div>
            ```
            """, 1);

        String body = sections.getFirst().body();
        assertThat(body).doesNotContain("<div class", "<b>", "<img", "注释", "<br>");
        assertThat(body).contains("注意 换行 加粗", "可见文字", "`<model>`", "<UUID>", "<div>代码里的标签保留</div>");
    }

    @Test
    void headingsLoseEmphasisAndLinks() {
        List<Section> sections = AssistantDocsChunker.split("""
            ## **粗体** 与 `代码` [链接](#/api-keys)
            正文。
            """, 1);

        assertThat(sections).extracting(Section::heading).containsExactly("粗体 与 代码 链接");
    }

    @Test
    void splitsALongSectionAtParagraphsInsteadOfDroppingTheRest() {
        StringBuilder markdown = new StringBuilder("## 很长的一节\n");
        for (int paragraph = 0; paragraph < 12; paragraph++) {
            markdown.append("\n第").append(paragraph).append("段：").append("字".repeat(450)).append('\n');
        }

        List<Section> sections = AssistantDocsChunker.split(markdown.toString(), 1);

        assertThat(sections).hasSizeGreaterThan(2).allSatisfy(section -> {
            assertThat(section.heading()).isEqualTo("很长的一节");
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
        });
        String all = String.join("\n", sections.stream().map(Section::body).toList());
        for (int paragraph = 0; paragraph < 12; paragraph++) {
            assertThat(all).contains("第" + paragraph + "段：");
        }
    }

    @Test
    void cutsAnOversizedCodeBlockAtLineBoundaries() {
        StringBuilder code = new StringBuilder("## 脚本\n\n```bash\n");
        for (int line = 0; line < 200; line++) {
            code.append("echo line-").append(line).append(" && sleep 1\n");
        }
        code.append("```\n");

        List<Section> sections = AssistantDocsChunker.split(code.toString(), 1);

        assertThat(sections).hasSizeGreaterThan(1).allSatisfy(section ->
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS));
        assertThat(String.join("\n", sections.stream().map(Section::body).toList()))
            .contains("echo line-0 &&", "echo line-199 &&");
    }

    @Test
    void aSingleEnormousLineIsCutAsALastResort() {
        List<Section> sections = AssistantDocsChunker.split("## 一行\n" + "a".repeat(5000), 1);

        assertThat(sections).hasSize(4);
        assertThat(sections).allSatisfy(section ->
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS));
    }

    @Test
    void dropsEmptyAndTooShortSections() {
        assertThat(AssistantDocsChunker.split("## 只有标题\n\n## 另一个标题\n", 1)).isEmpty();
        assertThat(AssistantDocsChunker.split("## 短\n太短了\n", 40)).isEmpty();
        assertThat(AssistantDocsChunker.split(null, 1)).isEmpty();
        assertThat(AssistantDocsChunker.split("   \n", 1)).isEmpty();
    }

    // ---- heading paths and short introductions -----------------------------------------------------------------

    @Test
    void aSubsectionCarriesTheHeadingsAboveItAndTakesAShortIntroductionAlong() {
        List<Section> sections = AssistantDocsChunker.split("""
            ## 给余额充值
            打开 余额充值。

            ### 在线充值
            选择金额并支付。

            ### 使用兑换码
            输入管理员发的兑换码。

            ## 版本
            1.2
            """, 1);

        assertThat(sections).extracting(Section::heading)
            .containsExactly("给余额充值 › 在线充值", "给余额充值 › 使用兑换码", "版本");
        assertThat(sections.getFirst().body()).isEqualTo("打开 余额充值。\n\n选择金额并支付。");
        assertThat(sections.get(1).body()).as("only the first subsection takes the introduction").doesNotContain("打开");
        assertThat(sections.getLast().body()).as("a short section with no subsections stays a section").isEqualTo("1.2");
    }

    @Test
    void aLongIntroductionOrASiblingIsNotMergedIntoTheNextHeading() {
        String intro = "这一节先交代背景，".repeat(10);
        List<Section> sections = AssistantDocsChunker.split("""
            ## 背景
            %s

            ### 细节
            具体做法。

            ## 短节
            很短。

            ## 另一节
            内容。
            """.formatted(intro), 1);

        assertThat(sections).extracting(Section::heading).containsExactly("背景", "背景 › 细节", "短节", "另一节");
    }

    @Test
    void headingsKnowTheirOrdinalAndTheDocumentCountsAllOfThem() {
        Parsed parsed = AssistantDocsChunker.parse("""
            开头。

            ## 一
            内容一。

            ## 空

            ### 二
            内容二。
            """, 1);

        assertThat(parsed.headings()).isEqualTo(3);
        assertThat(parsed.sections()).extracting(Section::ordinal).containsExactly(0, 1, 3);
    }

    @Test
    void closingHashesAreDroppedButAHashThatIsPartOfTheTitleIsKept() {
        assertThat(AssistantDocsChunker.split("## 标题 ##\n正文。", 1).getFirst().heading()).isEqualTo("标题");
        assertThat(AssistantDocsChunker.split("## 用 C#\n正文。", 1).getFirst().heading()).isEqualTo("用 C#");
        assertThat(AssistantDocsChunker.split("##\t制表符\n正文。", 1).getFirst().heading()).isEqualTo("制表符");
    }

    @Test
    void onlyOneToThreeHashesFollowedByASpaceAreHeadings() {
        assertThat(AssistantDocsChunker.headingLevel("# a")).isEqualTo(1);
        assertThat(AssistantDocsChunker.headingLevel("   ### a")).isEqualTo(3);
        assertThat(AssistantDocsChunker.headingLevel("    ## a")).isZero();
        assertThat(AssistantDocsChunker.headingLevel("#### a")).isZero();
        assertThat(AssistantDocsChunker.headingLevel("##a")).isZero();
        assertThat(AssistantDocsChunker.headingLevel("##")).isZero();
        assertThat(AssistantDocsChunker.headingLevel("文字 ## a")).isZero();
    }

    @Test
    void aHeadingIsCutToAReasonableLength() {
        List<Section> sections = AssistantDocsChunker.split("## " + "长".repeat(5000) + "\n正文。", 1);

        assertThat(sections.getFirst().heading().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_HEADING_CHARS + 1);
        assertThat(sections.getFirst().heading()).endsWith("…");
    }

    // ---- what must never reach the prompt, code included -------------------------------------------------------

    @Test
    void attachmentsAndSignedUrlsInsideFencedCodeAreRemovedButTheRestOfTheCodeIsNot() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        List<Section> sections = AssistantDocsChunker.split("""
            ## 附件示例
            说明。

            ```text
            ![截图示例](attachment:%s)
            https://host/documentation/media/%s?expires=1&admin=false&signature=abc
            <img src="attachment:%s">
            echo "<div>代码里的标签保留</div>" > a.html
            ```

            ~~~json
            {"logo": "attachment:%s", "media": "/documentation/media/%s?signature=x"}
            ~~~
            """.formatted(id, id, id, id, id), 1);

        String body = sections.getFirst().body();
        assertThat(body).doesNotContain(id, "attachment:", "documentation/media", "signature", "expires", "截图示例");
        assertThat(body).contains("```text", "echo \"<div>代码里的标签保留</div>\" > a.html", "~~~json", "\"logo\"");
    }

    @Test
    void anAttachmentIdentifierNeverSurvivesWhereverItSits() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        String markdown = "## 各处\n"
            + "![图](attachment:" + id + ")\n[文](attachment:" + id + ")\n裸 attachment:" + id + " 引用\n"
            + "<img src=\"attachment:" + id + "\">\n`attachment:" + id + "`\n```\nattachment:" + id + "\n```\n"
            + "https://x/documentation/media/" + id + "?signature=1\n";

        List<Section> sections = AssistantDocsChunker.split(markdown, 1);

        assertThat(sections).isNotEmpty().allSatisfy(section ->
            assertThat(section.body()).doesNotContain(id, "signature", "documentation/media"));
    }

    // ---- long blocks keep their shape --------------------------------------------------------------------------

    @Test
    void everyPieceOfACutCodeBlockIsAWholeFencedBlockWithTheLanguage() {
        StringBuilder code = new StringBuilder("## 脚本\n\n```bash title=\"install\"\n");
        for (int line = 0; line < 200; line++) {
            code.append("echo line-").append(line).append(" && sleep 1\n");
        }
        code.append("```\n\n## 之后\n后面的文字。\n");

        List<Section> sections = AssistantDocsChunker.split(code.toString(), 1);

        List<Section> script = sections.stream().filter(section -> section.heading().equals("脚本")).toList();
        assertThat(script).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertThat(section.body()).startsWith("```bash\n").endsWith("\n```");
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
            assertThat(section.body().split("```", -1)).as("one opening and one closing fence").hasSize(3);
        });
        String all = String.join("\n", script.stream().map(Section::body).toList());
        assertThat(all).contains("echo line-0 &&", "echo line-199 &&");
        assertThat(sections.getLast().heading()).isEqualTo("之后");
    }

    @Test
    void everyPieceOfACutTableRepeatsItsHeader() {
        StringBuilder table = new StringBuilder("## 错误码\n\n| 错误码 | 含义 | 处理 |\n| --- | --- | --- |\n");
        for (int row = 0; row < 60; row++) {
            table.append("| E").append(row).append(" | 第").append(row).append("号错误的含义说明文字 | 按文档处理这一类错误，必要时联系管理员 |\n");
        }

        List<Section> sections = AssistantDocsChunker.split(table.toString(), 1);

        assertThat(sections).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertThat(section.body()).startsWith("| 错误码 | 含义 | 处理 |\n| --- | --- | --- |\n");
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
        });
        assertThat(String.join("\n", sections.stream().map(Section::body).toList())).contains("| E0 |", "| E59 |");
    }

    // ---- a lead-in stays with what it introduces ---------------------------------------------------------------

    @Test
    void aLabelThatWouldEndAPieceMovesToTheNextPieceWithTheBlockItLabels() {
        String first = "先在终端里设置环境变量，然后重新打开终端。".repeat(75);
        String code = "```text\nset \"ANTHROPIC_BASE_URL=http://localhost\"\nset \"ANTHROPIC_AUTH_TOKEN=KEY\"\n```";
        List<Section> sections = AssistantDocsChunker.split("""
            ## Set environment variables manually

            %s

            **Windows CMD**:

            %s
            """.formatted(first, code), 1);

        assertThat(sections).hasSize(2);
        assertThat(sections.getFirst().body()).as("the piece does not end on the dangling label")
            .endsWith("重新打开终端。").doesNotContain("Windows CMD");
        assertThat(sections.getLast().body()).as("the label is in front of its code")
            .startsWith("**Windows CMD**:\n\n```text\nset ");
        assertThat(sections).allSatisfy(section ->
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS));
    }

    @Test
    void aShortParagraphBeforeABlockThatIsCutIsPartOfTheBlocksFirstPiece() {
        StringBuilder table = new StringBuilder("## 价格表\n\n以下价格以人民币计，按百万 token 计费。\n\n| 模型 | 输入 | 输出 |\n| --- | --- | --- |\n");
        for (int row = 0; row < 60; row++) {
            table.append("| model-").append(row).append(" | ¥").append(row).append(" | ¥").append(row * 4).append(" 每百万 token，不含缓存读取的折扣 |\n");
        }

        List<Section> sections = AssistantDocsChunker.split(table.toString(), 1);

        assertThat(sections).hasSizeGreaterThan(1);
        assertThat(sections.getFirst().body()).startsWith("以下价格以人民币计，按百万 token 计费。\n\n| 模型 | 输入 | 输出 |\n| --- | --- | --- |\n| model-0 |");
        assertThat(sections).as("the sentence is not a chunk of its own").noneMatch(section -> section.body().length() < 40);
        assertThat(sections.stream().skip(1)).as("only the first piece carries it")
            .allSatisfy(section -> assertThat(section.body()).startsWith("| 模型 | 输入 | 输出 |"));
        assertThat(sections).allSatisfy(section ->
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS));
        assertThat(String.join("\n", sections.stream().map(Section::body).toList())).contains("| model-0 |", "| model-59 |");
    }

    @Test
    void aShortParagraphBeforeAnOversizedCodeBlockIsPartOfTheFirstPieceInFrontOfTheOpenedFence() {
        StringBuilder code = new StringBuilder("## 脚本\n\n运行下面的脚本：\n\n```bash\n");
        for (int line = 0; line < 200; line++) {
            code.append("echo line-").append(line).append(" && sleep 1\n");
        }
        code.append("```\n");

        List<Section> sections = AssistantDocsChunker.split(code.toString(), 1);

        assertThat(sections).hasSizeGreaterThan(1);
        assertThat(sections.getFirst().body()).startsWith("运行下面的脚本：\n\n```bash\necho line-0 ");
        assertThat(sections.stream().skip(1)).allSatisfy(section -> assertThat(section.body()).startsWith("```bash\n"));
        assertThat(sections).allSatisfy(section ->
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS));
    }

    @Test
    void aShortParagraphIsLeftWhereItIsWhenTheNextBlockWouldNotFitWithIt() {
        String first = "字".repeat(1000);
        String second = "句".repeat(1597);
        List<Section> sections = AssistantDocsChunker.split("## 标题\n\n" + first + "\n\n注意：\n\n" + second + "\n", 1);

        assertThat(sections).hasSize(2);
        assertThat(sections.getFirst().body()).endsWith("注意：");
        assertThat(sections.getLast().body()).isEqualTo(second);
    }

    @Test
    void aLongParagraphBeforeABlockIsNotMistakenForALabel() {
        String first = "字".repeat(1000);
        String long80 = "这是一个八十字以上的完整段落，不是标签，它自己讲完了一件事情，所以留在原来的那一块里。".repeat(2);
        String second = "句".repeat(900);
        List<Section> sections = AssistantDocsChunker.split("## 标题\n\n" + first + "\n\n" + long80 + "\n\n" + second + "\n", 1);

        assertThat(long80.length()).isGreaterThanOrEqualTo(AssistantDocsChunker.LEAD_IN_CHARS);
        assertThat(sections.getLast().body()).isEqualTo(second);
    }

    // ---- hostile Markdown must not cost more than linear time --------------------------------------------------

    private static void withinASecond(String what, String markdown) {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            AssistantDocsChunker.parse(markdown, 1);
        }, what);
    }

    @Test
    void pathologicalMarkdownIsChunkedInTimeThatDoesNotGrowWithTheSquareOrCube() {
        withinASecond("spaces inside a heading", "# a" + " ".repeat(2500) + "x");
        withinASecond("tabs inside a heading", "## a" + "\t".repeat(2500) + "x");
        withinASecond("hashes after a heading", "# " + "#".repeat(32000) + "x");
        withinASecond("image openers", "![".repeat(32000));
        withinASecond("link openers", "[".repeat(32000));
        withinASecond("link openers with a tail", "[a](".repeat(8000));
        withinASecond("comment openers", "<!--".repeat(16000));
        withinASecond("tag openers", "<a ".repeat(10000));
        withinASecond("one long token without a media path", "x".repeat(60000));
        withinASecond("one long token before a media path", "x".repeat(60000) + "/documentation/media/");
        withinASecond("many media paths", "/documentation/media/".repeat(3000));
        withinASecond("backticks", "`".repeat(30000));
        withinASecond("a hundred thousand characters in one line", "字".repeat(100000));
    }
}
