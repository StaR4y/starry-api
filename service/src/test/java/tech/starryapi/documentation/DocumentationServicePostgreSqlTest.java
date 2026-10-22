package tech.starryapi.documentation;

import static org.assertj.core.api.Assertions.*;

import io.r2dbc.spi.ConnectionFactories;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
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
    private DocumentationStorage storage;
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
        var factory = ConnectionFactories.get(r2dbcUrl);
        var template = new R2dbcEntityTemplate(factory);
        var repository = new R2dbcRepositoryFactory(template).getRepository(SystemSettingRepository.class);
        storage = new DocumentationStorage(files.toString());
        var target = new DocumentationService(DatabaseClient.create(factory), new ObjectMapper(), repository, storage);
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
