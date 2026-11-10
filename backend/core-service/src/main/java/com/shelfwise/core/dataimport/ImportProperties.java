package com.shelfwise.core.dataimport;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Import settings from application.yml (prefix {@code shelfwise.import}). */
@ConfigurationProperties("shelfwise.import")
public record ImportProperties(boolean enabled, Path dataDir, String demoPassword) {

    @Override
    public String toString() {
        return "ImportProperties[enabled=" + enabled + ", dataDir=" + dataDir + ", demoPassword=***]";
    }
}
