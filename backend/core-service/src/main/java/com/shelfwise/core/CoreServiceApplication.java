package com.shelfwise.core;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the ShelfWise core service. */
@SpringBootApplication
public class CoreServiceApplication {

    public static void main(String[] args) {
        // The server always works in UTC; times are stored as exact moments and shown in IST by the apps (D9).
        // This also avoids depending on the host's time-zone name (Windows reports IST as "Asia/Calcutta").
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(CoreServiceApplication.class, args);
    }
}
