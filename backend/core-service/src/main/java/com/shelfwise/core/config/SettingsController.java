package com.shelfwise.core.config;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.shelfwise.core.auth.UserAccount;
import com.shelfwise.core.config.SettingsService.PublicConfig;
import com.shelfwise.core.config.SettingsService.Setting;

import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/** Remote configuration API (DL-06, M27). */
@RestController
public class SettingsController {

    /** {@code value} is any JSON value (number, true/false, text or null); the setting's rule decides what's allowed. */
    public record ChangeRequest(JsonNode value, @NotNull Long version) {
    }

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    /** Public settings (maintenance banner, feature flags). No sign-in needed, so the banner shows on the login page too. */
    @GetMapping("/api/config")
    public Mono<PublicConfig> config() {
        return settings.publicConfig();
    }

    @GetMapping("/api/admin/settings")
    public Flux<Setting> all(@AuthenticationPrincipal UserAccount user) {
        if (user.role() != UserAccount.Role.ADMIN) {
            return Flux.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins see all settings"));
        }
        return settings.all();
    }

    @PutMapping("/api/admin/settings/{key}")
    public Mono<Setting> change(@AuthenticationPrincipal UserAccount user, @PathVariable String key,
                                @Validated @RequestBody ChangeRequest request) {
        // a JSON null arrives as a Java null here; for the banner it means "hide it"
        JsonNode value = request.value() == null ? tools.jackson.databind.node.NullNode.getInstance() : request.value();
        return settings.update(user, key, value, request.version());
    }
}
