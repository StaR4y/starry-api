package tech.starryapi.admin;

import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tech.starryapi.common.ApiException;

@Service
public class PluginVerificationMailer {
    private final RegistrationSettingsService settings;

    public PluginVerificationMailer(RegistrationSettingsService settings) { this.settings = settings; }

    public Mono<Void> send(String recipient, String code) {
        return settings.smtpConfig().flatMap(config -> {
            if (!config.configured()) return Mono.error(new ApiException(HttpStatus.CONFLICT,
                "SMTP_NOT_CONFIGURED", "请先配置 SMTP，开发者模式不能跳过邮箱验证"));
            return Mono.fromRunnable(() -> {
                try {
                    JavaMailSenderImpl sender = new JavaMailSenderImpl();
                    sender.setHost(config.host());
                    sender.setPort(config.port());
                    sender.setUsername(config.username());
                    sender.setPassword(config.password());
                    sender.setDefaultEncoding("UTF-8");
                    EmailVerificationService.configureSmtpProperties(sender.getJavaMailProperties(), config);
                    var message = sender.createMimeMessage();
                    var helper = new MimeMessageHelper(message, "UTF-8");
                    helper.setFrom(
                        EmailVerificationService.mailFromAddress(config),
                        config.fromName().isBlank() ? "Starry API" : config.fromName()
                    );
                    helper.setTo(recipient);
                    helper.setSubject("[安全验证] 开启插件开发者模式");
                    helper.setText("""
                        你正在为 Starry API 开启插件开发者模式。

                        六位验证码：%s

                        验证码 10 分钟内有效，只能使用一次，请勿转发。
                        第三方插件将在服务进程内以完整信任运行，可访问业务 API、数据、文件和网络。
                        未经审查的插件可能泄露数据或中断服务。
                        如非本人操作，请勿输入验证码，并立即检查 root 账号安全。
                        """.formatted(code));
                    sender.send(message);
                } catch (Exception error) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLUGIN_EMAIL_FAILED",
                        "验证码发送失败，请检查 SMTP 配置和服务器网络");
                }
            }).subscribeOn(Schedulers.boundedElastic()).then();
        });
    }
}
