package tech.starryapi.documentation;

import static org.assertj.core.api.Assertions.*;

import io.r2dbc.spi.ConnectionFactories;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.reactive.TransactionContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.documentation.DocumentationDtos.*;
import tools.jackson.databind.ObjectMapper;

class DocumentationServicePostgreSqlTest {
    private static PostgreSQLContainer container;
    private static String r2dbcUrl;
    private DocumentationService service;
    /** The service itself, for the methods that are not reactive and so cannot go through the transaction interceptor. */
    private DocumentationService unproxied;
    private DocumentationStorage storage;
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final List<String> DEFAULT_TUTORIALS = List.of("getting-started", "console-tour", "billing", "coding-plan",
        "api-keys", "models-and-pricing", "cc-switch", "claude-code", "codex-cli", "opencode", "manual-api", "errors", "faq", "tickets");
    @TempDir Path files;

    @BeforeAll static void migrate() {
        String jdbcUrl = System.getenv("STARRY_DOCS_TEST_JDBC_URL");
        if (jdbcUrl == null) {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker or an isolated STARRY_DOCS_TEST_JDBC_URL is required");
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
    @AfterAll static void stop() { if (container != null) container.stop(); }
    @AfterEach void hideTestDocuments() {
        DatabaseClient.create(ConnectionFactories.get(r2dbcUrl))
            .sql("UPDATE documentation_pages SET deleted = TRUE, published = NULL WHERE draft->>'category' = 'Test'")
            .fetch().rowsUpdated().block(TIMEOUT);
    }
    @BeforeEach void setup() {
        events.clear();
        var factory = ConnectionFactories.get(r2dbcUrl);
        var template = new R2dbcEntityTemplate(factory);
        var repository = new R2dbcRepositoryFactory(template).getRepository(SystemSettingRepository.class);
        storage = new DocumentationStorage(files.toString());
        var target = new DocumentationService(DatabaseClient.create(factory), new ObjectMapper(), repository, storage, events::add);
        unproxied = target;
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(new R2dbcTransactionManager(factory));
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(transactions);
        service = (DocumentationService) proxy.getProxy();
    }

    @Test void seedsBeginnerGuideAndKeepsDraftSeparateFromPublication() {
        assertThat(service.catalog("").block(TIMEOUT).entries()).extracting(Entry::id)
            .contains("getting-started", "billing", "api-keys", "cc-switch", "manual-api", "faq");
        assertThat(service.read("cc-switch").block(TIMEOUT).content().body()).contains("https://ccswitch.io");
        var page = service.create(content("First", "draft text"), "test").block(TIMEOUT);
        assertThatThrownBy(() -> service.read(page.id()).block(TIMEOUT)).isInstanceOf(ApiException.class);
        var published = service.save(page.id(), new Save(page.draft(), page.version(), true), "test").block(TIMEOUT);
        var edited = service.save(page.id(), new Save(content("Second", "unpublished"), published.version(), false), "test").block(TIMEOUT);
        assertThat(service.read(page.id()).block(TIMEOUT).content().title()).isEqualTo("First");
        assertThat(edited.draft().title()).isEqualTo("Second");
        assertThat(service.listAdmin().block(TIMEOUT)).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo(page.id()); assertThat(e.hasChanges()).isTrue(); assertThat(e.published()).isTrue();
        });
        service.withdraw(page.id(), edited.version(), true, "test").block(TIMEOUT);
        assertThatThrownBy(() -> service.read(page.id()).block(TIMEOUT)).isInstanceOf(ApiException.class);
    }

