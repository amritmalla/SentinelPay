package com.sentinelpay.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification Service — consumes business events and delivers notifications (email),
 * idempotent per event id.
 */
@SpringBootApplication(scanBasePackages = "com.sentinelpay")
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
