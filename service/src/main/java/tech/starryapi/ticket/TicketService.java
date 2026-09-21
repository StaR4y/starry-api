package tech.starryapi.ticket;

import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.AdminPrincipal;
import tech.starryapi.common.ApiException;
import tech.starryapi.ticket.TicketDtos.*;

@Service
public class TicketService {
    static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "WAITING_USER", "RESOLVED", "CLOSED");
    static final Set<String> CATEGORIES = Set.of("GENERAL", "API", "BILLING", "ACCOUNT", "BUG");
    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public TicketService(DatabaseClient db, TransactionalOperator tx) { this.db = db; this.tx = tx; }

    public Mono<Page> list(AdminPrincipal actor, String status, String search, int page, int size) {
        if (!status.isEmpty() && !STATUSES.contains(status)) throw invalid("工单状态无效");
        if (page < 1 || page > 100000 || size < 1 || size > 100 || search.length() > 160)
            throw invalid("分页或搜索参数无效");
        String owner = admin(actor) ? "" : actor.userId();
        String where = " FROM support_tickets WHERE (:owner = '' OR user_id = :owner) "
            + "AND (:status = '' OR status = :status) AND (:search = '' OR strpos(lower(title || ' ' || id || ' ' || username), lower(:search)) > 0)";
        var rows = db.sql("SELECT *" + where + " ORDER BY updated_at DESC, id LIMIT :size OFFSET :offset")
            .bind("owner", owner).bind("status", status).bind("search", search.trim())
            .bind("size", size).bind("offset", (long) (page - 1) * size).map((r, m) -> ticket(r)).all().collectList();
        var total = db.sql("SELECT count(*) AS n" + where).bind("owner", owner)
            .bind("status", status).bind("search", search.trim()).map(r -> r.get("n", Long.class)).one();
        var counts = db.sql("SELECT status, count(*) AS n FROM support_tickets WHERE (:owner = '' OR user_id = :owner) GROUP BY status")
            .bind("owner", owner).map((r, m) -> java.util.Map.entry(r.get("status", String.class), r.get("n", Long.class)))
            .all().collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue);
        return Mono.zip(rows, total, counts).map(v -> new Page(v.getT1(), v.getT2(), page, size, v.getT3()));
    }

    public Mono<Ticket> find(AdminPrincipal actor, String id) { return find(actor, id, false); }

    private Mono<Ticket> find(AdminPrincipal actor, String id, boolean lock) {
        return db.sql("SELECT * FROM support_tickets WHERE id = :id AND (:admin OR user_id = :user)" + (lock ? " FOR UPDATE" : ""))
            .bind("id", id).bind("admin", admin(actor)).bind("user", actor.userId())
            .map((r, m) -> ticket(r)).one().switchIfEmpty(Mono.error(notFound()));
    }

    public Mono<ThreadPage> thread(AdminPrincipal actor, String id, long before) {
        if (before < 0) throw invalid("消息分页参数无效");
        return find(actor, id).flatMap(ticket -> messages(id, before, 31).collectList().map(rows -> {
            boolean more = rows.size() > 30;
            List<Message> page = new ArrayList<>(rows.subList(0, Math.min(30, rows.size())));
            long next = page.isEmpty() ? 0 : page.getLast().id();
            java.util.Collections.reverse(page);
            return new ThreadPage(ticket, page, more, next);
        }));
    }

    Flux<Message> messages(String id, long before, int limit) {
        return db.sql("SELECT * FROM support_ticket_messages WHERE ticket_id = :id AND (:before = 0 OR id < :before) ORDER BY id DESC LIMIT :limit")
            .bind("id", id).bind("before", before).bind("limit", limit).map((r, m) -> new Message(
                r.get("id", Long.class), r.get("actor_name", String.class), Boolean.TRUE.equals(r.get("admin", Boolean.class)),
                r.get("kind", String.class), r.get("body", String.class), r.get("status", String.class), r.get("created_at", Instant.class), List.of()))
            .all().concatMap(msg -> db.sql("SELECT id, filename, size_bytes FROM support_ticket_attachments WHERE message_id = :id ORDER BY id")
                .bind("id", msg.id()).map((r, m) -> new Attachment(r.get("id", String.class), r.get("filename", String.class), r.get("size_bytes", Integer.class)))
                .all().collectList().map(files -> new Message(msg.id(), msg.actorName(), msg.admin(), msg.kind(), msg.body(), msg.status(), msg.createdAt(), files)));
    }

    Mono<List<Message>> notificationMessages(String id) {
        return messages(id, 0, 9).collectList().flatMap(recent -> db.sql("SELECT min(id) AS id FROM support_ticket_messages WHERE ticket_id = :ticket")
            .bind("ticket", id).map(row -> row.get("id", Long.class)).one().flatMap(firstId -> {
                if (recent.stream().anyMatch(message -> message.id() == firstId)) return Mono.just(recent);
                return messages(id, firstId + 1, 1).collectList().map(first -> {
                    List<Message> combined = new ArrayList<>(recent); combined.addAll(first); return combined;
                });
            }));
    }

    public Mono<Ticket> create(AdminPrincipal actor, Submission request, List<TicketAttachments.Upload> files) {
        validateSubmission(request, true);
        String id = UUID.randomUUID().toString();
        Mono<Ticket> work = lockActor(actor).then(checkQuota(actor, files, true))
            .then(db.sql("""
                INSERT INTO support_tickets (id, user_id, username, title, category)
                VALUES (:id, :user, :name, :title, :category)
                """).bind("id", id).bind("user", actor.userId()).bind("name", actor.username())
                .bind("title", request.title().trim()).bind("category", request.category()).fetch().rowsUpdated())
            .then(insertMessage(actor, id, false, "MESSAGE", request.body().trim(), "OPEN", files))
            .then(find(actor, id));
        return tx.transactional(work).doOnSuccess(t -> audit(actor, t, "CREATED"));
    }

    public Mono<Ticket> reply(AdminPrincipal actor, String id, Submission request, List<TicketAttachments.Upload> files) {
        validateSubmission(request, false);
        Mono<Ticket> work = lockActor(actor).then(find(actor, id, true)).flatMap(current -> {
            checkVersion(current, request.version());
            if (Set.of("CLOSED", "RESOLVED").contains(current.status())) throw conflict("请先重新打开工单再回复");
            if (current.messageCount() >= 200) throw conflict("工单已达 200 条记录，请新建工单继续沟通");
            String status = admin(actor) ? request.status() == null ? "WAITING_USER" : request.status() : "OPEN";
            if (!STATUSES.contains(status)) throw invalid("工单状态无效");
            return checkQuota(actor, files, false)
                .then(insertMessage(actor, id, admin(actor), "MESSAGE", request.body().trim(), status, files))
                .then(update(id, status, !admin(actor))).then(find(actor, id));
        });
        return tx.transactional(work).doOnSuccess(t -> audit(actor, t, "REPLIED"));
    }

    public Mono<Ticket> changeStatus(AdminPrincipal actor, String id, StatusUpdate request) {
        if (request == null || !STATUSES.contains(request.status() == null ? "" : request.status())) throw invalid("工单状态无效");
        Mono<Ticket> work = lockActor(actor).then(find(actor, id, true)).flatMap(current -> {
            checkVersion(current, request.version());
            validateTransition(admin(actor), current.status(), request.status());
            if (current.status().equals(request.status())) return Mono.just(current);
            Mono<Void> reopenQuota = !admin(actor) && request.status().equals("OPEN")
                ? db.sql("SELECT count(*) AS n FROM support_tickets WHERE user_id = :user AND status NOT IN ('CLOSED', 'RESOLVED')")
                    .bind("user", actor.userId()).map(row -> row.get("n", Long.class)).one()
                    .flatMap(count -> count < 20 ? Mono.empty() : Mono.error(TicketService.conflict("最多保留 20 个未解决工单，请先处理现有工单")))
                : Mono.empty();
            return reopenQuota.then(checkQuota(actor, List.of(), false))
                .then(insertMessage(actor, id, admin(actor), "STATUS", "", request.status(), List.of()))
                .then(update(id, request.status(), true)).then(find(actor, id));
        });
        return tx.transactional(work).doOnSuccess(t -> audit(actor, t, "STATUS_CHANGED"));
    }

    public Mono<TicketAttachments.Download> download(AdminPrincipal actor, String ticketId, String attachmentId) {
        return find(actor, ticketId).then(db.sql("SELECT filename, data FROM support_ticket_attachments WHERE id = :id AND ticket_id = :ticket")
            .bind("id", attachmentId).bind("ticket", ticketId)
            .map((r, m) -> new TicketAttachments.Download(r.get("filename", String.class), r.get("data", byte[].class)))
            .one().switchIfEmpty(Mono.error(notFound())));
    }

    private Mono<Void> lockActor(AdminPrincipal actor) {
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "support-ticket:" + actor.userId()).fetch().rowsUpdated().then();
    }

    private Mono<Void> checkQuota(AdminPrincipal actor, List<TicketAttachments.Upload> files, boolean creating) {
        if (files.size() > TicketAttachments.MAX_FILES || files.stream().anyMatch(f -> f.bytes().length < 1 || f.bytes().length > TicketAttachments.MAX_BYTES))
            throw invalid("附件数量或体积超出限制");
        long uploadSize = files.stream().mapToLong(f -> f.bytes().length).sum();
        return db.sql("""
            SELECT
              (SELECT count(*) FROM support_tickets WHERE user_id = :actor AND status NOT IN ('CLOSED', 'RESOLVED')) AS active,
              (SELECT count(*) FROM support_tickets WHERE user_id = :actor AND created_at > now() - interval '1 day') AS daily,
              (SELECT count(*) FROM support_ticket_messages WHERE actor_id = :actor AND created_at > now() - interval '1 minute') AS recent,
              (SELECT COALESCE(sum(size_bytes), 0)::bigint FROM support_ticket_attachments WHERE uploader_id = :actor) AS bytes
            """).bind("actor", actor.userId()).map((r, m) -> {
                if (creating && (r.get("active", Long.class) >= 20 || r.get("daily", Long.class) >= 20))
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TICKET_LIMIT", "最多保留 20 个未解决工单，每天最多新建 20 个工单");
                if (r.get("recent", Long.class) >= 20)
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TICKET_RATE_LIMIT", "操作过于频繁，请稍后再试");
                if (uploadSize > 0 && r.get("bytes", Long.class) + uploadSize > 100L * 1024 * 1024)
                    throw conflict("工单附件总容量已达 100 MB，请联系管理员");
                return true;
            }).one().then();
    }

    private Mono<Void> insertMessage(AdminPrincipal actor, String ticket, boolean staff, String kind, String body,
                                     String status, List<TicketAttachments.Upload> files) {
        return db.sql("""
            INSERT INTO support_ticket_messages (ticket_id, actor_id, actor_name, admin, kind, body, status)
            VALUES (:ticket, :actor, :name, :admin, :kind, :body, :status) RETURNING id
            """).bind("ticket", ticket).bind("actor", actor.userId()).bind("name", actor.username()).bind("admin", staff)
            .bind("kind", kind).bind("body", body).bind("status", status).map(r -> r.get("id", Long.class)).one()
            .flatMap(message -> Flux.fromIterable(files).concatMap(file -> db.sql("""
                INSERT INTO support_ticket_attachments (id, message_id, ticket_id, uploader_id, filename, size_bytes, data)
                VALUES (:id, :message, :ticket, :actor, :filename, :size, :data)
                """).bind("id", file.id()).bind("message", message).bind("ticket", ticket).bind("actor", actor.userId())
                .bind("filename", file.filename()).bind("size", file.bytes().length).bind("data", file.bytes()).fetch().rowsUpdated()).then());
    }

    private Mono<Void> update(String id, String status, boolean notifyNow) {
        return db.sql("""
            UPDATE support_tickets SET status = :status, version = version + 1, message_count = message_count + 1,
              updated_at = clock_timestamp(), notify_at = CASE WHEN :notify THEN clock_timestamp() ELSE notify_at END,
              notify_attempts = CASE WHEN :notify THEN 0 ELSE notify_attempts END,
              notify_error = NULL
            WHERE id = :id
            """).bind("id", id).bind("status", status).bind("notify", notifyNow).fetch().rowsUpdated().then();
    }

    static void validateSubmission(Submission request, boolean creating) {
        if (request == null || request.body() == null || request.body().isBlank() || request.body().length() > 20000 || request.body().indexOf('\0') >= 0)
            throw invalid("请输入问题内容，最多 20000 字");
        if (creating && (request.title() == null || request.title().isBlank() || request.title().length() > 160
            || request.title().matches("(?s).*[\\p{Cntrl}].*") || !CATEGORIES.contains(request.category() == null ? "" : request.category())))
            throw invalid("请输入有效的标题和分类，标题最多 160 字");
        if (!creating && (request.version() == null || request.version() < 1)) throw invalid("缺少工单版本，请刷新后重试");
    }

    static void validateTransition(boolean staff, String from, String to) {
        if (!staff && !(to.equals("CLOSED") || to.equals("OPEN") && Set.of("RESOLVED", "CLOSED").contains(from)))
            throw new ApiException(HttpStatus.FORBIDDEN, "TICKET_STATUS_FORBIDDEN", "用户只能关闭工单或重新打开已结束的工单");
    }

    static void checkVersion(Ticket ticket, Long version) {
        if (version == null || ticket.version() != version) throw conflict("工单已有新回复或状态变更，请刷新后重试");
    }

    static Ticket ticket(Row r) {
        return new Ticket(r.get("id", String.class), r.get("user_id", String.class), r.get("username", String.class),
            r.get("title", String.class), r.get("category", String.class), r.get("status", String.class), r.get("version", Long.class),
            r.get("message_count", Integer.class), r.get("created_at", Instant.class), r.get("updated_at", Instant.class));
    }
    static boolean admin(AdminPrincipal actor) { return "ADMIN".equals(actor.role()); }
    static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "TICKET_INVALID", message); }
    static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT", message); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单或附件不存在"); }
    private static void audit(AdminPrincipal actor, Ticket ticket, String action) {
        if (ticket != null) log.info("ticket_action={} ticket_id={} actor_id={} status={} version={}", action, ticket.id(), actor.userId(), ticket.status(), ticket.version());
    }
}
