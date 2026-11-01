package tech.starryapi.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
import io.netty.util.ResourceLeakDetector;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Subscription;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.assistant.AssistantDocsIndex.Chunk;
import tech.starryapi.assistant.AssistantDocsIndex.Retrieval;
import tech.starryapi.documentation.DocumentationDtos.Content;
import tech.starryapi.documentation.DocumentationDtos.Document;
import tech.starryapi.documentation.DocumentationDtos.Save;
import tech.starryapi.documentation.DocumentationDtos.Translation;
import tech.starryapi.documentation.DocumentationService;
import tech.starryapi.documentation.DocumentationStorage;
import tools.jackson.databind.ObjectMapper;

/**
 * The assistant over the real Flyway schema: which pages it can see, that a publish, unpublish or delete through
 * {@link DocumentationService} reaches it as a committed Spring event, and that the seeded tutorials answer the
 * questions a new user asks. The beans are wired in a real application context, so the transaction proxies and
 * the {@code @TransactionalEventListener} infrastructure are the ones the application uses.
 */
class AssistantDocsPostgreSqlTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Path NO_FILES = Path.of("does-not-exist");
    private static PostgreSQLContainer container;
    private static String r2dbcUrl;

    @TempDir Path attachments;
    private AnnotationConfigApplicationContext context;
    private DocumentationService documentation;
    private AssistantDocsIndex index;
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicInteger fingerprintReads = new AtomicInteger();
    /** What another node does right after this one has read the fingerprint of the published pages. */
    private volatile java.util.function.Supplier<Mono<Void>> afterFingerprintRead = Mono::empty;
    private final AssistantDocsIndexTest.TestClock clock = new AssistantDocsIndexTest.TestClock();

    @Configuration
    @EnableTransactionManagement
    static class Transactions {
    }

    @BeforeAll
    static void migrate() {
        String jdbcUrl = System.getenv("STARRY_DOCS_TEST_JDBC_URL");
        if (jdbcUrl == null) {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker or an isolated STARRY_DOCS_TEST_JDBC_URL is required");
            container = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("starry_api")
                .withUsername("starry_api_bootstrap").withPassword("bootstrap_test")
                .withInitScript("postgresql/init-test-roles.sql");
            container.start();
            jdbcUrl = container.getJdbcUrl();
        }
        Flyway.configure().dataSource(jdbcUrl, "starry_api_migration", "starry_api_migration_test")
            .schemas("starry_api").defaultSchema("starry_api").createSchemas(false)
            .initSql("SET ROLE starry_api_owner").load().migrate();
        r2dbcUrl = jdbcUrl.replace("jdbc:", "r2dbc:").split("\\?")[0]
            .replace("postgresql://", "postgresql://starry_api:starry_api_app_test@") + "?schema=starry_api";
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void start() {
        ConnectionFactory factory = ConnectionFactories.get(r2dbcUrl);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(ConnectionFactory.class, () -> factory);
        context.registerBean(DatabaseClient.class, () -> DatabaseClient.create(factory));
        context.registerBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(factory));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(SystemSettingRepository.class, () ->
            new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory)).getRepository(SystemSettingRepository.class));
        context.registerBean(DocumentationStorage.class, () -> new DocumentationStorage(attachments.toString()));
        context.registerBean(DocumentationService.class);
        // The same wiring as production, except that the loaders are wrapped to count how often they are read and
        // that the clock is the test's own.
        context.registerBean(AssistantDocsIndex.class, () -> AssistantDocsIndex.streamingWithFingerprint(NO_FILES, () -> {
            loads.incrementAndGet();
            return context.getBean(DocumentationService.class).publishedStream();
        }, () -> {
            fingerprintReads.incrementAndGet();
            return context.getBean(DocumentationService.class).publishedFingerprint()
                .flatMap(read -> afterFingerprintRead.get().thenReturn(read));
        }, clock));
        context.refresh();
        documentation = context.getBean(DocumentationService.class);
        index = context.getBean(AssistantDocsIndex.class);
    }

    @AfterEach
    void hideTestDocuments() {
        context.close();
        DatabaseClient.create(ConnectionFactories.get(r2dbcUrl))
            .sql("UPDATE documentation_pages SET deleted = TRUE, published = NULL WHERE draft->>'category' = 'AssistantTest'")
            .fetch().rowsUpdated().block(TIMEOUT);
    }

    private Retrieval ask(String question) {
        return index.retrieve(question, "").block(TIMEOUT);
    }

    private Retrieval ask(String question, String previous) {
        return index.retrieve(question, previous).block(TIMEOUT);
    }

    private String createPage(String title, String body, Translation english, boolean publish) {
        var created = documentation.create(new Content(title, "AssistantTest", "", body, 5, english), "test").block(TIMEOUT);
        if (publish) {
            documentation.save(created.id(), new Save(created.draft(), created.version(), true), "test").block(TIMEOUT);
        }
        return created.id();
    }

    private List<String> pageIds(Retrieval retrieval) {
        return retrieval.chunks().stream().map(Chunk::pageId).distinct().toList();
    }

    /** The earlier user messages are given the latest first, as the chat service gives them. */
    private Retrieval askAfter(String question, String... earlier) {
        return index.retrieveFor(question, List.of(earlier)).block(TIMEOUT);
    }

    private List<String> sections(Retrieval retrieval) {
        return retrieval.chunks().stream().map(Chunk::section).toList();
    }

    // ---- what the assistant may see ----------------------------------------------------------------------------

    @Test
    void onlyPublishedNonDeletedPagesAreSearchableInBothLanguages() {
        String published = createPage("Quasar 网关", "## 用法\n启用 zorblaxzh 之后重启服务。", new Translation(
            "Quasar gateway", "AssistantTest", "", "## Usage\nEnable zorblaxen and restart the service."), true);
        createPage("尚未发布", "## 草稿\n这里写着 draftonlyqq。", null, false);
        String unpublished = createPage("已下线", "## 内容\n这里写着 unpubqq。", null, true);
        String deleted = createPage("已删除", "## 内容\n这里写着 delqq。", null, true);
        String edited = createPage("先发布后改草稿", "## 内容\n已发布的写法 publishedwordqq。", null, true);

        var current = documentation.readAdmin(unpublished).block(TIMEOUT);
        documentation.withdraw(unpublished, current.version(), false, "test").block(TIMEOUT);
        documentation.withdraw(deleted, documentation.readAdmin(deleted).block(TIMEOUT).version(), true, "test").block(TIMEOUT);
        var editedAdmin = documentation.readAdmin(edited).block(TIMEOUT);
        documentation.save(edited, new Save(new Content("先发布后改草稿", "AssistantTest", "", "## 内容\n草稿里的新写法 editeddraftqq。", 5),
            editedAdmin.version(), false), "test").block(TIMEOUT);

        assertThat(pageIds(ask("zorblaxzh"))).containsExactly(published);
        assertThat(ask("zorblaxzh").chunks().getFirst()).as("a message in Latin letters only is answered in English")
            .satisfies(chunk -> {
                assertThat(chunk.language()).isEqualTo("en");
                assertThat(chunk.pageTitle()).isEqualTo("Quasar gateway");
                assertThat(chunk.section()).isEqualTo("Usage");
            });
        assertThat(ask("zorblaxzh").sources()).containsExactly("Docs: Quasar gateway");
        assertThat(ask("zorblaxzh", "怎么启用").chunks().getFirst()).as("but not in a Chinese conversation").satisfies(chunk -> {
            assertThat(chunk.language()).isEqualTo("zh");
            assertThat(chunk.pageTitle()).isEqualTo("Quasar 网关");
            assertThat(chunk.section()).isEqualTo("用法");
        });
        assertThat(ask("zorblaxzh", "怎么启用").sources()).containsExactly("使用文档《Quasar 网关》");
        assertThat(ask("zorblaxen").chunks().getFirst()).satisfies(chunk -> {
            assertThat(chunk.language()).isEqualTo("en");
            assertThat(chunk.pageId()).isEqualTo(published);
        });
        assertThat(ask("zorblaxen").sources()).containsExactly("Docs: Quasar gateway");
        assertThat(ask("draftonlyqq").chunks()).as("a page that was never published").isEmpty();
        assertThat(ask("unpubqq").chunks()).as("an unpublished page").isEmpty();
        assertThat(ask("delqq").chunks()).as("a deleted page").isEmpty();
        assertThat(pageIds(ask("publishedwordqq"))).as("the published copy of an edited page").containsExactly(edited);
        assertThat(ask("editeddraftqq").chunks()).as("the unpublished draft of an edited page").isEmpty();
        assertThat(ask("Quasar").pageTitles()).as("titles of the published pages only")
            .contains("Quasar 网关 / Quasar gateway", "创建与管理 API Key / Create and manage API keys")
            .doesNotContain("尚未发布", "已下线", "已删除");
    }

    // ---- freshness ---------------------------------------------------------------------------------------------

    @Test
    void publishRepublishUnpublishAndDeleteAreSeenAtOnceWithoutWaitingForTheTimeToLive() {
        assertThat(ask("freshwordqq").chunks()).isEmpty();
        assertThat(loads).hasValue(1);
        assertThat(ask("freshwordqq").chunks()).isEmpty();
        assertThat(loads).as("the snapshot is shared between questions").hasValue(1);

        var page = documentation.create(new Content("即时生效", "AssistantTest", "", "## 内容\n第一版 freshwordqq 一。", 5), "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks()).as("creating a draft changes nothing").isEmpty();
        assertThat(loads).hasValue(1);

        var published = documentation.save(page.id(), new Save(page.draft(), page.version(), true), "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks()).singleElement().satisfies(chunk -> assertThat(chunk.body()).contains("第一版"));
        assertThat(loads).as("publish").hasValue(2);

        var draft = documentation.save(page.id(), new Save(new Content("即时生效", "AssistantTest", "", "## 内容\n第二版 freshwordqq 二。", 5),
            published.version(), false), "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks().getFirst().body()).as("saving a draft").contains("第一版");
        assertThat(loads).hasValue(2);

        var republished = documentation.save(page.id(), new Save(draft.draft(), draft.version(), true), "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks().getFirst().body()).as("re-publish").contains("第二版");
        assertThat(loads).hasValue(3);

        documentation.withdraw(page.id(), republished.version(), false, "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks()).as("unpublish").isEmpty();
        assertThat(loads).hasValue(4);

        var again = documentation.readAdmin(page.id()).block(TIMEOUT);
        var back = documentation.save(page.id(), new Save(again.draft(), again.version(), true), "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks()).hasSize(1);
        documentation.withdraw(page.id(), back.version(), true, "test").block(TIMEOUT);
        assertThat(ask("freshwordqq").chunks()).as("delete").isEmpty();
        assertThat(loads).hasValue(6);
    }

    @Test
    void aPublishThatIsRolledBackNeverReachesTheAssistant() {
        assertThat(ask("rollbackwordqq").chunks()).isEmpty();
        assertThat(loads).hasValue(1);
        var page = documentation.create(new Content("回滚", "AssistantTest", "", "## 内容\n rollbackwordqq 出现在这里。", 5), "test").block(TIMEOUT);

        TransactionalOperator.create(context.getBean(ReactiveTransactionManager.class)).execute(transaction -> {
            transaction.setRollbackOnly();
            return documentation.save(page.id(), new Save(page.draft(), page.version(), true), "test");
        }).blockLast(TIMEOUT);

        assertThat(documentation.readAdmin(page.id()).block(TIMEOUT).published()).as("rolled back").isNull();
        assertThat(ask("rollbackwordqq").chunks()).isEmpty();
        assertThat(loads).as("the event waits for a commit that never came").hasValue(1);
    }

    /** No wrapped loader here: the constructor Spring uses in production, injected with the real service. */
    private void useTheApplicationWiring(Path attachmentDirectory, ObjectMapper mapper) {
        context.close();
        context = new AnnotationConfigApplicationContext();
        ConnectionFactory factory = ConnectionFactories.get(r2dbcUrl);
        context.register(Transactions.class);
        context.registerBean(DatabaseClient.class, () -> DatabaseClient.create(factory));
        context.registerBean(ReactiveTransactionManager.class, () -> new R2dbcTransactionManager(factory));
        context.registerBean(ObjectMapper.class, () -> mapper);
        context.registerBean(SystemSettingRepository.class, () ->
            new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory)).getRepository(SystemSettingRepository.class));
        context.registerBean(DocumentationStorage.class, () -> new DocumentationStorage(attachmentDirectory.toString()));
        context.registerBean(DocumentationService.class);
        context.registerBean(AssistantDocsIndex.class);
        context.refresh();
        documentation = context.getBean(DocumentationService.class);
        index = context.getBean(AssistantDocsIndex.class);
    }

    @Test
    void theApplicationWiringHearsTheEventsOfTheDocumentationService(@TempDir Path elsewhere) {
        useTheApplicationWiring(elsewhere, new ObjectMapper());

        assertThat(ask("wiredwordqq").chunks()).isEmpty();
        String id = createPage("接线", "## 内容\n wiredwordqq 出现在这里。", null, true);

        assertThat(pageIds(ask("wiredwordqq"))).containsExactly(id);
    }

    @Test
    void theJsonOfThePublishedPagesIsParsedOnAWorkerThreadNotOnTheDatabaseConnection(@TempDir Path elsewhere) {
        Set<String> parsedOn = ConcurrentHashMap.newKeySet();
        useTheApplicationWiring(elsewhere, new ObjectMapper() {
            @Override
            public <T> T readValue(String content, Class<T> valueType) {
                parsedOn.add(Thread.currentThread().getName());
                return super.readValue(content, valueType);
            }
        });

        assertThat(ask("怎么创建 API Key").chunks()).isNotEmpty();

        assertThat(parsedOn).as("the pages were parsed, 14 of them at least").isNotEmpty();
        assertThat(parsedOn).allSatisfy(thread -> assertThat(thread).as("not reactor-tcp-nio, which delivers the rows").startsWith("boundedElastic"));
    }

    @Test
    void theTutorialsTheBenchmarkReadsAreWhatTheMigrationsPublish() {
        List<Document> published = documentation.publishedPages().block(TIMEOUT);

        assertThat(SeededTutorials.pages()).hasSize(14);
        // Every page this class creates is in the category AssistantTest and is hidden again after each test, so what is
        // left is what a fresh installation publishes. It has to be exactly the pages the benchmark reads, in the order
        // the catalog lists them (the order breaks ties between chunks): a migration that adds, removes or reorders a
        // tutorial fails here, not only one that rewrites a page the benchmark already reads.
        assertThat(published.stream().filter(page -> !"AssistantTest".equals(page.content().category())).map(Document::id).toList())
            .containsExactlyElementsOf(SeededTutorials.pages().stream().map(Document::id).toList());
        for (var expected : SeededTutorials.pages()) {
            assertThat(published).filteredOn(page -> page.id().equals(expected.id())).singleElement()
                .satisfies(page -> assertThat(page.content()).as(expected.id()).isEqualTo(expected.content()));
        }
    }

    // ---- the seeded tutorials (V23, V24 and V30) ---------------------------------------------------------------

    @Test
    void seededTutorialsAnswerWhatNewUsersAsk() {
        for (String[] expectation : new String[][] {
            {"怎么创建 API Key", "api-keys", "使用文档《创建与管理 API Key》"},
            {"如何创建密钥", "api-keys", "使用文档《创建与管理 API Key》"},
            {"how do I create an API key", "api-keys", "Docs: Create and manage API keys"},
            {"怎么配置 Claude Code", "claude-code", "使用文档《手动配置 Claude Code》"},
            {"How do I configure Claude Code?", "claude-code", "Docs: Set up Claude Code"},
            {"Codex CLI 怎么接入", "codex-cli", "使用文档《手动配置 Codex CLI》"},
            {"OpenCode 配置", "opencode", "使用文档《手动配置 OpenCode》"},
            {"CC Switch 一键导入", "cc-switch", "使用文档《CC Switch 一键接入》"},
            {"怎么充值", "billing", "使用文档《充值与计费》"},
            {"Coding Plan 额度用完了会怎样", "coding-plan", "使用文档《使用 Coding Plan 订阅》"},
            {"怎么提交工单", "tickets", "使用文档《如何提交工单》"},
            {"哪里看模型 ID 和价格", "models-and-pricing", "使用文档《选择模型与查看价格》"},
            {"第一次使用要做什么", "getting-started", "使用文档《第一次使用，从这里开始》"},
        }) {
            Retrieval result = ask(expectation[0]);
            assertThat(result.chunks()).as(expectation[0]).isNotEmpty();
            assertThat(result.chunks().getFirst().pageId()).as(expectation[0]).isEqualTo(expectation[1]);
            assertThat(result.sources().getFirst()).as(expectation[0]).isEqualTo(expectation[2]);
        }
    }

    @Test
    void seededTutorialsAreCitedForErrorsAndForFollowUps() {
        Retrieval error = ask("报错 401 怎么办");
        assertThat(pageIds(error)).contains("errors", "faq");

        Retrieval followUp = ask("那怎么改？", "怎么创建 API Key");
        assertThat(followUp.chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(followUp.chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(ask("那怎么删？", "怎么创建 API Key").chunks().getFirst().section()).isEqualTo("列表里的操作");
        assertThat(pageIds(ask("那怎么改？", ""))).as("nothing to find '改' in without the previous question")
            .doesNotContain("api-keys");
    }

    @Test
    void whatIsRetrievedFromTheSeededTutorialsIsBoundedAndLabelled() {
        for (String question : List.of("怎么创建 API Key", "How do I configure Claude Code?", "怎么充值", "请求报错 401 怎么办",
            "第一次使用要做什么", "如何手动调用 API", "what is the api base url")) {
            List<Chunk> chunks = ask(question).chunks();
            assertThat(chunks).as(question).isNotEmpty().hasSizeLessThanOrEqualTo(6);
            assertThat(chunks.stream().mapToInt(Chunk::cost).sum()).as(question).isLessThanOrEqualTo(8000);
            assertThat(chunks).as(question).allSatisfy(chunk -> {
                assertThat(chunk.body().length()).isLessThanOrEqualTo(AssistantDocsChunker.MAX_CHUNK_CHARS);
                // The tutorials have no attachments; the case with a real one is aPageThatReferencesARealAttachment...
                assertThat(chunk.body()).doesNotContain("attachment:", "documentation/media");
                assertThat(chunk.pageId()).isNotBlank();
                assertThat(chunk.pageTitle()).isNotBlank();
                assertThat(chunk.language()).isIn("zh", "en");
            });
            assertThat(chunks.stream().collect(java.util.stream.Collectors.groupingBy(Chunk::pageId, java.util.stream.Collectors.counting())))
                .as(question).allSatisfy((page, count) -> assertThat(count).isLessThanOrEqualTo(3));
        }
    }

    @Test
    void casualAndPaddedQuestionsFindTheSeededTutorialsTheyAreAbout() {
        for (String[] expectation : new String[][] {
            // question, the page of the first excerpt
            {"502 是怎么回事", "errors"},
            {"502 啥意思", "errors"},
            {"502 咋回事", "errors"},
            {"502 Bad Gateway", "errors"},
            {"400 Bad Request", "errors"},
            {"报错 502 Bad Gateway", "errors"},
            {"调用的时候报 400 Bad Request 是怎么回事", "errors"},
            {"我想问下充值的话怎么操作", "billing"},
            {"充值怎么操作", "billing"},
            {"订阅怎么操作", "coding-plan"},
            {"余额有什么规则", "billing"},
            {"在哪里看密钥", "api-keys"},
            {"I want to know how to get a key", "api-keys"},
        }) {
            Retrieval result = ask(expectation[0]);
            assertThat(result.chunks()).as(expectation[0]).isNotEmpty();
            assertThat(result.chunks().getFirst().pageId()).as(expectation[0]).isEqualTo(expectation[1]);
        }
        assertThat(ask("I want to know how to get a key").chunks().getFirst().section()).isEqualTo("Create a key");
        assertThat(sections(ask("充值怎么操作"))).as("the key list has '操作' in its heading").doesNotContain("列表里的操作");
        assertThat(pageIds(ask("OpenCode 怎么验证"))).as("every client page has a 验证 heading")
            .contains("opencode").doesNotContain("claude-code", "codex-cli");
        assertThat(pageIds(ask("Codex 的常见问题"))).contains("codex-cli").doesNotContain("claude-code");
    }

    @Test
    void questionsWithManyWordsAroundTheirTopicAreStillAnswered() {
        for (String[] expectation : new String[][] {
            // question, pages of which at least one is cited
            {"帮我看看这个 401 报错", "errors,faq,codex-cli"},
            {"余额在哪看", "billing,console-tour"},
            {"去哪看余额", "billing,console-tour"},
            {"哪里能看到余额", "billing,console-tour"},
            {"查用量", "console-tour,billing,api-keys"},
            {"在哪里看用量", "console-tour,billing,api-keys"},
            {"用量在哪看", "console-tour,billing,api-keys"},
            {"怎么看用量", "console-tour,billing,api-keys"},
            {"怎么看新闻", "console-tour"},
            {"在哪里看新闻", "console-tour"},
            {"为啥扣了这么多钱", "billing,faq"},
            {"我的余额为什么变少了", "billing,faq"},
            {"402 Payment Required", "errors,billing"},
            {"Is there a way to see my usage?", "console-tour,billing,api-keys"},
            {"API Error: 502 <html><head><title>502 Bad Gateway</title></head><body><center><h1>502 Bad Gateway</h1></center>"
                + "<hr><center>nginx</center></body></html>", "errors"},
        }) {
            Retrieval result = ask(expectation[0]);
            assertThat(pageIds(result)).as(expectation[0]).containsAnyOf(expectation[1].split(","));
        }
    }

    @Test
    void followUpsThatDoNotNameTheirTopicStayWithTheEarlierQuestion() {
        assertThat(askAfter("具体步骤呢？", "怎么充值").chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(askAfter("详细说一下", "怎么充值").chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(askAfter("具体怎么操作？", "怎么充值").chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(askAfter("能详细点吗", "Claude Code 怎么配置").chunks().getFirst().pageId()).isEqualTo("claude-code");
        assertThat(askAfter("步骤是什么", "Claude Code 怎么配置").chunks().getFirst().pageId()).isEqualTo("claude-code");
        assertThat(askAfter("怎么操作", "怎么开通 Coding Plan").chunks().getFirst().pageId()).isEqualTo("coding-plan");
        for (String example : List.of("举个例子", "有例子吗")) {
            Retrieval hits = askAfter(example, "用 Python 调用");
            assertThat(hits.chunks().getFirst().pageId()).as(example).isEqualTo("manual-api");
            assertThat(hits.chunks().getFirst().section()).as(example).isEqualTo("在 Python 中调用");
        }
        Retrieval english = askAfter("Can you give more detail?", "How do I top up?");
        assertThat(english.chunks().getFirst().pageId()).isEqualTo("billing");
        assertThat(english.chunks()).extracting(Chunk::language).containsOnly("en");

        Retrieval more = askAfter("还有呢", "那怎么改？", "怎么创建 API Key");
        assertThat(more.chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(sections(more)).as("'改' is also in the password sections").noneMatch(section -> section.contains("修改密码"));
        assertThat(askAfter("那怎么改？", "怎么创建 API Key").chunks()).extracting(Chunk::section)
            .noneMatch(section -> section.contains("修改密码"));
        assertThat(askAfter("What about Codex?", "How do I configure Claude Code?").chunks().getFirst().pageId())
            .isEqualTo("codex-cli");
        assertThat(askAfter("今天天气怎么样", "怎么充值").chunks()).as("a new question the tutorials do not cover").isEmpty();
    }

    // ---- the reader's language ---------------------------------------------------------------------------------

    @Test
    void chineseQuestionsOverTheSeededTutorialsGetChineseExcerptsAndChineseSources() {
        for (String question : List.of("怎么创建 API Key", "API Key 是什么", "API Key 泄露了怎么办", "MODEL_PRICE_UNAVAILABLE 是什么",
            "报错 API_KEY_MODEL_PROVIDER_FORBIDDEN", "Claude Code 报错 401", "怎么配置 Claude Code", "怎么充值",
            "Codex CLI 怎么接入", "Base URL 要不要带 /v1")) {
            Retrieval result = ask(question);
            assertThat(result.chunks()).as(question).isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.language()).isEqualTo("zh"));
            assertThat(result.sources()).as(question).isNotEmpty()
                .allSatisfy(source -> assertThat(source).startsWith("使用文档《").endsWith("》"));
        }
        // The Chinese section that answers, although only the English heading says 'key'.
        Retrieval what = ask("API Key 是什么");
        assertThat(what.chunks().getFirst().pageId()).isEqualTo("api-keys");
        assertThat(what.chunks()).extracting(Chunk::section).contains("密钥是什么");
        assertThat(what.sources().getFirst()).isEqualTo("使用文档《创建与管理 API Key》");
    }

    @Test
    void englishQuestionsOverTheSeededTutorialsGetEnglishExcerptsAndEnglishSources() {
        for (String question : List.of("how do I create an API key", "What is an API key", "How do I configure Claude Code?",
            "How do I top up?", "what does error 503 mean", "codex refresh token was already used")) {
            Retrieval result = ask(question);
            assertThat(result.chunks()).as(question).isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.language()).isEqualTo("en"));
            assertThat(result.sources()).as(question).isNotEmpty()
                .allSatisfy(source -> assertThat(source).startsWith("Docs: "));
        }
    }

    @Test
    void genericEnglishWordsDoNotCiteUnrelatedPages() {
        Retrieval configure = ask("How do I configure Claude Code?");
        assertThat(configure.sources().getFirst()).isEqualTo("Docs: Set up Claude Code");
        assertThat(configure.sources()).as("'code' of 'status code' and 'Redeem a code' is not 'Claude Code'")
            .doesNotContain("Docs: Error reference", "Docs: Top-ups and billing");

        assertThat(ask("How do I top up?").sources()).as("'up' of 'Set up' is not 'top up'")
            .containsExactly("Docs: Top-ups and billing");
    }

    @Test
    void aLatinOnlyErrorPastedIntoAChineseConversationIsStillAnsweredInChinese() {
        String pasted = "API Error: 401 {\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid API key\"}}";

        Retrieval inChinese = ask(pasted, "Claude Code 怎么配置");
        Retrieval onItsOwn = ask(pasted);

        assertThat(inChinese.chunks()).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.language()).isEqualTo("zh"));
        assertThat(pageIds(inChinese)).contains("errors", "faq");
        assertThat(inChinese.sources()).allSatisfy(source -> assertThat(source).startsWith("使用文档《"));
        assertThat(onItsOwn.chunks()).isNotEmpty().allSatisfy(chunk -> assertThat(chunk.language()).isEqualTo("en"));
    }

    // ---- what the questions are about --------------------------------------------------------------------------

    @Test
    void errorCodesThatThePagesListAreFoundByTheirName() {
        for (String[] expectation : new String[][] {
            {"报错 API_KEY_MODEL_PROVIDER_FORBIDDEN", "API_KEY_MODEL_PROVIDER_FORBIDDEN", "errors"},
            {"MODEL_PRICE_UNAVAILABLE 是什么", "MODEL_PRICE_UNAVAILABLE", "errors"},
            {"SYSTEM_MAINTENANCE 是什么", "SYSTEM_MAINTENANCE", "errors"},
            {"what is API_KEY_MODEL_PROVIDER_FORBIDDEN", "API_KEY_MODEL_PROVIDER_FORBIDDEN", "errors"},
        }) {
            Retrieval result = ask(expectation[0]);
            assertThat(pageIds(result)).as(expectation[0]).contains(expectation[2]);
            // Every excerpt is one that names the code, not one that merely has words like 'model' or 'key' in it.
            assertThat(result.chunks()).as(expectation[0]).isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.body()).contains(expectation[1]));
        }
    }

    @Test
    void whatIsNotInTheTutorialsRetrievesNothingAndLeavesTheTitleList() {
        for (String question : List.of("今天天气怎么样", "怎么看监控大屏", "管理员怎么添加 Kiro 账号", "What is the capital of France?")) {
            Retrieval result = ask(question);
            assertThat(result.chunks()).as(question).isEmpty();
            assertThat(result.sources()).as(question).isEmpty();
            assertThat(result.pageTitles()).as(question).hasSizeGreaterThanOrEqualTo(14);
        }
    }

    @Test
    void aFollowUpThatChangesTheTopicIsNotTakenOverByThePreviousQuestion() {
        Retrieval cost = ask("那费用怎么算？", "Claude Code 怎么配置");
        Retrieval other = ask("那 Codex 呢？", "Claude Code 怎么配置");

        assertThat(cost.chunks().getFirst().pageId()).isEqualTo("models-and-pricing");
        assertThat(pageIds(cost)).doesNotContain("claude-code", "codex-cli", "opencode");
        assertThat(other.chunks().getFirst().pageId()).isEqualTo("codex-cli");
    }

    @Test
    void aPastedLogIsAnsweredFromItsErrorAndNotFromItsStackFrames() {
        StringBuilder log = new StringBuilder("我的程序报错了：");
        for (int frame = 0; frame < 30; frame++) {
            log.append(" at com.example.module").append(frame).append(".Handler").append(frame)
                .append(".process").append(frame).append("(Handler").append(frame).append(".java:").append(100 + frame).append(')');
        }
        log.append(" Caused by: 401 Invalid API key 怎么办");

        Retrieval result = ask(log.toString());

        assertThat(pageIds(result)).contains("faq");
        assertThat(result.chunks()).extracting(Chunk::section).anyMatch(section -> section.contains("401"));
    }

    // ---- reading the pages only when they changed --------------------------------------------------------------

    @Test
    void anUnchangedSetOfPagesIsNotReadAgainWhenTheTimeToLiveRunsOut() {
        assertThat(ask("怎么创建 API Key").chunks()).isNotEmpty();
        assertThat(loads).hasValue(1);
        assertThat(fingerprintReads).hasValue(1);

        clock.advance(Duration.ofSeconds(61));
        assertThat(ask("怎么创建 API Key").chunks()).isNotEmpty();
        assertThat(fingerprintReads).hasValue(2);
        assertThat(loads).as("nothing was published, so nothing is read or chunked again").hasValue(1);

        String id = createPage("过期后发布", "## 内容\n过期后发布的 expiryqq 页面。", null, true);
        assertThat(ask("expiryqq").chunks()).as("this node was told, whatever the clock says").isNotEmpty();
        assertThat(loads).hasValue(2);

        // Another node publishes: this one is not told and finds out when the time to live has run out.
        DatabaseClient.create(ConnectionFactories.get(r2dbcUrl))
            .sql("UPDATE documentation_pages SET published = jsonb_set(published, '{body}', to_jsonb(CAST(:body AS text))), published_at = clock_timestamp() WHERE id = :id")
            .bind("body", "## 内容\n另一个节点改写成 rewrittenqq。").bind("id", id).fetch().rowsUpdated().block(TIMEOUT);
        assertThat(ask("rewrittenqq").chunks()).as("within the minute the old text is still served").isEmpty();
        clock.advance(Duration.ofSeconds(61));
        assertThat(ask("rewrittenqq").chunks()).hasSize(1);
        assertThat(loads).hasValue(3);
    }

    @Test
    void aPagePublishedByAnotherNodeBetweenTheFingerprintAndTheListIsNotQuotedAfterItIsWithdrawnAgain() {
        String secret = createPage("竞态页面", "## 内容\n这里写着 racesecretqq。", null, false);
        var db = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        // Another node publishes the page right after this one has read the fingerprint, before it lists the pages.
        afterFingerprintRead = () -> {
            afterFingerprintRead = Mono::empty;
            return db.sql("UPDATE documentation_pages SET published = draft, published_at = clock_timestamp() WHERE id = :id")
                .bind("id", secret).fetch().rowsUpdated().then();
        };

        assertThat(pageIds(ask("racesecretqq"))).as("published when the pages were listed").containsExactly(secret);
        assertThat(loads).hasValue(1);

        // Another node withdraws it again; this one is told nothing.
        db.sql("UPDATE documentation_pages SET published = NULL WHERE id = :id").bind("id", secret).fetch().rowsUpdated().block(TIMEOUT);
        clock.advance(Duration.ofSeconds(61));

        assertThat(ask("racesecretqq").chunks()).as("quoted until another change comes, before the fix").isEmpty();
        assertThat(ask("racesecretqq").pageTitles()).doesNotContain("竞态页面");
        assertThat(loads).as("the check found the pages changed").hasValue(2);
        assertThat(ask("怎么创建 API Key").chunks()).as("the other pages are still there").isNotEmpty();
    }

    // ---- attachments never reach the prompt --------------------------------------------------------------------

    @Test
    void aPageThatReferencesARealAttachmentNeverShowsItsIdentifierOrAddressToTheAssistant() {
        var created = documentation.create(new Content("附件页面", "AssistantTest", "", "草稿", 5), "test").block(TIMEOUT);
        String file = UUID.randomUUID().toString();
        var attachment = documentation.attach(created.id(),
            new DocumentationStorage.Stored(file, "screenshot.png", "image/png", 1234), "test").block(TIMEOUT);
        String signed = "https://starry.example.com/documentation/media/" + file + "?expires=1999999999&signature=abcdef0123456789";
        String body = """
            ## 看图说明
            先看截图 ![控制台截图](attachment:%s) 再继续，演示视频见 [演示](attachment:%s)，直链是 %s ，裸引用 attachment:%s 也不留，关键字 attachqq。

            ```bash
            curl "%s" -o out.png   # attachment:%s
            echo attachcodeqq
            ```
            """.formatted(file, file, signed, file, signed, file);
        var published = documentation.save(created.id(), new Save(new Content("附件页面", "AssistantTest", "", body, 5,
            new Translation("Attachment page", "AssistantTest", "", body.replace("看图说明", "How to read the picture")
                .replace("关键字 attachqq", "keyword attachqq"))), created.version(), true), "test").block(TIMEOUT);

        assertThat(documentation.read(created.id()).block(TIMEOUT).attachments()).as("the attachment is real and referenced")
            .extracting(a -> a.id()).containsExactly(file);
        assertThat(attachment.url()).contains(file);
        assertThat(published.published().body()).as("the stored page does contain the reference").contains("attachment:" + file);

        for (String question : List.of("attachqq", "attachcodeqq", "关键字 attachqq 看图说明")) {
            Retrieval result = ask(question);
            assertThat(pageIds(result)).as(question).contains(created.id());
            assertThat(result.chunks()).as(question).allSatisfy(chunk -> {
                assertThat(chunk.body()).doesNotContain(file, "attachment:", "documentation/media", "signature=", "![");
                assertThat(chunk.section()).doesNotContain(file);
                assertThat(chunk.pageTitle()).doesNotContain(file);
            });
            assertThat(result.sources()).as(question).allSatisfy(source -> assertThat(source).doesNotContain(file));
        }
        String prompt = AssistantChatService.systemPrompt(
            new AssistantDtos.Settings(true, "鲸鱼", "你好", "管理员的提示", "auto", 8, 0.3), ask("attachqq"), "https://starry.example.com");
        assertThat(prompt).doesNotContain(file, "attachment:", "documentation/media", "signature=");
        assertThat(ask("curl out.png").chunks()).as("the rest of the code is still there").anySatisfy(chunk ->
            assertThat(chunk.body()).contains("curl", "-o out.png"));
    }

    // ---- damaged rows ------------------------------------------------------------------------------------------

    @Test
    void aPublishedRowThatCannotBeReadDoesNotHideTheOtherPages() {
        String broken = "broken-" + java.util.UUID.randomUUID();
        DatabaseClient.create(ConnectionFactories.get(r2dbcUrl)).sql("""
            INSERT INTO documentation_pages (id, draft, published, published_at) VALUES (:id,
                '{"title":"坏页面","category":"AssistantTest","summary":"","body":"x","sortOrder":0}'::jsonb,
                '{"title":"坏页面","category":"AssistantTest","summary":"","body":{"not":"text"},"sortOrder":0,"english":5}'::jsonb,
                clock_timestamp())
            """).bind("id", broken).fetch().rowsUpdated().block(TIMEOUT);
        String good = createPage("好页面", "## 内容\n好页面里的 goodpageqq 说明。", null, true);

        var pages = documentation.publishedPages().block(TIMEOUT);
        Retrieval result = ask("goodpageqq");

        assertThat(pages).filteredOn(page -> page.id().equals(broken)).singleElement().satisfies(page -> assertThat(page.content()).isNull());
        assertThat(pageIds(result)).containsExactly(good);
        assertThat(ask("怎么创建 API Key").chunks()).as("the seeded tutorials are still there").isNotEmpty();
        assertThat(result.pageTitles()).doesNotContain("坏页面");
        clock.advance(Duration.ofSeconds(11));
        ask("goodpageqq");
        assertThat(loads).as("a damaged row is not retried every ten seconds").hasValue(1);
    }

    @Test
    void aPublishedRowWhoseSortOrderIsNotAWholeNumberDoesNotHideTheOtherPagesOrTriggerRetries() {
        var db = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        String run = UUID.randomUUID().toString();
        String[][] damaged = {{"sortbadaqq", "1.5"}, {"sortbadbqq", "\"abc\""}, {"sortbadcqq", "99999999999"}};
        for (String[] row : damaged) {
            String content = "{\"title\":\"排序异常 " + row[0] + "\",\"category\":\"AssistantTest\",\"summary\":\"\",\"body\":\"## 内容\\n"
                + row[0] + " 说明。\",\"sortOrder\":" + row[1] + "}";
            db.sql("INSERT INTO documentation_pages (id, draft, published, published_at) VALUES (:id, CAST(:content AS jsonb), CAST(:content AS jsonb), clock_timestamp())")
                .bind("id", row[0] + run).bind("content", content).fetch().rowsUpdated().block(TIMEOUT);
        }
        String good = createPage("排序正常", "## 内容\n排序正常页里的 sortgoodqq 说明。", null, true);

        assertThat(pageIds(ask("sortgoodqq"))).containsExactly(good);
        assertThat(ask("怎么创建 API Key").chunks()).as("the seeded tutorials are still there").isNotEmpty();
        assertThat(pageIds(ask("sortbadaqq"))).as("a page whose sort order is not a number is still readable")
            .containsExactly("sortbadaqq" + run);
        clock.advance(Duration.ofSeconds(11));
        ask("sortgoodqq");
        assertThat(loads).as("nothing failed, so nothing is retried").hasValue(1);
    }

    @Test
    void aQuestionAskedWhileACancelledWithdrawalWaitsForALockDoesNotKeepThePageOnceTheWithdrawalIsApplied() throws Exception {
        String id = createPage("撤回测试", "## 内容\n这里写着 zorbwithdrawqq。", null, true);
        assertThat(pageIds(ask("zorbwithdrawqq"))).as("published").containsExactly(id);
        var current = documentation.readAdmin(id).block(TIMEOUT);
        // An upload takes the page FOR UPDATE, so the withdrawal waits behind it; the administrator closes the browser meanwhile.
        Connection locker = Mono.from(ConnectionFactories.get(r2dbcUrl).create()).block(TIMEOUT);
        try {
            Mono.from(locker.beginTransaction()).block(TIMEOUT);
            Flux.from(locker.createStatement("SELECT id FROM documentation_pages WHERE id = $1 FOR UPDATE").bind(0, id).execute())
                .flatMap(result -> result.map((row, metadata) -> row.get("id", String.class))).blockLast(TIMEOUT);
            reactor.core.Disposable request = documentation.withdraw(id, current.version(), false, "test").subscribe();
            Thread.sleep(500);
            request.dispose();
            Thread.sleep(200);
            assertThat(pageIds(ask("zorbwithdrawqq"))).as("a question in the window: the page is still published").containsExactly(id);

            Mono.from(locker.commitTransaction()).block(TIMEOUT);
            // The statement goes through and is announced; the node must not go on quoting the page until the time to live is out.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!ask("zorbwithdrawqq").chunks().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(ask("zorbwithdrawqq").chunks()).as("withdrawn, and seen at once").isEmpty();
        } finally {
            Mono.from(locker.close()).block(TIMEOUT);
        }
    }

    // ---- the driver's direct memory ----------------------------------------------------------------------------

    /** What Netty's default allocator holds in direct buffers: the rows the driver has received and not yet handed on are in it. */
    private static long nettyDirectMemory() {
        return ByteBufAllocator.DEFAULT instanceof ByteBufAllocatorMetricProvider provider
            ? provider.metric().usedDirectMemory() : io.netty.util.internal.PlatformDependent.usedDirectMemory();
    }

    /** Pages of 100,000 characters of Chinese text, published, so that the rows are some 300 KB each. */
    private void publishLargePages(int count) {
        DatabaseClient.create(ConnectionFactories.get(r2dbcUrl)).sql("""
            INSERT INTO documentation_pages (id, draft, published, published_at)
            SELECT 'large-' || :run || '-' || n, content, content, clock_timestamp() FROM (
                SELECT n, jsonb_build_object('title', '大页面 ' || n, 'category', 'AssistantTest', 'summary', '', 'sortOrder', n,
                    'body', E'## 大页面\\n' || repeat('第' || n || '行的文字，说明一二三四五六七八九十。' || E'\\n', 5000)) AS content
                FROM generate_series(1, :count) AS n) AS pages
            """).bind("run", UUID.randomUUID().toString()).bind("count", count).fetch().rowsUpdated().block(TIMEOUT);
    }

    /** Waits until the direct memory is no more than {@code limit} above {@code baseline}, or for ten seconds; the last reading. */
    private static long settledAbove(long baseline, long limit) throws InterruptedException {
        long above = nettyDirectMemory() - baseline;
        for (int tries = 0; tries < 100 && above > limit; tries++) {
            Thread.sleep(100);
            above = nettyDirectMemory() - baseline;
        }
        return above;
    }

    private static final long DIRECT_MEMORY_SLACK = 4L * 1024 * 1024;

    @Test
    void aReadThatIsGivenUpWhileTheIndexIsHeldUpLeavesNoRowsOfTheResultBufferedInTheDriversDirectMemory() throws Exception {
        // The driver asks the network for 256 messages at a time whatever the consumer asks it for, so a result of many
        // large rows is received into direct buffers while the consumer is still busy with the first. A read that is
        // cancelled then (the index gives up after READ_TIMEOUT) left those buffers unreleased for good, and direct
        // memory is capped at 256 MB: after a few such loads every allocation of the node failed.
        publishLargePages(60);
        ResourceLeakDetector.Level level = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
        try {
            assertThat(ask("大页面").chunks()).as("an ordinary load first, which also warms the allocator up").isNotEmpty();
            Thread.sleep(500);
            long baseline = nettyDirectMemory();

            index.readTimeout = Duration.ofSeconds(1);
            for (int round = 0; round < 2; round++) {
                // The consumer is held up for longer than the read may go without a page, at the first page of each read.
                AtomicInteger pages = new AtomicInteger();
                index.pageHook = document -> {
                    if (pages.getAndIncrement() == 0) {
                        try {
                            Thread.sleep(2500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
                index.invalidate();
                assertThat(ask("大页面").chunks()).as("the read timed out: answered without the pages").isEmpty();
                Thread.sleep(3000);
                assertThat(settledAbove(baseline, DIRECT_MEMORY_SLACK)).as("direct memory above the baseline after the %d. cancelled read", round + 1)
                    .isLessThanOrEqualTo(DIRECT_MEMORY_SLACK);
            }
        } finally {
            ResourceLeakDetector.setLevel(level);
        }
    }

    @Test
    void aStreamOfPagesThatIsHeldAtOneRowHoldsNoMoreThanThatRowInTheDriversDirectMemory() throws Exception {
        publishLargePages(60);
        assertThat(ask("大页面").chunks()).as("an ordinary load first, which also warms the allocator up").isNotEmpty();
        Thread.sleep(500);
        long baseline = nettyDirectMemory();
        AtomicInteger received = new AtomicInteger();
        BaseSubscriber<Document> holder = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(Document value) {
                received.incrementAndGet();
            }
        };
        documentation.publishedPageStream().subscribe(holder);
        Thread.sleep(2000);

        assertThat(received).as("one page was taken, as asked").hasValue(1);
        assertThat(nettyDirectMemory() - baseline).as("direct memory above the baseline while the stream is held at one row")
            .isLessThanOrEqualTo(DIRECT_MEMORY_SLACK);
        holder.cancel();
        assertThat(settledAbove(baseline, DIRECT_MEMORY_SLACK)).as("and after it is cancelled").isLessThanOrEqualTo(DIRECT_MEMORY_SLACK);
    }
}
