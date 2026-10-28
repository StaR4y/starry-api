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

    private static final String FIRST_ID = "0f0e0d0c-0b0a-4908-8706-050403020100";
    private static final String SECOND_ID = "9a9b9c9d-8e8f-4a4b-9c9d-0e0f10111213";

    /** Ways to write two references so that nothing stands between them, or only a hyphen or what an image leaves behind. */
    private static List<String> gluedReferences() {
        return List.of(
            "attachment:" + FIRST_ID + "attachment:" + SECOND_ID,
            "attachment:" + FIRST_ID + "-attachment:" + SECOND_ID,
            "attachment:" + FIRST_ID + "-![图](attachment:" + SECOND_ID + ")attachment:" + SECOND_ID,
            "attachment:" + FIRST_ID + "![图](attachment:" + FIRST_ID + ")attachment:" + SECOND_ID,
            "attachment:attachment:" + SECOND_ID,
            "attachment:" + FIRST_ID + "attachment:attachment:" + SECOND_ID,
            "attachment:" + FIRST_ID + "attachment:" + SECOND_ID + "attachment:" + FIRST_ID);
    }

    @Test
    void anAttachmentReferenceThatFollowsAnotherOneDirectlyIsRemovedToo() {
        for (String glued : gluedReferences()) {
            List<Section> sections = AssistantDocsChunker.split(
                "## 附件 " + glued + "\n在发票页面申请开票，附件 " + glued + " 是发票样例。\n\n```text\n" + glued + "\n```\n", 1);

            assertThat(sections).as(glued).isNotEmpty().allSatisfy(section -> {
                assertThat(section.body()).doesNotContain(FIRST_ID, SECOND_ID);
                assertThat(section.heading()).doesNotContain(FIRST_ID, SECOND_ID);
            });
            assertThat(sections.getFirst().body()).contains("在发票页面申请开票");
        }
    }

    @Test
    void anAttachmentReferenceEndsWhereTheNextOneBeginsAndNothingElseIsLost() {
        assertThat(AssistantDocsChunker.clean("A attachment:" + FIRST_ID + "attachment:" + SECOND_ID + " B")).isEqualTo("A  B");
        assertThat(AssistantDocsChunker.clean("attachment:" + FIRST_ID + "-attachment:" + SECOND_ID)).isEmpty();
        assertThat(AssistantDocsChunker.clean("文件 attachment:12345678 之后")).as("an id as the page's own writes it").isEqualTo("文件  之后");
        assertThat(AssistantDocsChunker.clean("attachment:short 不是引用")).isEqualTo("attachment:short 不是引用");
    }

    @Test
    void aTextShownAsItIsLosesAttachmentsAndSignedAddressesAndNothingElse() {
        assertThat(AssistantDocsChunker.withoutMedia("发票样例 attachment:" + FIRST_ID)).isEqualTo("发票样例 ");
        assertThat(AssistantDocsChunker.withoutMedia("a![图](attachment:" + FIRST_ID + ")b")).isEqualTo("ab");
        assertThat(AssistantDocsChunker.withoutMedia("x /documentation/media/" + FIRST_ID + "?signature=1 y")).isEqualTo("x  y");
        assertThat(AssistantDocsChunker.withoutMedia("List<String> <b>x</b> `y`")).isEqualTo("List<String> <b>x</b> `y`");
        assertThat(AssistantDocsChunker.withoutMedia("attachment:" + FIRST_ID + "attachment:" + SECOND_ID)).isEmpty();
    }

    /**
     * A reference whose removal joins the text around it into another one, {@code depth} times over: the text in front of
     * the first is the word "attachment" repeated, and what is behind each is the colon that completes the next.
     */
    private static String joiningReference(int depth, String id) {
        return "attachment".repeat(depth) + "attachment:11111111" + ":22222222".repeat(depth - 1) + ":" + id;
    }

    @Test
    void aReferenceThatTheRemovalOfAnotherOneJoinsIsRemovedTooHoweverDeeplyTheyAreNested() {
        for (int depth = 1; depth <= 8; depth++) {
            String joining = joiningReference(depth, FIRST_ID);

            assertThat(AssistantDocsChunker.withoutMedia(joining)).as("a line of code or a title, depth " + depth)
                .doesNotContain("attachment:").doesNotContainPattern(CLEAN_REFERENCE);
            assertThat(AssistantDocsChunker.clean(joining)).as("a line of prose, depth " + depth)
                .doesNotContain("attachment:").doesNotContainPattern(CLEAN_REFERENCE);

            List<Section> sections = AssistantDocsChunker.split(
                "## 标题 " + joining + "\n正文 " + joining + " 在这里。\n\n```text\n代码 " + joining + "\n```\n", 1);
            assertThat(sections).as("depth " + depth).isNotEmpty().allSatisfy(section -> {
                assertThat(section.heading()).doesNotContain("attachment:").doesNotContainPattern(CLEAN_REFERENCE);
                assertThat(section.body()).doesNotContain("attachment:").doesNotContainPattern(CLEAN_REFERENCE);
            });
            assertThat(sections.stream().map(Section::body).collect(java.util.stream.Collectors.joining("\n")))
                .as("the code and the prose around it stay").contains("正文", "在这里。", "代码");
        }
    }

    @Test
    void aReferenceThatAnEmbedOrAHalfWrittenIdJoinsIsRemovedToo() {
        for (String joining : List.of(
            "attachment![图](attachment:" + SECOND_ID + "):" + FIRST_ID,
            "attachment:" + FIRST_ID.substring(0, 5) + "![图](attachment:" + SECOND_ID + ")" + FIRST_ID.substring(5))) {
            assertThat(AssistantDocsChunker.withoutMedia(joining)).as(joining).doesNotContainPattern(CLEAN_REFERENCE);
            assertThat(AssistantDocsChunker.clean(joining)).as(joining).doesNotContainPattern(CLEAN_REFERENCE);
        }
    }

    @Test
    void removingReferencesNestedAsDeeplyAsALineCanHoldTakesNoMoreThanAFewPassesOverIt() {
        // 3,000 levels (about 100,000 characters, the longest a page may be): taking the references out until none is
        // left would be a pass over the line for each level.
        int depth = 3_000;
        String joining = joiningReference(depth, FIRST_ID);

        long start = System.nanoTime();
        String withoutMedia = AssistantDocsChunker.withoutMedia(joining);
        String clean = AssistantDocsChunker.clean(joining);
        long elapsed = System.nanoTime() - start;

        assertThat(withoutMedia).doesNotContain("attachment:").doesNotContainPattern(CLEAN_REFERENCE);
        assertThat(clean).doesNotContain("attachment:").doesNotContainPattern(CLEAN_REFERENCE);
        assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(1));
    }

    /** A reference as the model would read it: "attachment:" and an id of eight characters or more that does not run into the next "attachment:". */
    private static final String CLEAN_REFERENCE = "attachment:(?!attachment:)[0-9A-Za-z](?:(?!attachment:)[0-9A-Za-z-]){7,}";

    /** References that a tag or a mark in the middle hides from the scrub that comes first: they join when it is taken out. */
    private static List<String> brokenReferences() {
        String media = "/documentation/media/" + FIRST_ID + "?expires=1&admin=true&signature=abc";
        return List.of(
            "attach**ment:" + FIRST_ID, "attach`ment:" + FIRST_ID + "`", "attach*ment:" + FIRST_ID, "attach<b>ment:" + FIRST_ID,
            "attach</i>ment:" + FIRST_ID, "attachment<b></b>:" + FIRST_ID, "attachment:<b>" + FIRST_ID.substring(0, 8) + "</b>",
            "/documentation/me**dia/" + FIRST_ID + "?admin=true&signature=abc", "/docu**mentation/media/" + FIRST_ID + "?signature=1",
            "/documentation/<i>media/" + FIRST_ID + "?admin=true&signature=def", media.replace("media", "me<b>dia"));
    }

    @Test
    void aReferenceThatOnlyEmphasisOrATagKeepsApartIsRemovedWhenTheyAreTakenOut() {
        for (String broken : brokenReferences()) {
            List<Section> sections = AssistantDocsChunker.split(
                "## 标题 " + broken + "\n正文在这里 " + broken + " 和更多的文字。\n\n### 小节 " + broken + "\n小节的内容，也有 " + broken + " 在其中。\n", 1);

            assertThat(sections).as(broken).isNotEmpty().allSatisfy(section -> {
                assertThat(section.heading()).as(broken).doesNotContainPattern(CLEAN_REFERENCE).doesNotContain("documentation/media");
                assertThat(section.body()).as(broken).doesNotContainPattern(CLEAN_REFERENCE).doesNotContain("documentation/media");
            });
            assertThat(AssistantDocsChunker.clean("文字 " + broken)).as(broken)
                .doesNotContainPattern(CLEAN_REFERENCE).doesNotContain("documentation/media");
        }
    }

    @Test
    void noMixOfMarksTagsAndReferencesLeavesAReferenceInAHeadingOrALine() {
        java.util.Random random = new java.util.Random(11);
        List<String> pieces = List.of("attach", "ment:", "attachment:", FIRST_ID, FIRST_ID.substring(0, 9), "<b>", "</i>", "<br>", "**", "*", "`",
            "/documentation/", "media/", "me", "dia/", "?signature=1", " ", " ", "x", "![图](a)", "[文](#b)", "&admin=true");
        for (int round = 0; round < 20_000; round++) {
            StringBuilder text = new StringBuilder();
            for (int part = 1 + random.nextInt(8); part > 0; part--) {
                text.append(pieces.get(random.nextInt(pieces.size())));
            }
            String markdown = "## 标题 " + text + "\n" + text + "\n";

            for (Section section : AssistantDocsChunker.split(markdown, 1)) {
                for (String shown : List.of(section.heading(), section.body())) {
                    assertThat(shown).as(markdown).doesNotContainPattern(CLEAN_REFERENCE).doesNotContain("/documentation/media/");
                }
            }
            assertThat(AssistantDocsChunker.clean(text.toString())).as(text.toString())
                .doesNotContainPattern(CLEAN_REFERENCE).doesNotContain("/documentation/media/");
        }
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

    @Test
    void aTableThatFollowsAParagraphLineDirectlyIsStillATableWhoseEveryPieceHasItsHeader() {
        // Without a blank line in front it is a table all the same, as the console's reader page shows it.
        for (String header : List.of("| 错误码 | 含义 | 处理 |\n| --- | --- | --- |", "错误码 | 含义 | 处理\n--- | --- | ---")) {
            StringBuilder table = new StringBuilder("## 错误码\n\n以下是全部错误码：\n" + header + "\n");
            for (int row = 0; row < 60; row++) {
                table.append("| E").append(row).append(" | 第").append(row).append("号错误的含义说明文字 | 按文档处理这一类错误，必要时联系管理员 |\n");
            }

            List<Section> sections = AssistantDocsChunker.split(table.toString(), 1);

            assertThat(sections).as(header).hasSizeGreaterThan(1).allSatisfy(section -> {
                assertThat(section.body()).contains(header);
                assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
            });
            assertThat(sections.getFirst().body()).as("the sentence stays with its table")
                .startsWith("以下是全部错误码：\n\n" + header + "\n| E0 |");
            assertThat(sections.stream().skip(1)).allSatisfy(section -> assertThat(section.body()).startsWith(header + "\n"));
            assertThat(String.join("\n", sections.stream().map(Section::body).toList())).contains("| E0 |", "| E59 |");
        }
    }

    @Test
    void aRowOfDashesAndPipesInsideATableIsARowAndNotAnotherDelimiterRow() {
        // An empty or "not applicable" row looks like a delimiter row; the reader page shows the table whole all the same.
        for (String empty : List.of("| - | - | - |", "|  | - | - |", "- | - | -")) {
            for (String lead : List.of("", "以下是各模型的价格：\n")) {
                String table = "| 模型 | 输入 | 输出 |\n|---|---|---|\n| model-a | 1.0 | 2.0 |\n" + empty + "\n| model-b | 3.0 | 4.0 |";

                List<Section> sections = AssistantDocsChunker.split("## 价格\n\n" + lead + table + "\n", 1);

                assertThat(sections).as(lead + empty).singleElement().satisfies(section ->
                    assertThat(section.body()).as("one table, no blank line inside it").isEqualTo(lead.isEmpty() ? table : lead + "\n" + table));
            }
        }
    }

    @Test
    void aLongTableWithARowOfDashesRepeatsItsRealHeaderInEveryPiece() {
        String header = "| 错误码 | 含义 | 处理 |\n| --- | --- | --- |";
        StringBuilder table = new StringBuilder("## 错误码\n\n" + header + "\n");
        for (int row = 0; row < 60; row++) {
            table.append(row == 29 ? "|  | - | - |" : "| E" + row + " | 第" + row + "号错误的含义说明文字 | 按文档处理这一类错误，必要时联系管理员 |").append('\n');
        }

        List<Section> sections = AssistantDocsChunker.split(table.toString(), 1);

        assertThat(sections).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertThat(section.body()).startsWith(header + "\n");
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
        });
        assertThat(String.join("\n", sections.stream().map(Section::body).toList())).contains("| E28 |", "|  | - | - |", "| E30 |", "| E59 |");
    }

    @Test
    void aParagraphLineAboveARowThatIsNotATableStaysAParagraph() {
        List<Section> sections = AssistantDocsChunker.split("## 说明\n\n用 | 隔开两个值\n| 不是表格 | 因为没有分隔行 |\n", 1);

        assertThat(sections).singleElement().satisfies(section ->
            assertThat(section.body()).isEqualTo("用 | 隔开两个值\n| 不是表格 | 因为没有分隔行 |"));
    }

    // ---- a lead-in stays with what it introduces ---------------------------------------------------------------

    @Test
    void aLabelStaysInFrontOfItsCodeWhenTheCodeFitsAPieceAloneButNotWithTheLabel() {
        String intro = "先在终端里设置环境变量，然后重新打开终端。".repeat(48);
        StringBuilder code = new StringBuilder("```text\n");
        for (int line = 0; line < 45; line++) {
            code.append(String.format("set \"VAR_%02d=%s\"%n", line, "x".repeat(21)));
        }
        code.append("```");
        assertThat(code.length()).as("fits a piece alone (1600), but not with the 16 characters of the label and a blank line")
            .isEqualTo(1586);

        List<Section> sections = AssistantDocsChunker.split("## Set environment variables manually\n\n" + intro
            + "\n\n**Windows CMD**:\n\n" + code + "\n", 1);

        assertThat(sections).as("no piece ends on the label").noneMatch(section -> section.body().endsWith("**Windows CMD**:"));
        assertThat(sections).as("the code is introduced by it").anyMatch(section -> section.body().startsWith("**Windows CMD**:\n\n```text\nset "));
        assertThat(sections).allSatisfy(section -> {
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
            assertThat(section.body().lines().filter(line -> line.startsWith("```")).count() % 2).as("no fence is left open").isZero();
        });
        assertThat(String.join("\n", sections.stream().map(Section::body).toList())).contains("VAR_00=", "VAR_44=");
    }

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
        // String.lines() leaves U+0085, U+2028 and U+2029 inside a line and "." does not match them: a fence opener
        // pattern that backtracks retries the whole line for every fence character it gives back.
        for (String separator : List.of("\n", "\r", "\u0085", "\u2028", "\u2029")) {
            withinASecond("backticks before a line separator " + (int) separator.charAt(0), "`".repeat(99999) + separator);
            withinASecond("tildes before a line separator " + (int) separator.charAt(0), "~".repeat(99999) + separator);
        }
        withinASecond("indented backticks before a separator", "   " + "`".repeat(99997) + "\u2028");
        withinASecond("backticks, a separator and more", "`".repeat(50000) + "\u0085" + "`".repeat(49999));
        withinASecond("an opened fence closed by a hostile line", "```\n" + "`".repeat(99990) + "\u2029");
        withinASecond("a hundred thousand characters in one line", "字".repeat(100000));
    }

    // ---- a section is never longer than the limit, however odd the fence that opens its code ------------------------

    private static void assertNoLongerThanTheTextItCameFrom(String what, String markdown) {
        Parsed parsed = AssistantDocsChunker.parse(markdown, 1);
        long total = parsed.sections().stream().mapToLong(section -> section.body().length()).sum();
        int largest = parsed.sections().stream().mapToInt(section -> section.body().length()).max().orElse(0);

        assertThat(parsed.sections()).as(what).isNotEmpty();
        assertThat(largest).as(what + ": the largest section").isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
        assertThat(total).as(what + ": all sections together").isLessThanOrEqualTo(2L * markdown.length());
    }

    @Test
    void aFenceMarkerOfTensOfThousandsOfCharactersIsNotRepeatedAroundEveryPieceOfTheCodeItOpens() {
        for (char fence : new char[] {'`', '~'}) {
            String marker = String.valueOf(fence).repeat(50_000);
            assertNoLongerThanTheTextItCameFrom("unclosed " + fence, marker + "\n" + "x\n".repeat(25_000));
            assertNoLongerThanTheTextItCameFrom("one long line " + fence, marker + "\n" + "y".repeat(49_999));
            String closing = String.valueOf(fence).repeat(33_000);
            assertNoLongerThanTheTextItCameFrom("closed " + fence, closing + "\n" + "x\n".repeat(16_000) + closing + "\n");
            assertNoLongerThanTheTextItCameFrom("with a language " + fence, marker + "java" + "j".repeat(500) + "\n" + "x\n".repeat(20_000));
            assertNoLongerThanTheTextItCameFrom("indented " + fence, "   " + marker + "\n" + "x\n".repeat(25_000));
            assertNoLongerThanTheTextItCameFrom("after a label " + fence, "说明：\n\n" + marker + "\n" + "x\n".repeat(25_000));
        }
    }

    @Test
    void theCodeOfABlockWithAnEnormousFenceKeepsItsFenceAndItsLanguageInEveryPiece() {
        String marker = "`".repeat(5000);
        StringBuilder code = new StringBuilder("## 脚本\n\n").append(marker).append("bash\n");
        for (int line = 0; line < 300; line++) {
            code.append("echo line-").append(line).append(" && sleep 1\n");
        }
        code.append(marker).append("\n");

        List<Section> pieces = AssistantDocsChunker.split(code.toString(), 1);

        String fence = "`".repeat(AssistantDocsChunker.MAX_FENCE_CHARS);
        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
            assertThat(section.body()).startsWith(fence + "bash\n").endsWith("\n" + fence);
            assertThat(section.body().indexOf(fence, fence.length())).as("one opening and one closing fence")
                .isEqualTo(section.body().length() - fence.length());
        });
        String all = String.join("\n", pieces.stream().map(Section::body).toList());
        assertThat(all).contains("echo line-0 &&", "echo line-299 &&");
    }

    /** The piece is one fenced block: its first line opens a fence, and the first line that closes it is the last one. */
    private static void assertWholeFencedBlock(String piece) {
        String[] lines = piece.split("\n", -1);
        java.util.regex.Matcher opener = java.util.regex.Pattern.compile("^(`{3,}|~{3,})[^`]*$").matcher(lines[0]);
        assertThat(opener.matches()).as("opens a fence: %s", lines[0]).isTrue();
        String fence = opener.group(1);
        int closing = -1;
        for (int index = 1; index < lines.length && closing < 0; index++) {
            String line = lines[index].strip();
            if (line.length() >= fence.length() && line.chars().allMatch(value -> value == fence.charAt(0))) {
                closing = index;
            }
        }
        assertThat(closing).as("the first line that closes %s%nin%n%s", fence, piece).isEqualTo(lines.length - 1);
    }

    @Test
    void aLineInsideALongFenceThatIsAsLongAsThePiecesFenceDoesNotCloseThePiecesEarly() {
        for (char fence : new char[] {'`', '~'}) {
            String marker = String.valueOf(fence).repeat(20);
            String inner = String.valueOf(fence).repeat(18);
            StringBuilder code = new StringBuilder("## 代码\n\n").append(marker).append("markdown\n").append(inner).append('\n');
            for (int line = 0; line < 400; line++) {
                code.append("第 ").append(line).append(" 行的内容，用来把这个代码块切成几段\n");
            }
            code.append(inner).append('\n').append(marker).append('\n');

            List<Section> pieces = AssistantDocsChunker.split(code.toString(), 1);

            assertThat(pieces).as("cut into several pieces").hasSizeGreaterThan(2);
            assertThat(pieces).allSatisfy(section -> {
                assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
                assertWholeFencedBlock(section.body());
            });
            String all = String.join("\n", pieces.stream().map(Section::body).toList());
            assertThat(all).contains("第 0 行", "第 399 行").contains(inner);
        }
    }

    @Test
    void aFenceOfOneKindIsLeftForTheOtherWhenTheCodeNeedsALongOneOfTheFirst() {
        String marker = "`".repeat(40);
        StringBuilder code = new StringBuilder("## 代码\n\n").append(marker).append("bash\n").append("`".repeat(30)).append('\n');
        for (int line = 0; line < 100; line++) {
            code.append("echo ").append(line).append(" && sleep 1 # 一行代码\n");
        }
        code.append(marker).append('\n');

        List<Section> pieces = AssistantDocsChunker.split(code.toString(), 1);

        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertWholeFencedBlock(section.body());
            assertThat(section.body()).startsWith("~~~bash\n").endsWith("\n~~~");
        });
    }

    @Test
    void aBlockWhoseCodeHasLongFenceLinesOfBothKindsIsCutWithoutAFenceAndSaysSo() {
        String marker = "`".repeat(40);
        StringBuilder code = new StringBuilder("## 代码\n\n").append(marker).append("markdown\n")
            .append("`".repeat(30)).append('\n').append("~".repeat(30)).append('\n');
        for (int line = 0; line < 100; line++) {
            code.append("第 ").append(line).append(" 行的内容，用来把这个代码块切成几段\n");
        }
        code.append(marker).append('\n');

        List<Section> pieces = AssistantDocsChunker.split(code.toString(), 1);

        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertThat(section.body()).startsWith(AssistantDocsChunker.UNFENCED_NOTE + "\n");
            assertThat(section.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
            assertThat(section.body().lines().skip(1).filter(line -> line.startsWith("```") || line.startsWith("~~~")).count())
                .as("only the lines of the code itself, no fence of ours").isLessThanOrEqualTo(2);
        });
        String all = String.join("\n", pieces.stream().map(Section::body).toList());
        assertThat(all).contains("第 0 行", "第 99 行");
    }

    @Test
    void theLanguageOfAFenceOfTildesIsKeptWithoutBackticksWhenThePiecesTakeBackticks() {
        String marker = "~".repeat(40);
        StringBuilder code = new StringBuilder("## 代码\n\n").append(marker).append("a`b\n").append("~".repeat(30)).append('\n');
        for (int line = 0; line < 100; line++) {
            code.append("第 ").append(line).append(" 行的内容，用来把这个代码块切成几段\n");
        }
        code.append(marker).append('\n');

        List<Section> pieces = AssistantDocsChunker.split(code.toString(), 1);

        assertThat(pieces).hasSizeGreaterThan(1).allSatisfy(section -> {
            assertWholeFencedBlock(section.body());
            assertThat(section.body()).startsWith("```ab\n");
        });
    }

    @Test
    void aSectionIsCutToTheLimitWhateverMadeItLongerAndNeverInsideASurrogatePair() {
        assertThat(AssistantDocsChunker.limited("x".repeat(AssistantDocsChunker.MAX_CHUNK_CHARS))).hasSize(AssistantDocsChunker.MAX_CHUNK_CHARS);
        assertThat(AssistantDocsChunker.limited("x".repeat(100_000))).hasSize(AssistantDocsChunker.MAX_CHUNK_CHARS);
        String pair = "x".repeat(AssistantDocsChunker.MAX_CHUNK_CHARS - 1) + "\uD83D\uDE00" + "tail";
        String cut = AssistantDocsChunker.limited(pair);
        assertThat(cut).hasSize(AssistantDocsChunker.MAX_CHUNK_CHARS - 1);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
        assertThat(AssistantDocsChunker.limited("短")).isEqualTo("短");
    }
}
