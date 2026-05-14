package com.raghav.notificationservice.sender;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSender {

    private final JavaMailSender mailSender;

    @Value("${notification.email.from}")
    private String fromAddress;

    @Value("${notification.email.from-name}")
    private String fromName;

    public void send(String to, String subject, String content) {
        log.info("[EMAIL SENDER] Sending email to={}, subject='{}'", to, subject);

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject(subject);

            String htmlContent = wrapInHtmlTemplate(content);
            helper.setText(htmlContent, true);

            mailSender.send(message);

            log.info("[EMAIL SENDER] Email delivered successfully to={}", to);

        } catch (UnsupportedEncodingException e) {
            log.error("[EMAIL SENDER] Encoding error for to={}: {}", to, e.getMessage());
            throw new EmailSendException("Encoding error: " + e.getMessage(), e);

        } catch (MessagingException e) {
            log.error("[EMAIL SENDER] Failed to build message for to={}: {}", to, e.getMessage());
            throw new EmailSendException("Failed to build email message: " + e.getMessage(), e);

        } catch (MailException e) {
            log.error("[EMAIL SENDER] SMTP send failed for to={}: {}", to, e.getMessage());
            throw new EmailSendException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    private String wrapInHtmlTemplate(String content) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <div style="background-color: #f9f9f9; padding: 20px; border-radius: 8px;">
                        %s
                    </div>
                    <p style="font-size: 12px; color: #999; margin-top: 20px;">
                        You received this notification because you have an active account.
                    </p>
                </body>
                </html>
                """.formatted(content);
    }

    public static class EmailSendException extends RuntimeException {
        public EmailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}