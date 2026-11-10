package com.shelfwise.core.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request and response bodies of the sign-in API. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";   // never log a password
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";
        }
    }

    /** What the apps may know about the signed-in user. */
    public record UserSummary(String id, String name, String role, String storeId) {
        static UserSummary from(UserAccount user) {
            return new UserSummary(user.id(), user.name(), user.role().name(), user.storeId());
        }
    }

    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String refreshToken,
            UserSummary user) {
    }
}