    @Test void publishingAndWithdrawingAnnounceTheChangeButDraftsDoNot() {
        var page = service.create(content("Announce", "body"), "test").block(TIMEOUT);
        var draft = service.save(page.id(), new Save(content("Announce", "edited"), page.version(), false), "test").block(TIMEOUT);
        assertThat(events).as("creating and saving a draft change nothing readers see").isEmpty();

        var published = service.save(page.id(), new Save(draft.draft(), draft.version(), true), "test").block(TIMEOUT);
        assertThat(events).singleElement().isInstanceOfSatisfying(PayloadApplicationEvent.class, event -> {
            assertThat(event.getPayload()).isEqualTo(new DocumentationPublicationChanged(page.id()));
            assertThat(event.getSource()).as("inside the save transaction, so listeners wait for the commit")
                .isInstanceOf(TransactionContext.class);
        });

        service.withdraw(page.id(), published.version(), false, "test").block(TIMEOUT);
        assertThat(events).hasSize(2);
        assertThat(((PayloadApplicationEvent<?>) events.getLast()).getPayload())
            .isEqualTo(new DocumentationPublicationChanged(page.id()));

        var again = service.readAdmin(page.id()).block(TIMEOUT);
        service.withdraw(page.id(), again.version(), true, "test").block(TIMEOUT);
        assertThat(events).hasSize(3);
        assertThatThrownBy(() -> service.withdraw(page.id(), again.version(), true, "test").block(TIMEOUT))
            .isInstanceOf(ApiException.class);
        assertThat(events).as("a rejected change announces nothing").hasSize(3);
    }

    @Test void publishedPagesAreTheReaderCopyOnly() {
        var page = service.create(new Content("Visible 页面", "Test", "", "published body", 5), "test").block(TIMEOUT);
        var draftOnly = service.create(content("Never published", "draft only"), "test").block(TIMEOUT);
        var published = service.save(page.id(), new Save(page.draft(), page.version(), true), "test").block(TIMEOUT);
        service.save(page.id(), new Save(content("Edited draft", "unpublished edit"), published.version(), false), "test").block(TIMEOUT);

        var pages = service.publishedPages().block(TIMEOUT);

        assertThat(pages).extracting(Document::id).contains(page.id(), "getting-started", "api-keys")
            .doesNotContain(draftOnly.id());
        assertThat(pages).filteredOn(d -> d.id().equals(page.id())).singleElement().satisfies(d -> {
            assertThat(d.content().title()).isEqualTo("Visible 页面");
            assertThat(d.content().body()).isEqualTo("published body");
            assertThat(d.attachments()).isEmpty();
        });
        assertThat(pages).filteredOn(d -> d.id().equals("api-keys")).singleElement()
            .satisfies(d -> assertThat(d.content().english()).isNotNull());
        assertThat(pages).hasSizeLessThanOrEqualTo(200);
    }

    @Test void thePublishedFingerprintChangesWithPublicationsAndWithNothingElse() {
        String start = service.publishedFingerprint().block(TIMEOUT);
        assertThat(start).as("a digest of the published pages").hasSize(32);

        var page = service.create(content("Fingerprint", "body"), "test").block(TIMEOUT);
        var draft = service.save(page.id(), new Save(content("Fingerprint", "edited"), page.version(), false), "test").block(TIMEOUT);
        assertThat(service.publishedFingerprint().block(TIMEOUT)).as("drafts are not what readers see").isEqualTo(start);

        var published = service.save(page.id(), new Save(draft.draft(), draft.version(), true), "test").block(TIMEOUT);
        String first = service.publishedFingerprint().block(TIMEOUT);
        assertThat(first).as("publish").isNotEqualTo(start);
        assertThat(service.publishedFingerprint().block(TIMEOUT)).as("stable while nothing happens").isEqualTo(first);

        var republished = service.save(page.id(), new Save(content("Fingerprint", "second edition"), published.version(), true), "test").block(TIMEOUT);
        String second = service.publishedFingerprint().block(TIMEOUT);
        assertThat(second).as("re-publish").isNotIn(start, first);

        var edited = service.save(page.id(), new Save(content("Fingerprint", "only a draft"), republished.version(), false), "test").block(TIMEOUT);
        assertThat(service.publishedFingerprint().block(TIMEOUT)).as("an unpublished edit").isEqualTo(second);

        service.withdraw(page.id(), edited.version(), false, "test").block(TIMEOUT);
        assertThat(service.publishedFingerprint().block(TIMEOUT)).as("unpublish").isEqualTo(start);

        var again = service.readAdmin(page.id()).block(TIMEOUT);
        service.save(page.id(), new Save(again.draft(), again.version(), true), "test").block(TIMEOUT);
        String third = service.publishedFingerprint().block(TIMEOUT);
        assertThat(third).isNotEqualTo(start);
        service.withdraw(page.id(), service.readAdmin(page.id()).block(TIMEOUT).version(), true, "test").block(TIMEOUT);
        assertThat(service.publishedFingerprint().block(TIMEOUT)).as("delete").isEqualTo(start);
    }

