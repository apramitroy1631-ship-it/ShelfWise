package com.shelfwise.core.review;

/**
 * D33: an imported line with a quantity above {@link #LIMIT} is held for admin review, like a negative one (D10).
 * The real orders only ever use 1, 2, 3, 4, 6 or 12 (case-pack sizes); 50 is about four times the biggest, so
 * it catches typos (500 for 50) without ever touching a genuine order in the provided data. The order is kept as
 * received and left out of revenue until an admin confirms or corrects the quantity.
 */
public final class LargeQuantity {

    public static final int LIMIT = 50;

    private LargeQuantity() {
    }

    public static boolean isLarge(int qty) {
        return qty > LIMIT;
    }
}
