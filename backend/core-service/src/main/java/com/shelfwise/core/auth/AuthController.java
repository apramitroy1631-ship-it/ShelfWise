package com.shelfwise.core.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.shelfwise.core.auth.AuthDtos.LoginRequest;
import com.shelfwise.core.auth.AuthDtos.RefreshRequest;
import com.shelfwise.core.auth.AuthDtos.TokenResponse;
import com.shelfwise.core.auth.AuthDtos.UserSummary;

import reactor.core.publisher.Mono;

/** Sign-in API used by the web and mobile apps. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/auth/login")
    public Mono<TokenResponse> login(@Validated @RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password());
    }

    @PostMapping("/auth/refresh")
    public Mono<TokenResponse> refresh(@Validated @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> logout(@Validated @RequestBody RefreshRequest request) {
        return auth.logout(request.refreshToken());
    }

    /** The signed-in user, as the server sees them right now. */
    @GetMapping("/me")
    public Mono<UserSummary> me(@AuthenticationPrincipal UserAccount user) {
        return Mono.just(UserSummary.from(user));
    }
}