    @Test void withNothingPublishedThePageListIsEmptyAndTheFingerprintIsAnEmptyString() {
        var db = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        List<String> published = db.sql("SELECT id FROM documentation_pages WHERE deleted = FALSE AND published IS NOT NULL")
            .map(row -> row.get("id", String.class)).all().collectList().block(TIMEOUT);
        assertThat(published).as("the seeded tutorials at least").isNotEmpty();
        try {
            db.sql("UPDATE documentation_pages SET deleted = TRUE WHERE id = ANY(CAST(:ids AS text[]))")
                .bind("ids", published.toArray(new String[0])).fetch().rowsUpdated().block(TIMEOUT);

            assertThat(service.publishedPages().block(TIMEOUT)).isEmpty();
            assertThat(service.publishedFingerprint().block(TIMEOUT))
                .as("no rows to aggregate: not a digest, but a value all the same").isEqualTo("");
        } finally {
            db.sql("UPDATE documentation_pages SET deleted = FALSE WHERE id = ANY(CAST(:ids AS text[]))")
                .bind("ids", published.toArray(new String[0])).fetch().rowsUpdated().block(TIMEOUT);
        }
        assertThat(service.publishedFingerprint().block(TIMEOUT)).hasSize(32);
    }

    @Test void publishedRowsAreTheSamePagesBeforeTheirJsonIsParsed() {
        var rows = service.publishedRows().block(TIMEOUT);

        assertThat(rows).extracting(DocumentationService.PublishedRow::id).contains("getting-started", "api-keys");
        assertThat(rows).allSatisfy(row -> assertThat(row.json()).startsWith("{"));
        assertThat(unproxied.documents(rows)).isEqualTo(service.publishedPages().block(TIMEOUT));
    }

    @Test void thePublishedRowStreamDeliversTheSameRowsInTheSameOrderOneByOne() {
        var streamed = service.publishedRowStream().collectList().block(TIMEOUT);

        assertThat(streamed).isNotEmpty().isEqualTo(service.publishedRows().block(TIMEOUT));
    }

    @Test void aPublishedRowWhoseSortOrderIsNotAWholeNumberDoesNotFailTheListAndSortsLast() {
        var db = DatabaseClient.create(ConnectionFactories.get(r2dbcUrl));
        // The rows stay in the table (only hidden) when the test is over, so a database that is used again needs new ids.
        String run = "-" + UUID.randomUUID().toString().substring(0, 8);
        String fraction = "sort-bad-a" + run;
        String text = "sort-bad-b" + run;
        String huge = "sort-bad-c" + run;
        for (String[] row : new String[][] {{fraction, "1.5"}, {text, "\"abc\""}, {huge, "99999999999"}}) {
            String content = "{\"title\":\"排序异常\",\"category\":\"Test\",\"summary\":\"\",\"body\":\"x\",\"sortOrder\":" + row[1] + "}";
            db.sql("INSERT INTO documentation_pages (id, draft, published, published_at) VALUES (:id, CAST(:content AS jsonb), CAST(:content AS jsonb), clock_timestamp())")
                .bind("id", row[0]).bind("content", content).fetch().rowsUpdated().block(TIMEOUT);
        }
        var good = service.create(new Content("排序正常", "Test", "", "body", 7), "test").block(TIMEOUT);
        service.save(good.id(), new Save(good.draft(), good.version(), true), "test").block(TIMEOUT);

        var pages = service.publishedPages().block(TIMEOUT);

        assertThat(pages).extracting(Document::id).contains(good.id(), "getting-started", "faq", fraction, text, huge);
        assertThat(pages.subList(pages.size() - 3, pages.size())).extracting(Document::id)
            .as("the damaged rows come after every page that has a sort order").containsExactly(fraction, text, huge);
        assertThat(pages).filteredOn(d -> d.id().equals(good.id())).singleElement().satisfies(d -> assertThat(d.content()).isNotNull());
        assertThat(pages).filteredOn(d -> d.id().equals(text)).singleElement()
            .satisfies(d -> assertThat(d.content()).as("it does not parse, and is passed on as such").isNull());
    }

