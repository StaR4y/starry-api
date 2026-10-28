package tech.starryapi.documentation;

import io.r2dbc.spi.Row;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionContextManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.common.ApiException;
import tech.starryapi.documentation.DocumentationDtos.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class DocumentationService {
    private static final Logger log = LoggerFactory.getLogger(DocumentationService.class);
    private static final String SETTINGS_KEY = "documentation_apifox_url";
    private final DatabaseClient db;
    private final ObjectMapper json;
    private final SystemSettingRepository settings;
    private final DocumentationStorage storage;
    private final ApplicationEventPublisher events;

    public DocumentationService(DatabaseClient db, ObjectMapper json, SystemSettingRepository settings,
        DocumentationStorage storage, ApplicationEventPublisher events) {
        this.db = db; this.json = json; this.settings = settings; this.storage = storage; this.events = events;
    }

    public Mono<Settings> settings() {
        return settings.findById(SETTINGS_KEY).map(s -> new Settings(s.getSettingValue()))
            .defaultIfEmpty(new Settings(""));
    }

    public Mono<Settings> saveSettings(Settings request) {
        String url = validExternalUrl(request.apifoxUrl());
        return settings.upsertSetting(SETTINGS_KEY, url).thenReturn(new Settings(url));
    }

    static String validExternalUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) return "";
        try {
            URI uri = URI.create(url);
            if (url.length() > 2000 || !List.of("https", "http").contains(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw invalid("请输入有效的 HTTP/HTTPS 文档链接"); }
        return url;
    }

    public Mono<Catalog> catalog(String query) {
        return catalog(query, false);
    }

    public Mono<Catalog> catalog(String query, boolean english) {
        String q = query == null ? "" : query.trim();
        if (q.length() > 100) throw invalid("搜索内容过长");
        return db.sql("""
            WITH localized AS (
                SELECT id, published_at, (published - 'english') ||
                    CASE WHEN :english AND jsonb_typeof(published->'english') = 'object'
                        THEN published->'english' ELSE '{}'::jsonb END AS published
                FROM documentation_pages WHERE deleted = FALSE AND published IS NOT NULL
            )
            SELECT id, published->>'title' AS title, published->>'category' AS category,
                published->>'summary' AS summary, (published->>'sortOrder')::integer AS sort_order,
                published_at FROM localized
            WHERE (:q = '' OR strpos(lower(published::text), lower(:q)) > 0)
            ORDER BY (published->>'sortOrder')::integer, published->>'title', id LIMIT 200
            """).bind("q", q).bind("english", english).map((r, m) -> new Entry(r.get("id", String.class), r.get("title", String.class),
                r.get("category", String.class), r.get("summary", String.class), r.get("sort_order", Integer.class),
                r.get("published_at", Instant.class)))
            .all().collectList().flatMap(entries -> settings().map(s -> new Catalog(s, entries)));
    }

    public Mono<Document> read(String id) {
        return read(id, false);
    }

    public Mono<Document> read(String id, boolean english) {
        return db.sql("SELECT id, published::text AS content, published_at FROM documentation_pages WHERE id = :id AND deleted = FALSE AND published IS NOT NULL")
            .bind("id", id).map((r, m) -> new Document(r.get("id", String.class), content(r.get("content", String.class)).localized(english),
                r.get("published_at", Instant.class), List.of())).one().switchIfEmpty(Mono.error(notFound()))
            .flatMap(doc -> attachments(id, false).map(files -> new Document(doc.id(), doc.content(), doc.updatedAt(), files)));
    }

    /**
     * Every published, non-deleted page as readers see it: the published copy only, never a draft, in catalog order.
     * A row whose JSON cannot be read comes back with no content, and one whose sortOrder is not a whole number sorts
     * last, instead of failing the whole list, so that one damaged page cannot hide all the others from the assistant.
     */
    public Mono<List<Document>> publishedPages() {
        return publishedRows().map(this::documents);
    }

    /** A published page as it is stored: the text of its JSON is not read yet. */
    public record PublishedRow(String id, String json, Instant publishedAt) { }

    /**
     * The same pages as {@link #publishedPages()} in the same order, but not parsed. Reading the JSON of every page is
     * CPU work that does not belong on the database's event loop, which delivers the rows; {@link #documents} does it on
     * whichever thread the caller moves to.
     */
    public Mono<List<PublishedRow>> publishedRows() {
        return publishedRowStream().collectList();
    }

    /**
     * The rows of {@link #publishedRows()} as they arrive, so that a caller can tell a read that is slow but going on from
     * one that has stopped.
     */
    public Flux<PublishedRow> publishedRowStream() {
        return db.sql("""
            SELECT id, published::text AS content, published_at FROM documentation_pages
            WHERE deleted = FALSE AND published IS NOT NULL
            ORDER BY CASE WHEN published->>'sortOrder' ~ '^-?[0-9]{1,9}$' THEN (published->>'sortOrder')::integer END NULLS LAST,
                published->>'title', id LIMIT 200
            """).map((r, m) -> new PublishedRow(r.get("id", String.class), r.get("content", String.class),
                r.get("published_at", Instant.class))).all();
    }

    /**
     * What the assistant reads of a page, at most: twice the limits of {@link Content} and {@link Translation} (a title of
     * 100 characters, a category of 60, a summary of 240, a body of 100,000), so that a page that was saved through the
     * API is never touched and one that was not cannot make a read as long as its text is.
     */
    static final int ASSISTANT_TITLE_CHARS = 200;
    static final int ASSISTANT_CATEGORY_CHARS = 120;
    static final int ASSISTANT_SUMMARY_CHARS = 480;
    static final int ASSISTANT_BODY_CHARS = 200_000;
    /** A sort order that is a string is read as a number: no number the page can have is longer. */
    static final int ASSISTANT_SORT_ORDER_CHARS = 64;

    /**
     * A member of a JSON object as the assistant reads it: a string cut to {@code limit} characters, a number, a boolean or
     * a null as it is, and an array or object, which {@link Content} cannot read as text either, as an empty one (so the
     * page stays unreadable, as it is when read whole, and what is in it never leaves the database). Nothing when the
     * member is not there.
     */
    private static String member(String object, String key, int limit) {
        return ("CASE jsonb_typeof(%1$s->'%2$s') WHEN 'string' THEN to_jsonb(left(%1$s->>'%2$s', %3$d)) "
            + "WHEN 'array' THEN '[]'::jsonb WHEN 'object' THEN '{}'::jsonb ELSE %1$s->'%2$s' END").formatted(object, key, limit);
    }

    /** The four texts of a page or of its translation, each cut to what the assistant reads of it. */
    private static String texts(String object) {
        return String.join(", ", "'title', " + member(object, "title", ASSISTANT_TITLE_CHARS),
            "'category', " + member(object, "category", ASSISTANT_CATEGORY_CHARS),
            "'summary', " + member(object, "summary", ASSISTANT_SUMMARY_CHARS),
            "'body', " + member(object, "body", ASSISTANT_BODY_CHARS));
    }

    /**
     * The published JSON of a page as the assistant reads it, in both languages: only the members that {@link Content}
     * and {@link Translation} have, the text of each cut as above. Everything else in the row (a member of another name,
     * whatever its size, an {@code english} that is not an object) is left behind in the database, so that a row that was
     * not saved through the API cannot make a read as long as its JSON is. (The table only holds objects; should it ever
     * hold another value, an empty array stands for it, which {@link Content} cannot read.)
     */
    private static final String CUT_PUBLISHED = "CASE jsonb_typeof(published) WHEN 'object' THEN jsonb_strip_nulls(jsonb_build_object("
        + texts("published") + ", 'sortOrder', " + member("published", "sortOrder", ASSISTANT_SORT_ORDER_CHARS)
        + ", 'english', CASE WHEN published->'english' IS NULL OR jsonb_typeof(published->'english') = 'null' THEN NULL "
        + "WHEN jsonb_typeof(published->'english') = 'object' THEN jsonb_strip_nulls(jsonb_build_object("
        + texts("(published->'english')") + ")) ELSE '[]'::jsonb END)) ELSE '[]'::jsonb END";

    /**
     * What a fingerprint is made of: for each published page its id, the time it was published and the size of its JSON.
     * Both {@link #publishedFingerprint()} and {@link #publishedStream()} take it of a relation with those columns
     * ({@code id}, {@code published_at}, {@code size}), so that the one of a read and the one a check compares it with are
     * the same value for the same pages.
     */
    private static final String FINGERPRINT_OF_PAGES =
        "coalesce(md5(string_agg(id || '@' || coalesce(published_at::text, '') || '#' || size, ',' ORDER BY id)), '')";

    /**
     * The pages of {@link #publishedStream()} and the fingerprint of the set that was listed to read them, which is the
     * set the pages are of: it is made by the statement that lists them, and not by a statement of its own before or
     * after it, between which another node can publish or withdraw a page. A fingerprint read apart from the pages
     * can describe other pages than the ones read; a withdrawal then brings it back to a value that was kept, and a
     * cache that compares it never reads the pages again.
     */
    public record PublishedStream(String fingerprint, Flux<Document> pages) { }

    /**
     * The same pages as {@link #publishedPages()} in the same order, for the assistant, which keeps a few thousand
     * passages of them and no more. A page's JSON is cut in the database to what the assistant reads of its text (see
     * {@link #ASSISTANT_BODY_CHARS}), and the pages are read one at a time and parsed on a worker thread as they come: a
     * caller that handles each page as it arrives never holds more than one, where the JSON of all of them (up to 200
     * pages of two languages of 100,000 characters, which is more with the escapes of control characters) is hundreds of
     * megabytes. A row whose JSON cannot be read comes back with no content, as in {@link #documents}.
     *
     * <p>Each page is a query of its own, after one for the ids in catalog order, which also makes the fingerprint of
     * the published pages (see {@link PublishedStream}). One query for all of them would not
     * hold the rows back: the driver asks the network for 256 messages at a time whatever the subscriber asks of it, so
     * the whole result would be received into direct buffers while the consumer is busy with the first row, and a
     * subscriber that gives up then (the index does, after a read timeout) leaves them unreleased. Direct memory is
     * capped (256 MB in the deployment) and shared with the HTTP server and the proxy: a few such reads would fail every
     * allocation of the node until a restart. A fetch size does not help, the driver asks for the next batch of a cursor
     * without waiting for demand. With a query for a page the driver never holds more than the row in hand and the next.
     * A page that was unpublished or deleted since the ids were read is left out, and one published since is not read
     * (it is not in the fingerprint either, so the next check finds the pages changed).
     */
    public Mono<PublishedStream> publishedStream() {
        return db.sql("WITH pages AS (SELECT id, published_at, pg_column_size(published) AS size, "
                + "CASE WHEN published->>'sortOrder' ~ '^-?[0-9]{1,9}$' THEN (published->>'sortOrder')::integer END AS sort_order, "
                + "published->>'title' AS title FROM documentation_pages WHERE deleted = FALSE AND published IS NOT NULL) "
                + "SELECT id, (SELECT " + FINGERPRINT_OF_PAGES + " FROM pages) AS fingerprint FROM pages "
                + "ORDER BY sort_order NULLS LAST, title, id LIMIT 200")
            .map((r, m) -> List.of(r.get("id", String.class), r.get("fingerprint", String.class))).all().collectList()
            .map(rows -> new PublishedStream(rows.isEmpty() ? "" : rows.getFirst().get(1),
                Flux.fromIterable(rows).concatMap(row -> publishedPage(row.getFirst()), 1)
                    .publishOn(Schedulers.boundedElastic(), 1)
                    .map(row -> new Document(row.id(), readableContent(row.json()), row.publishedAt(), List.<Attachment>of()))));
    }

    /** The pages of {@link #publishedStream()} without the fingerprint. */
    public Flux<Document> publishedPageStream() {
        return publishedStream().flatMapMany(PublishedStream::pages);
    }

    /** One published page, cut as in {@link #publishedStream()}; nothing if it is not published or has been deleted. */
    Mono<PublishedRow> publishedPage(String id) {
        return db.sql("SELECT id, (" + CUT_PUBLISHED + ")::text AS content, published_at FROM documentation_pages "
                + "WHERE id = :id AND deleted = FALSE AND published IS NOT NULL")
            .bind("id", id).map((r, m) -> new PublishedRow(r.get("id", String.class), r.get("content", String.class),
                r.get("published_at", Instant.class))).one();
    }

    /** Parses the rows of {@link #publishedRows()}; a row whose JSON cannot be read comes back with no content. */
    public List<Document> documents(List<PublishedRow> rows) {
        return rows.stream().map(row -> new Document(row.id(), readableContent(row.json()), row.publishedAt(), List.<Attachment>of())).toList();
    }

    /**
     * A short value that changes whenever a page is published, re-published, unpublished or deleted (a draft save
     * does not change it), so that a cache of {@link #publishedPages()} can tell cheaply whether it is out of date. The
     * same value as {@link PublishedStream#fingerprint()} for the same pages.
     */
    public Mono<String> publishedFingerprint() {
        return db.sql("SELECT " + FINGERPRINT_OF_PAGES + " AS fingerprint FROM (SELECT id, published_at, pg_column_size(published) AS size "
                + "FROM documentation_pages WHERE deleted = FALSE AND published IS NOT NULL) pages")
            .map(r -> r.get("fingerprint", String.class)).one();
    }

    public Mono<List<AdminEntry>> listAdmin() {
        return db.sql("""
            SELECT id, draft->>'title' AS title, draft->>'category' AS category,
                draft->>'summary' AS summary, (draft->>'sortOrder')::integer AS sort_order,
                version, published IS NOT NULL AS is_published,
                draft IS DISTINCT FROM published AS has_changes, updated_at
            FROM documentation_pages WHERE deleted = FALSE
            ORDER BY (draft->>'sortOrder')::integer, updated_at DESC
            """).map((r, m) -> new AdminEntry(r.get("id", String.class), r.get("title", String.class),
                r.get("category", String.class), r.get("summary", String.class), r.get("sort_order", Integer.class),
                r.get("version", Long.class), r.get("is_published", Boolean.class), r.get("has_changes", Boolean.class),
                r.get("updated_at", Instant.class))).all().collectList();
    }

    public Mono<AdminDocument> readAdmin(String id) {
        return db.sql("SELECT id, draft::text, published::text, version, published_at, updated_at FROM documentation_pages WHERE id = :id AND deleted = FALSE")
            .bind("id", id).map((r, m) -> adminDocument(r, List.of())).one().switchIfEmpty(Mono.error(notFound()))
            .flatMap(d -> attachments(id, true).map(files -> new AdminDocument(d.id(), d.draft(), d.published(), d.version(), d.publishedAt(), d.updatedAt(), files)));
    }

    @Transactional
    public Mono<AdminDocument> create(Content content, String actor) {
        String id = UUID.randomUUID().toString();
        return settings.acquireTransactionLock("starry_api:documentation:create")
            .then(db.sql("SELECT count(*) AS count FROM documentation_pages WHERE deleted = FALSE").map(r -> r.get("count", Long.class)).one())
            .flatMap(count -> count >= 200 ? Mono.error(invalid("最多保留 200 篇文档"))
                : db.sql("INSERT INTO documentation_pages (id, draft) VALUES (:id, CAST(:content AS jsonb))")
                    .bind("id", id).bind("content", serialize(content)).fetch().rowsUpdated())
            .then(Mono.defer(() -> readAdmin(id)))
            .doOnSuccess(d -> log.info("documentation action=create actor={} id={}", actor, id));
    }

    @Transactional
    public Mono<AdminDocument> save(String id, Save request, String actor) {
        return db.sql("""
            UPDATE documentation_pages SET draft = CAST(:content AS jsonb),
                published = CASE WHEN :publish THEN CAST(:content AS jsonb) ELSE published END,
                published_at = CASE WHEN :publish THEN clock_timestamp() ELSE published_at END,
                version = version + 1, updated_at = clock_timestamp()
            WHERE id = :id AND version = :version AND deleted = FALSE
            """).bind("id", id).bind("version", request.version()).bind("content", serialize(request.content()))
            .bind("publish", request.publish()).fetch().rowsUpdated().flatMap(this::changed)
            .then(Mono.defer(() -> readAdmin(id)))
            .flatMap(d -> request.publish() ? publicationChanged(id).thenReturn(d) : Mono.just(d))
            .doOnSuccess(d -> log.info("documentation action={} actor={} id={} version={}", request.publish() ? "publish" : "save-draft", actor, id, d.version()));
    }

    /**
     * How long a withdrawal whose caller has gone is waited for before it is announced all the same: the statement may be
     * waiting for a lock for as long as the transaction that holds it takes. The node's own cache is then at most its time
     * to live out of date, as it is for a change made on another node.
     */
    static final Duration ABANDONED_WITHDRAWAL_WAIT = Duration.ofMinutes(1);

    /**
     * Unpublishes or deletes a page. The statement commits by itself, whoever is still listening: a request that is
     * cancelled while it runs (the administrator closes the browser) leaves the page withdrawn all the same. So the
     * change is announced whenever the statement may have been applied, not only when its result comes back and the
     * caller is still there: after a cancel, once the statement has settled, and after a failure of the statement itself,
     * where it is not known. Only a statement that is known to have changed nothing (the page was edited or deleted
     * meanwhile) announces nothing.
     *
     * <p>After a cancel the announcement waits for the statement. It may still be on its way, or waiting for a lock (an
     * upload takes the page {@code FOR UPDATE}); announced at the moment of the cancel it would make the next question
     * read the page as published and keep it, and the statement commits after that with nothing to tell this node. The
     * statement is shared ({@code cache}), so that the caller's cancel does not stop it and the wait for its outcome does
     * not run it again.
     */
    public Mono<Void> withdraw(String id, long version, boolean delete, String actor) {
        return Mono.defer(() -> {
            Mono<Long> statement = db.sql("UPDATE documentation_pages SET published = NULL, published_at = NULL, deleted = :deleted, version = version + 1, updated_at = clock_timestamp() WHERE id = :id AND version = :version AND deleted = FALSE")
                .bind("id", id).bind("version", version).bind("deleted", delete).fetch().rowsUpdated().cache();
            return statement
                .doOnError(error -> announce(id))
                .flatMap(this::changed)
                .then(Mono.defer(() -> publicationChanged(id)))
                .doOnCancel(() -> announceWhenSettled(statement, id))
                .doOnSuccess(v -> log.info("documentation action={} actor={} id={}", delete ? "delete" : "unpublish", actor, id));
        });
    }

    /** Announces the change when the statement of a withdrawal whose caller is gone has settled: applied, failed, or not heard of. */
    private void announceWhenSettled(Mono<Long> statement, String id) {
        statement.timeout(ABANDONED_WITHDRAWAL_WAIT).subscribe(rows -> {
            if (rows == 1) {
                announce(id);
            }
        }, error -> announce(id));
    }

    @Transactional
    public Mono<Attachment> attach(String id, DocumentationStorage.Stored file, String actor) {
        return db.sql("SELECT id FROM documentation_pages WHERE id = :id AND deleted = FALSE FOR UPDATE")
            .bind("id", id).map(r -> r.get("id", String.class)).one().switchIfEmpty(Mono.error(notFound()))
            .then(db.sql("SELECT count(*) AS count, coalesce(sum(size_bytes), 0)::bigint AS bytes FROM documentation_attachments WHERE document_id = :id")
                .bind("id", id).map(r -> r.get("count", Long.class) < 20 && r.get("bytes", Long.class) + file.sizeBytes() <= 256L * 1024 * 1024).one())
            .flatMap(allowed -> allowed ? db.sql("INSERT INTO documentation_attachments (id, document_id, filename, content_type, size_bytes) VALUES (:file, :doc, :name, :type, :size)")
                .bind("file", file.id()).bind("doc", id).bind("name", file.filename()).bind("type", file.contentType()).bind("size", file.sizeBytes()).fetch().rowsUpdated()
                : Mono.error(invalid("每篇文档最多 20 个附件，总计不超过 256 MB")))
            .thenReturn(new Attachment(file.id(), file.filename(), file.contentType(), file.sizeBytes(), storage.signedUrl(file.id(), true)))
            .doOnSuccess(a -> log.info("documentation action=upload actor={} document={} attachment={} bytes={}", actor, id, file.id(), file.sizeBytes()));
    }

    public Mono<List<Attachment>> attachments(String id, boolean admin) {
        return db.sql("""
            SELECT a.* FROM documentation_attachments a JOIN documentation_pages p ON p.id = a.document_id
            WHERE p.id = :id AND p.deleted = FALSE
              AND (:admin OR (p.published IS NOT NULL AND strpos(concat(p.published->>'body', p.published->'english'->>'body'), 'attachment:' || a.id) > 0))
            ORDER BY a.created_at, a.id
            """).bind("id", id).bind("admin", admin).map((r, m) -> attachment(r, admin)).all().collectList();
    }

    public Mono<Attachment> media(String id, long expires, boolean admin, String signature) {
        storage.verifySignature(id, expires, admin, signature);
        return db.sql("""
            SELECT a.* FROM documentation_attachments a JOIN documentation_pages p ON p.id = a.document_id
            WHERE a.id = :id AND p.deleted = FALSE
              AND (:admin OR (p.published IS NOT NULL AND strpos(concat(p.published->>'body', p.published->'english'->>'body'), 'attachment:' || a.id) > 0))
            """).bind("id", id).bind("admin", admin).map((r, m) -> attachment(r, admin)).one().switchIfEmpty(Mono.error(notFound()));
    }

    @Transactional
    public Mono<Void> removeAttachment(String doc, String file, String actor) {
        return db.sql("SELECT id FROM documentation_pages WHERE id = :id AND deleted = FALSE FOR UPDATE")
            .bind("id", doc).map(r -> r.get("id", String.class)).one().switchIfEmpty(Mono.error(notFound()))
            .then(db.sql("""
                DELETE FROM documentation_attachments a USING documentation_pages p
                WHERE a.id = :file AND a.document_id = :doc AND p.id = a.document_id
                  AND strpos(concat(p.draft->>'body', p.draft->'english'->>'body'), 'attachment:' || a.id) = 0
                  AND (p.published IS NULL OR strpos(concat(p.published->>'body', p.published->'english'->>'body'), 'attachment:' || a.id) = 0)
                """).bind("doc", doc).bind("file", file).fetch().rowsUpdated())
            .flatMap(n -> n == 0 ? Mono.error(invalid("请先从草稿和已发布正文移除该附件引用，再删除附件")) : Mono.<Void>empty())
            .doOnSuccess(v -> log.info("documentation action=delete-attachment actor={} document={} attachment={}", actor, doc, file));
    }

    private Attachment attachment(Row r, boolean admin) {
        String id = r.get("id", String.class);
        return new Attachment(id, r.get("filename", String.class), r.get("content_type", String.class),
            r.get("size_bytes", Long.class), storage.signedUrl(id, admin));
    }
    private AdminDocument adminDocument(Row r, List<Attachment> files) {
        return new AdminDocument(r.get("id", String.class), content(r.get("draft", String.class)), content(r.get("published", String.class)),
            r.get("version", Long.class), r.get("published_at", Instant.class), r.get("updated_at", Instant.class), files);
    }
    private Content content(String value) {
        if (value == null) return null;
        try { return json.readValue(value, Content.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid documentation content", e); }
    }
    private Content readableContent(String value) {
        try { return content(value); }
        catch (IllegalStateException e) { return null; }
    }
    private String serialize(Content value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    /**
     * Announces that what readers can see has changed. Inside a transaction the event carries the transaction as its
     * source, so @TransactionalEventListener methods receive it only after the commit; outside one (withdraw is a single
     * auto-committed statement) it is delivered right away.
     */
    private Mono<Void> publicationChanged(String id) {
        DocumentationPublicationChanged change = new DocumentationPublicationChanged(id);
        return TransactionContextManager.currentContext()
            .<ApplicationEvent>map(transaction -> new PayloadApplicationEvent<>(transaction, change))
            .onErrorResume(NoTransactionException.class, e -> Mono.just(new PayloadApplicationEvent<>(this, change)))
            .doOnNext(events::publishEvent).then();
    }
    /** The same announcement without a subscriber to wait for it: for a signal that comes when nobody is listening any more. */
    private void announce(String id) {
        events.publishEvent(new PayloadApplicationEvent<>(this, new DocumentationPublicationChanged(id)));
    }
    private Mono<Void> changed(long n) {
        return n == 1 ? Mono.empty() : Mono.error(new ApiException(HttpStatus.CONFLICT, "DOCUMENTATION_CONFLICT", "文档已被修改或删除，请重新加载后再编辑"));
    }
    static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENTATION_INVALID", message); }
    static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENTATION_NOT_FOUND", "文档或附件不存在"); }
}
