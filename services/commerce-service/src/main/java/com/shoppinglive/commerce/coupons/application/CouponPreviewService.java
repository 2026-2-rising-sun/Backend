package com.shoppinglive.commerce.coupons.application;

import com.shoppinglive.commerce.coupons.domain.CouponDiscountAllocator;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Validates a claimed coupon and calculates a non-reserving checkout quote. */
@Service
public class CouponPreviewService {
    public record PricedItem(String key, long productId, long amount) { }
    public record Preview(String couponId, long discountAmount, long payableAmount,
            Map<String, Long> itemDiscounts) { }
    private record Coupon(long fixedDiscount, String status, Instant startsAt, Instant expiresAt) { }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public CouponPreviewService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public CouponPreviewService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Preview preview(String memberId, String couponId, List<PricedItem> items) {
        if (memberId == null || memberId.isBlank() || items == null || items.isEmpty()) {
            throw invalid("회원과 주문 항목이 필요합니다.");
        }
        long total = 0;
        Set<String> keys = new HashSet<>();
        for (PricedItem item : items) {
            if (item == null || item.key() == null || item.key().isBlank()
                    || !keys.add(item.key()) || item.productId() < 1 || item.amount() < 0) {
                throw invalid("주문 금액 또는 상품을 확인해 주세요.");
            }
            try {
                total = Math.addExact(total, item.amount());
            } catch (ArithmeticException exception) {
                throw invalid("주문 금액이 너무 큽니다.");
            }
        }
        if (couponId == null) {
            return new Preview(null, 0, total, zeroDiscounts(items));
        }
        if (couponId.isBlank() || couponId.length() > 64) {
            throw invalid("쿠폰 식별자를 확인해 주세요.");
        }

        Coupon coupon = jdbc.query("""
                SELECT c.fixed_discount,m.status,c.starts_at,c.expires_at
                FROM member_coupon m JOIN coupon_definition c ON c.id=m.coupon_id
                WHERE m.member_id=? AND m.coupon_id=?
                """, result -> result.next()
                    ? new Coupon(result.getLong("fixed_discount"), result.getString("status"),
                        result.getTimestamp("starts_at").toInstant(), result.getTimestamp("expires_at").toInstant())
                    : null, memberId, couponId);
        if (coupon == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "보유 쿠폰을 찾을 수 없습니다.");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (!"AVAILABLE".equals(coupon.status()) || now.isBefore(coupon.startsAt())
                || !now.isBefore(coupon.expiresAt())) {
            throw conflict("사용할 수 없는 쿠폰입니다.");
        }

        Set<Long> targets = new HashSet<>(jdbc.queryForList(
            "SELECT product_id FROM coupon_target WHERE coupon_id=?", Long.class, couponId));
        boolean hasTarget = items.stream().anyMatch(item -> targets.contains(item.productId()));
        if (!hasTarget) {
            throw conflict("쿠폰 적용 대상 상품이 없습니다.");
        }
        List<CouponDiscountAllocator.Item> allocationItems = items.stream()
            .map(item -> new CouponDiscountAllocator.Item(item.key(), item.amount(), targets.contains(item.productId())))
            .toList();
        Map<String, Long> discounts = CouponDiscountAllocator.allocate(coupon.fixedDiscount(), allocationItems);
        long discount = discounts.values().stream().mapToLong(Long::longValue).sum();
        return new Preview(couponId, discount, total - discount, discounts);
    }

    private static Map<String, Long> zeroDiscounts(List<PricedItem> items) {
        return items.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(PricedItem::key, item -> 0L));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
