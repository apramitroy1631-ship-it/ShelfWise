package com.shelfwise.notification;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the ShelfWise notification service. */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        // Same rule as the core service: servers work in UTC; apps display IST (D9).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
