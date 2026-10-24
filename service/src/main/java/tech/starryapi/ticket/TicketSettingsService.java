package tech.starryapi.ticket;

import java.net.URI;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tech.starryapi.admin.SystemSettingEntity;
import tech.starryapi.admin.SystemSettingRepository;
import tech.starryapi.admin.TicketMailer;
import tech.starryapi.ticket.TicketDtos.*;
import tools.jackson.databind.ObjectMapper;

@Service
public class TicketSettingsService {
    private static final String KEY = "support_ticket_notifications";
    private final SystemSettingRepository repository;
    private final ObjectMapper json;
    private final DatabaseClient db;
    private final TicketMailer mailer;
    public TicketSettingsService(SystemSettingRepository repository, ObjectMapper json, DatabaseClient db, TicketMailer mailer) {
        this.repository = repository; this.json = json; this.db = db; this.mailer = mailer;
    }

    public Mono<Settings> get() {
        return repository.findById(KEY).map(SystemSettingEntity::getSettingValue).map(value -> {
            try { return json.readValue(value, Settings.class); }
            catch (Exception e) { throw new IllegalStateException("Invalid ticket notification settings", e); }
        }).defaultIfEmpty(new Settings(false, "SUMMARY", List.of(), 240, ""));
    }

    public Mono<List<String>> administrators() {
        return db.sql("SELECT DISTINCT lower(trim(email)) AS email FROM admin_users WHERE role = 'ADMIN' AND enabled = TRUE AND email IS NOT NULL AND email <> '' ORDER BY email LIMIT 100")
            .map(r -> r.get("email", String.class)).all().collectList();
    }

    public Mono<List<String>> recipients(Settings settings) {
        return administrators().map(all -> settings.recipients().isEmpty() ? all : all.stream().filter(settings.recipients()::contains).toList());
    }

    public Mono<SettingsView> view() {
        return get().flatMap(settings -> Mono.zip(mailer.configured(), administrators(), db.sql("""
            SELECT id, title, notify_error, notify_attempts, notify_at FROM support_tickets
            WHERE notify_error IS NOT NULL AND status IN ('OPEN', 'IN_PROGRESS') ORDER BY notify_at LIMIT 20
            """).map((r, m) -> new NotificationFailure(r.get("id", String.class), r.get("title", String.class),
                r.get("notify_error", String.class), r.get("notify_attempts", Integer.class), r.get("notify_at", java.time.Instant.class)))
            .all().collectList()).map(v -> new SettingsView(settings, v.getT1(),
                settings.recipients().isEmpty() ? v.getT2() : v.getT2().stream().filter(settings.recipients()::contains).toList(), v.getT2(), v.getT3())));
    }

    public Mono<SettingsView> save(Settings request) {
        Settings settings = normalize(request);
        return Mono.zip(mailer.configured(), administrators()).flatMap(v -> {
            if (!v.getT2().containsAll(settings.recipients())) throw TicketService.invalid("收件人必须是已启用管理员的邮箱");
            if (settings.enabled() && (!v.getT1() || v.getT2().isEmpty())) throw TicketService.conflict("请先配置 SMTP，并为管理员设置邮箱");
            try { return repository.upsertSetting(KEY, json.writeValueAsString(settings)); }
            catch (Exception e) { return Mono.error(e); }
        }).then(view());
    }

    public Mono<Void> retry(String id) {
        return get().flatMap(config -> {
            if (!config.enabled()) throw TicketService.conflict("请先启用工单邮件通知");
            return db.sql("""
                UPDATE support_tickets SET notify_at = now(), notify_attempts = 0
                WHERE id = :id AND notify_error IS NOT NULL AND status IN ('OPEN', 'IN_PROGRESS')
                  AND (notify_lease_until IS NULL OR notify_lease_until < now())
                """).bind("id", id).fetch().rowsUpdated().flatMap(n -> n > 0 ? Mono.empty()
                    : Mono.error(TicketService.conflict("此通知正在发送或已无需重试，请刷新查看")));
        });
    }

    static Settings normalize(Settings s) {
        if (s == null || !("SUMMARY".equals(s.mode()) || "CONTENT".equals(s.mode())) || s.reminderMinutes() < 15
            || s.reminderMinutes() > 10080 || s.recipients() == null || s.recipients().size() > 50)
            throw TicketService.invalid("通知模式或提醒间隔无效，间隔范围为 15 分钟至 7 天");
        List<String> recipients = s.recipients().stream().map(email -> {
            if (email == null || email.length() > 254 || !email.matches("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+"))
                throw TicketService.invalid("管理员邮箱格式无效");
            return email.trim().toLowerCase(java.util.Locale.ROOT);
        }).distinct().toList();
        String url = s.consoleUrl() == null ? "" : s.consoleUrl().trim();
        if (!url.isEmpty()) {
            try {
                URI uri = URI.create(url);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null || url.length() > 1000)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) { throw TicketService.invalid("控制台地址必须是有效的 HTTP/HTTPS 地址，不能包含查询参数或锚点"); }
        }
        return new Settings(s.enabled(), s.mode(), recipients, s.reminderMinutes(), url.replaceAll("/+$", ""));
    }
}
