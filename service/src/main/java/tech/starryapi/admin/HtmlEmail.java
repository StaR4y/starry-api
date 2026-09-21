package tech.starryapi.admin;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.util.HtmlUtils;

/**
 * HTML + plain multipart mail for Starry API.
 *
 * Colors come from {@link BrandTheme}; change the console palette and this theme
 * in the same change.
 */
final class HtmlEmail {

    static final String LOGO_CONTENT_ID = "starry-logo";
    static final String LOGO_RESOURCE = "email/logo.png";
    static String registrationSubject() {
        return "完成 " + AppearanceRuntime.siteName() + " 账号注册";
    }

    static String smtpVerifySubject() {
        return "验证 " + AppearanceRuntime.siteName() + " SMTP 发信";
    }

    private static final String FONT =
        "-apple-system,BlinkMacSystemFont,'PingFang SC','Helvetica Neue','Microsoft YaHei',Arial,sans-serif";
    private static final DateTimeFormatter INSTANT_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private HtmlEmail() {
    }

    static void write(
        MimeMessageHelper helper,
        String plain,
        String html
    ) throws Exception {
        helper.setText(plain, html);
        ClassPathResource logo = new ClassPathResource(LOGO_RESOURCE);
        if (logo.exists()) {
            helper.addInline(LOGO_CONTENT_ID, logo, "image/png");
        }
    }

    static MimeMessageHelper multipartHelper(jakarta.mail.internet.MimeMessage message) throws Exception {
        return new MimeMessageHelper(
            message,
            MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
            StandardCharsets.UTF_8.name()
        );
    }

    static String registrationPlain(String link) {
        return """
            完成账号注册

            你正在注册 %s 控制台账号。请打开以下链接继续。
            链接 10 分钟内有效，且只能使用一次。

            %s

            若非本人操作，请忽略本邮件。
            """.formatted(AppearanceRuntime.siteName(), link == null ? "" : link);
    }

    static String registrationHtml(String link) {
        return registrationHtml(link, BrandTheme.current());
    }

    static String registrationHtml(String link, BrandTheme theme) {
        String safeLink = link == null ? "" : link;
        String href = esc(safeLink);
        String content = """
            <tr>
              <td style="padding:36px 32px 8px;font-family:{{font}};color:{{text}};">
                <p style="margin:0 0 8px;font-size:13px;color:{{textMuted}};">注册验证</p>
                <h1 style="margin:0 0 16px;font-size:22px;line-height:1.4;font-weight:600;">完成账号注册</h1>
                <p style="margin:0 0 8px;font-size:15px;line-height:1.75;color:{{textSecondary}};">
                  你正在注册 {{siteName}} 控制台账号。请点击下方按钮继续。
                </p>
                <p style="margin:0 0 24px;font-size:14px;line-height:1.7;color:{{textMuted}};">
                  链接 10 分钟内有效，且只能使用一次。
                </p>
                <table role="presentation" cellspacing="0" cellpadding="0">
                  <tr>
                    <td bgcolor="{{primary}}" style="background:{{primary}};">
                      <a href="{{href}}" style="display:inline-block;padding:11px 22px;font-family:{{font}};font-size:14px;font-weight:600;color:{{onHeader}};text-decoration:none;">继续注册</a>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
            <tr>
              <td style="padding:28px 32px 0;">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
                  <tr>
                    <td style="height:1px;line-height:1px;font-size:0;background:{{rule}};">&nbsp;</td>
                  </tr>
                </table>
              </td>
            </tr>
            <tr>
              <td style="padding:18px 32px 32px;font-family:{{font}};">
                <p style="margin:0 0 6px;font-size:12px;color:{{textMuted}};">如果按钮无法打开，请将链接复制到浏览器：</p>
                <p style="margin:0 0 20px;font-size:12px;line-height:1.6;word-break:break-all;">
                  <a href="{{href}}" style="color:{{primary}};text-decoration:underline;">{{href}}</a>
                </p>
                <p style="margin:0;font-size:12px;line-height:1.7;color:{{textMuted}};">
                  若非本人操作，请忽略本邮件。<br>
                  © {{year}} {{siteName}}
                </p>
              </td>
            </tr>
            """;
        return wrap(
            theme,
            "完成账号注册",
            "验证链接 10 分钟内有效，请尽快完成注册。",
            headerRow(theme, ""),
            fill(content, Map.of("href", href))
        );
    }