    @Test void defaultTutorialsAreCompleteAndTranslated() {
        var original = service.catalog("").block(TIMEOUT).entries();
        var english = service.catalog("", true).block(TIMEOUT).entries();
        for (String id : DEFAULT_TUTORIALS) {
            String zh = original.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().title();
            String en = english.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow().title();
            assertThat(en).as("English title of %s", id).isNotEqualTo(zh);
        }
        assertThat(service.read("console-tour").block(TIMEOUT).content().body()).doesNotContain("管理员专属");
        assertThat(service.read("cc-switch", true).block(TIMEOUT).content().body()).doesNotContain("127.0.0.1");
    }

    @Test void concurrentEditorsCannotOverwriteEachOther() {
        var page = service.create(content("Concurrent", "start"), "test").block(TIMEOUT);
        var results = Flux.range(0, 2).flatMap(index -> service.save(page.id(),
            new Save(content("Edit " + index, "body"), page.version(), true), "test")
            .map(saved -> "saved").onErrorResume(ApiException.class, e -> Mono.just(e.getCode()))).collectList().block(TIMEOUT);
        assertThat(results).containsExactlyInAnyOrder("saved", "DOCUMENTATION_CONFLICT");
    }

    @Test void englishPublicationSearchAndFallbackDoNotExposeTranslatedDrafts() {
        assertThat(service.read("getting-started", true).block(TIMEOUT).content().title()).isEqualTo("Start here");
        assertThat(service.read("manual-api", true).block(TIMEOUT).content().body())
            .contains("{{api_base_url}}", "YOUR_API_KEY", "MODEL_ID");
        var page = service.create(content("Original", "Original body"), "test").block(TIMEOUT);
        var translated = new Content("Original", "Test", "", "Original body", 100,
            new Translation("English search marker", "English category", "Summary", "Published translation"));
        var published = service.save(page.id(), new Save(translated, page.version(), true), "test").block(TIMEOUT);
        var changed = new Content("Original", "Test", "", "Original body", 100,
            new Translation("Secret draft marker", "English category", "Summary", "Unpublished translation"));
        service.save(page.id(), new Save(changed, published.version(), false), "test").block(TIMEOUT);
        assertThat(service.catalog("English search marker", true).block(TIMEOUT).entries()).extracting(Entry::id).contains(page.id());
        assertThat(service.catalog("English search marker", false).block(TIMEOUT).entries()).isEmpty();
        assertThat(service.catalog("Secret draft marker", true).block(TIMEOUT).entries()).isEmpty();
        assertThat(service.read(page.id(), true).block(TIMEOUT).content().body()).isEqualTo("Published translation");
        assertThat(service.read(page.id()).block(TIMEOUT).content().title()).isEqualTo("Original");
        var original = service.create(content("Custom original", "Untranslated custom content"), "test").block(TIMEOUT);
        service.save(original.id(), new Save(original.draft(), original.version(), true), "test").block(TIMEOUT);
        assertThat(service.read(original.id(), true).block(TIMEOUT).content().body()).isEqualTo("Untranslated custom content");
    }

    @Test void englishOnlyMediaRequiresPublicationAndCannotBeDeletedWhileReferenced() {
        var page = service.create(content("English media", "Original"), "test").block(TIMEOUT);
        String id = UUID.randomUUID().toString();
        service.attach(page.id(), new DocumentationStorage.Stored(id, "english.mp4", "video/mp4", 24), "test").block(TIMEOUT);
        var translated = new Content("English media", "Test", "", "Original", 100,
            new Translation("English media", "Test", "", "![Video](attachment:" + id + ")"));
        var draft = service.save(page.id(), new Save(translated, page.version(), false), "test").block(TIMEOUT);
        assertThat(service.attachments(page.id(), false).block(TIMEOUT)).isEmpty();
        assertThatThrownBy(() -> service.removeAttachment(page.id(), id, "test").block(TIMEOUT)).isInstanceOf(ApiException.class);
        var published = service.save(page.id(), new Save(translated, draft.version(), true), "test").block(TIMEOUT);
        assertThat(service.read(page.id(), true).block(TIMEOUT).attachments()).hasSize(1);
        var grant = org.springframework.web.util.UriComponentsBuilder.fromUriString(storage.signedUrl(id, false)).build().getQueryParams();
        long expires = Long.parseLong(grant.getFirst("expires"));
        assertThat(service.media(id, expires, false, grant.getFirst("signature")).block(TIMEOUT)).isNotNull();
        assertThatThrownBy(() -> service.removeAttachment(page.id(), id, "test").block(TIMEOUT)).isInstanceOf(ApiException.class);
        service.withdraw(page.id(), published.version(), false, "test").block(TIMEOUT);
        assertThatThrownBy(() -> service.media(id, expires, false, grant.getFirst("signature")).block(TIMEOUT)).isInstanceOf(ApiException.class);
    }

