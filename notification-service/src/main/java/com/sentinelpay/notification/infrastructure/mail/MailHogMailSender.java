package com.sentinelpay.notification.infrastructure.mail;

import com.sentinelpay.notification.application.NotificationEmailRenderer.RenderedEmail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends notification email via SMTP (MailHog in local dev: {@code localhost:1025}).
 */
@Component
public class MailHogMailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public MailHogMailSender(
            JavaMailSender mailSender,
            @Value("${sentinelpay.notification.from:no-reply@sentinelpay.local}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void send(String recipient, RenderedEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(recipient);
        message.setSubject(email.subject());
        message.setText(email.body());
        mailSender.send(message);
    }
}