    static String smtpVerifyPlain(String link) {
        return """
            验证 SMTP 发信

            你正在配置 %s 的邮件发送服务。请打开以下链接完成 SMTP 验证。
            链接 30 分钟内有效，且只能使用一次。

            %s

            若非本人操作，请忽略本邮件。
            """.formatted(AppearanceRuntime.siteName(), link == null ? "" : link);
    }

    static String smtpVerifyHtml(String link) {
        return smtpVerifyHtml(link, BrandTheme.current());
    }

    static String smtpVerifyHtml(String link, BrandTheme theme) {
        String safeLink = link == null ? "" : link;
        String href = esc(safeLink);
        String content = """
            <tr>
              <td style="padding:36px 32px 8px;font-family:{{font}};color:{{text}};">
                <p style="margin:0 0 8px;font-size:13px;color:{{textMuted}};">SMTP 验证</p>
                <h1 style="margin:0 0 16px;font-size:22px;line-height:1.4;font-weight:600;">验证邮件发送服务</h1>
                <p style="margin:0 0 8px;font-size:15px;line-height:1.75;color:{{textSecondary}};">
                  你正在配置 {{siteName}} 的 SMTP 发信。请点击下方按钮完成验证。
                </p>
                <p style="margin:0 0 24px;font-size:14px;line-height:1.7;color:{{textMuted}};">
                  链接 30 分钟内有效，且只能使用一次。
                </p>
                <table role="presentation" cellspacing="0" cellpadding="0">
                  <tr>
                    <td bgcolor="{{primary}}" style="background:{{primary}};">
                      <a href="{{href}}" style="display:inline-block;padding:11px 22px;font-family:{{font}};font-size:14px;font-weight:600;color:{{onHeader}};text-decoration:none;">完成验证</a>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
            <tr>
              <td style="padding:28px 32px 0;">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
                  <tr>
                    <td style="height:1px;line-height:1px;font-size:0;background:{{rule}};">&nbsp;</td>
                  </tr>
                </table>
              </td>
            </tr>
            <tr>
              <td style="padding:18px 32px 32px;font-family:{{font}};">
                <p style="margin:0 0 6px;font-size:12px;color:{{textMuted}};">如果按钮无法打开，请将链接复制到浏览器：</p>
                <p style="margin:0 0 20px;font-size:12px;line-height:1.6;word-break:break-all;">
                  <a href="{{href}}" style="color:{{primary}};text-decoration:underline;">{{href}}</a>
                </p>
                <p style="margin:0;font-size:12px;line-height:1.7;color:{{textMuted}};">
                  若非本人操作，请忽略本邮件。<br>
                  © {{year}} {{siteName}}
                </p>
              </td>
            </tr>
            """;
        return wrap(
            theme,
            "验证 SMTP 发信",
            "验证链接 30 分钟内有效，请尽快完成 SMTP 验证。",
            headerRow(theme, ""),
            fill(content, Map.of("href", href))
        );
    }

    static String securityHtml(DataManagementSecurityAuditEntity audit) {
        return securityHtml(audit, BrandTheme.current());
    }

