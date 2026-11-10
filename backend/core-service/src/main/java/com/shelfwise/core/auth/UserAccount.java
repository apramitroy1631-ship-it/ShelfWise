package com.shelfwise.core.auth;

/**
 * A user as stored in the {@code users} table.
 * {@code passwordHash} never leaves the server: API responses use {@link AuthDtos.UserSummary}.
 */
public record UserAccount(
        String id,
        String name,
        String email,
        Role role,
        String storeId,
        boolean active,
        String passwordHash) {

    public enum Role { ADMIN, STORE_MANAGER, FINANCE_APPROVER }

    @Override
    public String toString() {
        // Keep the hash out of logs and error messages.
        return "UserAccount[id=" + id + ", role=" + role + ", storeId=" + storeId + ", active=" + active + "]";
    }
}
