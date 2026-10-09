package com.shoppinglive.commerce.coupons.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allocates a fixed discount using integer largest remainders over eligible orders. */
public final class CouponDiscountAllocator {
    public record Item(String key, long amount, boolean eligible) { }
    private record Share(String key, long amount, BigInteger remainder) { }

    private CouponDiscountAllocator() { }

    public static Map<String, Long> allocate(long fixedDiscount, List<Item> items) {
        if (fixedDiscount <= 0 || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("positive discount and nonempty items are required");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        BigInteger subtotal = BigInteger.ZERO;
        for (Item item : items) {
            if (item == null || item.key() == null || item.key().isBlank()
                    || item.amount() < 0 || result.putIfAbsent(item.key(), 0L) != null) {
                throw new IllegalArgumentException("unique allocation keys and nonnegative amounts are required");
            }
            if (item.eligible()) subtotal = subtotal.add(BigInteger.valueOf(item.amount()));
        }
        if (subtotal.signum() == 0) return Map.copyOf(result);
        long discount = subtotal.min(BigInteger.valueOf(fixedDiscount)).longValueExact();
        List<Share> shares = new ArrayList<>();
        long allocated = 0;
        for (Item item : items) {
            if (!item.eligible() || item.amount() == 0) continue;
            BigInteger[] parts = BigInteger.valueOf(discount).multiply(BigInteger.valueOf(item.amount()))
                    .divideAndRemainder(subtotal);
            long amount = parts[0].longValueExact();
            shares.add(new Share(item.key(), amount, parts[1]));
            allocated = Math.addExact(allocated, amount);
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed()
                .thenComparing(Share::key, CouponDiscountAllocator::compareAllocationKeys));
        long remaining = discount - allocated;
        for (int index = 0; index < shares.size(); index++) {
            Share share = shares.get(index);
            result.put(share.key(), share.amount() + (index < remaining ? 1 : 0));
        }
        return Map.copyOf(result);
    }

    private static int compareAllocationKeys(String left, String right) {
        Long leftCartItemId = cartItemId(left);
        Long rightCartItemId = cartItemId(right);
        if (leftCartItemId != null && rightCartItemId != null) {
            return Long.compare(leftCartItemId, rightCartItemId);
        }
        return left.compareTo(right);
    }

    private static Long cartItemId(String key) {
        if (!key.startsWith("cart:")) return null;
        try {
            long itemId = Long.parseLong(key.substring("cart:".length()));
            return itemId > 0 ? itemId : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