    @Test void onlyPublishedAttachmentReferencesGrantReaderAccessAndWithdrawalRevokesThem() {
        var page = service.create(content("Media", "draft"), "test").block(TIMEOUT);
        String id = UUID.randomUUID().toString();
        service.attach(page.id(), new DocumentationStorage.Stored(id, "video.mp4", "video/mp4", 24), "test").block(TIMEOUT);
        assertThat(service.readAdmin(page.id()).block(TIMEOUT).attachments()).hasSize(1);
        assertThat(service.attachments(page.id(), false).block(TIMEOUT)).isEmpty();
        var adminGrant = org.springframework.web.util.UriComponentsBuilder.fromUriString(storage.signedUrl(id, true)).build().getQueryParams();
        assertThat(service.media(id, Long.parseLong(adminGrant.getFirst("expires")), true, adminGrant.getFirst("signature")).block(TIMEOUT)).isNotNull();
        var grant = org.springframework.web.util.UriComponentsBuilder.fromUriString(storage.signedUrl(id, false)).build().getQueryParams();
        long expires = Long.parseLong(grant.getFirst("expires"));
        String signature = grant.getFirst("signature");
        assertThatThrownBy(() -> service.media(id, expires, false, signature).block(TIMEOUT)).isInstanceOf(ApiException.class);
        var published = service.save(page.id(), new Save(content("Media", "![Video](attachment:" + id + ")"), page.version(), true), "test").block(TIMEOUT);
        assertThat(service.read(page.id()).block(TIMEOUT).attachments()).hasSize(1);
        assertThat(service.media(id, expires, false, signature).block(TIMEOUT)).isNotNull();
        assertThatThrownBy(() -> service.media(id, expires, true, signature).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.removeAttachment(page.id(), id, "test").block(TIMEOUT)).isInstanceOf(ApiException.class);
        service.withdraw(page.id(), published.version(), false, "test").block(TIMEOUT);
        assertThatThrownBy(() -> service.media(id, expires, false, signature).block(TIMEOUT)).isInstanceOf(ApiException.class);
    }

    @Test void quotaIsSerializedForConcurrentUploadsAndSettingsRejectUnsafeLinks() {
        var page = service.create(content("Quota", "body"), "test").block(TIMEOUT);
        var results = Flux.range(0, 5).flatMap(index -> service.attach(page.id(),
            new DocumentationStorage.Stored(UUID.randomUUID().toString(), "large.mp4", "video/mp4", DocumentationStorage.MAX_BYTES), "test")
            .map(file -> "saved").onErrorResume(ApiException.class, e -> Mono.just(e.getCode())), 5).collectList().block(TIMEOUT);
        assertThat(results.stream().filter("saved"::equals).count()).isEqualTo(4);
        assertThat(results).contains("DOCUMENTATION_INVALID");
        assertThatThrownBy(() -> service.saveSettings(new Settings("javascript:alert(1)")).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.saveSettings(new Settings("https://user:secret@example.com")).block(TIMEOUT)).isInstanceOf(ApiException.class);
        assertThat(service.saveSettings(new Settings("https://docs.example.com/api")).block(TIMEOUT).apifoxUrl()).isEqualTo("https://docs.example.com/api");
        service.saveSettings(new Settings("")).block(TIMEOUT);
    }
    private Content content(String title, String body) { return new Content(title, "Test", "", body, 100); }
}
