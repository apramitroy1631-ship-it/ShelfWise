package com.shelfwise.core.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Store scope rules, checked on the server for every request (never trusted from the app):
 * a store manager works only in their own store; admins and finance see every store.
 */
public final class StoreAccess {

    private StoreAccess() {
    }

    /** For reading: managers are limited to their own store; returns the store to filter by (null = all stores). */
    public static String readableStore(UserAccount user, String requestedStoreId) {
        if (user.role() == UserAccount.Role.STORE_MANAGER) {
            if (requestedStoreId != null && !requestedStoreId.equals(user.storeId())) {
                throw forbidden();
            }
            return user.storeId();
        }
        return requestedStoreId;
    }

    /** For changing stock: a manager in their own store, or an admin. Finance approvers don't change stock. */
    public static void checkCanChangeStock(UserAccount user, String storeId) {
        boolean ownStore = user.role() == UserAccount.Role.STORE_MANAGER && storeId.equals(user.storeId());
        if (!ownStore && user.role() != UserAccount.Role.ADMIN) {
            throw forbidden();
        }
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only work with your own store");
    }
}
