package tech.starryapi.admin;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;
import tech.starryapi.ticket.TicketDtos.*;

@Service
public class TicketMailer {
    private final RegistrationSettingsService smtp;
    public TicketMailer(RegistrationSettingsService smtp) { this.smtp = smtp; }
    public Mono<Boolean> configured() { return smtp.smtpConfig().map(RegistrationSettingsService.SmtpConfig::configured); }

    public Mono<Void> send(Ticket ticket, List<Message> messages, Settings settings, List<String> recipients) {
        return smtp.smtpConfig().flatMap(config -> {
            if (!config.configured()) return Mono.error(new ApiException(HttpStatus.CONFLICT, "SMTP_NOT_CONFIGURED", "SMTP 未配置"));
            if (recipients.isEmpty()) return Mono.error(new ApiException(HttpStatus.CONFLICT, "NO_ADMIN_RECIPIENTS", "管理员邮箱未配置"));
            return Mono.fromRunnable(() -> {
                try {
                    JavaMailSenderImpl sender = new JavaMailSenderImpl();
                    sender.setHost(config.host()); sender.setPort(config.port());
                    sender.setUsername(config.username()); sender.setPassword(config.password()); sender.setDefaultEncoding("UTF-8");
                    EmailVerificationService.configureSmtpProperties(sender.getJavaMailProperties(), config);
                    var mail = sender.createMimeMessage();
                    var helper = new MimeMessageHelper(mail, "UTF-8");
                    helper.setFrom(EmailVerificationService.mailFromAddress(config), config.fromName().isBlank() ? "Starry API" : config.fromName());
                    helper.setBcc(recipients.toArray(String[]::new));
                    helper.setSubject("[待处理工单] #" + ticket.id().substring(0, 8) + " " + ticket.title());
                    mail.setHeader("X-Starry-Ticket-Id", ticket.id());
                    helper.setText(body(ticket, messages, settings));
                    sender.send(mail);
                } catch (Exception e) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TICKET_EMAIL_FAILED", "工单邮件发送失败，请检查 SMTP 配置和网络");
                }
            }).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }

    static String body(Ticket ticket, List<Message> messages, Settings settings) {
        StringBuilder text = new StringBuilder("有工单等待管理员处理。\n\n")
            .append("工单编号：").append(ticket.id()).append("\n标题：").append(ticket.title())
            .append("\n提交人：").append(ticket.username()).append("\n状态：").append(ticket.status()).append('\n');
        if ("CONTENT".equals(settings.mode())) {
            text.append("\n最近的沟通内容（完整记录及附件请登录控制台查看）：\n");
            for (Message message : messages.reversed()) {
                text.append("\n--- ").append(message.admin() ? "管理员" : "用户").append(" · ")
                    .append(message.actorName()).append(" · ").append(message.createdAt()).append(" ---\n")
                    .append(message.kind().equals("STATUS") ? "状态变更：" + message.status() : message.body()).append('\n');
                for (Attachment file : message.attachments()) text.append("附件：").append(file.filename()).append('\n');
            }
        }
        if (!settings.consoleUrl().isBlank()) text.append("\n处理工单：").append(settings.consoleUrl()).append("/#/tickets/").append(ticket.id()).append('\n');
        return text.append("\n本邮箱不接收工单回复，请在控制台处理。\n").toString();
    }
}
