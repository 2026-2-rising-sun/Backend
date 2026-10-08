package com.shoppinglive.commerce.coupons.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allocates a fixed discount using integer largest remainders over eligible orders. */
public final class CouponDiscountAllocator {
    public record Item(String orderNumber, long amount, boolean eligible) { }
    private record Share(String number, long amount, BigInteger remainder) { }

    private CouponDiscountAllocator() { }

    public static Map<String, Long> allocate(long fixedDiscount, List<Item> items) {
        if (fixedDiscount <= 0 || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("positive discount and nonempty items are required");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        BigInteger subtotal = BigInteger.ZERO;
        for (Item item : items) {
            if (item == null || item.orderNumber() == null || item.orderNumber().isBlank()
                    || item.amount() < 0 || result.putIfAbsent(item.orderNumber(), 0L) != null) {
                throw new IllegalArgumentException("unique order numbers and nonnegative amounts are required");
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
            shares.add(new Share(item.orderNumber(), amount, parts[1]));
            allocated = Math.addExact(allocated, amount);
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparing(Share::number));
        long remaining = discount - allocated;
        for (int index = 0; index < shares.size(); index++) {
            Share share = shares.get(index);
            result.put(share.number(), share.amount() + (index < remaining ? 1 : 0));
        }
        return Map.copyOf(result);
    }
}
