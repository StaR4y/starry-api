package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        assertThat(ask(index, "created").chunks()).as("an ending the question has and the document has not")
            .extracting(Chunk::pageId).containsExactly("keys");
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
    void aLoneChineseCharacterIsAWeakTermOfAnyQuestion() {
        assertThat(AssistantDocsIndex.terms("那怎么改？")).containsExactly("改");
        assertThat(AssistantDocsIndex.terms("那怎么删？")).containsExactly("删");
        assertThat(AssistantDocsIndex.terms("怎么办呢")).as("only grammar is left").isEmpty();
        assertThat(AssistantDocsIndex.terms("OpenCode 怎么用")).as("'用' asks for nothing in particular, next to a topic")
            .containsExactly("opencode");
        assertThat(AssistantDocsIndex.terms("用")).as("but on its own it is what the message has").containsExactly("用");
        assertThat(AssistantDocsIndex.terms("OpenCode 怎么停")).as("a verb that names an action is a weak term")
            .containsExactly("opencode", "停");
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

        for (String question : List.of("今天天气怎么样", "怎么看监控大屏", "What is the capital of France?")) {
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
    void aVerbBeforeTheTopicIsAJoinButTheEndOfAWordTheDocumentsLackIsNot() {
        AssistantDocsIndex index = indexOf(
            page("pay", "充值", "帮助", "", "## 在线充值\n支持微信和支付宝在线支付，到账后余额增加。"),
            page("tips", "好习惯", "帮助", "", "## 命名\n把日常开发和临时测试分开，各用一把密钥。"));

        assertThat(pageIds(ask(index, "可以用微信吗"))).as("'用微' joins the verb to the word the documents have").containsExactly("pay");
        assertThat(pageIds(ask(index, "怎么开发"))).containsExactly("tips");
        // After wording that asks how the verb comes first, so "发票" is a word the documents lack, not the join of "开发"
        // and "票": it cannot be answered by the page that has "开发". Without such wording the order cannot be told
        // ("开发票" looks like "协议选"), which the benchmark lists among its known gaps.
        for (String question : List.of("怎么开发票", "怎样开发票", "如何开发票", "咋开发票", "请问怎么开发票", "那如何开发票")) {
            assertThat(ask(index, question).chunks()).as(question).isEmpty();
        }
    }

    @Test
    void whenTheTopicComesFirstTheLastPairIsTheJoinOfTheTopicAndItsVerb() {
        // The topic is in the text of a few chunks and in no heading, so a chunk that has it is a modest match: it is quoted
        // only if the question asks for nothing else than the topic.
        AssistantDocsIndex index = indexOf(
            page("proto", "接口说明", "帮助", "", "## 各接口的地址\n不同的接口有不同的协议和路径，请求头也不一样。"),
            page("errors", "错误排查", "帮助", "", "## 状态码\n400 表示请求内容无效，检查协议是否选对，检查请求头是否写对。"),
            page("faq", "常见问题", "帮助", "", "## 连接\n换了协议还是连不上，先看状态码；请求头里不要多写空格。"),
            page("tips", "好习惯", "帮助", "", "## 命名\n把日常开发和临时测试分开，各用一把密钥。"),
            page("pay", "充值", "帮助", "", "## 在线充值\n支持微信和支付宝在线支付，到账后余额增加。"),
            page("misc", "杂项", "帮助", "", "## 其他\n没有别的了。"));

        // "议选", "头填" and "头加" are not words, they are where "协议" and "请求头" meet "选", "填" and "加": the documents lack
        // the pair, not the topic. Counted against the question, they would leave no chunk good enough to be quoted.
        for (String question : List.of("协议选哪个", "协议选什么", "请求头填什么", "请求头加什么", "怎么选协议")) {
            assertThat(pageIds(ask(index, question))).as(question).contains("proto");
        }
    }

    @Test
    void aTopicTheDocumentsLackIsNotAnsweredBecauseTheirHeadingsHaveTheVerbOrTheWordingBesideIt() {
        AssistantDocsIndex index = seeded();

        // Nothing of 发票, 邮箱, 团队 ... is in the tutorials; "开", "改", "建", "操作", "解决" only order what a topic has.
        for (String question : List.of("发票怎么开", "邮箱怎么改", "昵称怎么改", "会员怎么买", "团队怎么建", "积分怎么换", "手机号怎么绑",
            "发票怎么操作", "发票怎么解决", "怎么开发票", "怎么改邮箱", "怎么建团队", "How do I get an invoice?", "How do I fix an invoice?")) {
            Retrieval hits = ask(index, question);
            assertThat(hits.chunks()).as(question).isEmpty();
            assertThat(hits.sources()).as(question).isEmpty();
            assertThat(hits.pageTitles()).as("the model is handed the titles instead: " + question).isNotEmpty();
        }
        assertThat(ask(index, "密钥怎么删").chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(ask(index, "工单怎么关").chunks().stream().limit(3).map(Chunk::section).toList()).contains("状态的含义");
        assertThat(ask(index, "余额怎么扣").chunks().getFirst().section()).isEqualTo("余额是怎么扣的");
        assertThat(ask(index, "充值怎么操作").chunks().getFirst().pageId()).isEqualTo("billing");
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

    // ---- the tutorials of a fresh installation: wordings that were answered with the wrong page ----------------

    /** The tutorials a fresh installation publishes (V23 and V24), as they are. */
    private static AssistantDocsIndex seeded() {
        return indexOf(SeededTutorials.pages());
    }

    private static String where(Chunk chunk) {
        return chunk.pageId() + " § " + chunk.section();
    }

    @Test
    void askingHowToSolveOrWhatAStatusCodeMeansIsAnsweredByThePagesThatNameTheCode() {
        AssistantDocsIndex index = seeded();

        assertThat(AssistantDocsIndex.termWeights("403 怎么解决")).containsEntry("403", 1.0).containsEntry("解决", 0.2);
        assertThat(AssistantDocsIndex.termWeights("401 如何处理")).containsEntry("401", 1.0).containsEntry("处理", 0.2);
        assertThat(AssistantDocsIndex.termWeights("What does 401 mean?")).containsEntry("mean", 0.2);
        assertThat(AssistantDocsIndex.termWeights("How to solve 401")).containsEntry("solve", 0.2);
        assertThat(AssistantDocsIndex.termWeights("solving 401")).as("the -ing form of the word").containsEntry("solving", 0.2);
        assertThat(AssistantDocsIndex.termWeights("ticket resolved")).as("a participle is a status the documents may name")
            .containsEntry("ticket", 1.0).containsEntry("resolved", 1.0);
        for (String code : List.of("401", "402", "403", "503")) {
            for (String question : List.of(code + " 怎么解决", code + " 怎么处理", code + " 如何处理", code + " 怎么修复",
                "How to solve " + code, "What does " + code + " mean?", "How do I fix " + code + "?")) {
                Retrieval hits = ask(index, question);
                assertThat(hits.chunks()).as(question).isNotEmpty();
                assertThat(hits.chunks()).as(question).allSatisfy(chunk ->
                    assertThat(chunk.section() + "\n" + chunk.body()).as(question + " → " + where(chunk)).contains(code));
            }
            assertThat(ask(index, code + " 怎么解决").sources()).as(code).doesNotContain("使用文档《如何提交工单》");
        }
        assertThat(ask(index, "403 怎么解决").chunks().stream().limit(3).map(AssistantDocsIndexTest::where).toList())
            .contains("faq § 模型 › 提示 403");
    }

    @Test
    void aParticipleThatTheDocumentsUseAsAStatusIsATopicWordAndNotWording() {
        AssistantDocsIndex index = seeded();

        // "Resolved" is a status of a ticket; "resolve" and "resolving" are only a way to ask how to solve something.
        for (String question : List.of("ticket resolved", "resolved ticket", "What does the Resolved status mean?", "Resolved status")) {
            assertThat(ask(index, question).chunks().stream().limit(3).map(AssistantDocsIndexTest::where).toList()).as(question)
                .contains("tickets § What the statuses mean");
        }
    }

    @Test
    void theOneCharacterVerbOfAQuestionOrdersTheChunksOfItsTopic() {
        AssistantDocsIndex index = seeded();

        assertThat(AssistantDocsIndex.termWeights("密钥怎么删")).containsEntry("密钥", 1.0).containsEntry("删", 2.0);
        assertThat(AssistantDocsIndex.terms("OpenCode 怎么用")).as("'用' asks for nothing in particular").containsExactly("opencode");
        assertThat(AssistantDocsIndex.terms("OpenCode 怎么停")).as("a verb that names an action is a weak term").containsExactly("opencode", "停");
        assertThat(ask(index, "密钥怎么删").chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(ask(index, "怎么删除密钥").chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(ask(index, "工单怎么关").chunks().stream().limit(3).map(Chunk::section).toList()).contains("状态的含义");
        assertThat(ask(index, "余额怎么扣").chunks().getFirst().section()).isEqualTo("余额是怎么扣的");
    }

    @Test
    void theVerbBesideAPastedIdentifierTheDocumentsHaveOnlyInPartsDoesNotChooseTheChunks() {
        AssistantDocsIndex index = seeded();

        // None of these names is in the tutorials, but their parts are (API, KEY, INVALID; CODEX; BASE, URL ...). The parts are
        // the topic. "改", "填", "换" and "钱" only order the chunks that have a part: they are in the heading of the password
        // sections, the form tables, the redeem-code section and the wallet, which have nothing to do with the name.
        assertThat(AssistantDocsIndex.termWeights("API_KEY_INVALID 怎么改")).containsEntry("api_key_invalid", 4.0)
            .containsEntry("invalid", 0.3).containsEntry("改", 2.0);
        assertThat(where(ask(index, "API_KEY_INVALID 怎么改").chunks().getFirst())).isEqualTo("faq § 密钥与登录 › 提示 401，或者密钥无效");
        assertThat(where(ask(index, "OPENAI_BASE_URL 怎么填").chunks().getFirst()))
            .isEqualTo("faq § 地址与连接 › Base URL 到底要不要带 /v1");
        for (String question : List.of("CLAUDE_CODE_MAX_OUTPUT_TOKENS 怎么改", "怎么改 CLAUDE_CODE_MAX_OUTPUT_TOKENS")) {
            assertThat(where(ask(index, question).chunks().getFirst())).as(question).isEqualTo("claude-code § 手动设置环境变量");
        }
        assertThat(where(ask(index, "claude-sonnet-4.5 怎么换").chunks().getFirst())).isEqualTo("claude-code § 手动设置环境变量");
        assertThat(where(ask(index, "CODEX_HOME 怎么改").chunks().getFirst())).isEqualTo("codex-cli § 手动配置");
        assertThat(pageIds(ask(index, "config.yaml 怎么改"))).containsExactly("codex-cli");
        assertThat(pageIds(ask(index, "CODEX_HOME 怎么设"))).containsExactly("codex-cli");
        for (String question : List.of("API_KEY_INVALID 怎么改", "config.yaml 怎么改", "settings.local.json 怎么改",
            "CLAUDE_CODE_MAX_OUTPUT_TOKENS 怎么改", "OPENAI_BASE_URL 怎么填")) {
            assertThat(sections(ask(index, question))).as(question + " is not about the password")
                .noneMatch(section -> section.contains("修改密码"));
        }
        for (String question : List.of("gpt-5.5-mini 多少钱", "claude-opus-4.9 多少钱", "claude-opus-4.5 多少钱",
            "claude-opus-4.5-20251101 多少钱")) {
            assertThat(pageIds(ask(index, question))).as(question + " is not about the wallet")
                .doesNotContain("coding-plan", "billing", "console-tour");
        }
        // A verb still decides among the chunks of a topic that has a word of its own.
        assertThat(ask(index, "密钥怎么删").chunks().getFirst().section()).isEqualTo("列表里的操作");
    }

    @Test
    void aParticipleOfAWordOfWordingTheDocumentsLackIsWordingAndNotAStopWordOrATopicWord() {
        AssistantDocsIndex index = seeded();

        // "viewed", "caused", "detailed" are not in the tutorials; they must not become "view", "cause" or "detail" as topic
        // words, nor "noted" the stop word "not" (which every chunk has). "Resolved" is a status the tutorials do have, and
        // stays a topic word (see aParticipleThatTheDocumentsUseAsAStatusIsATopicWordAndNotWording).
        assertThat(where(ask(index, "What caused my 401?").chunks().getFirst()))
            .isEqualTo("faq § Keys and sign-in › I get 401, or the key is invalid");
        for (String question : List.of("Where is my balance viewed?", "Where can my subscription be viewed?",
            "Where can the statistics be viewed?", "Where can the model list be viewed?")) {
            assertThat(pageIds(ask(index, question))).as(question + " is not about tickets").doesNotContain("tickets");
        }
        assertThat(where(ask(index, "Where is my balance viewed?").chunks().getFirst())).startsWith("billing § ");
        for (String message : List.of("Noted.", "noted", "Noted, thanks")) {
            Retrieval hits = askAfter(index, message, "How do I top up?");
            assertThat(hits.chunks()).as(message).isEmpty();
            assertThat(hits.pageTitles()).as("an acknowledgement, not a question the documents lack: " + message).isEmpty();
        }
        assertThat(ask(index, "Noted.").pageTitles()).isEmpty();
        assertThat(askAfter(index, "more detailed", "How do I top up?").chunks()).as("a request for more about the earlier topic")
            .isNotEmpty().allSatisfy(chunk -> assertThat(chunk.pageId()).isEqualTo("billing"));

        // The word the ending is taken off to may be a stop word of the documents' own text: "wanted" is not "want".
        AssistantDocsIndex small = indexOf(page("keys", "Keys", "Help", "", "## Create\nYou will want a key, and you will need one."));
        assertThat(ask(small, "wanted").chunks()).isEmpty();
        assertThat(ask(small, "needed").chunks()).isEmpty();
    }

    @Test
    void aLoneCharacterOnlyOrdersTheChunksOfTheTopicAndNeverMakesAChunkAnAnswer() {
        AssistantDocsIndex index = indexOf(
            page("keys", "管理密钥", "帮助", "", "## 密钥列表\n这里列出所有密钥，可以查看用量。"),
            page("other", "杂项", "帮助", "", "## 其他\n点这里可以删除记录，删除之后无法恢复。"));

        // 删 is only in the other page, which has nothing about keys.
        assertThat(ask(index, "密钥怎么删").chunks()).extracting(Chunk::pageId).containsExactly("keys");
    }

    @Test
    void aComplaintThatItStillFailsIsAboutTheEarlierQuestionAndNotAboutOneOfItsCharacters() {
        AssistantDocsIndex index = seeded();

        for (String complaint : List.of("还是不行", "不行", "不行啊", "没用", "不管用", "还是没反应")) {
            Retrieval hits = askAfter(index, complaint, "Claude Code 报错 401");
            assertThat(hits.chunks().stream().limit(3).map(AssistantDocsIndexTest::where).toList()).as(complaint)
                .contains("claude-code § 常见问题");
        }
        assertThat(askAfter(index, "不行", "怎么创建 API Key").chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(askAfter(index, "不对", "Codex 怎么配置").chunks().getFirst().pageId()).isEqualTo("codex-cli");
        assertThat(AssistantDocsIndex.terms("还是不行")).as("nothing but grammar and wording").doesNotContain("行");
    }

    @Test
    void thanksAndGreetingsRetrieveNothingWhateverWasAskedBefore() {
        AssistantDocsIndex index = seeded();

        for (String message : List.of("谢谢", "谢谢！", "好的，明白了", "收到", "你是谁", "Thanks!", "thank you", "非常感谢啊", "再见", "Hello", "bye")) {
            Retrieval hits = askAfter(index, message, "怎么配置 Claude Code");
            assertThat(hits.chunks()).as(message).isEmpty();
            assertThat(hits.sources()).as(message).isEmpty();
            assertThat(hits.pageTitles()).as("the model is not told that nothing was found: " + message).isEmpty();
        }
        assertThat(ask(index, "谢谢，怎么创建 API Key").chunks()).as("a thanks is not the whole message").isNotEmpty();
        assertThat(askAfter(index, "好的，具体步骤呢", "怎么充值").chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(askAfter(index, "可以吗", "怎么充值").chunks()).as("a question, not an acknowledgement").isNotEmpty();
    }

    @Test
    void aBareYesToAnOfferOfMoreIsAFollowUpOfTheEarlierTopic() {
        AssistantDocsIndex index = seeded();

        // "需要我详细说说吗？" - "可以": the user wants the excerpts, not to be told that the documents have none.
        for (String message : List.of("可以", "嗯", "行", "好的", "好吧", "嗯嗯，好的", "要", "是的", "ok", "Sure!", "yes")) {
            Retrieval hits = askAfter(index, message, "怎么充值");
            assertThat(hits.chunks()).as(message).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.pageId()).isEqualTo("billing"));
            assertThat(hits.sources()).as(message).containsExactly("使用文档《充值与计费》");
        }
        assertThat(askAfter(index, "可以", "怎么配置 Claude Code").chunks().getFirst().pageId()).isEqualTo("claude-code");
        assertThat(askAfter(index, "sure", "How do I set up Claude Code?").sources()).startsWith("Docs: Set up Claude Code");
        assertThat(askAfter(index, "可以", "好的", "怎么充值").chunks().getFirst().pageId()).as("past an earlier bare yes").isEqualTo("billing");
        assertThat(ask(index, "好的").chunks()).as("nothing to follow up").isEmpty();
    }

    @Test
    void aBareYesAtTheHeadOfAQuestionIsNotAWordOfTheQuestion() {
        AssistantDocsIndex index = seeded();

        // "好" must not become the verb of the question: 好习惯 and 写出好懂的工单 would then be quoted and cited.
        for (String question : List.of("好的，那怎么删除密钥", "好，那怎么删除密钥", "那好，怎么删除密钥", "谢谢，怎么删除密钥")) {
            Retrieval hits = ask(index, question);
            assertThat(hits.chunks().getFirst().section()).as(question).isEqualTo("列表里的操作");
            assertThat(hits.sources()).as(question).doesNotContain("使用文档《如何提交工单》");
            assertThat(hits.chunks()).as(question).isEqualTo(ask(index, "怎么删除密钥").chunks());
        }
        assertThat(ask(index, "OK, how do I delete an API key").chunks()).isEqualTo(ask(index, "How do I delete an API key").chunks());
        assertThat(AssistantDocsIndex.terms("好的，怎么充值")).containsExactly("充值");
        assertThat(AssistantDocsIndex.terms("怎么好好充值")).as("only at the head, and only before a break").contains("好好");
    }

    @Test
    void ofSiblingsThatShareAHeadingAboveThemTheOneWhoseOwnHeadingHasTheWordComesFirst() {
        AssistantDocsIndex index = indexOf(page("faq", "常见问题", "帮助", "", """
            ## 地址与连接
            ### 地址怎么写
            地址要写完整，不要多写斜杠。

            ### 连接失败
            先确认网络正常，再重试。
            """));

        assertThat(ask(index, "连接").chunks().getFirst().section()).isEqualTo("地址与连接 › 连接失败");
        assertThat(ask(index, "地址与连接").chunks()).as("the parent heading still finds its children").hasSize(2);

        AssistantDocsIndex tutorials = seeded();
        assertThat(where(ask(tutorials, "Connection problems").chunks().getFirst())).isEqualTo("faq § Addresses and connections › Connection failure, or 5xx");
        assertThat(where(ask(tutorials, "连接有问题").chunks().getFirst())).isEqualTo("faq § 地址与连接 › 连接失败，或 5xx");
        assertThat(ask(tutorials, "地址怎么填").chunks().stream().map(Chunk::section).toList()).as("the sections about the address come first")
            .startsWith("准备地址与密钥", "各接口的地址");
    }

    @Test
    void anEnglishWordIsFoundInTheFormTheDocumentsHaveItInWhateverEndingItIsAsked() {
        AssistantDocsIndex index = seeded();

        assertThat(where(ask(index, "redeeming a code").chunks().getFirst())).isEqualTo("billing § Top up your balance › Redeem a code");
        assertThat(ask(index, "verifying Claude Code").chunks().stream().limit(3).map(AssistantDocsIndexTest::where).toList())
            .contains("claude-code § Verify");
        assertThat(pageIds(ask(index, "how am I billed"))).contains("billing");
        assertThat(ask(index, "changing my password").chunks().stream().limit(3).map(Chunk::pageId).toList()).contains("faq");

        AssistantDocsIndex endings = indexOf(
            page("settings", "Preferences", "Help", "", "## Preferences\nOpen the settings page and change the language."),
            page("setup", "Installation", "Help", "", "## Installation\nSet up the tool and run it."));
        assertThat(ask(endings, "settings").chunks()).as("a word of the documents keeps its form").extracting(Chunk::pageId)
            .containsExactly("settings");
        assertThat(ask(endings, "running").chunks()).as("a doubled letter before the ending").extracting(Chunk::pageId)
            .containsExactly("setup");
        assertThat(ask(endings, "setting").chunks()).extracting(Chunk::pageId).containsExactly("settings");

        AssistantDocsIndex small = indexOf(page("keys", "Keys", "Help", "", "## Create\nCreate keys in the console."));
        assertThat(ask(small, "created").chunks()).as("'create' with an ending").extracting(Chunk::pageId).containsExactly("keys");
        assertThat(ask(small, "creating").chunks()).extracting(Chunk::pageId).containsExactly("keys");
        assertThat(ask(small, "creatin").chunks()).as("not a form of anything").isEmpty();
    }

    @Test
    void wordsOfNoMeaningInAnEnglishQuestionDoNotPullInPages() {
        AssistantDocsIndex index = seeded();

        Retrieval hits = askAfter(index, "How do I buy one?", "What is a Coding Plan?");

        assertThat(pageIds(hits)).as("'one' is in 'one provider per key' and 'Import with one click'").doesNotContain("api-keys", "cc-switch");
        assertThat(pageIds(hits)).contains("coding-plan");
    }

    @Test
    void aWordThatTheTutorialsNeedIsNotDroppedAsAWordOfNoMeaning() {
        AssistantDocsIndex index = seeded();

        // "out" is how the tutorials say how to leave the console and what happens when the credits are gone; "one" is what
        // a heading about a single provider per key is about.
        for (String question : List.of("How do I sign out?", "sign out", "Where do I sign out?")) {
            assertThat(ask(index, question).chunks().stream().limit(3).map(AssistantDocsIndexTest::where).toList())
                .as(question).contains("console-tour § Top bar");
        }
        for (String question : List.of("my plan ran out", "I ran out of credits", "credits ran out")) {
            assertThat(ask(index, question).chunks().stream().limit(2).map(Chunk::section).toList())
                .as(question).anyMatch(section -> section.contains("What if the credits run out?"));
        }
        assertThat(ask(index, "Is one key enough?").chunks().stream().limit(3).map(Chunk::section).toList())
            .contains("Model category: one provider per key");
    }

    @Test
    void aMessageWithoutAnyEnglishWordFollowsTheConversationAndWithoutOneIsReadInChinese() {
        AssistantDocsIndex index = seeded();

        for (String message : List.of("401", "API_KEY_MODEL_PROVIDER_FORBIDDEN", "config.toml", "gpt-5.5", "502, 503")) {
            Retrieval hits = ask(index, message);
            assertThat(hits.chunks()).as(message).isNotEmpty();
            assertThat(hits.sources()).as(message).allSatisfy(source -> assertThat(source).startsWith("使用文档《"));
            assertThat(hits.chunks()).extracting(Chunk::language).as(message).containsOnly("zh");
        }
        assertThat(askAfter(index, "401", "How do I set up Claude Code?").sources()).as("unless the conversation is in English")
            .allSatisfy(source -> assertThat(source).startsWith("Docs: "));
        assertThat(ask(index, "Error 401").sources()).as("an ordinary English word").allSatisfy(source -> assertThat(source).startsWith("Docs: "));
        assertThat(ask(index, "billing").sources()).allSatisfy(source -> assertThat(source).startsWith("Docs: "));

        // Three status codes in a row after an English question: the question is more than two messages back.
        for (List<String> earlier : List.of(List.of("401"), List.of("403", "401"), List.of("403", "401", "How do I set up Claude Code?"),
            List.of("403", "401", "502", "How do I set up Claude Code?"), List.of("502, 503", "403", "401", "billing"))) {
            Retrieval hits = askAfter(index, "502", earlier.toArray(String[]::new));
            boolean english = earlier.stream().anyMatch(message -> message.matches(".*[A-Za-z]{3,}.*"));
            assertThat(hits.sources()).as(earlier.toString()).isNotEmpty().allSatisfy(source ->
                assertThat(source.startsWith("Docs: ")).isEqualTo(english));
        }
        assertThat(askAfter(index, "502", "403", "401", "怎么配置 Claude Code").sources()).as("and in Chinese when that is the conversation")
            .allSatisfy(source -> assertThat(source).startsWith("使用文档《"));
        assertThat(askAfter(index, "502", "403", "401", "怎么配置 Claude Code", "How do I set up Claude Code?").sources())
            .as("the latest earlier message that says").allSatisfy(source -> assertThat(source).startsWith("使用文档《"));
    }

    @Test
    void aLongMessageOfDottedOrHyphenatedPartsDoesNotOverflowASmallThreadStack() throws Exception {
        // java.util.regex calls itself once for each repetition of a greedy group. A message may have 4000 characters, and a
        // thread of the Linux default (1 MB) overflowed with about 3700 of "x.x.x…"; a StackOverflowError is fatal to Reactor,
        // so the question was never answered. 512 KB stands for that default here, whatever the stack of the machine is.
        String dotted = "a.".repeat(1999) + "a";
        String hyphenated = "ab" + "-a".repeat(1998);
        String version = "v1.2.3 " + "x.".repeat(1900) + "x";
        AssistantDocsIndex index = seeded();

        assertThat(onSmallStack(() -> AssistantDocsIndex.hasEnglishWord(hyphenated))).as("a hyphenated run of letters is an English word").isTrue();
        assertThat(onSmallStack(() -> AssistantDocsIndex.hasEnglishWord(dotted))).as("a dotted one is a name, not a word").isFalse();
        assertThat(onSmallStack(() -> AssistantDocsIndex.hasEnglishWord(version))).isFalse();
        // And the question is answered, also when the long message is an earlier one and the question has no language of its own.
        assertThat(ask(index, dotted).chunks()).isNotNull();
        for (String earlier : List.of(dotted, hyphenated, version)) {
            assertThat(askAfter(index, "401", earlier).chunks()).as("401 after " + earlier.substring(0, 12)).isNotEmpty();
        }
    }

    private static <T> T onSmallStack(Supplier<T> work) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(null, task, "small-stack", 512 * 1024));
        try {
            return executor.submit(work::get).get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void theVocabularyOfVariedChineseTextHasNoStringForEveryPairOfCharacters() {
        // The 2000 chunks that are indexed at most, 1500 characters each, drawn evenly from 3500 common characters: almost
        // every pair is a new one, some 1.4 million of them. As strings with a count each they were 165 MB.
        Random random = new Random(7);
        List<Document> pages = new ArrayList<>();
        for (int page = 0; page < 40; page++) {
            StringBuilder body = new StringBuilder();
            for (int section = 0; section < 50; section++) {
                body.append("## 小节").append(section).append('\n');
                for (int character = 0; character < 1400; character++) {
                    body.append((char) (0x4E00 + random.nextInt(3500)));
                }
                body.append("\n\n");
            }
            pages.add(page("page-" + page, "页面" + page, "帮助", "", body.toString()));
        }
        AssistantDocsIndex index = indexOf(pages);

        Retrieval hits = ask(index, "页面7");
        int[] sizes = index.vocabularySizes();

        assertThat(hits.chunks()).as("and it still finds what is asked for").isNotEmpty();
        assertThat(sizes[1]).as("pairs of characters, kept as ints").isGreaterThan(1_000_000);
        assertThat(sizes[0]).as("words kept as strings: the headings only").isLessThan(1_000);
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

    /**
     * A database of published pages as another node can change it between two statements of this one: the fingerprint of
     * a set of pages is the sorted list of its ids, the list of pages is read in one statement that also makes the
     * fingerprint of what it lists, and a hook runs after each statement, standing for the other node.
     */
    private static final class ChangingDatabase {
        final List<String> published = new java.util.concurrent.CopyOnWriteArrayList<>(List.of("open"));
        final AtomicInteger listings = new AtomicInteger();
        final TestClock clock = new TestClock();
        volatile Runnable afterFingerprintRead = () -> { };

        private static String fingerprintOf(List<String> ids) {
            return ids.stream().sorted().collect(java.util.stream.Collectors.joining(","));
        }

        private static Document document(String id) {
            return page(id, "页面" + id, "帮助", "", "## 内容\n页面" + id + "里写着 word" + id + "qq。");
        }

        AssistantDocsIndex index() {
            return AssistantDocsIndex.streamingWithFingerprint(NO_FILES, () -> Mono.fromSupplier(() -> {
                listings.incrementAndGet();
                List<String> listed = List.copyOf(published);
                return new tech.starryapi.documentation.DocumentationService.PublishedStream(fingerprintOf(listed),
                    Flux.fromIterable(listed).map(ChangingDatabase::document));
            }), () -> Mono.fromSupplier(() -> {
                String read = fingerprintOf(List.copyOf(published));
                afterFingerprintRead.run();
                return read;
            }), clock);
        }
    }

    @Test
    void aPagePublishedBetweenTheFingerprintAndTheListIsNotQuotedAfterItIsWithdrawnAgain() {
        ChangingDatabase database = new ChangingDatabase();
        AssistantDocsIndex index = database.index();
        // Another node publishes "secret" after this node has read the fingerprint and before it lists the pages.
        database.afterFingerprintRead = () -> {
            database.published.add("secret");
            database.afterFingerprintRead = () -> { };
        };

        assertThat(pageIds(ask(index, "wordsecretqq"))).as("it was published when the pages were listed").containsExactly("secret");

        database.published.remove("secret");
        database.clock.advance(Duration.ofSeconds(61));

        assertThat(ask(index, "wordsecretqq").chunks()).as("the check finds the pages changed, however the published set looks").isEmpty();
        assertThat(ask(index, "wordsecretqq").pageTitles()).doesNotContain("页面secret");
        assertThat(database.listings).as("the pages were read again").hasValue(2);
        assertThat(pageIds(ask(index, "wordopenqq"))).containsExactly("open");
    }

    @Test
    void anUnchangedSetOfPagesIsStillNotReadAgainWhenTheSourceSaysWhichPagesItListed() {
        ChangingDatabase database = new ChangingDatabase();
        AssistantDocsIndex index = database.index();
        assertThat(pageIds(ask(index, "wordopenqq"))).containsExactly("open");

        database.clock.advance(Duration.ofSeconds(61));

        assertThat(pageIds(ask(index, "wordopenqq"))).containsExactly("open");
        assertThat(database.listings).as("the fingerprint of the listing is the one a check finds").hasValue(1);
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
    void questionsAreScoredOnTheirOwnThreadsWhicheverThreadDeliversTheSnapshot() {
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
                assertThat(searched.getName()).startsWith("assistant-search-").doesNotStartWith("fake-db-io");
                assertThat(Schedulers.isNonBlockingThread(searched)).as("not a Reactor event loop or parallel thread").isFalse();
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

    /** The supplier of pages whose every read is a sink that the test completes by hand. */
    private static final class ManualReads {
        final List<Sinks.One<List<Document>>> reads = new ArrayList<>();

        Supplier<Mono<List<Document>>> supplier() {
            return () -> Mono.defer(() -> {
                Sinks.One<List<Document>> sink = Sinks.one();
                synchronized (reads) {
                    reads.add(sink);
                }
                return sink.asMono();
            });
        }

        int count() {
            synchronized (reads) {
                return reads.size();
            }
        }

        /** The load that follows another begins on the thread that finished the other, so the test waits for it. */
        void awaitCount(int expected) throws InterruptedException {
            long until = System.nanoTime() + TIMEOUT.toNanos();
            while (count() < expected && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertThat(count()).isEqualTo(expected);
        }
    }

    @Test
    void aLoadThatStartedBeforeAChangeIsNotSharedWithQuestionsAskedAfterItButWaitsForItsTurn() throws Exception {
        ManualReads source = new ManualReads();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, source.supplier(), new TestClock());

        CompletableFuture<Retrieval> before = index.retrieve("发票", "").toFuture();
        assertThat(source.count()).isEqualTo(1);
        index.invalidate();
        CompletableFuture<Retrieval> after = index.retrieve("发票", "").toFuture();
        assertThat(source.count()).as("one load at a time: the next begins when this one is done").isEqualTo(1);

        source.reads.get(0).tryEmitValue(List.of(page("old", "发票", "帮助", "", "## 内容\n旧的发票说明。")));
        assertThat(before.get().chunks().getFirst().body()).contains("旧的");
        source.awaitCount(2);
        assertThat(after.isDone()).as("it is waiting for the pages that were published after the first load began").isFalse();
        source.reads.get(1).tryEmitValue(List.of(page("new", "发票", "帮助", "", "## 内容\n新的发票说明。")));
        assertThat(after.get().chunks().getFirst().body()).contains("新的");

        assertThat(ask(index, "发票").chunks().getFirst().body()).contains("新的");
        assertThat(source.count()).isEqualTo(2);
    }

    @Test
    void aBurstOfChangesWhileALoadRunsCostsOneMoreLoadAndNotOnePerChange() throws Exception {
        ManualReads source = new ManualReads();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, source.supplier(), new TestClock());

        List<CompletableFuture<Retrieval>> questions = new ArrayList<>();
        questions.add(index.retrieve("发票", "").toFuture());
        for (int change = 0; change < 12; change++) {
            index.onPublicationChanged(new tech.starryapi.documentation.DocumentationPublicationChanged("p" + change));
            questions.add(index.retrieve("发票", "").toFuture());
        }
        assertThat(source.count()).as("the pages are not read again while they are being read").isEqualTo(1);

        source.reads.get(0).tryEmitValue(List.of(page("p", "发票", "帮助", "", "## 内容\n第一次读到的发票说明。")));
        source.awaitCount(2);
        index.invalidate();
        questions.add(index.retrieve("发票", "").toFuture());
        assertThat(source.count()).as("a change during the second load is not read in parallel either").isEqualTo(2);
        source.reads.get(1).tryEmitValue(List.of(page("p", "发票", "帮助", "", "## 内容\n第二次读到的发票说明。")));
        source.awaitCount(3);
        source.reads.get(2).tryEmitValue(List.of(page("p", "发票", "帮助", "", "## 内容\n第三次读到的发票说明。")));

        for (CompletableFuture<Retrieval> question : questions) {
            assertThat(question.get().chunks()).hasSize(1);
        }
        assertThat(questions.get(1).get().chunks().getFirst().body()).as("asked after a change, answered after the next load")
            .contains("第二次");
        assertThat(source.count()).isEqualTo(3);
    }

    /** The title list of the snapshot that answered the question; nothing but the weak reference is kept. */
    private static WeakReference<List<String>> snapshotOf(CompletableFuture<Retrieval> question) throws Exception {
        return new WeakReference<>(question.get().pageTitles());
    }

    @Test
    void loadsQueuedBehindEachOtherDoNotKeepEveryEarlierSnapshotInMemory() throws Exception {
        ManualReads source = new ManualReads();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, source.supplier(), new TestClock());
        int loads = 8;

        // A script that updates many pages: every load is still running when the next change and the next question come,
        // so there is always one more load waiting for it. Each one answers with a snapshot of its own.
        List<WeakReference<List<String>>> snapshots = new ArrayList<>();
        CompletableFuture<Retrieval> question = index.retrieve("发票", "").toFuture();
        for (int load = 0; load < loads; load++) {
            index.invalidate();
            CompletableFuture<Retrieval> next = index.retrieve("发票", "").toFuture();
            source.reads.get(load).tryEmitValue(List.of(page("p", "发票", "帮助", "", "## 内容\n第" + load + "次读到的发票说明。")));
            snapshots.add(snapshotOf(question));
            question = next;
            source.awaitCount(load + 2);
        }

        // The last load is still being read, and one question waits for it. Only the newest finished snapshot is kept,
        // as the one to answer with until that load is done; the ones before it are garbage.
        awaitCollected(snapshots.subList(0, loads - 1));
        assertThat(snapshots.get(loads - 1).get()).as("the newest finished snapshot is still the one in use").isNotNull();
        assertThat(question.isDone()).isFalse();
        source.reads.get(loads).tryEmitValue(List.of(page("p", "发票", "帮助", "", "## 内容\n最后一次读到的发票说明。")));
        assertThat(question.get().chunks().getFirst().body()).contains("最后一次");
    }

    private static void awaitCollected(List<WeakReference<List<String>>> references) throws InterruptedException {
        long until = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < until && references.stream().anyMatch(reference -> reference.get() != null)) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(references).as("snapshots that are still held by the queue of loads")
            .allSatisfy(reference -> assertThat(reference.get()).isNull());
    }

    @Test
    void aReadThatNeverReturnsIsAFailureLikeAnyOtherAndIsRetried() {
        TestClock clock = new TestClock();
        AtomicInteger pageReads = new AtomicInteger();
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            pageReads.incrementAndGet();
            return Mono.never();
        }, clock);
        index.readBudget = Duration.ofMillis(200);

        assertThat(ask(index, "发票").chunks()).as("answered without the pages once the read has taken too long").isEmpty();
        assertThat(ask(index, "发票").pageTitles()).isEmpty();
        assertThat(pageReads).as("and not retried on every question").hasValue(1);

        clock.advance(Duration.ofSeconds(11));
        assertThat(ask(index, "发票").chunks()).isEmpty();
        assertThat(pageReads).as("but retried soon, so a node does not stay without the pages").hasValue(2);

        AtomicInteger fingerprintReads = new AtomicInteger();
        AssistantDocsIndex hanging = new AssistantDocsIndex(NO_FILES,
            () -> Mono.just(List.of(page("p", "发票", "帮助", "", "## 内容\n发票说明。"))), () -> {
                fingerprintReads.incrementAndGet();
                return Mono.never();
            }, clock);
        hanging.readTimeout = Duration.ofMillis(200);
        assertThat(ask(hanging, "发票").chunks()).as("a fingerprint that never comes is the same failure").isEmpty();
        clock.advance(Duration.ofSeconds(11));
        ask(hanging, "发票");
        assertThat(fingerprintReads).hasValue(2);
    }

    @Test
    void aReadOfThePagesIsNotCutOffForTakingLongAsLongAsItDeliversPages() {
        AtomicInteger reads = new AtomicInteger();
        // 8 pages, one every 60 ms: about half a second in all, but never 250 ms without one.
        AssistantDocsIndex index = AssistantDocsIndex.streaming(NO_FILES, () -> {
            reads.incrementAndGet();
            return Flux.interval(Duration.ofMillis(60)).take(8)
                .map(row -> page("p" + row, "发票" + row, "帮助", "", "## 内容\n第" + row + "页的发票说明。"));
        }, Mono::empty, new TestClock());
        index.readTimeout = Duration.ofMillis(250);

        Retrieval slow = ask(index, "发票");

        assertThat(slow.pageTitles()).as("the whole read took longer than the limit on silence").hasSize(8);
        assertThat(slow.chunks()).isNotEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void aReadOfThePagesThatStopsDeliveringFailsAfterTheSilenceLimit() {
        TestClock clock = new TestClock();
        AtomicInteger reads = new AtomicInteger();
        AssistantDocsIndex index = AssistantDocsIndex.streaming(NO_FILES, () -> {
            reads.incrementAndGet();
            return Flux.concat(Flux.just(page("p", "发票", "帮助", "", "## 内容\n发票说明。")), Flux.never());
        }, Mono::empty, clock);
        index.readTimeout = Duration.ofMillis(200);

        long start = System.nanoTime();
        Retrieval stalled = index.retrieve("发票", "").block(Duration.ofSeconds(5));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("failed after the silence limit, not the budget").isLessThan(Duration.ofSeconds(4));
        assertThat(stalled.chunks()).as("answered without the pages, not the one that came").isEmpty();
        assertThat(stalled.pageTitles()).isEmpty();
        clock.advance(Duration.ofSeconds(11));
        ask(index, "发票");
        assertThat(reads).as("and tried again soon").hasValue(2);

        AssistantDocsIndex none = AssistantDocsIndex.streaming(NO_FILES, Flux::empty, Mono::empty, new TestClock());
        assertThat(ask(none, "发票").chunks()).as("no pages at all is an empty list, not a failure").isEmpty();
    }

    @Test
    void thePagesAreNotHeldToTheLimitThatTheFingerprintIsHeldTo() {
        // Reading the pages, as a supplier that does not limit itself, is bounded only by the (much larger) budget.
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () ->
            Mono.delay(Duration.ofMillis(500)).thenReturn(List.of(page("p", "发票", "帮助", "", "## 内容\n发票说明。"))), new TestClock());
        index.readTimeout = Duration.ofMillis(200);

        assertThat(ask(index, "发票").chunks()).hasSize(1);
        assertThat(AssistantDocsIndex.READ_BUDGET).isGreaterThan(AssistantDocsIndex.READ_TIMEOUT.multipliedBy(10));
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

    // ---- safety: time, memory and a load that cannot finish ---------------------------------------------------------

    private static void within(Duration limit, String what, Runnable work) {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(limit, work::run, what);
    }

    @Test
    void aRunOfHyphenatedWordsIsScannedOnceAndNotOncePerWord() {
        // Every part is a stop word or a one-word duplicate, so nothing ends the scan early: the cost is the length of
        // the run, not the length times the number of words in it. 180000 characters took half a minute when each word
        // measured the run to its end again.
        for (String unit : List.of("is-", "ab-", "\u33cc-", "\ufdf2-", "ab-cd-")) {
            String run = unit.repeat(180_000 / unit.length());
            within(Duration.ofSeconds(3), "scan of " + (int) unit.charAt(0), () -> {
                AssistantDocsIndex.termWeights(run);
                AssistantDocsIndex.phrases(run);
            });
        }
    }

    @Test
    void hyphenatedWordsAreStillCutIntoWordsUnlessTheNameHasADigit() {
        assertThat(AssistantDocsIndex.termWeights("alpha-beta-gamma")).containsOnlyKeys("alpha", "beta", "gamma");
        assertThat(AssistantDocsIndex.termWeights("built-in ab-cd.ef-gh")).containsKeys("built", "ab", "gh")
            .doesNotContainKey("built-in").doesNotContainKey("ab-cd.ef-gh");
        assertThat(AssistantDocsIndex.termWeights("alpha-beta-gpt-5.5-turbo")).as("a digit anywhere makes one name")
            .containsEntry("alpha-beta-gpt-5.5-turbo", 4.0);
        assertThat(AssistantDocsIndex.termWeights("x-1 alpha-beta")).containsEntry("alpha", 1.0).containsEntry("beta", 1.0);
        assertThat(AssistantDocsIndex.phrases("alpha-beta gamma")).as("a hyphen is no space between two words of a phrase")
            .containsExactly("beta gamma");
    }

    @Test
    void aQuestionOfHyphenatedStopWordsAfterSixLikeItIsAnsweredAtOnce() {
        AssistantDocsIndex index = help();
        ask(index, "怎么创建 API Key");
        // The chat service stops at 4000 characters a message, the index does not rely on it: seven messages of 40000
        // characters took half a minute when every word measured its run to the end again.
        String message = "\u33cc-".repeat(20_000);
        List<String> earlier = List.of(message, message, message, message, message, message);

        within(Duration.ofSeconds(5), "a question of hyphenated stop words", () ->
            assertThat(index.retrieveFor(message, earlier).block(TIMEOUT).chunks()).isEmpty());
    }

    @Test
    void pagesOfHyphenatedParagraphsAreIndexedInTimeThatGrowsWithTheirLength() {
        // 2000 chunks of 1500 characters, nothing in them but hyphenated words (U+FDF2 is four letters after folding).
        List<Document> pages = new ArrayList<>();
        for (int number = 0; number < 200; number++) {
            StringBuilder body = new StringBuilder("## 连字符\n");
            for (int paragraph = 0; paragraph < 10; paragraph++) {
                body.append("\ufdf2-".repeat(750)).append("\n\n");
            }
            pages.add(page("hyphen-" + number, "连字符" + number, "帮助", "", body.toString()));
        }
        AssistantDocsIndex index = indexOf(pages);

        within(Duration.ofSeconds(5), "indexing hyphenated paragraphs", () -> ask(index, "连字符"));
        assertThat(index.searchCharacters()).isGreaterThan(1_000_000);
    }

    /** The Arabic ligature U+FDFA, which normalization turns into 18 characters. */
    private static final String LIGATURE = "\ufdfa";

    @Test
    void whatIsKeptToSearchInIsBoundedByTheRawTextAndNotByWhatNormalizationMakesOfIt() {
        List<Document> pages = new ArrayList<>();
        int rawCharacters = 0;
        for (int number = 0; number < 6; number++) {
            String title = LIGATURE.repeat(100);
            String category = LIGATURE.repeat(60);
            String summary = LIGATURE.repeat(240);
            String paragraph = LIGATURE.repeat(1500);
            String heading = LIGATURE.repeat(100);
            String body = "## " + heading + "\n" + paragraph;
            Translation english = new Translation(title, category, summary, "## " + heading + "\n" + paragraph);
            pages.add(page("ligature-" + number, new Content(title, category, summary, body, 10, english)));
            // Two language versions, one chunk each: the raw text that is kept to search in.
            rawCharacters += 2 * (title.length() + category.length() + summary.length() + paragraph.length() + heading.length());
        }
        AssistantDocsIndex index = indexOf(pages);

        ask(index, "ligature");

        // 18 times the raw text, 2.4 million characters, before the folded text was bounded; ordinary text folds to about
        // its own length.
        assertThat(index.searchCharacters()).isLessThan(rawCharacters * 3L);
        assertThat(index.searchCharacters()).isGreaterThan(rawCharacters / 2L);
    }

    @Test
    void textThatNormalizationDoesNotLengthenIsKeptWhole() {
        String paragraph = "创建 API Key 的步骤。".repeat(100);
        AssistantDocsIndex index = indexOf(page("p", "创建密钥", "开始使用", "在控制台创建密钥", "## 创建\n" + paragraph));

        Retrieval hits = ask(index, "创建密钥");

        assertThat(hits.chunks()).hasSize(1);
        assertThat(index.searchCharacters()).isGreaterThanOrEqualTo(AssistantDocsIndex.fold(paragraph).replaceAll("\\s+", " ").length());
    }

    @Test
    void thePagesSayingsAboutThemselvesAreFoldedOnceAndSharedByTheirChunks() {
        StringBuilder body = new StringBuilder();
        for (int section = 0; section < 40; section++) {
            body.append("## 小节").append(section).append('\n').append("说明文字".repeat(60)).append("\n\n");
        }
        // Capital letters, so that folding makes a new string of each: text that folding leaves alone is shared anyway.
        String category = "Getting Started ".repeat(3);
        String summary = "Page Summary 页面摘要 ".repeat(15);
        AssistantDocsIndex shared = indexOf(page("p", "共享页面", category, summary, body.toString()));
        AssistantDocsIndex single = indexOf(page("p", "共享页面", category, summary, "## 小节0\n" + "说明文字".repeat(60)));
        ask(shared, "说明文字");
        ask(single, "说明文字");

        long perChunkOfTheSharedPage = (shared.searchCharacters() - single.searchCharacters()) / 39;
        assertThat(perChunkOfTheSharedPage).as("a further chunk costs its own text, not the page's summary again")
            .isLessThan(240 + 20 + 12);
        assertThat(summary.length() + category.length()).isGreaterThan(300);
    }

    @Test
    void aBuildThatMeetsAnErrorTheJvmCannotRecoverFromStillAnswersAndRecoversWhenThePagesChange() {
        for (Throwable fatal : List.of(new OutOfMemoryError("test"), new StackOverflowError("test"), new LinkageError("test"))) {
            TestClock clock = new TestClock();
            AtomicInteger loads = new AtomicInteger();
            List<Document> exploding = new java.util.AbstractList<>() {
                @Override
                public Document get(int index) {
                    if (fatal instanceof OutOfMemoryError oom) {
                        throw oom;
                    }
                    if (fatal instanceof StackOverflowError overflow) {
                        throw overflow;
                    }
                    throw (LinkageError) fatal;
                }

                @Override
                public int size() {
                    return 1;
                }
            };
            AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
                int number = loads.incrementAndGet();
                return Mono.just(number == 1 ? exploding : List.of(page("p", "发票", "帮助", "", "## 开票\n在发票页面申请开票。")));
            }, clock);

            Retrieval during = index.retrieve("发票", "").block(Duration.ofSeconds(5));
            assertThat(during.chunks()).as(fatal.getClass().getSimpleName() + " while indexing: answered without the pages").isEmpty();
            assertThat(index.retrieve("发票", "").block(Duration.ofSeconds(5)).chunks()).isEmpty();
            assertThat(loads).as("not retried at once, it would fail again").hasValue(1);

            index.invalidate();
            assertThat(index.retrieve("发票", "").block(Duration.ofSeconds(5)).chunks())
                .as("a change in the pages starts a new load").hasSize(1);
            assertThat(loads).hasValue(2);
        }
    }

    @Test
    void anOutOfMemoryErrorOfTheBuildIsNotKeptAsIfThePagesWereToBlameSoTheyAreReadAgainWithoutAChange() {
        TestClock clock = new TestClock();
        AtomicInteger pageReads = new AtomicInteger();
        List<Document> exploding = new java.util.AbstractList<>() {
            @Override
            public Document get(int index) {
                throw new OutOfMemoryError("test");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        // The fingerprint never changes: the pages are not what failed, the heap was.
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            int number = pageReads.incrementAndGet();
            return Mono.just(number == 1 ? exploding : List.of(page("p", "发票", "帮助", "", "## 开票\n在发票页面申请开票。")));
        }, () -> Mono.just("unchanged"), clock);

        assertThat(ask(index, "发票").chunks()).as("answered without the pages").isEmpty();
        assertThat(pageReads).hasValue(1);

        clock.advance(Duration.ofMillis(AssistantDocsIndex.RETRY_AFTER + 1));
        assertThat(ask(index, "发票").chunks()).as("the pages are read again soon, as after any failure to load").hasSize(1);
        assertThat(pageReads).hasValue(2);

        clock.advance(Duration.ofMillis(AssistantDocsIndex.CACHE_TTL + 1));
        assertThat(ask(index, "发票").chunks()).as("and, once indexed, kept while their fingerprint is the same").hasSize(1);
        assertThat(pageReads).hasValue(2);
    }

    @Test
    void aBuildThatFailsBecauseOfWhatThePagesSayIsStillNotRetriedWhileTheirFingerprintIsTheSame() {
        for (Throwable fault : List.of(new IllegalStateException("test"), new StackOverflowError("test"), new LinkageError("test"))) {
            TestClock clock = new TestClock();
            AtomicInteger pageReads = new AtomicInteger();
            List<Document> exploding = new java.util.AbstractList<>() {
                @Override
                public Document get(int index) {
                    if (fault instanceof StackOverflowError overflow) {
                        throw overflow;
                    }
                    if (fault instanceof LinkageError linkage) {
                        throw linkage;
                    }
                    throw (RuntimeException) fault;
                }

                @Override
                public int size() {
                    return 1;
                }
            };
            AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
                pageReads.incrementAndGet();
                return Mono.just(exploding);
            }, () -> Mono.just("unchanged"), clock);

            assertThat(ask(index, "发票").chunks()).isEmpty();
            clock.advance(Duration.ofMillis(AssistantDocsIndex.CACHE_TTL * 5));
            assertThat(ask(index, "发票").chunks()).isEmpty();
            assertThat(pageReads).as(fault.getClass().getSimpleName()).hasValue(1);
        }
    }

    @Test
    void anErrorTheJvmCannotRecoverFromWhileTurningAPageIntoChunksStillEndsTheLoadAndTheOthersAreAnswered() {
        for (Error fatal : List.of(new OutOfMemoryError("test"), new LinkageError("test"))) {
            TestClock clock = new TestClock();
            AtomicInteger loads = new AtomicInteger();
            AssistantDocsIndex index = AssistantDocsIndex.streaming(NO_FILES, () -> {
                loads.incrementAndGet();
                return Flux.just(page("a", "发票", "帮助", "", "## 开票\n在发票页面申请开票。"),
                    page("boom", "发票二", "帮助", "", "## 开票\n在发票页面申请开票。"),
                    page("c", "发票三", "帮助", "", "## 开票\n在发票页面申请开票。"));
            }, () -> Mono.just("unchanged"), clock);
            index.pageHook = document -> {
                if (document.id().equals("boom")) {
                    throw fatal;
                }
            };

            Retrieval during = index.retrieve("发票", "").block(Duration.ofSeconds(5));
            assertThat(during.chunks()).as(fatal.getClass().getSimpleName() + ": the load ended, without the pages").isEmpty();
            assertThat(during.pageTitles()).isEmpty();
            assertThat(loads).hasValue(1);

            index.pageHook = document -> { };
            clock.advance(Duration.ofMillis(AssistantDocsIndex.RETRY_AFTER + 1));
            if (!(fatal instanceof OutOfMemoryError)) {
                // The text of the pages decides it, and would again: not before something changes. The heap of the moment
                // does not, so running out of it is tried again soon, as after any failure to load.
                assertThat(ask(index, "发票").pageTitles()).isEmpty();
                assertThat(loads).hasValue(1);
                index.invalidate();
            }
            assertThat(ask(index, "发票").pageTitles()).as("and the pages are read again").hasSize(3);
            assertThat(loads).hasValue(2);
        }
    }

    @Test
    void aPageThatOverflowsTheStackWhileItIsTurnedIntoChunksIsLeftOutAndTheOthersAreKept() {
        AssistantDocsIndex index = AssistantDocsIndex.streaming(NO_FILES, () ->
            Flux.just(page("a", "发票", "帮助", "", "## 开票\n在发票页面申请开票。"),
                page("boom", "发票二", "帮助", "", "## 开票\n在发票页面申请开票。"),
                page("c", "发票三", "帮助", "", "## 开票\n在发票页面申请开票。")), Mono::empty, new TestClock());
        index.pageHook = document -> {
            if (document.id().equals("boom")) {
                throw new StackOverflowError("test");
            }
        };

        assertThat(ask(index, "发票").pageTitles()).containsExactly("发票", "发票三");
    }

    @Test
    void aLoadThatNeverReportsBackIsAbandonedAtItsDeadlineAndTheNextOneBegins() throws Exception {
        TestClock clock = new TestClock();
        AtomicInteger loads = new AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        // A build that is stuck: whatever the reason, it will not say that it is done.
        List<Document> stuck = new java.util.AbstractList<>() {
            @Override
            public Document get(int index) {
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Cancelling the load interrupts the thread; it carries on being stuck.
                    }
                }
                return page("late", "迟到", "帮助", "", "## 迟到\n迟到的发票。");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        AssistantDocsIndex index = new AssistantDocsIndex(NO_FILES, () -> {
            int number = loads.incrementAndGet();
            return Mono.just(number == 1 ? stuck : List.of(page("p", "发票", "帮助", "", "## 开票\n在发票页面申请开票。")));
        }, clock);
        index.readTimeout = Duration.ofMillis(200);
        index.readBudget = Duration.ofMillis(200);
        index.buildBudget = Duration.ofMillis(300);
        try {
            assertThat(index.retrieve("发票", "").block(Duration.ofSeconds(5)).chunks())
                .as("answered without the pages once the deadline has passed").isEmpty();
            assertThat(index.retrieve("发票", "").block(Duration.ofSeconds(5)).chunks()).isEmpty();
            assertThat(loads).as("and not retried at once").hasValue(1);

            clock.advance(Duration.ofSeconds(11));
            assertThat(index.retrieve("发票", "").block(Duration.ofSeconds(5)).chunks())
                .as("a new load begins instead of every question waiting for the stuck one").hasSize(1);
            assertThat(loads).hasValue(2);
        } finally {
            release.countDown();
        }
    }

    // ---- what a hostile page can cost -------------------------------------------------------------------------------

    @Test
    void aCodeBlockWithAnEnormousFenceLeavesNoMoreInTheSnapshotThanThePagesHad() {
        StringBuilder code = new StringBuilder("`".repeat(50_000)).append('\n');
        while (code.length() < 99_990) {
            code.append("x\n");
        }
        String body = code.toString();
        List<Document> pages = new ArrayList<>();
        for (int number = 0; number < 3; number++) {
            pages.add(page("fence-" + number, new Content("围栏" + number, "帮助", "", body, number + 1,
                new Translation("Fence " + number, "Help", "", body))));
        }
        AssistantDocsIndex index = indexOf(pages);

        assertThat(ask(index, "围栏").pageTitles()).as("the pages were indexed").hasSize(3);
        assertThat(index.retainedCharacters()).isGreaterThan(100_000).isLessThanOrEqualTo(2L * pages.size() * 2 * body.length());
        assertThat(ask(index, "围栏").chunks()).isNotEmpty().allSatisfy(chunk ->
            assertThat(chunk.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS));
    }

    @Test
    void anAttachmentReferenceInAPageTitleReachesNeitherTheLabelNorTheSourcesNorTheListOfTitles() {
        String id = "0f0e0d0c-0b0a-4908-8706-050403020100";
        AssistantDocsIndex index = indexOf(page("invoice", new Content("发票样例 attachment:" + id, "帮助", "",
            "## 开票\n在发票页面申请开票，开票后可以下载发票。", 1,
            new Translation("Invoice sample attachment:" + id + " /documentation/media/" + id + "?signature=1", "Help", "",
                "## Invoicing\nApply for an invoice on the invoice page and download the invoice afterwards."))));

        for (String question : List.of("怎么申请开票", "How do I apply for an invoice", "与文档毫无关系的量子计算")) {
            Retrieval found = ask(index, question);
            List<String> shown = new ArrayList<>(found.sources());
            shown.addAll(found.pageTitles());
            found.chunks().forEach(chunk -> shown.add(chunk.pageTitle()));

            assertThat(shown).as(question).isNotEmpty().allSatisfy(text ->
                assertThat(text).doesNotContain(id, "attachment:", "documentation/media", "signature"));
        }
        assertThat(ask(index, "怎么申请开票").chunks()).as("a title cleaned of its attachment is still the page's title")
            .extracting(Chunk::pageTitle).containsOnly("发票样例");
        assertThat(ask(index, "How do I apply for an invoice").chunks()).extracting(Chunk::pageTitle)
            .containsOnly("Invoice sample");
    }

    @Test
    void aReferenceThatTheRemovalOfAnotherOneJoinsReachesNeitherTheTitleNorTheTextNorTheSources() {
        String id = "0f0e0d0c-0b0a-4908-8706-050403020100";
        // Taking the middle reference out joins the rest into "attachment:<id>", which no later pass looked for.
        String joining = "attachmentattachment:11111111:22222222:" + id;
        AssistantDocsIndex index = indexOf(page("joined", new Content("页面 " + joining, "帮助", "摘要 " + joining,
            "## 开票\n关键字 scrubqq " + joining + "\n\n```text\nscrubcodeqq " + joining + "\n```\n", 1,
            new Translation("Page " + joining, "Help", "Summary " + joining,
                "## Invoicing\nkeyword scrubqq " + joining + "\n\n```text\nscrubcodeqq " + joining + "\n```\n"))));

        for (String question : List.of("scrubqq scrubcodeqq", "关键字 scrubqq")) {
            Retrieval found = ask(index, question);
            List<String> shown = new ArrayList<>(found.sources());
            shown.addAll(found.pageTitles());
            found.chunks().forEach(chunk -> {
                shown.add(chunk.pageTitle());
                shown.add(chunk.section());
                shown.add(chunk.summary());
                shown.add(chunk.body());
            });

            assertThat(shown).as(question).isNotEmpty().allSatisfy(text ->
                assertThat(text).doesNotContain("attachment:" + id).doesNotContain("attachment:"));
            assertThat(found.chunks()).as(question).isNotEmpty();
        }
    }

    @Test
    void aPageWhoseTitleIsNothingButAnAttachmentIsLeftOutLikeAPageWithoutATitle() {
        String id = "0f0e0d0c-0b0a-4908-8706-050403020100";
        AssistantDocsIndex index = indexOf(
            page("hidden", "attachment:" + id, "帮助", "", "## 开票\n在发票页面申请开票。"),
            page("shown", "发票", "帮助", "", "## 开票\n在发票页面申请开票。"));

        Retrieval found = ask(index, "发票开票");

        assertThat(found.pageTitles()).containsExactly("发票");
        assertThat(pageIds(found)).containsExactly("shown");
    }

    @Test
    void aQuestionOfShortWordsIsAnsweredQuicklyWhereverThePagesHaveLongRunsOfLetters() {
        // Every word of the question is a word of the index (one page lists them) and a part of the runs everywhere, but
        // never the whole of one: each hit in a run failed the test for a word and the next try was made one character
        // on, 1590 times in each chunk for each of the 59 words, on 2000 chunks.
        StringBuilder words = new StringBuilder();
        for (int length = 2; length <= 60; length++) {
            words.append("a".repeat(length)).append(' ');
        }
        String run = ("a".repeat(1590) + "\n\n").repeat(62);
        List<Document> pages = new ArrayList<>();
        pages.add(page("words", "词表", "分类", "摘要", words.toString()));
        for (int number = 0; number < 20; number++) {
            pages.add(page("run-" + number, new Content("页面" + number, "分类", "摘要", run, number + 1,
                new Translation("Page " + number, "Category", "Summary", run))));
        }
        AssistantDocsIndex index = indexOf(pages);
        String question = words.toString().strip();
        ask(index, question);
        assertThat(index.searchCharacters()).as("the index is full").isGreaterThan(3_000_000);

        long asked = Long.MAX_VALUE;
        long followed = Long.MAX_VALUE;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            ask(index, question);
            asked = Math.min(asked, System.nanoTime() - start);
            start = System.nanoTime();
            askAfter(index, "再讲讲", question);
            followed = Math.min(followed, System.nanoTime() - start);
        }

        assertThat(Duration.ofNanos(asked)).as("the question").isLessThan(Duration.ofMillis(SCORING_LIMIT_MILLIS));
        assertThat(Duration.ofNanos(followed)).as("a follow-up to it").isLessThan(Duration.ofMillis(SCORING_LIMIT_MILLIS));
    }

    /** A question takes some tens of milliseconds here, and took seconds (2.5 s and 4 s, a hundred times as long) before. */
    private static final long SCORING_LIMIT_MILLIS = 500;

    // ---- what a hostile question can cost ---------------------------------------------------------------------------

    /**
     * The worst case for a substring search: the index full (2000 chunks) of runs of one letter, in both languages, and a
     * page that lists words. A term made of that letter matches a long way into every run and nowhere as a whole.
     */
    private static List<Document> runPages(String vocabulary) {
        return runPages(vocabulary, ("a".repeat(1590) + "\n\n").repeat(62));
    }

    private static List<Document> runPages(String vocabulary, String run) {
        List<Document> pages = new ArrayList<>();
        pages.add(page("words", "词表", "分类", "摘要", vocabulary));
        for (int number = 0; number < 20; number++) {
            pages.add(page("run-" + number, new Content("页面" + number, "分类", "摘要", run, number + 1,
                new Translation("Page " + number, "Category", "Summary", run))));
        }
        return pages;
    }

    private static String joined(int count, java.util.function.IntFunction<String> word) {
        return java.util.stream.IntStream.range(0, count).mapToObj(word).collect(java.util.stream.Collectors.joining(" "));
    }

    /** Questions of up to 4000 characters that cost seconds before: long names, dotted numbers, long words that make phrases. */
    private static java.util.Map<String, String> hostileQuestions() {
        java.util.Map<String, String> questions = new java.util.LinkedHashMap<>();
        questions.put("long identifiers", joined(9, number -> "a".repeat(398 - number) + "_x"));
        questions.put("longer identifiers", joined(4, number -> "a".repeat(798 - number) + "_x"));
        questions.put("dotted numbers", joined(9, number -> "a".repeat(197) + ".1_" + "a".repeat(197) + "." + (number + 2)));
        questions.put("long words", joined(10, number -> "a".repeat(300 + number)));
        return questions;
    }

    /** The slowest of the questions, each asked on its own, then as the follow-up's topic and after six of its kind; the best of three rounds. */
    private static long slowestMillis(AssistantDocsIndex index, java.util.Map<String, String> questions) {
        long slowest = 0;
        for (java.util.Map.Entry<String, String> hostile : questions.entrySet()) {
            String question = hostile.getValue();
            String[] six = new String[6];
            java.util.Arrays.fill(six, question);
            for (Supplier<Retrieval> ask : List.<Supplier<Retrieval>>of(
                () -> ask(index, question),
                () -> askAfter(index, "再讲讲", question),
                () -> askAfter(index, "那怎么改", question),
                () -> askAfter(index, question, six))) {
                long best = Long.MAX_VALUE;
                for (int round = 0; round < 3; round++) {
                    long start = System.nanoTime();
                    ask.get();
                    best = Math.min(best, System.nanoTime() - start);
                }
                slowest = Math.max(slowest, TimeUnit.NANOSECONDS.toMillis(best));
            }
        }
        return slowest;
    }

    @Test
    void aQuestionOfLongNamesAndWordsIsAnsweredQuicklyWhereverThePagesHaveLongRunsOfLetters() {
        AssistantDocsIndex index = indexOf(runPages("词表"));
        ask(index, "词表");
        assertThat(index.searchCharacters()).as("the index is full").isGreaterThan(3_000_000);

        for (java.util.Map.Entry<String, String> hostile : hostileQuestions().entrySet()) {
            assertThat(Duration.ofMillis(slowestMillis(index, java.util.Map.of(hostile.getKey(), hostile.getValue()))))
                .as(hostile.getKey()).isLessThan(Duration.ofMillis(SCORING_LIMIT_MILLIS));
        }
    }

    /**
     * Chinese pages that fill the index to the caps of its folded fields (2000 chunks; the title, category, summary and
     * headings of each at their longest): the characters are ones that normalization expands (U+337F folds to four
     * ideographs), so that every field sits at its cap, and the text is the documentation's own language.
     */
    private static List<Document> chinesePagesAtTheirCaps() {
        String expanding = "\u337f";
        List<Document> pages = new ArrayList<>();
        for (int number = 0; number < 200; number++) {
            StringBuilder body = new StringBuilder("# ").append(expanding.repeat(99)).append(number).append('\n')
                .append("## ").append(expanding.repeat(99)).append('\n')
                .append("### ").append(expanding.repeat(99)).append('\n');
            while (body.length() < 99_000) {
                body.append(expanding.repeat(39)).append('\n');
            }
            pages.add(page("zh-" + number, new Content(expanding.repeat(96) + number, expanding.repeat(60),
                expanding.repeat(240), body.toString(), number, null)));
        }
        return pages;
    }

    /**
     * Twelve names of {@code parts} parts joined by underscores, each part {@code part} or, in one of the first four
     * places, {@code other}: all longer than a long term and made of the same few parts, so that the topic of a question
     * that holds them has only a few terms more than names and is kept as the topic of a follow-up.
     */
    private static String twelveNames(String part, String other, int parts) {
        return joined(12, mask -> java.util.stream.IntStream.range(0, parts)
            .mapToObj(place -> place < 4 && (mask >> place & 1) == 1 ? other : part)
            .collect(java.util.stream.Collectors.joining("_")));
    }

    /** The topic of twelve names; {@link #slowestMillis} asks it alone, as the topic of "再讲讲" and of "那怎么改", and after six questions of its kind. */
    private static java.util.Map<String, String> topicOfTwelveNames(String part, String other, int parts) {
        return java.util.Map.of("twelve names", twelveNames(part, other, parts));
    }

    @Test
    void aTopicOfLongNamesAndItsFollowUpAreAnsweredQuicklyOnAnIndexFullOfChineseText() {
        // Chinese is the documentation's own language: the matcher for long terms must not cost more per character on
        // ideographs than on letters. It cost 28 ns for each (a lookup of the Unicode block of every character, 850
        // times the string search), twelve names a question, twice for a follow-up that read the same topic again:
        // seconds of a search thread's time for one short question.
        AssistantDocsIndex index = indexOf(chinesePagesAtTheirCaps());
        ask(index, "标题");
        assertThat(index.searchCharacters()).as("the index is full").isGreaterThan(6_000_000);

        assertThat(Duration.ofMillis(slowestMillis(index, topicOfTwelveNames("aaaa", "bbbb", 4))))
            .isLessThan(Duration.ofMillis(SCORING_LIMIT_MILLIS));
    }

    @Test
    void aTopicOfLongNamesAndItsFollowUpAreAnsweredQuicklyWhereverTheTextIsRunsOfOneLetterJoinedByUnderscores() {
        // No word of such a text is longer than a letter, and the names of the question begin with that letter: a partial
        // match is begun everywhere and no run of word characters is long enough to be crossed in one step.
        for (String letter : List.of("a", "\u00e9")) {
            String run = ((letter + "_").repeat(795) + "\n\n").repeat(62);
            AssistantDocsIndex index = indexOf(runPages("词表", run));
            ask(index, "词表");
            assertThat(index.searchCharacters()).as("the index is full").isGreaterThan(3_000_000);

            assertThat(Duration.ofMillis(slowestMillis(index, topicOfTwelveNames(letter, letter.equals("a") ? "b" : "\u00ea", 10))))
                .as(letter).isLessThan(Duration.ofMillis(SCORING_LIMIT_MILLIS / 2));
        }
    }

    @Test
    void theFollowUpReadingOfAMessageDoesNotSearchTheDocumentsAgainForWhatTheQuestionAlreadySearchedFor() {
        AssistantDocsIndex index = indexOf(page("p", "发票", "帮助", "", "## 开票\n在发票页面申请开票。invoice_number_field 是发票编号。"));
        List<String> searched = new java.util.concurrent.CopyOnWriteArrayList<>();
        index.searchHook = searched::add;
        String topic = "invoice_number_field 发票";

        assertThat(ask(index, topic).chunks()).isNotEmpty();
        assertThat(searched).as("the terms of a question with one reading").contains("invoice_number_field").doesNotHaveDuplicates();
        searched.clear();

        // None of the words of "再讲讲" is a word of the documents: the question is read again as a follow-up of the topic.
        assertThat(askAfter(index, "再讲讲", topic).chunks()).as("found by the follow-up reading").isNotEmpty();
        assertThat(searched).as("the topic is in both readings and the chunks are gone through for it once")
            .contains("invoice_number_field").doesNotHaveDuplicates();
    }

    @Test
    void ofTheLongTermsOfAQuestionNoMoreThanTheLimitAreLookedForWhicheverReadingsTheyBelongTo() {
        java.util.Set<String> admitted = new java.util.HashSet<>();
        List<AssistantDocsIndex.Term> main = new ArrayList<>();
        List<AssistantDocsIndex.Term> followUp = new ArrayList<>();
        for (int number = 0; number < AssistantDocsIndex.MAX_LONG_TERMS; number++) {
            main.add(new AssistantDocsIndex.Term("known_identifier_" + number, 1.0, true, false, false, java.util.Set.of()));
            followUp.add(new AssistantDocsIndex.Term("known_identifier_" + number, 1.0, false, false, false, java.util.Set.of()));
        }
        followUp.add(new AssistantDocsIndex.Term("another_known_identifier", 9.0, false, false, false, java.util.Set.of()));

        assertThat(AssistantDocsIndex.searchable(main, text -> true, admitted)).hasSize(AssistantDocsIndex.MAX_LONG_TERMS);
        assertThat(AssistantDocsIndex.searchable(followUp, text -> true, admitted)).extracting(AssistantDocsIndex.Term::text)
            .as("the twelve of the first reading, which cost nothing to look for again; the thirteenth is not part of the question")
            .hasSize(AssistantDocsIndex.MAX_LONG_TERMS).doesNotContain("another_known_identifier");
    }

    @Test
    void theBlocksOfIdeographsAreToldApartFromEverythingElseAsTheUnicodeBlocksOfTheJdkDo() {
        for (int value = Character.MIN_VALUE; value <= Character.MAX_VALUE; value++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of((char) value);
            boolean expected = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
            assertThat(AssistantDocsIndex.isCjk((char) value)).as("U+%04X", value).isEqualTo(expected);
        }
    }

    /**
     * Names and words that are no longer than a name may be, and that the string search finds slow where the text is a run
     * of the letters they end with: a question of 15 of them took 450 ms with the string search, a hundred times what the
     * linear matcher takes. They are words of the index (a page lists them), which is what lets them through the checks.
     */
    private static java.util.Map<String, String> longestNamesQuestions() {
        java.util.Map<String, String> questions = new java.util.LinkedHashMap<>();
        questions.put("identifiers of the greatest length", joined(15, number -> "x0_" + "a".repeat(40 + number)));
        questions.put("dotted numbers of the greatest length", joined(15, number -> "x.1_" + "a".repeat(40 + number)));
        questions.put("words of the greatest length", joined(15, number -> "a".repeat(30 + number)));
        return questions;
    }

    /**
     * 31 identifiers of at most 16 characters a question: the vocabulary has nothing to say against them (a page lists
     * the runs of letters they are made of, or they are made of single letters), and the string search was what they were
     * looked for with, 16 comparisons for each character of a text of runs.
     */
    private static java.util.Map<String, String> shortNamesQuestions(String run) {
        java.util.Map<String, String> questions = new java.util.LinkedHashMap<>();
        questions.put("short names, the run first", joined(31, number -> run.repeat(13) + "_" + oneCharacter(number)));
        questions.put("short names, the run last", joined(31, number -> oneCharacter(number) + "_" + run.repeat(13)));
        questions.put("short names of single letters", joined(31, number -> (run + "_").repeat(7) + oneCharacter(number)));
        questions.put("short dotted names", joined(31, number -> run.repeat(14) + "." + oneCharacter(number)));
        return questions;
    }

    /** 31 plain words of 16 characters: 15 of the letter and a last character that differs. No page needs more than to list them. */
    private static String plainWords(String letter) {
        return joined(31, number -> letter.repeat(15) + oneCharacter(number));
    }

    /** What a page lists so that the plain words, and the words of 2 to 16 letters that phrases are made of, are words of the index. */
    private static String plainWordsListing(String letter) {
        return wordsOf(letter) + " " + plainWords(letter);
    }

    /**
     * Words that are plain words, no identifiers, of at most 16 characters: the vocabulary has nothing to say against them
     * once a page lists them, and the string search compared up to 16 characters at every place of a text of runs of their
     * letter for each of them (31 words a question, and the phrases of up to 16 characters that two of the shorter ones make).
     */
    private static java.util.Map<String, String> plainWordsQuestions(String letter) {
        java.util.Map<String, String> questions = new java.util.LinkedHashMap<>();
        int[] lengths = {7, 8, 6, 9, 5, 10, 4, 11, 3};
        questions.put("31 plain words of 16 characters", plainWords(letter));
        questions.put("31 plain words of 16 characters, apart", plainWords(letter).replace(' ', '，'));
        questions.put("phrases of 16 characters", joined(lengths.length, number -> letter.repeat(lengths[number])));
        return questions;
    }

    /** A page that lists the words of one letter, 2 to 16 characters long. */
    private static String wordsOf(String letter) {
        StringBuilder words = new StringBuilder("词表");
        for (int length = 2; length <= 16; length++) {
            words.append(' ').append(letter.repeat(length));
        }
        return words.toString();
    }

    /**
     * 200 pages whose every field is at its cap once folded: U+FB00 is "ff", so the text is runs of "f" twice as long as
     * it looks, and the characters of the index are the most there can be (8 million) of the plainest kind.
     */
    private static List<Document> foldedPagesAtTheirCaps(String vocabulary) {
        String ligature = "\uFB00";
        List<Document> pages = new ArrayList<>();
        pages.add(page("words", "词表", "分类", "摘要", vocabulary));
        for (int number = 0; number < 199; number++) {
            StringBuilder body = new StringBuilder("# ").append(ligature.repeat(99)).append(number).append('\n')
                .append("## ").append(ligature.repeat(99)).append('\n')
                .append("### ").append(ligature.repeat(99)).append('\n');
            while (body.length() < 99_000) {
                body.append(ligature.repeat(39)).append('\n');
            }
            pages.add(page("ff-" + number, new Content(ligature.repeat(96) + number, ligature.repeat(60), ligature.repeat(240),
                body.toString(), number, null)));
        }
        return pages;
    }

    /**
     * Run by {@link #aQuestionIsAnsweredQuicklyOnAPlatformWhoseStringSearchIsNotSpedUp} (no arguments), in a JVM that has no
     * fast String.indexOf, and by {@link #theWholeTextOfTheDocumentationNeverHasToFitInTheHeapAtOnce} ({@code heap}), in a
     * JVM with a small heap.
     */
    public static void main(String[] arguments) {
        if (arguments.length > 0 && arguments[0].equals("heap")) {
            loadTheLargestDocumentation();
            return;
        }
        StringBuilder vocabulary = new StringBuilder("词表");
        for (int length = 20; length <= 70; length++) {
            vocabulary.append(' ').append("a".repeat(length));
        }
        AssistantDocsIndex index = indexOf(runPages(vocabulary.toString()));
        ask(index, "词表");
        System.out.println("characters=" + index.searchCharacters());
        System.out.println("worst=" + slowestMillis(index, longestNamesQuestions()));

        // The text of the documentation's own language, every field at its cap, and a text of names made of one letter.
        AssistantDocsIndex chinese = indexOf(chinesePagesAtTheirCaps());
        ask(chinese, "标题");
        System.out.println("chinese-characters=" + chinese.searchCharacters());
        System.out.println("chinese-worst=" + slowestMillis(chinese, topicOfTwelveNames("aaaa", "bbbb", 4)));
        AssistantDocsIndex joined = indexOf(runPages("词表", (("\u00e9" + "_").repeat(795) + "\n\n").repeat(62)));
        ask(joined, "词表");
        System.out.println("joined-characters=" + joined.searchCharacters());
        System.out.println("joined-worst=" + slowestMillis(joined, topicOfTwelveNames("\u00e9", "\u00ea", 10)));

        // Identifiers of at most 16 characters, over runs of a letter, over a text of single letters between underscores,
        // and over the most characters an index can hold.
        AssistantDocsIndex runs = indexOf(runPages(wordsOf("a")));
        ask(runs, "词表");
        System.out.println("short-characters=" + runs.searchCharacters());
        System.out.println("short-worst=" + slowestMillis(runs, shortNamesQuestions("a")));
        AssistantDocsIndex underscores = indexOf(runPages("词表", ("a_".repeat(795) + "\n\n").repeat(62)));
        ask(underscores, "词表");
        System.out.println("short-joined-characters=" + underscores.searchCharacters());
        System.out.println("short-joined-worst=" + slowestMillis(underscores, shortNamesQuestions("a")));
        AssistantDocsIndex ligatures = indexOf(foldedPagesAtTheirCaps(wordsOf("f")));
        ask(ligatures, "词表");
        System.out.println("short-folded-characters=" + ligatures.searchCharacters());
        System.out.println("short-folded-worst=" + slowestMillis(ligatures, shortNamesQuestions("f")));

        // Plain words and phrases of at most 16 characters that a page lists, over runs of their letter and over the most
        // characters an index can hold.
        AssistantDocsIndex plain = indexOf(runPages(plainWordsListing("a")));
        ask(plain, "词表");
        System.out.println("plain-characters=" + plain.searchCharacters());
        System.out.println("plain-worst=" + slowestMillis(plain, plainWordsQuestions("a")));
        AssistantDocsIndex plainFolded = indexOf(foldedPagesAtTheirCaps(plainWordsListing("f")));
        ask(plainFolded, "词表");
        System.out.println("plain-folded-characters=" + plainFolded.searchCharacters());
        System.out.println("plain-folded-worst=" + slowestMillis(plainFolded, plainWordsQuestions("f")));
    }

    @Test
    void aQuestionIsAnsweredQuicklyOnAPlatformWhoseStringSearchIsNotSpedUp() throws Exception {
        // The cost of String.indexOf where a long prefix of the term matches at many places depends on the JIT having an
        // intrinsic for it (_indexOfL, _indexOfU, _indexOfUL for a string whose length is not known, _indexOfIL, _indexOfIU,
        // _indexOfIUL for the one with a start, which the search of the pages calls). A JVM without them is what a platform
        // without them is; the bound must not rely on it, nor on the one for a single character that the matcher of long
        // names jumps with.
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(javaBinary.toString(), "-XX:+UnlockDiagnosticVMOptions",
            "-XX:DisableIntrinsic=_indexOfL,_indexOfU,_indexOfUL,_indexOfL_char,_indexOfU_char,_indexOfIL,_indexOfIU,_indexOfIUL",
            "-Xmx768m", "-cp",
            System.getProperty("java.class.path"), AssistantDocsIndexTest.class.getName()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as(output).isTrue();

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).as("the index was full").containsPattern("(?m)^characters=[3-9]\\d{6}");
        assertThat(output).as("the index of Chinese text was full").containsPattern("(?m)^chinese-characters=[6-9]\\d{6}");
        assertThat(output).as("the index of joined letters was full").containsPattern("(?m)^joined-characters=[3-9]\\d{6}");
        assertThat(output).as("the index of runs was full").containsPattern("(?m)^short-characters=[3-9]\\d{6}");
        assertThat(output).as("the index of single letters was full").containsPattern("(?m)^short-joined-characters=[3-9]\\d{6}");
        assertThat(output).as("the index of ligatures was full").containsPattern("(?m)^short-folded-characters=[7-9]\\d{6}");
        assertThat(output).as("the index of runs for plain words was full").containsPattern("(?m)^plain-characters=[3-9]\\d{6}");
        assertThat(output).as("the index of ligatures for plain words was full").containsPattern("(?m)^plain-folded-characters=[7-9]\\d{6}");
        for (String measure : List.of("worst", "chinese-worst", "joined-worst", "short-worst", "short-joined-worst", "short-folded-worst",
            "plain-worst", "plain-folded-worst")) {
            java.util.regex.Matcher worst = java.util.regex.Pattern.compile("(?m)^" + measure + "=(\\d+)").matcher(output);
            assertThat(worst.find()).as(output).isTrue();
            assertThat(Long.parseLong(worst.group(1))).as(measure + ": " + output).isLessThan(SCORING_LIMIT_MILLIS / 2);
        }
    }

    /**
     * The largest documentation the limits allow: 200 pages of a title and two bodies of 100,000 characters of text that is
     * not repeated (random Chinese characters in both, so that the strings are two bytes a character and the vocabulary is
     * large), made one at a time as they are asked for, the way the database delivers them. At the last page, with the
     * pages before it in the index's hands, the heap in use is reported: it holds what the snapshot will keep and the page
     * in hand, and not the text of the pages that are read.
     */
    private static void loadTheLargestDocumentation() {
        Random random = new Random(42);
        Flux<Document> pages = Flux.range(0, 200).map(number -> {
            StringBuilder chinese = new StringBuilder("## 标题").append(number).append('\n');
            for (int index = 0; index < 99_990; index++) {
                chinese.append(index % 40 == 39 ? '\n' : (char) (0x4E00 + random.nextInt(3000)));
            }
            StringBuilder english = new StringBuilder("## Title ").append(number).append('\n');
            for (int index = 0; index < 99_990; index++) {
                english.append(index % 40 == 39 ? '\n' : (char) (0x4E00 + random.nextInt(3000)));
            }
            return page("page-" + number, new Content("页面" + number, "分类", "摘要", chinese.toString(), number,
                new Translation("Page " + number, "Category", "Summary", english.toString())));
        });
        AssistantDocsIndex index = AssistantDocsIndex.streaming(NO_FILES, () -> pages, Mono::empty, new TestClock());
        index.pageHook = document -> {
            if (document.id().equals("page-199")) {
                System.gc();
                Runtime runtime = Runtime.getRuntime();
                System.out.println("heap-at-last-page=" + (runtime.totalMemory() - runtime.freeMemory()));
            }
        };
        Retrieval found = index.retrieve("标题" + 7, "").block(Duration.ofMinutes(2));
        System.out.println("titles=" + found.pageTitles().size() + " characters=" + index.searchCharacters());
    }

    @Test
    void theWholeTextOfTheDocumentationNeverHasToFitInTheHeapAtOnce() throws Exception {
        // 200 pages of 200,000 characters are 40 million characters, 80 MB of Chinese text, several times that as rows of
        // escaped JSON and again as the chunks of every page before the share of each is known. The chunks that are kept
        // are 3.2 million characters. The JVM exits, as in production (docs/PERFORMANCE.md), if it runs out of memory.
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(javaBinary.toString(), "-Xmx" + HEAP_FOR_THE_LARGEST_DOCUMENTATION_MEGABYTES + "m",
            "-XX:+ExitOnOutOfMemoryError", "-cp", System.getProperty("java.class.path"),
            AssistantDocsIndexTest.class.getName(), "heap").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor(3, TimeUnit.MINUTES)).as(output).isTrue();

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).as("every page is listed, and the chunks fill the index").containsPattern("titles=200 characters=[2-9]\\d{6}");
        java.util.regex.Matcher heap = java.util.regex.Pattern.compile("heap-at-last-page=(\\d+)").matcher(output);
        assertThat(heap.find()).as(output).isTrue();
        // 20 MB or so: the chunks kept so far, the vocabulary of none yet, the page in hand. The 200 English bodies of the
        // pages before it, which nothing uses once they are parsed, are another 40 MB.
        assertThat(Long.parseLong(heap.group(1))).as("heap in use at the last page: " + output)
            .isLessThan(HEAP_IN_USE_AT_THE_LAST_PAGE_MEGABYTES * 1024 * 1024);
    }

    private static final long HEAP_IN_USE_AT_THE_LAST_PAGE_MEGABYTES = 40;

    private static final int HEAP_FOR_THE_LARGEST_DOCUMENTATION_MEGABYTES = 112;

    @Test
    void pagesDeliveredOneAtATimeMakeTheSnapshotOfPagesDeliveredTogetherEvenWhereTheyAreMoreThanFitAndAreCutDownAsTheyCome() {
        // 40 pages of very different lengths in two languages that differ: far more than 2000 chunks, so that the share of
        // each page shrinks as pages are added and is a different one at every step.
        Random random = new Random(5);
        List<Document> pages = new ArrayList<>();
        for (int number = 0; number < 40; number++) {
            StringBuilder chinese = new StringBuilder();
            StringBuilder english = new StringBuilder();
            for (int section = 0, sections = 5 + random.nextInt(120); section < sections; section++) {
                chinese.append("## 小节").append(number).append("甲").append(section).append("\n")
                    .append(("说明文字" + number + "x" + section + "。").repeat(8 + random.nextInt(60))).append("\n\n");
            }
            for (int section = 0, sections = 5 + random.nextInt(120); section < sections; section++) {
                english.append("## Section ").append(number).append("a").append(section).append("\n")
                    .append(("Explanation text" + number + "x" + section + ". ").repeat(8 + random.nextInt(60))).append("\n\n");
            }
            pages.add(page("p" + number, new Content("页面" + number, "分类", "摘要", chinese.toString(), number,
                number % 3 == 0 ? null : new Translation("Page " + number, "Category", "Summary", english.toString()))));
        }
        AssistantDocsIndex together = indexOf(pages);
        AssistantDocsIndex oneByOne = AssistantDocsIndex.streaming(NO_FILES, () -> Flux.fromIterable(pages), Mono::empty, new TestClock());

        for (String question : List.of("小节3甲5", "小节17甲60", "小节39甲0", "说明文字12x3", "Section 21a7", "Explanation text5x2", "页面9")) {
            Retrieval expected = ask(together, question);
            assertThat(ask(oneByOne, question)).as(question).isEqualTo(expected);
        }
        long source = 0;
        for (Document document : pages) {
            source += AssistantDocsChunker.parse(document.content().body(), 1).sections().size();
            if (document.content().english() != null) {
                source += AssistantDocsChunker.parse(document.content().english().body(), 1).sections().size();
            }
        }
        assertThat(source).as("more chunks than the index keeps, so that the share of every page shrinks as pages come")
            .isGreaterThan(AssistantDocsIndex.MAX_PAGE_CHUNKS * 3L / 2);
        assertThat(oneByOne.retainedCharacters()).isEqualTo(together.retainedCharacters());
        assertThat(oneByOne.searchCharacters()).isEqualTo(together.searchCharacters());
        assertThat(oneByOne.vocabularySizes()).isEqualTo(together.vocabularySizes());
    }

    @Test
    void aWordOrNameLongerThanAnyNameIsNotLookedForAndMakesNoPhrase() {
        String atTheLimit = "a".repeat(AssistantDocsIndex.MAX_TERM_CHARS);
        String beyond = "a".repeat(AssistantDocsIndex.MAX_TERM_CHARS + 1);

        assertThat(AssistantDocsIndex.terms(atTheLimit + " 发票")).contains(atTheLimit, "发票");
        assertThat(AssistantDocsIndex.terms(beyond + " 发票")).doesNotContain(beyond).contains("发票");
        assertThat(AssistantDocsIndex.terms(beyond.substring(0, 40) + "_" + "b".repeat(30))).as("not cut: neither the whole nor a part")
            .isEmpty();
        assertThat(AssistantDocsIndex.terms("x".repeat(70) + ".1_b")).isEmpty();
        assertThat(AssistantDocsIndex.terms("bc" + "_".repeat(100))).as("trailing underscores are not part of the name").contains("bc");
        assertThat(AssistantDocsIndex.phrases("alpha " + atTheLimit + " beta")).containsExactly("alpha " + atTheLimit, atTheLimit + " beta");
        assertThat(AssistantDocsIndex.phrases("alpha " + beyond + " beta")).as("no phrase with it or across it").isEmpty();
        assertThat(AssistantDocsIndex.phrases("alpha beta")).containsExactly("alpha beta");
    }

    @Test
    void aQuestionWithAnEnormousNameStillFindsWhatTheRestOfItAsksFor() {
        AssistantDocsIndex index = help();
        Retrieval plain = ask(index, "怎么创建 API Key");
        Retrieval withName = ask(index, "怎么创建 API Key " + "x".repeat(300) + "_" + "y".repeat(300) + " " + "z".repeat(500));

        assertThat(plain.chunks()).isNotEmpty();
        assertThat(withName.chunks()).isEqualTo(plain.chunks());
    }

    @Test
    void aPhraseIsLookedForOnlyWhereBothOfItsWordsAreWordsOfTheIndex() {
        AssistantDocsIndex index = indexOf(page("p", "发票", "帮助", "", "## 开票\nClaude Code 和 keys 的说明，还有 gpt-5.5 与 config.toml。"));
        ask(index, "发票");

        assertThat(index.mayOccur("claude code")).as("both are words of the index").isTrue();
        assertThat(index.mayOccur("code keys")).isTrue();
        assertThat(index.mayOccur("claude key")).as("the second as a stem of the word with an ending").isTrue();
        assertThat(index.mayOccur("claude missing")).as("the second is nowhere").isFalse();
        assertThat(index.mayOccur("missing code")).as("the first is nowhere").isFalse();
        assertThat(index.mayOccur("missing absent")).isFalse();
        assertThat(index.mayOccur("gpt-5.5")).as("an identifier may stand in front of a longer one").isTrue();
        assertThat(index.mayOccur("config.toml")).isTrue();
        assertThat(index.mayOccur("nowhere")).as("a word").isFalse();
        assertThat(index.mayOccur("keys")).isTrue();
    }

    /** A different letter or digit for each number below 35: the last part of a name, which is no word. */
    private static String oneCharacter(int number) {
        return String.valueOf("bcdefghijklmnopqrstuvwxyz0123456789".charAt(number));
    }

    @Test
    void anIdentifierOfWhichAWordIsNoWordOfTheDocumentsIsNotLookedForHoweverShortItIs() {
        // The pages are runs of one letter and one page that lists some words: a name is found only where each of its
        // words is a whole word of the text, so a name with a word that is nowhere is nowhere.
        AssistantDocsIndex index = indexOf(runPages("词表 claude code gpt-5.5 config.toml api_key_id"));
        ask(index, "词表");

        for (String name : List.of("aaaaaaaaaaaaa_x", "x_aaaaaaaaaaaaa", "aaaaaaaaaaaaaa.x", "claude_missing", "missing.toml", "gpt-5.x_nowhere")) {
            assertThat(index.mayOccur(name)).as(name).isFalse();
        }
        for (String name : List.of("claude_code", "config.toml", "gpt-5.5", "api_key_id", "code.x", "a_b_c", "x.1_y", "claude-5.5")) {
            assertThat(index.mayOccur(name)).as(name + ": every word of it is a word of the documents, or a single character").isTrue();
        }
        List<String> searched = new java.util.concurrent.CopyOnWriteArrayList<>();
        index.searchHook = searched::add;
        ask(index, joined(31, number -> "aaaaaaaaaaaaa_" + oneCharacter(number)));
        assertThat(searched).as("a question of such names searches the pages for none of them")
            .noneMatch(term -> term.contains("_") || term.contains("."));
    }

    @Test
    void anIdentifierMadeOfWordsOfTheDocumentsAndNotOneOfTheirLeafHeadingsIsStillLookedFor() {
        // The section of a chunk is cut where the folded headings are far longer than they were (U+FDFA is 18 characters), and
        // can leave the last heading out; the heading is kept to search in on its own and its words are words of the index.
        String body = "# " + "\uFDFA".repeat(100) + "\n## 二级\n### leaf_alpha_beta 标题\n正文内容。";
        AssistantDocsIndex index = indexOf(page("p", "页面", "分类", "摘要", body));
        ask(index, "页面");

        assertThat(index.mayOccur("leaf_alpha_beta")).isTrue();
        assertThat(index.mayOccur("leaf_alpha_missing")).isFalse();
    }

    @Test
    void ofTheIdentifiersOfAQuestionNoMoreThanTheLimitAreLookedForHoweverShortTheyAre() {
        // Single letters between the underscores: the vocabulary has nothing to say against any of these names.
        AssistantDocsIndex index = indexOf(page("p", "词表", "分类", "摘要", "## 名字\na_b_c_d 和 a_b_c_e 是名字。"));
        ask(index, "词表");
        List<String> searched = new java.util.concurrent.CopyOnWriteArrayList<>();
        index.searchHook = searched::add;

        ask(index, joined(31, number -> "a_b_c_" + oneCharacter(number)));

        assertThat(searched).as("the long terms and the identifiers together").hasSize(AssistantDocsIndex.MAX_LONG_TERMS)
            .allMatch(term -> term.startsWith("a_b_c_"));
        List<AssistantDocsIndex.Term> names = new ArrayList<>();
        for (int number = 0; number < AssistantDocsIndex.MAX_LONG_TERMS + 3; number++) {
            names.add(new AssistantDocsIndex.Term("a_b_" + number, 4.0, false, false, false, java.util.Set.of()));
        }
        assertThat(AssistantDocsIndex.searchable(names, text -> true)).hasSize(AssistantDocsIndex.MAX_LONG_TERMS);
    }

    @Test
    void ofTheLongTermsOfAQueryTheHeaviestAreLookedForAndTheRestAreNotPartOfTheQuestion() {
        java.util.List<AssistantDocsIndex.Term> terms = new ArrayList<>();
        // Short terms and long ones that could not occur are never counted against the limit.
        terms.add(new AssistantDocsIndex.Term("short", 1.0, false, false, false, java.util.Set.of()));
        terms.add(new AssistantDocsIndex.Term("a".repeat(30) + "_unknown", 4.0, false, false, false, java.util.Set.of()));
        for (int number = 0; number < AssistantDocsIndex.MAX_LONG_TERMS + 3; number++) {
            double weight = number % 2 == 0 ? 1.0 : 4.0;
            terms.add(new AssistantDocsIndex.Term("known_identifier_" + number, weight, false, false, false, java.util.Set.of()));
        }

        List<String> kept = AssistantDocsIndex.searchable(terms, text -> !text.endsWith("_unknown")).stream()
            .map(AssistantDocsIndex.Term::text).toList();

        assertThat(kept).hasSize(terms.size() - 3).contains("short", "a".repeat(30) + "_unknown");
        // Fifteen long terms, seven of weight 4 (odd numbers) and eight of weight 1: the last three of weight 1 are the ones left out.
        assertThat(kept).doesNotContain("known_identifier_14", "known_identifier_12", "known_identifier_10")
            .contains("known_identifier_8", "known_identifier_13", "known_identifier_1");
        assertThat(AssistantDocsIndex.searchable(terms.subList(0, 1 + AssistantDocsIndex.MAX_LONG_TERMS), text -> true))
            .as("up to the limit nothing is left out").hasSize(1 + AssistantDocsIndex.MAX_LONG_TERMS);
    }

    @Test
    void aQuestionOfPlainWordsAndPhrasesOfAtMostSixteenCharactersIsAnsweredQuicklyWhereverTheTextIsRunsOfTheirLetter() {
        // A page lists the words, so that the vocabulary lets them through; the other pages are runs of the letter they
        // begin with, or the ligatures that fold to it, to the caps of the index. 39 terms: the words and their phrases.
        for (java.util.Map.Entry<String, List<Document>> hostile : java.util.Map.of(
            "a", runPages(plainWordsListing("a")), "f", foldedPagesAtTheirCaps(plainWordsListing("f"))).entrySet()) {
            AssistantDocsIndex index = indexOf(hostile.getValue());
            ask(index, "词表");
            assertThat(index.searchCharacters()).as("the index is full").isGreaterThan(3_000_000);

            assertThat(Duration.ofMillis(slowestMillis(index, plainWordsQuestions(hostile.getKey()))))
                .as(hostile.getKey()).isLessThan(Duration.ofMillis(SCORING_LIMIT_MILLIS));
        }
    }

    @Test
    void everyTermButAPairOrALoneCharacterOfIdeographsIsLookedForWithTheLinearMatcher() {
        for (String term : List.of("key", "aaaaaaaaaaaaaaab", "aaaaaaa aaaaaaaa", "api key", "api_key", "gpt-5.5", "config.toml",
            "x", "é", "ﬀ", "a".repeat(AssistantDocsIndex.LINEAR_TERM_CHARS + 1), "7", "kérnel")) {
            assertThat(AssistantDocsIndex.usesLinearMatcher(term)).as(term).isTrue();
        }
        for (String term : List.of("文件", "删", "发票", "株甲")) {
            assertThat(AssistantDocsIndex.usesLinearMatcher(term)).as(term).isFalse();
        }
    }

    @Test
    void aPhraseIsFoundByTheLinearMatcherExactlyWhereTheStringSearchFindsIt() {
        Random random = new Random(29);
        for (String alphabet : List.of("aabb  s_.-1é", "aa  b  ", "ab ab ab ab", "aab  \u337f\u00e9s")) {
            for (int round = 0; round < 40_000; round++) {
                StringBuilder builder = new StringBuilder();
                for (int length = random.nextInt(90); length > 0; length--) {
                    builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                String text = builder.toString();
                String term = randomWord(random) + " " + randomWord(random);
                int[] failure = AssistantDocsIndex.failureTable(term);
                java.util.BitSet mask = AssistantDocsIndex.wordMask(text);
                for (int limit : new int[] {1, 3, 100}) {
                    int expected = AssistantDocsIndex.occurrences(text, null, term, limit);
                    assertThat(AssistantDocsIndex.occurrences(text, null, term, limit, failure))
                        .as("'%s' in '%s' by the linear matcher, up to %d", term, text, limit).isEqualTo(expected);
                    assertThat(AssistantDocsIndex.occurrences(text, mask, term, limit, failure))
                        .as("'%s' in '%s' by the linear matcher and the mask, up to %d", term, text, limit).isEqualTo(expected);
                }
            }
        }
    }

    /** A word of one to four letters of {@code ab} and {@code s} (an ending), the way a question writes the words of a phrase. */
    private static String randomWord(Random random) {
        StringBuilder word = new StringBuilder();
        for (int length = 1 + random.nextInt(4); length > 0; length--) {
            word.append("aabs".charAt(random.nextInt(4)));
        }
        return word.toString();
    }

    @Test
    void aTermIsFoundByTheLinearMatcherExactlyWhereTheStringSearchFindsIt() {
        // The second alphabet is mostly letters and underscores: long runs of an identifier, in which no whole name can begin.
        for (String alphabet : List.of("aabbab _-.1é汉s", "aab__b_-.é_a_ \u337f\u00e9")) {
            assertTheLinearMatcherFindsWhatTheStringSearchFinds(alphabet);
        }
    }

    private static void assertTheLinearMatcherFindsWhatTheStringSearchFinds(String alphabet) {
        Random random = new Random(13);
        for (int round = 0; round < 60_000; round++) {
            StringBuilder builder = new StringBuilder();
            for (int length = random.nextInt(90); length > 0; length--) {
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = builder.toString();
            String term;
            if (random.nextBoolean() && text.length() > 2) {
                int from = random.nextInt(text.length() - 1);
                term = text.substring(from, Math.min(text.length(), from + 1 + random.nextInt(8)));
            } else {
                StringBuilder word = new StringBuilder();
                for (int length = 1 + random.nextInt(5); length > 0; length--) {
                    word.append("ab_-.1".charAt(random.nextInt(6)));
                }
                term = word.toString();
            }
            if (term.isBlank() || term.startsWith("_") || term.contains(" ")) {
                continue;
            }
            int[] failure = AssistantDocsIndex.failureTable(term);
            java.util.BitSet mask = AssistantDocsIndex.wordMask(text);
            for (int limit : new int[] {1, 3, 100}) {
                int expected = AssistantDocsIndex.occurrences(text, null, term, limit);
                assertThat(AssistantDocsIndex.occurrences(text, null, term, limit, failure))
                    .as("'%s' in '%s' by the linear matcher, up to %d", term, text, limit).isEqualTo(expected);
                assertThat(AssistantDocsIndex.occurrences(text, mask, term, limit, failure))
                    .as("'%s' in '%s' by the linear matcher and the mask, up to %d", term, text, limit).isEqualTo(expected);
                assertThat(AssistantDocsIndex.occurrences(text, mask, term, limit))
                    .as("'%s' in '%s' by the string search and the mask, up to %d", term, text, limit).isEqualTo(expected);
            }
        }
    }

    @Test
    void theLinearMatcherDoesNotLookAtAnyPlaceTwiceHoweverPeriodicTheTextAndTheTermAre() {
        String text = "a".repeat(200_000);
        String term = "a".repeat(60) + "x";
        int[] failure = AssistantDocsIndex.failureTable(term);
        long start = System.nanoTime();
        for (int round = 0; round < 50; round++) {
            assertThat(AssistantDocsIndex.occurrences(text, null, term, 3, failure)).isZero();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(AssistantDocsIndex.failureTable("abab")).containsExactly(0, 0, 1, 2);
        assertThat(AssistantDocsIndex.failureTable("aaaa")).containsExactly(0, 1, 2, 3);
        assertThat(AssistantDocsIndex.failureTable("x")).containsExactly(0);
        assertThat(AssistantDocsIndex.occurrences("aaaa x aaaa", null, "aaa", 5, AssistantDocsIndex.failureTable("aaa")))
            .as("a place that begins inside the previous match is passed over, as by the string search").isEqualTo(
                AssistantDocsIndex.occurrences("aaaa x aaaa", null, "aaa", 5));
    }

    // ---- where the questions are scored -------------------------------------------------------------------------------

    @Test
    void aQuestionIsScoredEvenWhenEveryWorkerOfReactorsParallelSchedulerIsBusy() throws Exception {
        // Reactor's timers (every Mono.delay and timeout of the application) run on the workers of the parallel scheduler.
        AssistantDocsIndex index = indexOf(page("p", "发票", "帮助", "", "## 开票\n在发票页面申请开票。"));
        assertThat(ask(index, "发票").chunks()).hasSize(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        List<reactor.core.Disposable> busy = new ArrayList<>();
        // One task for each worker, so that all of them are held; a question that needs one of them waits for it.
        for (int worker = 0; worker < Schedulers.DEFAULT_POOL_SIZE; worker++) {
            busy.add(Schedulers.parallel().schedule(() -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        try {
            Retrieval during = index.retrieve("发票", "").block(Duration.ofSeconds(5));
            assertThat(during.chunks()).as("scored while every parallel worker is held").hasSize(1);
        } finally {
            release.countDown();
            busy.forEach(reactor.core.Disposable::dispose);
        }
    }

    @Test
    void aQuestionThatFindsNoThreadAndNoPlaceInTheQueueIsAnsweredWithoutExcerptsAtOnce() throws Exception {
        AssistantDocsIndex index = indexOf(page("p", "发票", "帮助", "", "## 开票\n在发票页面申请开票。"));
        assertThat(ask(index, "发票").chunks()).hasSize(1);
        Scheduler narrow = Schedulers.newBoundedElastic(1, 1, "narrow-search", 60, true);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        index.searchScheduler = narrow;
        try {
            narrow.schedule(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).as("the only thread is busy, and the queue is empty").isTrue();
            CompletableFuture<Retrieval> waiting = index.retrieve("发票", "").toFuture();
            Thread.sleep(100);
            assertThat(waiting).as("waits for the only thread").isNotDone();

            long start = System.nanoTime();
            Retrieval turnedAway = index.retrieve("发票", "").block(Duration.ofSeconds(5));
            assertThat(turnedAway.chunks()).as("no place in the queue: answered without excerpts, and not left waiting").isEmpty();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));

            release.countDown();
            assertThat(waiting.get(5, TimeUnit.SECONDS).chunks()).as("the one that waited is answered in its turn").hasSize(1);
        } finally {
            release.countDown();
            narrow.dispose();
        }
    }


    @Test
    void aWordIsFoundWhereARunOfLettersIsTheWordOrTheWordWithAnEndingWhetherTheRunsAreCrossedByMaskOrByCharacter() {
        Random random = new Random(7);
        String alphabet = "aabbsedaing  _-.1é汉";
        List<String> endings = List.of("s", "es", "d", "ed", "ing");
        for (int round = 0; round < 30_000; round++) {
            StringBuilder builder = new StringBuilder();
            for (int length = random.nextInt(70); length > 0; length--) {
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String text = builder.toString();
            StringBuilder word = new StringBuilder();
            for (int length = 2 + random.nextInt(3); length > 0; length--) {
                word.append("abse".charAt(random.nextInt(4)));
            }
            String term = word.toString();
            // What the rule says, without the scanning: split the text into its runs of letters and digits and count the
            // runs that are the word or the word and one of the endings.
            int expected = 0;
            for (String run : text.split("[^\\p{L}\\p{N}]|[\u6c49]")) {
                if (run.equals(term) || (run.startsWith(term) && endings.contains(run.substring(term.length())))) {
                    expected++;
                }
            }
            for (int limit : new int[] {1, 3}) {
                assertThat(AssistantDocsIndex.occurrences(text, null, term, limit)).as("'%s' in '%s' by character, up to %d", term, text, limit)
                    .isEqualTo(Math.min(limit, expected));
                assertThat(AssistantDocsIndex.occurrences(text, AssistantDocsIndex.wordMask(text), term, limit))
                    .as("'%s' in '%s' by mask, up to %d", term, text, limit).isEqualTo(Math.min(limit, expected));
                int[] failure = AssistantDocsIndex.failureTable(term);
                assertThat(AssistantDocsIndex.occurrences(text, null, term, limit, failure))
                    .as("'%s' in '%s' by the linear matcher, up to %d", term, text, limit).isEqualTo(Math.min(limit, expected));
                assertThat(AssistantDocsIndex.occurrences(text, AssistantDocsIndex.wordMask(text), term, limit, failure))
                    .as("'%s' in '%s' by the linear matcher and the mask, up to %d", term, text, limit)
                    .isEqualTo(Math.min(limit, expected));
            }
        }
    }
}