    static String securityHtml(DataManagementSecurityAuditEntity audit, BrandTheme theme) {
        boolean urgent = isUrgent(audit);
        int attempt = safeAttempt(audit);
        boolean locked = audit != null && Boolean.TRUE.equals(audit.getLocked());
        String kicker = urgent
            ? "紧急 · 第 " + attempt + " 次错误密钥"
            : "高危 · 第 " + attempt + " 次失败";
        String title = urgent ? "后台极可能已遭入侵" : "数据管理安全密钥验证失败";
        String intro = urgent
            ? "数据管理功能已锁定。请立即确认是否为授权操作，并轮换安全密钥、检查管理员账号。"
            : "请立即确认是否为授权操作。若非本人操作，请尽快轮换安全密钥并检查管理员账号。";
        String metricBg = urgent ? theme.surfaceDanger() : theme.surface();
        String metricBorder = urgent ? "#ead4d4" : theme.rule();
        String lockedColor = locked ? theme.danger() : theme.text();
        String headerLabel = urgent ? "紧急告警" : "安全告警";
        String preheader = urgent
            ? "后台极可能已遭入侵，数据管理已锁定，请立即处理。"
            : "数据管理安全密钥验证失败，请立即确认是否为授权操作。";

        String accountRows = rows(
            theme,
            new String[][] {
                {"发起账号", show(audit == null ? null : audit.getActorUsername())},
                {"用户 ID", show(audit == null ? null : audit.getActorUserId())},
                {"账号邮箱", show(audit == null ? null : audit.getActorEmail())},
                {"显示名称", show(audit == null ? null : audit.getActorDisplayName())},
                {"账号角色", show(audit == null ? null : audit.getActorRole())},
                {"账号已启用", showEnabled(audit == null ? null : audit.getActorEnabled())},
                {"是否为 root", yesNo(audit != null && Boolean.TRUE.equals(audit.getActorIsRoot()))},
                {"账号创建时间", showInstant(audit == null ? null : audit.getActorCreatedAt())},
                {"最近登录时间", showInstant(audit == null ? null : audit.getActorLastLoginAt())}
            }
        );
        String requestRows = rows(
            theme,
            new String[][] {
                {"事件时间", showInstant(audit == null ? null : audit.getOccurredAt())},
                {"远端地址", show(audit == null ? null : audit.getRemoteAddress())},
                {
                    "代理转发地址",
                    show(DataManagementSecuritySanitizer.forwardedAddresses(
                        audit == null ? null : audit.getForwardedAddress()
                    ))
                },
                {"请求端点", show(audit == null ? null : audit.getEndpoint())},
                {
                    "User-Agent 指纹",
                    show(DataManagementSecuritySanitizer.fingerprint(
                        audit == null ? null : audit.getUserAgent()
                    ))
                },
                {"会话指纹", show(audit == null ? null : audit.getSessionFingerprint())},
                {
                    "请求 ID 指纹",
                    show(DataManagementSecuritySanitizer.fingerprint(
                        audit == null ? null : audit.getRequestId()
                    ))
                }
            }
        );

        String content = """
            <tr>
              <td style="padding:32px 32px 8px;font-family:{{font}};color:{{text}};">
                <p style="margin:0 0 8px;font-size:13px;color:{{danger}};">{{kicker}}</p>
                <h1 style="margin:0 0 12px;font-size:20px;line-height:1.4;font-weight:600;">{{title}}</h1>
                <p style="margin:0;font-size:14px;line-height:1.75;color:{{textSecondary}};">{{intro}}</p>
              </td>
            </tr>
            <tr>
              <td style="padding:24px 32px 8px;font-family:{{font}};">
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:{{metricBg}};">
                  <tr>
                    <td width="50%" style="padding:16px 18px;">
                      <p style="margin:0 0 4px;font-size:12px;color:{{textMuted}};">连续失败次数</p>
                      <p style="margin:0;font-size:22px;line-height:1.2;font-weight:600;color:{{danger}};">{{attempt}}</p>
                    </td>
                    <td width="50%" style="padding:16px 18px;border-left:1px solid {{metricBorder}};">
                      <p style="margin:0 0 4px;font-size:12px;color:{{textMuted}};">系统已锁定</p>
                      <p style="margin:0;font-size:22px;line-height:1.2;font-weight:600;color:{{lockedColor}};">{{locked}}</p>
                    </td>
                  </tr>
                </table>
              </td>
            </tr>
            <tr>
              <td style="padding:24px 32px 6px;font-family:{{font}};">
                <p style="margin:0 0 8px;font-size:13px;font-weight:600;color:{{text}};">账号</p>
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0">{{accountRows}}</table>
              </td>
            </tr>
            <tr>
              <td style="padding:22px 32px 6px;font-family:{{font}};">
                <p style="margin:0 0 8px;font-size:13px;font-weight:600;color:{{text}};">请求</p>
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0">{{requestRows}}</table>
              </td>
            </tr>
            <tr>
              <td style="padding:22px 32px 32px;font-family:{{font}};font-size:12px;line-height:1.7;color:{{textMuted}};">
                本邮件不含请求原文或密钥内容。请登录控制台核对数据管理安全设置。<br>
                © {{year}} {{siteName}} · 系统通知，请勿直接回复
              </td>
            </tr>
            """;

        return wrap(
            theme,
            title,
            preheader,
            headerRow(theme, headerLabel),
            fill(
                content,
                Map.of(
                    "kicker", esc(kicker),
                    "title", esc(title),
                    "intro", esc(intro),
                    "metricBg", metricBg,
                    "metricBorder", metricBorder,
                    "attempt", Integer.toString(attempt),
                    "lockedColor", lockedColor,
                    "locked", locked ? "是" : "否",
                    "accountRows", accountRows,
                    "requestRows", requestRows
                )
            )
        );
    }

    static boolean isUrgent(DataManagementSecurityAuditEntity audit) {
        return safeAttempt(audit) >= 5 || (audit != null && Boolean.TRUE.equals(audit.getLocked()));
    }

