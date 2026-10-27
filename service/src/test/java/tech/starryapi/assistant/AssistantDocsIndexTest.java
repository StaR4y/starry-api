package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.assistant.AssistantDocsIndex.Chunk;
import tech.starryapi.assistant.AssistantDocsIndex.Retrieval;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Translation;

class AssistantDocsIndexTest {

    private static final Path NO_FILES = Path.of("does-not-exist");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** A clock the tests move by hand. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static Document page(String id, String title, String category, String summary, String body) {
        return new Document(id, new Content(title, category, summary, body, 100), Instant.EPOCH, List.of());
    }

    static Document page(String id, Content content) {
        return new Document(id, content, Instant.EPOCH, List.of());
    }

    static AssistantDocsIndex indexOf(Document... pages) {
        return new AssistantDocsIndex(NO_FILES, () -> Mono.just(List.of(pages)), new TestClock());
    }

    static AssistantDocsIndex indexOf(List<Document> pages) {
        return new AssistantDocsIndex(NO_FILES, () -> Mono.just(pages), new TestClock());
    }

    static Retrieval ask(AssistantDocsIndex index, String question) {
        return index.retrieve(question, "").block(TIMEOUT);
    }

    static Retrieval ask(AssistantDocsIndex index, String question, String previous) {
        return index.retrieve(question, previous).block(TIMEOUT);
    }

    private static List<String> pageIds(Retrieval retrieval) {
        return retrieval.chunks().stream().map(Chunk::pageId).distinct().toList();
    }

    private static List<String> sections(Retrieval retrieval) {
        return retrieval.chunks().stream().map(Chunk::section).toList();
    }

    /** The earlier user messages are given the latest first, as the chat service gives them. */
    static Retrieval askAfter(AssistantDocsIndex index, String question, String... earlier) {
        return index.retrieveFor(question, List.of(earlier)).block(TIMEOUT);
    }

    /** The tutorials of a typical installation, in miniature. */
    private static AssistantDocsIndex help() {
        return indexOf(AssistantDocsCorpus.pages());
    }

    // ---- files (development checkouts) -------------------------------------------------------------------------

    @Test
    void retrievesChineseDocChunksFromFiles(@TempDir Path root) throws Exception {
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

        Retrieval hits = ask(index, "怎么创建 API Key");

        assertThat(hits.chunks()).isNotEmpty();
        assertThat(hits.chunks().getFirst().body()).contains("sk-");
        assertThat(hits.chunks().getFirst().isPage()).isFalse();
        assertThat(hits.sources()).containsExactly("docs/API.md");
    }

