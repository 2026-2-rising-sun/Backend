package com.shoppinglive.commercecore.payment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.shoppinglive.common.core.*;
import java.util.*;

/** 입력 형태·생략 수량까지 원문에 고정한다. 잔여 전량 확장은 접수 트랜잭션에서만 한다. */
public record RefundSelection(
        String mode, List<Item> items, List<Long> cartItemIds, Long expectedAmount) {
    public record Item(long orderId, Integer quantity) {}

    public static RefundSelection parse(JsonNode body) {
        if (body == null) return new RefundSelection("ALL", List.of(), List.of(), null);
        if (!body.isObject()) throw invalid();
        body.fieldNames()
                .forEachRemaining(
                        k -> {
                            if (!Set.of("items", "cartItemIds", "expectedAmount").contains(k))
                                throw invalid();
                        });
        boolean itemMode = body.has("items"), cartMode = body.has("cartItemIds");
        if (itemMode == cartMode) throw invalid();
        Long expected = null;
        if (body.has("expectedAmount")) {
            expected = integer(body.get("expectedAmount"), false);
        }
        JsonNode values = body.get(itemMode ? "items" : "cartItemIds");
        if (!values.isArray() || values.isEmpty() || values.size() > 100) throw invalid();
        List<Item> items = new ArrayList<>();
        List<Long> carts = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (JsonNode value : values) {
            long id;
            if (itemMode) {
                if (!value.isObject()) throw invalid();
                value.fieldNames()
                        .forEachRemaining(
                                k -> {
                                    if (!Set.of("orderId", "quantity").contains(k)) throw invalid();
                                });
                id = integer(value.get("orderId"), true);
                Integer quantity = null;
                if (value.has("quantity")) {
                    long q = integer(value.get("quantity"), true);
                    if (q > Integer.MAX_VALUE) throw invalid();
                    quantity = (int) q;
                }
                items.add(new Item(id, quantity));
            } else {
                id = integer(value, true);
                carts.add(id);
            }
            if (!ids.add(id)) throw invalid();
        }
        return new RefundSelection(
                itemMode ? "ITEMS" : "CART",
                items.stream().sorted(Comparator.comparingLong(Item::orderId)).toList(),
                carts.stream().sorted().toList(),
                expected);
    }

    private static long integer(JsonNode node, boolean positive) {
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToLong()
                || node.longValue() < (positive ? 1 : 0)) throw invalid();
        return node.longValue();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "환불 항목·수량·예상 금액 형식이 올바르지 않습니다.");
    }
}