    private static String wrap(
        BrandTheme theme,
        String title,
        String preheader,
        String headerRow,
        String body
    ) {
        String shell = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>{{title}}</title>
            </head>
            <body style="margin:0;padding:0;background:{{page}};">
              <div style="display:none;max-height:0;overflow:hidden;font-size:1px;line-height:1px;color:{{page}};">{{preheader}}</div>
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="background:{{page}};">
                <tr>
                  <td align="center" style="padding:36px 12px;">
                    <table role="presentation" width="560" cellspacing="0" cellpadding="0" style="width:100%;max-width:560px;background:#ffffff;border:1px solid {{cardBorder}};">
                      <tr>
                        <td height="4" bgcolor="{{primary}}" style="height:4px;line-height:4px;font-size:0;background:{{gradient}};background-color:{{primary}};">&nbsp;</td>
                      </tr>
                      <tr>
                        <td style="background:{{header}};padding:18px 32px;">{{headerRow}}</td>
                      </tr>
                      {{body}}
                    </table>
                  </td>
                </tr>
              </table>
            </body>
            </html>
            """;
        return fill(
            shell,
            Map.of(
                "title", esc(title),
                "preheader", esc(preheader),
                "headerRow", headerRow,
                "body", body
            ),
            theme
        );
    }

    private static String headerRow(BrandTheme theme, String rightLabel) {
        String right = rightLabel == null || rightLabel.isBlank()
            ? ""
            : """
              <td align="right" style="vertical-align:middle;font-family:{{font}};font-size:13px;color:{{onHeaderMuted}};">{{rightLabel}}</td>
              """;
        String row = """
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
              <tr>
                <td style="vertical-align:middle;width:28px;padding-right:10px;">
                  <img src="cid:{{logoId}}" width="28" height="28" alt="" style="display:block;border:0;">
                </td>
                <td style="vertical-align:middle;font-family:{{font}};font-size:15px;font-weight:600;color:{{onHeader}};">{{siteName}}</td>
                {{right}}
              </tr>
            </table>
            """;
        return fill(
            row,
            Map.of(
                "logoId", LOGO_CONTENT_ID,
                "right", fill(right, Map.of("rightLabel", esc(rightLabel)), theme)
            ),
            theme
        );
    }

    private static String rows(BrandTheme theme, String[][] fields) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            boolean last = i == fields.length - 1;
            String border = last ? "" : "border-bottom:1px solid " + theme.rule() + ";";
            html.append("""
                <tr>
                  <td style="width:34%%;padding:9px 0;font-size:12px;color:%s;%s">%s</td>
                  <td style="padding:9px 0;font-size:13px;color:%s;%s;word-break:break-all;">%s</td>
                </tr>
                """.formatted(
                theme.textMuted(),
                border,
                esc(fields[i][0]),
                theme.text(),
                border,
                esc(fields[i][1])
            ));
        }
        return html.toString();
    }

    private static String fill(String template, Map<String, String> values) {
        return fill(template, values, BrandTheme.current());
    }

    private static String fill(String template, Map<String, String> values, BrandTheme theme) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("font", FONT);
        merged.put("primary", theme.primary());
        merged.put("primaryMid", theme.primaryMid());
        merged.put("accent", theme.accent());
        merged.put("danger", theme.danger());
        merged.put("header", theme.header());
        merged.put("page", theme.page());
        merged.put("cardBorder", theme.cardBorder());
        merged.put("rule", theme.rule());
        merged.put("surface", theme.surface());
        merged.put("surfaceDanger", theme.surfaceDanger());
        merged.put("text", theme.text());
        merged.put("textSecondary", theme.textSecondary());
        merged.put("textMuted", theme.textMuted());
        merged.put("onHeader", theme.onHeader());
        merged.put("onHeaderMuted", theme.onHeaderMuted());
        merged.put("gradient", theme.headerGradientCss());
        merged.put("year", Integer.toString(Year.now(ZoneOffset.UTC).getValue()));
        merged.put("siteName", esc(AppearanceRuntime.siteName()));
        merged.putAll(values);
        String out = template;
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    private static String esc(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    private static String show(String value) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isBlank() ? "未提供" : cleaned;
    }

    private static String showInstant(Instant value) {
        return value == null ? "未提供" : INSTANT_FORMAT.format(value);
    }

    private static String showEnabled(Boolean enabled) {
        return enabled == null ? "未提供" : yesNo(Boolean.TRUE.equals(enabled));
    }

    private static String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private static int safeAttempt(DataManagementSecurityAuditEntity audit) {
        return audit == null || audit.getConsecutiveFailures() == null
            ? 0
            : Math.max(0, audit.getConsecutiveFailures());
    }
}