    @Test
    void splitsMarkdownSectionsOfAFile() {
        List<Chunk> chunks = AssistantDocsIndex.split("docs/API.md", """
            # Title
            ## First
            This section is long enough to be indexed as a document chunk for retrieval.
            ## Second
            Another sufficiently long paragraph about billing multipliers and official prices.
            """);
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunks).extracting(Chunk::section).contains("Title › First", "Title › Second");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.pageTitle()).isEqualTo("docs/API.md"));
    }

    @Test
    void mergesPagesAndFilesIntoOneRankingWithPagesFirstAmongEqualScores(@TempDir Path root) throws Exception {
        String body = "余额不足时请求会被拒绝，请先到余额充值页面完成充值，再重新发起请求，充值到账后一般不需要其他操作。";
        Files.writeString(root.resolve("README.md"), "## 余额不足\n" + body + "\n");
        AssistantDocsIndex index = new AssistantDocsIndex(root,
            () -> Mono.just(List.of(page("p1", "x", "y", "", "## 余额不足\n" + body))), new TestClock());

        Retrieval hits = ask(index, "余额不足");

        assertThat(hits.chunks()).extracting(Chunk::isPage).containsExactly(true, false);
        assertThat(hits.sources()).containsExactly("使用文档《x》", "README.md");
    }

    // ---- chunks of pages ---------------------------------------------------------------------------------------

    @Test
    void everyChunkCarriesPageIdTitleSectionAndLanguage() {
        Content content = new Content("创建与管理 API Key", "开始使用", "怎么创建密钥", """
            ## 新建密钥
            点击新建 API Key，填写名称。

            ## 复制密钥
            在列表里点复制密钥。
            """, 10, new Translation("Create and manage API keys", "Getting started", "How to create a key", """
            ## Create a key
            Select Create API key and enter a name.
            """));
        AssistantDocsIndex index = indexOf(page("api-keys", content));

        List<Chunk> zh = ask(index, "新建密钥").chunks();
        List<Chunk> en = ask(index, "create a key").chunks();

        assertThat(zh.getFirst()).satisfies(chunk -> {
            assertThat(chunk.pageId()).isEqualTo("api-keys");
            assertThat(chunk.pageTitle()).isEqualTo("创建与管理 API Key");
            assertThat(chunk.category()).isEqualTo("开始使用");
            assertThat(chunk.summary()).isEqualTo("怎么创建密钥");
            assertThat(chunk.section()).isEqualTo("新建密钥");
            assertThat(chunk.language()).isEqualTo("zh");
        });
        assertThat(en.getFirst()).satisfies(chunk -> {
            assertThat(chunk.pageId()).isEqualTo("api-keys");
            assertThat(chunk.pageTitle()).isEqualTo("Create and manage API keys");
            assertThat(chunk.section()).isEqualTo("Create a key");
            assertThat(chunk.language()).isEqualTo("en");
        });
    }

    @Test
    void attachmentsImagesAndHtmlNeverReachTheChunks() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        AssistantDocsIndex index = indexOf(page("media", "视频教程", "教程", "<b>看视频</b>学习", """
            ## 观看
            ![演示](attachment:%s)
            [下载讲义](attachment:%s)，页面里有教程 <span>视频</span>。

            ```json
            {"cover": "attachment:%s", "url": "/documentation/media/%s?signature=x"}
            ```
            """.formatted(id, id, id, id)));

        List<Chunk> chunks = ask(index, "教程视频").chunks();

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().body()).contains("下载讲义", "视频").doesNotContain(id, "attachment", "<span>", "signature");
        assertThat(chunks.getFirst().summary()).isEqualTo("看视频学习");
        assertThat(String.join(" ", ask(index, "教程视频").sources())).doesNotContain(id);
    }

    @Test
    void aSubsectionIsFoundThroughItsParentHeadingAndTheShortIntroductionIsNotAChunkOfItsOwn() {
        AssistantDocsIndex index = indexOf(
            page("billing", "充值与计费", "开始使用", "", """
                ## 给余额充值
                打开 余额充值。

                ### 在线充值
                选择金额，在线支付。

                ### 使用兑换码
                输入管理员发给你的兑换码，额度立即到账。
                """),
            page("other", "其他", "帮助", "", "## 杂项\n与此无关的内容。"));

        List<Chunk> chunks = ask(index, "怎么充值").chunks();

        assertThat(chunks).extracting(Chunk::section).containsExactlyInAnyOrder("给余额充值 › 在线充值", "给余额充值 › 使用兑换码");
        assertThat(chunks).extracting(Chunk::body).noneMatch(body -> body.equals("打开 余额充值。"));
    }

    // ---- scoring -----------------------------------------------------------------------------------------------

    @Test
    void aMatchInThePageTitleOutranksTheSameMatchInBodyText() {
        AssistantDocsIndex index = indexOf(
            page("faq", "常见问题", "帮助", "", "## 提问\n发票的说明：发票要在发票页面申请。"),
            page("invoice", "发票说明", "帮助", "", "## 提问\n发票的说明：发票要在发票页面申请。"));

        Retrieval hits = ask(index, "发票");

        assertThat(hits.chunks()).extracting(Chunk::pageId).containsExactly("invoice", "faq");
    }

    @Test
    void aMatchInTheSectionHeadingOutranksTheSameMatchInBodyText() {
        AssistantDocsIndex index = indexOf(page("guide", "指南", "帮助", "", """
            ## 其他
            退款的内容在这里提到，退款也会提到两次。

            ## 退款
            这里的内容与此无关。
            """));

        Retrieval hits = ask(index, "退款");

        assertThat(hits.chunks()).extracting(Chunk::section).containsExactly("退款", "其他");
    }

    @Test
    void categoryAndSummaryAreSearchable() {
        AssistantDocsIndex index = indexOf(
            page("a", "页面甲", "账号安全", "", "## 内容\n无关的正文。"),
            page("b", "页面乙", "帮助", "介绍如何申请开票", "## 内容\n另一段无关的正文。"),
            page("c", "页面丙", "帮助", "", "## 内容\n第三段无关的正文。"));

        assertThat(ask(index, "账号安全").chunks()).extracting(Chunk::pageId).containsExactly("a");
        assertThat(ask(index, "申请开票").chunks()).extracting(Chunk::pageId).containsExactly("b");
    }

    @Test
    void rareWordsCountForMoreThanWordsThatAreEverywhere() {
        List<Document> pages = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            pages.add(page("common-" + index, "页面" + index, "帮助", "", "## 内容\n按钮按钮按钮。"));
        }
        pages.add(page("rare", "页面稀有", "帮助", "", "## 内容\n按钮，沙箱。"));

        // Three mentions of a word that every page has are worth less than one mention of a word only one page has.
        assertThat(ask(indexOf(pages), "按钮 沙箱").chunks().getFirst().pageId()).isEqualTo("rare");
    }

    @Test
    void latinWordsMatchWholeWordsOptionallyWithACommonEnding() {
        AssistantDocsIndex index = indexOf(
            page("mail", "邮件", "帮助", "", "## 邮件\nSend an email to the administrator."),
            page("keys", "密钥", "帮助", "", "## 密钥\nCreate keys in the console, then copy the Key."),
            page("claude", "Claude", "帮助", "", "## Claude\nSet up Claude Code with the generated settings."),
            page("codex", "Codex", "帮助", "", "## Codex\nSet up Codex CLI with the generated settings."));

        assertThat(ask(index, "ai").chunks()).as("not inside 'email'").isEmpty();
        assertThat(ask(index, "monk").chunks()).isEmpty();
        assertThat(ask(index, "key").chunks()).extracting(Chunk::pageId).containsExactly("keys");
        assertThat(ask(index, "keys").chunks()).as("a plural question word also finds 'Key'").extracting(Chunk::pageId)
            .containsExactly("keys");
        assertThat(ask(index, "created").chunks()).isEmpty();
        assertThat(ask(index, "create").chunks()).extracting(Chunk::pageId).containsExactly("keys");
        assertThat(ask(index, "code").chunks()).as("'Claude Code' is not 'Codex'").extracting(Chunk::pageId)
            .containsExactly("claude");
        assertThat(ask(index, "settings").chunks()).extracting(Chunk::pageId).containsExactly("claude", "codex");
    }

    @Test
    void questionFillerAndStopWordsAreNotSearchTerms() {
        assertThat(AssistantDocsIndex.terms("怎么创建 API Key")).containsExactly("创建", "api", "key");
        assertThat(AssistantDocsIndex.terms("How do I create the API key?")).containsExactly("create", "api", "key");
        assertThat(AssistantDocsIndex.terms("ＡＰＩ密钥")).containsExactly("api", "密钥");
        assertThat(AssistantDocsIndex.terms("API keys and access plans")).containsExactly("api", "key", "access", "plan");
        assertThat(AssistantDocsIndex.terms("Coding Plan 和余额有什么区别")).containsExactly("coding", "plan", "和余", "余额");
    }

    @Test
    void aLoneChineseCharacterIsATermOnlyWhenTheQuestionHasNothingElse() {
        assertThat(AssistantDocsIndex.terms("那怎么改？")).containsExactly("改");
        assertThat(AssistantDocsIndex.terms("那怎么删？")).containsExactly("删");
        assertThat(AssistantDocsIndex.terms("怎么办呢")).as("only grammar is left").isEmpty();
        assertThat(AssistantDocsIndex.terms("OpenCode 怎么用")).as("a word is there, so the lone 用 is no term")
            .containsExactly("opencode");
        assertThat(AssistantDocsIndex.termWeights("改")).containsEntry("改", 0.6);
    }

    @Test
    void wholeIdentifiersAreTermsOfTheirOwnAndCountForMoreThanTheirParts() {
        assertThat(AssistantDocsIndex.termWeights("报错 API_KEY_MODEL_PROVIDER_FORBIDDEN"))
            .containsEntry("api_key_model_provider_forbidden", 4.0)
            .containsEntry("api", 0.3).containsEntry("key", 0.3).containsEntry("model", 0.3)
            .containsEntry("provider", 0.3).containsEntry("forbidden", 0.3);
        assertThat(AssistantDocsIndex.termWeights("opencode.json 放哪里"))
            .containsEntry("opencode.json", 4.0).containsEntry("opencode", 0.3).containsEntry("json", 0.3);
        assertThat(AssistantDocsIndex.termWeights("Create an API key. Then copy it."))
            .as("a full stop between sentences is no identifier").doesNotContainKeys("key.", "key.then");
        assertThat(AssistantDocsIndex.termWeights("API key and the api"))
            .as("a part that is also a word of its own keeps the higher weight").containsEntry("api", 1.0);
    }

    @Test
    void neighbouringLatinWordsFormPhrases() {
        assertThat(AssistantDocsIndex.phrases("How do I configure Claude Code?")).containsExactly("configure claude", "claude code");
        assertThat(AssistantDocsIndex.phrases("API keys and models")).containsExactly("api key");
        assertThat(AssistantDocsIndex.phrases("Claude，Code")).as("punctuation breaks a phrase").isEmpty();
        assertThat(AssistantDocsIndex.phrases("创建 API Key")).containsExactly("api key");
    }

    @Test
    void anIdentifierThatThePageNamesBeatsPagesThatOnlyShareItsWords() {
        AssistantDocsIndex index = indexOf(
            page("errors", "错误码速查", "遇到问题", "", """
                ## 先看状态码
                | 状态码 | 含义 |
                | --- | --- |
                | 403 | `API_KEY_MODEL_PROVIDER_FORBIDDEN`：这把密钥不能用这个厂商的模型 |
                """),
            page("keys", "创建与管理 API Key", "开始使用", "", """
                ## 模型分类
                每把 API Key 只能使用一个厂商的模型。选好 provider 之后，这把 key 的 model 就固定了。API Key 的 model provider 设置之后不要随意改动。
                """),
            page("models", "选择模型", "开始使用", "", "## 模型\n在 model 列表里查看每个 provider 的模型，也可以用 API Key 调用。"));

        Retrieval hits = ask(index, "报错 API_KEY_MODEL_PROVIDER_FORBIDDEN");

        assertThat(hits.chunks().getFirst().pageId()).isEqualTo("errors");
        assertThat(ask(index, "API_KEY_MODEL_PROVIDER_FORBIDDEN 是什么").chunks().getFirst().pageId()).isEqualTo("errors");
    }

    @Test
    void anIdentifierIsMatchedWholeAndNotInsideALongerOne() {
        AssistantDocsIndex index = indexOf(
            page("longer", "说明甲", "帮助", "", "## 说明\n请求头里放 api_key_id，不要放别处。"),
            page("exact", "说明乙", "帮助", "", "## 说明\n请求头里放 api_key，不要放别处。"));

        assertThat(ask(index, "api_key 放哪里").chunks()).extracting(Chunk::pageId).first().isEqualTo("exact");
        assertThat(ask(index, "api_key_id 放哪里").chunks()).extracting(Chunk::pageId).first().isEqualTo("longer");
    }

    @Test
    void aFileNameIsFoundAsAWholeName() {
        AssistantDocsIndex index = indexOf(
            page("opencode", "手动配置 OpenCode", "连接客户端", "", "## 手动配置\n编辑 `opencode.json`，把地址和密钥填进去。"),
            page("codex", "手动配置 Codex CLI", "连接客户端", "", "## 手动配置\n编辑 `config.toml`，把地址和密钥填进去。"),
            page("other", "其他", "帮助", "", "## 杂项\n与此无关。"));

        assertThat(ask(index, "config.toml 放哪里").chunks().getFirst().pageId()).isEqualTo("codex");
        assertThat(ask(index, "opencode.json 放哪里").chunks().getFirst().pageId()).isEqualTo("opencode");
    }

    @Test
    void modelNamesAreIdentifiersAndAVersionOfOneIsNotAnother() {
        AssistantDocsIndex index = indexOf(
            page("models", "模型说明", "模型", "", """
                ## claude-opus-4.8
                旗舰模型，输入 ¥15，适合复杂任务。

                ## claude-opus-4.5
                上一代旗舰，输入 ¥10，已不推荐。

                ## gpt-5.5
                通用模型，输入 ¥8。

                ## gpt-5.5-mini
                轻量模型，输入 ¥1。
                """),
            page("claude", "手动配置 Claude Code", "连接客户端", "", "## 手动设置环境变量\n把 ANTHROPIC_MODEL 设为 claude-opus-4.8，其余按默认。"));

        assertThat(AssistantDocsIndex.termWeights("claude-opus-4.5 多少钱")).containsEntry("claude-opus-4.5", 4.0)
            .containsEntry("4.5", 0.3).containsEntry("claude", 0.3);
        assertThat(AssistantDocsIndex.termWeights("4.5")).as("a version on its own is a whole identifier").containsEntry("4.5", 4.0);
        assertThat(ask(index, "claude-opus-4.5 多少钱").chunks().getFirst().section()).isEqualTo("claude-opus-4.5");
        assertThat(ask(index, "claude-opus-4.8 多少钱").chunks().getFirst().section()).isEqualTo("claude-opus-4.8");
        assertThat(ask(index, "gpt-5.5 多少钱").chunks().getFirst().section()).isEqualTo("gpt-5.5");
        assertThat(ask(index, "gpt-5.5-mini 多少钱").chunks().getFirst().section()).isEqualTo("gpt-5.5-mini");
        assertThat(ask(index, "How much is claude-opus-4.5-20251101?").chunks().getFirst().section())
            .as("a dated name of the same model").isEqualTo("claude-opus-4.5");
    }

    @Test
    void aPhraseOutranksTheSameWordsApartFromEachOther() {
        AssistantDocsIndex index = indexOf(
            page("apart", "Notes", "Help", "", "## Notes\nThe code of the status is in the table. Claude is another product. Read the code first."),
            page("together", "Notes", "Help", "", "## Notes\nOpen Claude Code from your terminal. The tool is called Claude Code in the docs."),
            page("filler", "Other", "Help", "", "## Other\nSomething else entirely."));

        Retrieval hits = ask(index, "claude code");

        assertThat(hits.chunks().getFirst().pageId()).isEqualTo("together");
    }

    // ---- not covered: nothing is better than a weak match ------------------------------------------------------

    private static List<Document> userGuide() {
        return List.of(
            page("tickets", "如何提交工单", "遇到问题", "遇到问题时怎么联系管理员", """
                ## 提交一张工单
                在「我的工单」里新建工单，写清楚发生的时间，例如 今天 14:20 左右，再附上截图和请求日志。

                ## 状态的含义
                待处理表示管理员还没看，处理中表示正在排查。
                """),
            page("claude", "手动配置 Claude Code", "连接客户端", "把 Claude Code 接到本站", """
                ## 手动设置环境变量
                配置 Claude Code 需要两个环境变量：ANTHROPIC_BASE_URL 和 ANTHROPIC_AUTH_TOKEN。配置之后重新打开终端，再运行 Claude Code。
                """),
            page("errors", "错误码速查", "遇到问题", "", """
                ## 先看状态码
                请求失败时，先看返回的 status code：401 表示密钥无效，403 表示没有权限，429 表示请求太频繁。
                """),
            page("billing", "充值与计费", "开始使用", "", """
                ## 使用兑换码
                在余额充值页面输入兑换码（Redeem a code），额度立即到账。
                """));
    }

    @Test
    void aQuestionTheDocumentsDoNotCoverRetrievesNothingAndOffersTheTitlesInstead() {
        AssistantDocsIndex index = indexOf(userGuide());

        for (String question : List.of("今天天气怎么样", "怎么看监控大屏", "你是谁", "What is the capital of France?")) {
            Retrieval hits = ask(index, question);
            assertThat(hits.chunks()).as(question).isEmpty();
            assertThat(hits.sources()).as(question).isEmpty();
            assertThat(hits.pageTitles()).as(question).hasSize(4);
        }
    }

    @Test
    void oneWordInAnExampleRowDoesNotMakeAPageARelevantAnswer() {
        AssistantDocsIndex index = indexOf(userGuide());

        // "今天" is in an example; "天气" and "监控报表" are what the question asks about, and nobody has them.
        assertThat(ask(index, "今天天气怎么样").chunks()).isEmpty();
        assertThat(ask(index, "今天的监控报表怎么导出").chunks()).isEmpty();
        assertThat(ask(index, "今天").chunks()).as("but a question that is only that word is answered").hasSize(1);
    }

    @Test
    void genericWordsOfAMultiWordQuestionDoNotCiteUnrelatedPages() {
        AssistantDocsIndex index = indexOf(
            page("claude", "Set up Claude Code", "Clients", "Connect Claude Code to this site", """
                ## Set environment variables
                To configure Claude Code, set ANTHROPIC_BASE_URL and ANTHROPIC_AUTH_TOKEN, then start Claude Code again.
                """),
            page("errors", "Error reference", "Problems", "", """
                ## Start with the status code
                Every failed request returns a status code: 401 means the key is invalid, 403 means no permission.
                """),
            page("billing", "Top-ups and billing", "Start", "", """
                ## Redeem a code
                Enter the redeem code on the top-up page and the credit arrives at once.
                """));

        Retrieval hits = ask(index, "How do I configure Claude Code?");

        assertThat(pageIds(hits)).containsExactly("claude");
    }

    @Test
    void aTopUpQuestionDoesNotCiteSetUpPages() {
        AssistantDocsIndex index = indexOf(
            page("billing", "Top-ups and billing", "Start", "", "## Top up your balance\nOpen Top up, choose an amount and pay online."),
            page("claude", "Set up Claude Code", "Clients", "", "## Set up\nSet up Claude Code in two steps: install it, then set the address."),
            page("codex", "Set up Codex CLI", "Clients", "", "## Set up\nSet up Codex CLI in two steps: install it, then set the address."));

        assertThat(pageIds(ask(index, "How do I top up?"))).containsExactly("billing");
    }

    // ---- ordinary wording around the topic ---------------------------------------------------------------------

    private static void assertFirstPage(AssistantDocsIndex index, String expected, String... questions) {
        for (String question : questions) {
            Retrieval hits = ask(index, question);
            assertThat(hits.chunks()).as(question).isNotEmpty();
            assertThat(hits.chunks().getFirst().pageId()).as(question).isEqualTo(expected);
        }
    }

    @Test
    void wordsAroundTheTopicDoNotHideAnAnswerTheDocumentsHave() {
        AssistantDocsIndex index = help();

        assertFirstPage(index, "errors", "502 是怎么回事", "502 啥意思", "502 咋回事");
        assertThat(pageIds(ask(index, "调用的时候报 400 Bad Request 是怎么回事"))).contains("errors");
        assertFirstPage(index, "billing", "我想问下充值的话怎么操作", "为啥扣了这么多钱", "我的余额为什么变少了");
        assertFirstPage(index, "console-tour", "查用量", "在哪里看用量", "用量在哪看", "怎么看用量", "怎么看新闻", "在哪里看新闻");
        for (String question : List.of("余额在哪看", "去哪看余额", "哪里能看到余额")) {
            assertThat(pageIds(ask(index, question))).as(question).containsAnyOf("billing", "console-tour");
        }
        assertThat(pageIds(ask(index, "帮我看看这个 401 报错"))).containsAnyOf("errors", "faq");
    }

    @Test
    void theJoinBetweenATopicAndTheWordingBesideItIsNeitherATermNorWhatTheQuestionLacks() {
        // "看密" is only in the 429 row ("就看密钥的用量和上限"), where it is not about the key page.
        Retrieval hits = ask(help(), "在哪里看密钥");

        assertThat(hits.chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(AssistantDocsIndex.termWeights("在哪里看密钥")).containsEntry("密钥", 1.0).doesNotContainKey("看密钥");
        assertThat(AssistantDocsIndex.termWeights("怎么看用量")).containsEntry("用量", 1.0).containsEntry("看用", 0.2);
    }

    @Test
    void aJoinBetweenTwoWordsOfTheQuestionIsNotSomethingTheDocumentsLack() {
        AssistantDocsIndex index = indexOf(
            page("pay", "说明甲", "帮助", "", "## 杂项\n充值成功，等待到账；之后余额增加。"),
            page("other", "其他", "帮助", "", "## 杂项\n与此无关的内容。"));

        assertThat(AssistantDocsIndex.terms("充值到账之后")).as("'值到' and '账之' are pairs of the message").contains("值到", "账之");
        assertThat(pageIds(ask(index, "充值到账之后"))).as("but the documents have the words").containsExactly("pay");
        assertThat(ask(index, "充值到账之后天气预报").chunks()).as("a word they lack is another matter").isEmpty();
    }

    @Test
    void weakPassingMentionsAreStillNotAnAnswerToAQuestionAboutSomethingTheDocumentsLack() {
        AssistantDocsIndex index = help();

        // "今天" and "天气", "服务器", "监控": the seams of padded wording are forgiven, words of the topic are not.
        for (String question : List.of("今天天气怎么样", "怎么看监控大屏", "你是谁", "你们支持 Kiro 吗", "What is the capital of France?")) {
            assertThat(ask(index, question).chunks()).as(question).isEmpty();
        }
    }

    @Test
    void theReasonPhraseOfAStatusCodeAndTheMarkupOfAPastedErrorPageAreNotWhatTheQuestionAsksFor() {
        AssistantDocsIndex index = help();

        assertFirstPage(index, "errors", "502 Bad Gateway", "400 Bad Request", "402 Payment Required", "报错 502 Bad Gateway",
            "API Error: 502 <html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center>"
                + "<hr><center>nginx</center></body></html>");
        assertThat(AssistantDocsIndex.termWeights("502 Bad Gateway")).containsEntry("502", 1.0)
            .containsEntry("bad", 0.2).containsEntry("gateway", 0.2);
        assertThat(AssistantDocsIndex.termWeights("a bad idea")).as("without a status code before it").containsEntry("bad", 1.0);
    }

    @Test
    void anEnglishQuestionWithWordsOfRequestingFindsItsPage() {
        AssistantDocsIndex index = help();

        assertFirstPage(index, "console-tour", "Is there a way to see my usage?");
        Retrieval key = ask(index, "I want to know how to get a key");
        assertThat(key.chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(key.chunks().getFirst().section()).isEqualTo("Create a key");
        assertThat(sections(key)).as("'know' and 'get' are in 'Rules to know' and 'I get 401'")
            .doesNotContain("Rules to know");
    }

    @Test
    void wordsOfTheStructureOfADocumentCannotCarryAChunkThatLacksTheTopic() {
        AssistantDocsIndex index = help();

        assertThat(AssistantDocsIndex.termWeights("充值怎么操作")).containsEntry("充值", 1.0).containsEntry("操作", 0.2);
        assertThat(AssistantDocsIndex.termWeights("How do I verify the steps")).containsEntry("verify", 0.2).containsEntry("step", 0.2);

        Retrieval topUp = ask(index, "充值怎么操作");
        assertThat(topUp.chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(sections(topUp)).as("'操作' is in the heading of the key list").doesNotContain("列表里的操作");
        Retrieval plan = ask(index, "订阅怎么操作");
        assertThat(plan.chunks().getFirst().pageId()).isEqualTo("coding-plan");
        assertThat(sections(plan)).doesNotContain("列表里的操作");
        assertThat(pageIds(ask(index, "OpenCode 怎么验证"))).as("every client page has a 验证 heading").containsExactly("opencode");
        assertThat(ask(index, "余额有什么规则").chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(sections(ask(index, "充值的下一步"))).as("'下一步' is a heading of several pages, none about 充值").doesNotContain("下一步");
        assertThat(pageIds(ask(index, "Codex 的常见问题"))).contains("codex-cli").doesNotContain("claude-code");
        assertThat(askAfter(index, "What about Codex?", "How do I configure Claude Code?").chunks().getFirst().pageId())
            .as("'about' is not worth more than 'codex'").isEqualTo("codex-cli");
    }

    @Test
    void aWordOfWordingStillBreaksATieBetweenChunksThatHaveTheTopic() {
        AssistantDocsIndex index = indexOf(
            page("a", "甲页", "帮助", "", "## 套餐\n套餐的说明在这里。"),
            page("b", "乙页", "帮助", "", "## 套餐\n套餐的步骤和说明在这里。"));

        assertThat(ask(index, "套餐的步骤").chunks()).extracting(Chunk::pageId).containsExactly("b", "a");
        assertThat(ask(index, "套餐").chunks()).extracting(Chunk::pageId).as("without the word the catalog order decides")
            .containsExactly("a", "b");
    }

    // ---- language ----------------------------------------------------------------------------------------------

    private static Content tutorial() {
        return new Content("手动接入 API", "连接客户端", "", """
            ## 准备地址与密钥
            在控制台复制站点地址，再创建一把密钥。

            ## 密钥是什么
            密钥是你调用接口的凭证，请妥善保管。
            """, 10, new Translation("Manual API setup", "Connect a client", "", """
            ## Prepare the address and key
            Copy the site address from the console and create a key.

            ## What a key is
            A key is your credential for the API. Keep it safe.
            """));
    }

    @Test
    void aChineseQuestionIsAnsweredFromTheChineseVersionEvenWhenItsLatinWordsFitTheEnglishHeadingBetter() {
        AssistantDocsIndex index = indexOf(page("manual-api", tutorial()));

        Retrieval hits = ask(index, "API Key 是什么");

        assertThat(hits.chunks()).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.language()).isEqualTo("zh"));
        assertThat(hits.chunks().getFirst().section()).as("the twin of 'What a key is'").isEqualTo("密钥是什么");
        assertThat(hits.sources()).containsExactly("使用文档《手动接入 API》");
    }

    @Test
    void theTwoLanguageVersionsOfAPageAreMatchedAsOneDocument() {
        AssistantDocsIndex index = indexOf(page("manual-api", tutorial()));

        // 'key' only appears in the English heading; the Chinese section that says the same thing is what is quoted.
        Retrieval chinese = ask(index, "key 准备");
        Retrieval english = ask(index, "密钥 prepare address", "how do I prepare the address and key");

        assertThat(chinese.chunks()).extracting(Chunk::section).first().isEqualTo("准备地址与密钥");
        assertThat(chinese.chunks()).extracting(Chunk::language).containsOnly("zh");
        assertThat(english.chunks()).extracting(Chunk::language).containsOnly("zh");
    }

    @Test
    void anEnglishQuestionIsAnsweredFromTheEnglishVersion() {
        AssistantDocsIndex index = indexOf(page("manual-api", tutorial()));

        Retrieval hits = ask(index, "what is an API key");

        assertThat(hits.chunks()).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.language()).isEqualTo("en"));
        assertThat(hits.chunks().getFirst().section()).isEqualTo("What a key is");
        assertThat(hits.sources()).containsExactly("Docs: Manual API setup");
    }

    @Test
    void aLatinOnlyMessageInAChineseConversationStaysChinese() {
        AssistantDocsIndex index = indexOf(page("manual-api", tutorial()));
        String pasted = "API Error: 401 Invalid API key";

        Retrieval inChinese = ask(index, pasted, "怎么创建 API Key");
        Retrieval onItsOwn = ask(index, pasted);

        assertThat(inChinese.sources()).containsExactly("使用文档《手动接入 API》");
        assertThat(onItsOwn.sources()).containsExactly("Docs: Manual API setup");
    }

    @Test
    void aPageWithOnlyOneLanguageIsQuotedInThatLanguageToEveryone() {
        Content chineseOnly = new Content("发票说明", "帮助", "", "## 开票\n在 invoice 页面申请开票，invoice 页面里有开具 invoice 的入口。", 10);
        Content englishOnly = new Content("发票", "帮助", "", "   ", 10,
            new Translation("Invoices", "Help", "", "## Request an invoice\nOpen the invoice page to request one."));
        AssistantDocsIndex index = indexOf(page("zh", chineseOnly), page("en", englishOnly));

        assertThat(ask(index, "how do I get an invoice").sources()).containsExactlyInAnyOrder("使用文档《发票说明》", "Docs: Invoices");
        assertThat(ask(index, "怎么开 invoice").sources()).containsExactlyInAnyOrder("使用文档《发票说明》", "Docs: Invoices");
    }

    @Test
    void aPageWhoseVersionsHaveDifferentStructuresIsStillQuotedInOneLanguageOnly() {
        Content content = new Content("API Key 指南", "帮助", "", """
            ## 创建
            创建 API Key 时选择模型分类。
            ## 复制
            复制 API Key 保存好。
            """, 10, new Translation("API key guide", "Help", "", """
            ## Create
            Choose a model category when you create an API key.
            ## Copy
            Copy the API key and keep it safe.
            ## Extra
            An English-only section about the API key.
            """));
        AssistantDocsIndex index = indexOf(page("keys", content));

        for (String question : List.of("API Key 怎么创建", "create an API key", "copy API key")) {
            List<Chunk> chunks = ask(index, question).chunks();
            assertThat(chunks).as(question).isNotEmpty();
            assertThat(chunks.stream().map(Chunk::language).distinct()).as(question).hasSize(1);
        }
    }

    @Test
    void theQuestionLanguageDecidesWhichTranslationIsQuotedAndHowItIsNamed() {
        Content content = new Content("创建与管理 API Key", "开始使用", "", "## 新建\n点击新建 API Key 创建密钥。", 10,
            new Translation("Create and manage API keys", "Getting started", "", "## Create\nSelect Create API key to make a key."));
        AssistantDocsIndex index = indexOf(page("api-keys", content));

        Retrieval chinese = ask(index, "怎么创建 API Key");
        Retrieval english = ask(index, "how do I create an API key");

        assertThat(chinese.sources()).containsExactly("使用文档《创建与管理 API Key》");
        assertThat(english.sources()).containsExactly("Docs: Create and manage API keys");
        assertThat(english.chunks().getFirst().language()).isEqualTo("en");
    }

    // ---- follow-ups --------------------------------------------------------------------------------------------

    private static AssistantDocsIndex keysAndBilling() {
        return indexOf(
            page("api-keys", "创建与管理 API Key", "开始使用", "", """
                ## 创建一把密钥
                点击新建 API Key，填写名称，选择模型分类，保存后复制密钥。

                ## 列表里的操作
                在 API Key 列表里点编辑，可以修改名称、额度上限和模型分类；点删除可以永久删除这把密钥。
                """),
            page("account", "账号设置", "开始使用", "", """
                ## 修改密码
                点右上角的头像，选择修改密码。
                """),
            page("billing", "充值与计费", "开始使用", "", """
                ## 充值
                在余额充值页面在线支付，或者输入兑换码。
                """));
    }

    @Test
    void aFollowUpWithoutATopicOfItsOwnFindsItsAnswerWithinTheTopicOfThePreviousQuestion() {
        AssistantDocsIndex index = keysAndBilling();

        Retrieval edit = ask(index, "那怎么改？", "怎么创建 API Key");
        Retrieval delete = ask(index, "那怎么删？", "怎么创建 API Key");
        Retrieval withoutContext = ask(index, "那怎么改？", "");

        assertThat(edit.chunks().getFirst().section()).as("not the section the previous question was answered from")
            .isEqualTo("列表里的操作");
        assertThat(pageIds(edit)).as("'修改密码' of another page is about another topic").containsExactly("api-keys");
        assertThat(delete.chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(withoutContext.chunks()).as("nothing to find '改' in").extracting(Chunk::pageId)
            .doesNotContain("api-keys");
    }

    @Test
    void aChunkWhoseHeadingRepeatsThePreviousQuestionIsNotAnAnswerToAFollowUp() {
        AssistantDocsIndex index = indexOf(
            page("api-keys", "创建与管理 API Key", "开始使用", "", "## 列表里的操作\n点编辑可以修改名称，点删除可以永久删除。"),
            page("changelog", "更新日志", "其他", "", "## 创建 API Key 的新界面\n新界面改了按钮位置，改了颜色，改了顺序。"));

        Retrieval hits = ask(index, "那怎么改？", "怎么创建 API Key");

        assertThat(pageIds(hits)).containsExactly("api-keys");
    }

    @Test
    void aMessageWithNothingToSearchForIsAboutThePreviousQuestion() {
        AssistantDocsIndex index = keysAndBilling();

        Retrieval hits = ask(index, "还有呢", "怎么创建 API Key");

        assertThat(hits.chunks()).isNotEmpty();
        assertThat(pageIds(hits)).containsExactly("api-keys");
        assertThat(ask(index, "还有呢", "").chunks()).isEmpty();
    }

    @Test
    void aFollowUpThatChangesTheTopicIsNotTakenOverByThePreviousQuestion() {
        AssistantDocsIndex index = indexOf(
            page("claude", "手动配置 Claude Code", "连接客户端", "把 Claude Code 接入本站", """
                ## 开始之前
                配置 Claude Code 之前，先创建一把密钥。

                ## 手动设置环境变量
                配置 Claude Code：设置 ANTHROPIC_BASE_URL 和密钥，再启动 Claude Code。

                ## 验证
                启动 Claude Code 后问一句话，确认能收到回复。
                """),
            page("pricing", "选择模型与查看价格", "开始使用", "", """
                ## 价格怎么算
                费用按模型的单价和用量计算，用多少扣多少。
                """),
            page("codex", "手动配置 Codex CLI", "连接客户端", "把 Codex CLI 接入本站", """
                ## 手动配置
                配置 Codex CLI：编辑 config.toml，填入地址和密钥。
                """));

        Retrieval cost = ask(index, "那费用怎么算？", "Claude Code 怎么配置");
        Retrieval other = ask(index, "那 Codex 呢？", "Claude Code 怎么配置");

        assertThat(pageIds(cost)).containsExactly("pricing");
        assertThat(pageIds(other)).containsExactly("codex");
    }

    @Test
    void theLatestQuestionCountsForMoreThanThePreviousOne() {
        AssistantDocsIndex index = keysAndBilling();

        Retrieval hits = ask(index, "余额怎么充值", "怎么创建 API Key");

        assertThat(hits.chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(pageIds(hits)).containsExactly("billing");
    }

    @Test
    void aFollowUpThatAsksForMoreWithoutNamingTheTopicIsAboutTheEarlierQuestion() {
        AssistantDocsIndex index = help();

        for (String more : List.of("具体步骤呢？", "详细说一下", "具体怎么操作？", "再讲讲")) {
            Retrieval hits = askAfter(index, more, "怎么充值");
            assertThat(hits.chunks()).as(more).isNotEmpty();
            assertThat(hits.chunks().getFirst().pageId()).as(more).isEqualTo("billing");
            assertThat(sections(hits)).as(more).doesNotContain("列表里的操作");
        }
        assertThat(askAfter(index, "能详细点吗", "Claude Code 怎么配置").chunks().getFirst().pageId()).isEqualTo("claude-code");
        Retrieval steps = askAfter(index, "步骤是什么", "Claude Code 怎么配置");
        assertThat(steps.chunks().getFirst().pageId()).isEqualTo("claude-code");
        assertThat(pageIds(steps)).as("'步骤' is a word of the ticket and the error pages").doesNotContain("tickets", "errors");
        assertThat(askAfter(index, "怎么操作", "怎么开通 Coding Plan").chunks().getFirst().pageId()).isEqualTo("coding-plan");
        for (String example : List.of("举个例子", "有例子吗")) {
            Retrieval hits = askAfter(index, example, "用 Python 调用");
            assertThat(hits.chunks().getFirst().pageId()).as(example).isEqualTo("manual-api");
            assertThat(hits.chunks().getFirst().section()).as(example).isEqualTo("在 Python 中调用");
        }
        Retrieval english = askAfter(index, "Can you give more detail?", "How do I top up?");
        assertThat(english.chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(english.chunks()).extracting(Chunk::language).containsOnly("en");
        assertThat(askAfter(index, "举个例子").chunks()).as("nothing to give an example of").isEmpty();
        assertThat(askAfter(index, "What is the capital of France?", "怎么充值").chunks()).as("a new topic that the documents lack").isEmpty();
    }

    @Test
    void aFollowUpAfterAFollowUpIsAboutTheLatestEarlierQuestionThatHasATopic() {
        AssistantDocsIndex index = help();

        Retrieval more = askAfter(index, "还有呢", "那怎么改？", "怎么创建 API Key");
        Retrieval change = askAfter(index, "那怎么改？", "怎么创建 API Key");
        Retrieval steps = askAfter(index, "具体步骤呢", "还有呢", "怎么充值");

        assertThat(more.chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(pageIds(more)).doesNotContain("faq");
        assertThat(sections(more)).noneMatch(section -> section.contains("修改密码"));
        assertThat(change.chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(sections(change)).as("'改' is also in the password sections").noneMatch(section -> section.contains("修改密码"));
        assertThat(steps.chunks().getFirst().pageId()).isEqualTo("billing");
    }

    // ---- very long messages ------------------------------------------------------------------------------------

    @Test
    void aPastedLogIsSearchedForTheWordsTheDocumentsKnowNotForItsFirstWords() {
        AssistantDocsIndex index = indexOf(
            page("faq", "常见问题与求助", "遇到问题", "", """
                ## 提示 401，或者密钥无效
                返回 401 Invalid API key 表示密钥填错了或者已被停用，请回到 API Key 页面重新复制。

                ## 提示 429
                请求太频繁，稍后再试。
                """),
            page("other", "其他", "帮助", "", "## 杂项\n与此无关的内容。"));
        StringBuilder log = new StringBuilder("我的程序报错了：");
        for (int frame = 0; frame < 30; frame++) {
            log.append(" at com.example.module").append(frame).append(".Handler").append(frame)
                .append(".process").append(frame).append("(Handler").append(frame).append(".java:").append(100 + frame).append(')');
        }
        log.append(" Caused by: 401 Invalid API key 怎么办");

        assertThat(AssistantDocsIndex.terms(log.toString()).size()).isGreaterThan(AssistantDocsIndex.MAX_QUERY_TERMS);
        Retrieval hits = ask(index, log.toString());

        assertThat(hits.chunks()).isNotEmpty();
        assertThat(hits.chunks().getFirst().section()).isEqualTo("提示 401，或者密钥无效");
    }

    @Test
    void aHugeQuestionIsBoundedToAFewHundredTerms() {
        StringBuilder many = new StringBuilder();
        for (int character = 0x4E00; character < 0x4E00 + 2000; character++) {
            many.append((char) character);
        }
        String huge = many.toString();

        assertThat(AssistantDocsIndex.terms(huge)).hasSize(600);
        assertThat(ask(indexOf(page("a", "a", "a", "", "## a\n" + huge.substring(0, 100))), huge).chunks()).hasSize(1);
    }

    // ---- selection ---------------------------------------------------------------------------------------------

    @Test
    void takesAtMostThreeChunksOfOnePageSoOtherPagesCanBeCited() {
        StringBuilder big = new StringBuilder();
        for (int section = 0; section < 8; section++) {
            big.append("## 部分").append(section).append("\n密钥的用法第").append(section)
                .append("点：保管密钥，不要泄露密钥。\n\n");
        }
        AssistantDocsIndex index = indexOf(
            page("big", "密钥大全", "帮助", "", big.toString()),
            page("small", "其他页面", "帮助", "", "## 提示\n顺带提一句，密钥也可以在这里查看，密钥的更多说明见上文，请妥善保管密钥。"));

        Retrieval hits = ask(index, "密钥");

        assertThat(hits.chunks().stream().filter(chunk -> chunk.pageId().equals("big"))).hasSize(3);
        assertThat(hits.chunks()).extracting(Chunk::pageId).contains("small");
        assertThat(hits.sources()).containsExactly("使用文档《密钥大全》", "使用文档《其他页面》");
    }

    @Test
    void aPageDoesNotGiveWayToMuchWeakerChunksOfOtherPagesAtItsThirdChunksExpense() {
        List<Document> pages = new ArrayList<>();
        StringBuilder strong = new StringBuilder();
        for (int section = 0; section < 4; section++) {
            strong.append("## 密钥管理").append(section).append("\n密钥的说明第").append(section).append("点。\n\n");
        }
        pages.add(page("strong", "密钥管理", "帮助", "", strong.toString()));
        for (int weak = 0; weak < 6; weak++) {
            // About 40% of what a chunk of the strong page scores: enough for the sixth place, not for the third chunk's.
            pages.add(page("weak-" + weak, "杂项" + weak, "帮助", "关于密钥", "## 杂项\n密钥，密钥，密钥。"));
        }

        List<Chunk> chunks = ask(indexOf(pages), "密钥").chunks();

        assertThat(chunks).hasSize(AssistantDocsIndex.MAX_RESULTS);
        assertThat(chunks.stream().filter(chunk -> chunk.pageId().equals("strong"))).as("the strong page keeps its three").hasSize(3);
        assertThat(chunks.stream().filter(chunk -> chunk.pageId().startsWith("weak-"))).as("and the rest are other pages").hasSize(3);
        assertThat(chunks.getFirst().pageId()).isEqualTo("strong");
    }

    @Test
    void thePiecesOfOneSectionAreQuotedInTheOrderOfTheSectionWhateverTheyScore() {
        StringBuilder table = new StringBuilder("## 价格表\n\n| 模型 | 说明 |\n| --- | --- |\n");
        for (int row = 0; row < 60; row++) {
            table.append("| row-").append(row).append(" | 价格按百万 token 计算，").append(row == 59 ? "兔子是特别说明" : "没有特别说明").append(" |\n");
        }
        AssistantDocsIndex index = indexOf(page("prices", "价格", "帮助", "", table.toString()));

        List<Chunk> chunks = ask(index, "兔子 价格").chunks();

        assertThat(chunks).hasSize(2).allSatisfy(chunk -> assertThat(chunk.section()).isEqualTo("价格表"));
        assertThat(chunks.getFirst().body()).as("the beginning of the table comes first although the end scores higher")
            .contains("| row-0 |").doesNotContain("兔子");
        assertThat(chunks.getLast().body()).contains("兔子", "| row-59 |");
    }

    @Test
    void returnsNoMoreThanSixChunksWhenThereIsRoomForMore() {
        List<Document> pages = new ArrayList<>();
        for (int page = 0; page < 9; page++) {
            pages.add(page("p" + page, "页面" + page, "帮助", "", "## 套餐\n套餐说明在这里。"));
        }

        List<Chunk> chunks = ask(indexOf(pages), "套餐").chunks();

        assertThat(chunks).hasSize(6);
        assertThat(chunks.stream().mapToInt(Chunk::cost).sum()).isLessThan(AssistantDocsIndex.EXCERPT_BUDGET);
        assertThat(chunks).extracting(Chunk::pageId).containsExactly("p0", "p1", "p2", "p3", "p4", "p5");
    }

    @Test
    void returnsNoMoreThanTheCharacterBudgetAllowsLabelsIncluded() {
        List<Document> pages = new ArrayList<>();
        for (int page = 0; page < 9; page++) {
            pages.add(page("p" + page, "页面" + page, "帮助", "", "## 内容\n" + ("费用说明" + "。").repeat(317)));
        }

        List<Chunk> chunks = ask(indexOf(pages), "费用说明").chunks();

        assertThat(chunks.stream().mapToInt(chunk -> chunk.body().length()).sum()).isLessThanOrEqualTo(AssistantDocsIndex.EXCERPT_BUDGET);
        assertThat(chunks.stream().mapToInt(Chunk::cost).sum()).isLessThanOrEqualTo(AssistantDocsIndex.EXCERPT_BUDGET);
        // 1585 characters of text and 21 of label each: four fit into 8000, five would not.
        assertThat(chunks).hasSize(4);
    }

    @Test
    void aHugeHeadingIsNotRepeatedIntoThePromptOfEveryChunkOfItsSection() {
        List<Document> pages = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            StringBuilder body = new StringBuilder();
            for (int section = 0; section < 6; section++) {
                body.append("## 配置说明").append("很长的标题".repeat(4000)).append("\n配置第").append(section).append("点。\n\n");
            }
            pages.add(page("long-" + page, "页面" + page, "帮助", "", body.toString()));
        }

        List<Chunk> chunks = ask(indexOf(pages), "配置说明").chunks();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.section().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_HEADING_CHARS + 1));
        assertThat(chunks.stream().mapToInt(Chunk::cost).sum()).isLessThanOrEqualTo(AssistantDocsIndex.EXCERPT_BUDGET);
        assertThat(chunks.stream().mapToInt(chunk -> chunk.section().length() + chunk.pageTitle().length()).sum())
            .isLessThan(AssistantDocsIndex.EXCERPT_BUDGET);
    }

    @Test
    void aTailOfWeakMatchesStopsAfterTheFourthChunk() {
        List<Document> pages = new ArrayList<>();
        pages.add(page("main", "主题页", "帮助", "", "## 概述\n" + "密钥".repeat(3)));
        for (int index = 0; index < 6; index++) {
            pages.add(page("noise-" + index, "杂项" + index, "帮助", "", "## 杂项\n偶尔提到一次密钥而已，其他都是别的内容 " + index));
        }

        List<Chunk> chunks = ask(indexOf(pages), "密钥").chunks();

        // Four chunks are returned while they reach a fifth of the best score; the fifth and sixth need more than that.
        assertThat(chunks).hasSize(4);
        assertThat(chunks.getFirst().pageId()).isEqualTo("main");
    }

    @Test
    void sourcesAreDistinctAndInRankOrder() {
        AssistantDocsIndex index = indexOf(
            page("low", "低相关", "帮助", "", "## 内容\n提到发票，再提到发票，还提到发票。"),
            page("high", "高相关", "帮助", "", "## 发票\n发票发票发票，开具发票的说明。\n\n## 更多发票\n发票的更多说明，发票发票。"));

        Retrieval hits = ask(index, "发票");

        assertThat(hits.chunks().size()).isGreaterThan(2);
        assertThat(hits.sources()).containsExactly("使用文档《高相关》", "使用文档《低相关》");
    }

    @Test
    void equalScoresKeepThePagesInTheirCatalogOrder() {
        AssistantDocsIndex first = indexOf(
            page("a", "同名页面", "帮助", "", "## 内容\n套餐说明在这里。"),
            page("b", "同名页面", "帮助", "", "## 内容\n套餐说明在这里。"));
        AssistantDocsIndex second = indexOf(
            page("b", "同名页面", "帮助", "", "## 内容\n套餐说明在这里。"),
            page("a", "同名页面", "帮助", "", "## 内容\n套餐说明在这里。"));

        assertThat(ask(first, "套餐").chunks()).extracting(Chunk::pageId).containsExactly("a", "b");
        assertThat(ask(second, "套餐").chunks()).extracting(Chunk::pageId).containsExactly("b", "a");
        assertThat(ask(first, "套餐").chunks()).isEqualTo(ask(first, "套餐").chunks());
    }

    @Test
    void nothingFoundStillOffersThePageTitles() {
        Content translated = new Content("第一次使用", "开始使用", "", "## 登录\n用管理员给的账号登录。", 10,
            new Translation("Start here", "Getting started", "", "## Sign in\nSign in with the account you were given."));
        AssistantDocsIndex index = indexOf(page("start", translated), page("faq", "常见问题", "帮助", "", "## 问\n答案。"));

        Retrieval hits = ask(index, "今天天气怎么样");

        assertThat(hits.chunks()).isEmpty();
        assertThat(hits.pageTitles()).containsExactly("第一次使用 / Start here", "常见问题");
        assertThat(hits.sources()).isEmpty();
    }

    @Test
    void aBlankQuestionFindsNothingAndLoadsNothing() {
        AtomicInteger loads = new AtomicInteger();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.just(List.of());
        }, new TestClock());

        assertThat(index.retrieve("  ", "上一个问题").block(TIMEOUT).chunks()).isEmpty();
        assertThat(index.retrieve(null, null).block(TIMEOUT).chunks()).isEmpty();
        assertThat(loads).hasValue(0);
    }

    // ---- size limits -------------------------------------------------------------------------------------------

    @Test
    void veryLargeDocumentationIsSharedOutFairlyBetweenThePages() {
        List<Document> pages = new ArrayList<>();
        for (int page = 0; page < 30; page++) {
            StringBuilder body = new StringBuilder();
            for (int section = 0; section < 100; section++) {
                body.append("## Part ").append(section).append("\nwidget").append(page).append('x').append(section)
                    .append("end\n\n");
            }
            pages.add(page("big-" + page, "Big page " + page, "Help", "", body.toString()));
        }
        AssistantDocsIndex index = indexOf(pages);

        Retrieval early = ask(index, "widget0x5end");
        Retrieval late = ask(index, "widget29x5end");
        Retrieval beyond = ask(index, "widget29x95end");

        assertThat(early.chunks()).extracting(Chunk::pageId).containsExactly("big-0");
        assertThat(late.chunks()).as("the last page is not left out because earlier pages were large")
            .extracting(Chunk::pageId).containsExactly("big-29");
        assertThat(beyond.chunks()).as("each page is cut after its share of the 2000 chunks").isEmpty();
        assertThat(beyond.pageTitles()).hasSize(30);
    }

    @Test
    void aSmallPageAfterVeryLargeOnesIsStillSearched() {
        List<Document> pages = new ArrayList<>();
        for (int page = 0; page < 40; page++) {
            StringBuilder body = new StringBuilder();
            for (int section = 0; section < 100; section++) {
                body.append("## Part ").append(section).append("\nfiller text number ").append(section).append("\n\n");
            }
            pages.add(page("big-" + page, "Big page " + page, "Help", "", body.toString()));
        }
        pages.add(page("small", "Invoices", "Help", "", "## Invoice\nOpen the invoicezzq page."));

        assertThat(ask(indexOf(pages), "invoicezzq").chunks()).extracting(Chunk::pageId).containsExactly("small");
    }

    // ---- damaged pages -----------------------------------------------------------------------------------------

    @Test
    void aPageThatCannotBeUsedIsLeftOutWithoutHidingTheOthers() {
        Document good = page("good", "创建密钥", "帮助", "", "## 创建\n在控制台创建密钥。");
        Document noContent = new Document("no-content", null, Instant.EPOCH, List.of());
        Document noTitle = page("no-title", new Content(null, "帮助", "", "## 创建\n创建密钥的另一种写法。", 10));
        Document blankTitle = page("blank-title", new Content("  ", "帮助", "", "## 创建\n创建密钥的第三种写法。", 10));
        Document noId = new Document(null, new Content("无编号", "帮助", "", "## 创建\n创建密钥。", 10), Instant.EPOCH, List.of());
        Document brokenTranslation = page("broken-translation", new Content("英文有问题", "帮助", "", "## 创建\n创建密钥的写法。", 10,
            new Translation(null, null, null, null)));
        AtomicInteger loads = new AtomicInteger();
        TestClock clock = new TestClock();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.just(List.of(noContent, noTitle, good, blankTitle, noId, brokenTranslation));
        }, clock);

        Retrieval hits = ask(index, "创建密钥");

        assertThat(pageIds(hits)).containsExactlyInAnyOrder("good", "broken-translation");
        assertThat(hits.pageTitles()).containsExactly("创建密钥", "英文有问题");
        clock.advance(Duration.ofSeconds(11));
        ask(index, "创建密钥");
        assertThat(loads).as("a page that is damaged for good is not a reason to read everything every ten seconds").hasValue(1);
    }

    @Test
    void aFailureToIndexTheWholeListIsNotRetriedEveryTenSecondsBecauseItWouldFailAgain() {
        TestClock clock = new TestClock();
        AtomicInteger loads = new AtomicInteger();
        List<Document> unreadable = new java.util.AbstractList<>() {
            @Override
            public Document get(int index) {
                throw new IllegalStateException("cannot be read");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.just(unreadable);
        }, clock);

        assertThat(ask(index, "发票").chunks()).as("answered without the pages").isEmpty();
        clock.advance(Duration.ofSeconds(11));
        ask(index, "发票");
        assertThat(loads).as("not retried after the ten seconds of a database outage").hasValue(1);
        clock.advance(Duration.ofSeconds(50));
        ask(index, "发票");
        assertThat(loads).as("but with the next time to live").hasValue(2);
    }

    @Test
    void aTranslationWithoutATitleOrBodyIsTreatedAsMissing() {
        Content content = new Content("发票", "帮助", "", "## 开票\n在发票页面申请开票。", 10, new Translation("Invoices", "Help", "", "  "));
        AssistantDocsIndex index = indexOf(page("invoice", content));

        assertThat(ask(index, "怎么开发票").sources()).containsExactly("使用文档《发票》");
        assertThat(ask(index, "invoice").chunks()).isEmpty();
        assertThat(ask(index, "invoice").pageTitles()).containsExactly("发票");
    }

    // ---- freshness, sharing and failure ------------------------------------------------------------------------

    @Test
    void theSnapshotIsSharedUntilItsTimeToLiveRunsOut() {
        TestClock clock = new TestClock();
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("## 内容\n旧的发票说明。");
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.just(List.of(page("p", "发票", "帮助", "", body.get())));
        }, clock);

        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("旧的");
        body.set("## 内容\n新的发票说明。");
        clock.advance(Duration.ofSeconds(59));
        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("旧的");
        assertThat(loads).hasValue(1);

        clock.advance(Duration.ofSeconds(2));
        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("新的");
        assertThat(loads).hasValue(2);
        ask(index, "发票");
        assertThat(loads).hasValue(2);
    }

    /** A source with a fingerprint that the tests control, counting how often it and the pages are read. */
    private static final class FingerprintedPages {
        final AtomicInteger pageReads = new AtomicInteger();
        final AtomicInteger fingerprintReads = new AtomicInteger();
        final AtomicReference<String> fingerprint = new AtomicReference<>("v1");
        final AtomicReference<String> body = new AtomicReference<>("## 内容\n旧的发票说明。");
        final AtomicBoolean fingerprintFails = new AtomicBoolean();
        final TestClock clock = new TestClock();

        AssistantDocsIndex index() {
            return new AssistantDocsIndex(NO_FILES, () -> {
                pageReads.incrementAndGet();
                return Mono.just(List.of(page("p", "发票", "帮助", "", body.get())));
            }, () -> {
                fingerprintReads.incrementAndGet();
                return fingerprintFails.get() ? Mono.error(new IllegalStateException("down")) : Mono.just(fingerprint.get());
            }, clock);
        }
    }

    @Test
    void whenTheTimeToLiveRunsOutThePagesAreOnlyReadAgainIfTheirFingerprintChanged() {
        FingerprintedPages source = new FingerprintedPages();
        AssistantDocsIndex index = source.index();

        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("旧的");
        assertThat(source.pageReads).hasValue(1);
        assertThat(source.fingerprintReads).hasValue(1);

        source.clock.advance(Duration.ofSeconds(61));
        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("旧的");
        assertThat(source.fingerprintReads).as("one cheap check").hasValue(2);
        assertThat(source.pageReads).as("nothing changed, so nothing is read or rebuilt").hasValue(1);

        source.clock.advance(Duration.ofSeconds(30));
        ask(index, "发票");
        assertThat(source.fingerprintReads).as("and the check itself is good for another minute").hasValue(2);

        source.body.set("## 内容\n新的发票说明。");
        source.fingerprint.set("v2");
        source.clock.advance(Duration.ofSeconds(61));
        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("新的");
        assertThat(source.pageReads).hasValue(2);
    }

    @Test
    void aReportedChangeReadsThePagesEvenThoughTheFingerprintLooksTheSame() {
        FingerprintedPages source = new FingerprintedPages();
        AssistantDocsIndex index = source.index();
        ask(index, "发票");

        source.body.set("## 内容\n新的发票说明。");
        index.invalidate();

        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("新的");
        assertThat(source.pageReads).hasValue(2);
    }

    @Test
    void aFailingFingerprintCheckIsAFailureToLoadAndIsRetriedSoon() {
        FingerprintedPages source = new FingerprintedPages();
        AssistantDocsIndex index = source.index();
        ask(index, "发票");

        source.clock.advance(Duration.ofSeconds(61));
        source.fingerprintFails.set(true);
        assertThat(ask(index, "发票").chunks()).as("answered without the pages while the database is down").isEmpty();
        ask(index, "发票");
        assertThat(source.fingerprintReads).as("no retry on every question").hasValue(2);

        source.fingerprintFails.set(false);
        source.clock.advance(Duration.ofSeconds(11));
        assertThat(ask(index, "发票").chunks()).hasSize(1);
        assertThat(source.pageReads).as("a failed check leaves nothing to compare with").hasValue(2);
    }

    @Test
    void thePagesAreIndexedOnAWorkerThreadNotOnTheThreadThatDeliveredThem() throws Exception {
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES,
            () -> Mono.just(List.of(page("p", "发票", "帮助", "", "## 内容\n发票说明。"))).publishOn(Schedulers.parallel()),
            new TestClock());

        assertThat(ask(index, "发票").chunks()).hasSize(1);

        Thread builder = index.lastBuildThread;
        assertThat(builder).isNotNull().isNotSameAs(Thread.currentThread());
        assertThat(Schedulers.isNonBlockingThread(builder)).as("not a Reactor event loop or parallel thread").isFalse();
    }

    @Test
    void questionsAreScoredOnTheParallelSchedulerWhicheverThreadDeliversTheSnapshot() {
        // The thread of a database connection: the pages and the fingerprint arrive on it, and so does every renewal.
        Scheduler io = Schedulers.newSingle("fake-db-io");
        try {
            TestClock clock = new TestClock();
            AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES,
                () -> Mono.just(List.of(page("p", "发票", "帮助", "", "## 内容\n发票说明。"))).publishOn(io),
                () -> Mono.just("same").publishOn(io), clock);

            assertThat(ask(index, "发票").chunks()).hasSize(1);
            Thread first = index.lastSearchThread;
            assertThat(ask(index, "发票").chunks()).hasSize(1);
            Thread cached = index.lastSearchThread;
            clock.advance(Duration.ofSeconds(61));
            assertThat(ask(index, "发票").chunks()).hasSize(1);
            Thread renewed = index.lastSearchThread;

            for (Thread searched : List.of(first, cached, renewed)) {
                assertThat(searched.getName()).startsWith("parallel-").doesNotStartWith("fake-db-io");
            }
        } finally {
            io.dispose();
        }
    }

    @Test
    void theTimeToLiveAndTheRetryAreMeasuredOnAClockThatAWallClockStepCannotMove() throws Exception {
        Clock clock = AssistantDocsIndex.MONOTONIC;
        long before = clock.millis();
        Thread.sleep(20);
        assertThat(clock.millis()).isGreaterThanOrEqualTo(before + 15);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneId.of("Asia/Shanghai"))).isSameAs(clock);
        assertThat(clock.instant().toEpochMilli()).isEqualTo(clock.millis());

        // The index that Spring creates uses it, not the system clock.
        tech.starryapi.documentation.DocumentationService documentation = org.mockito.Mockito.mock(tech.starryapi.documentation.DocumentationService.class);
        AssistantDocsIndex production = new AssistantDocsIndex(documentation);
        java.lang.reflect.Field field = AssistantDocsIndex.class.getDeclaredField("clock");
        field.setAccessible(true);
        assertThat(field.get(production)).isSameAs(AssistantDocsIndex.MONOTONIC);
    }

    @Test
    void anEmptyFingerprintIsAFingerprintToo() {
        AtomicInteger loads = new AtomicInteger();
        TestClock clock = new TestClock();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.just(List.of());
        }, () -> Mono.just(""), clock);

        assertThat(ask(index, "发票").chunks()).isEmpty();
        clock.advance(Duration.ofSeconds(61));
        assertThat(ask(index, "发票").pageTitles()).isEmpty();

        assertThat(loads).as("no page published before or after: nothing to read again").hasValue(1);
    }

    @Test
    void invalidationMakesTheNextQuestionReadThePagesAgainAtOnce() {
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<List<Document>> pages = new AtomicReference<>(List.of(page("a", "发票甲", "帮助", "", "## 内容\n发票。")));
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.just(pages.get());
        }, new TestClock());

        assertThat(ask(index, "发票").chunks()).extracting(Chunk::pageId).containsExactly("a");
        pages.set(List.of());
        index.onPublicationChanged(new tech.starryapi.documentation.DocumentationPublicationChanged("a"));

        assertThat(ask(index, "发票").chunks()).isEmpty();
        assertThat(loads).hasValue(2);
    }

    @Test
    void manyQuestionsAtOnceShareASingleLoad() {
        AtomicInteger loads = new AtomicInteger();
        Document published = page("p", "发票", "帮助", "", "## 内容\n发票说明。");
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> Mono.defer(() -> {
            loads.incrementAndGet();
            return Mono.delay(Duration.ofMillis(200)).thenReturn(List.of(published));
        }), new TestClock());

        List<Retrieval> all = Flux.range(0, 25).flatMap(i -> index.retrieve("发票", ""), 25).collectList().block(TIMEOUT);

        assertThat(all).hasSize(25).allSatisfy(hit -> assertThat(hit.chunks()).hasSize(1));
        assertThat(loads).hasValue(1);
    }

    @Test
    void aLoadThatStartedBeforeAChangeIsNotSharedWithQuestionsAskedAfterIt() throws Exception {
        List<Sinks.One<List<Document>>> loads = new ArrayList<>();
        Supplier<Mono<List<Document>>> supplier = () -> Mono.defer(() -> {
            Sinks.One<List<Document>> sink = Sinks.one();
            synchronized (loads) {
                loads.add(sink);
            }
            return sink.asMono();
        });
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, supplier, new TestClock());

        CompletableFuture<Retrieval> before = index.retrieve("发票", "").toFuture();
        assertThat(loads).hasSize(1);
        index.invalidate();
        CompletableFuture<Retrieval> after = index.retrieve("发票", "").toFuture();
        assertThat(loads).hasSize(2);

        loads.get(1).tryEmitValue(List.of(page("new", "发票", "帮助", "", "## 内容\n新的发票说明。")));
        assertThat(after.get().chunks().getFirst().body()).contains("新的");
        loads.get(0).tryEmitValue(List.of(page("old", "发票", "帮助", "", "## 内容\n旧的发票说明。")));
        assertThat(before.get().chunks().getFirst().body()).contains("旧的");

        // The late, older load must not replace the newer snapshot.
        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("新的");
        assertThat(loads).hasSize(2);
    }

    @Test
    void failingToLoadThePagesLeavesTheFilesAndRetriesSoon(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "## 快速开始\n先安装服务并确认数据库已经准备好，再打开控制台创建第一把密钥，最后把密钥接入你正在使用的客户端并发出第一个请求。\n");
        TestClock clock = new TestClock();
        AtomicInteger loads = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean(true);
        AssistantDocsIndex index = new AssistantDocsIndex(root, () -> {
            loads.incrementAndGet();
            return failing.get()
                ? Mono.error(new IllegalStateException("connection refused"))
                : Mono.just(List.of(page("p", "密钥", "帮助", "", "## 创建\n在控制台创建密钥，然后接入客户端。")));
        }, clock);

        Retrieval during = ask(index, "控制台创建密钥");
        assertThat(during.chunks()).extracting(Chunk::source).containsExactly("README.md");
        assertThat(during.pageTitles()).isEmpty();
        ask(index, "控制台创建密钥");
        assertThat(loads).as("no retry on every question during an outage").hasValue(1);

        failing.set(false);
        clock.advance(Duration.ofSeconds(11));
        Retrieval after = ask(index, "控制台创建密钥");

        assertThat(loads).hasValue(2);
        assertThat(after.chunks().getFirst().isPage()).isTrue();
    }

    @Test
    void aFailureIsLoggedAtWarnAtMostOncePerMinute() {
        Logger logger = (Logger) LoggerFactory.getLogger(AssistantDocsIndex.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            TestClock clock = new TestClock();
            AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES,
                () -> Mono.error(new IllegalStateException("connection refused")), clock);

            ask(index, "发票");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("published documentation pages");
            });
            clock.advance(Duration.ofSeconds(11));
            ask(index, "发票");
            clock.advance(Duration.ofSeconds(11));
            ask(index, "发票");
            assertThat(appender.list).as("two more failures within the minute").hasSize(1);

            clock.advance(Duration.ofSeconds(60));
            ask(index, "发票");
            assertThat(appender.list).hasSize(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aLoaderThatThrowsInsteadOfFailingTheMonoIsAlsoContained() {
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            throw new IllegalStateException("not even a Mono");
        }, new TestClock());

        assertThat(ask(index, "发票").chunks()).isEmpty();
    }

    @Test
    void anInvalidationDuringAnOutageRetriesAtOnceInsteadOfWaitingForTheBackoff() {
        TestClock clock = new TestClock();
        AtomicInteger loads = new AtomicInteger();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            loads.incrementAndGet();
            return Mono.error(new IllegalStateException("down"));
        }, clock);

        ask(index, "发票");
        ask(index, "发票");
        assertThat(loads).hasValue(1);
        index.invalidate();
        ask(index, "发票");
        assertThat(loads).as("a change reported by the event is read at once").hasValue(2);
    }
}
