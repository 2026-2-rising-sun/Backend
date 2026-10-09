package com.shoppinglive.commerce.coupons.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Performs coupon reservation transitions in the caller's purchase transaction. */
@Service
public class CouponReservationService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public CouponReservationService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public CouponReservationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void reserve(String memberId, String couponId) {
        if (couponId == null) return;
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int changed = jdbc.update("""
            UPDATE member_coupon m SET status='RESERVED'
            WHERE m.member_id=? AND m.coupon_id=? AND m.status='AVAILABLE'
              AND EXISTS (SELECT 1 FROM coupon_definition c WHERE c.id=m.coupon_id
                AND c.starts_at<=? AND c.ends_at>? AND c.expires_at>?)
            """, memberId, couponId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        if (changed != 1) throw conflict("쿠폰을 예약할 수 없습니다. 주문서를 다시 확인해 주세요.");
    }

    public void confirm(String memberId, String couponId) {
        transition(memberId, couponId, "USED");
    }

    public void release(String memberId, String couponId) {
        transition(memberId, couponId, "AVAILABLE");
    }

    private void transition(String memberId, String couponId, String nextStatus) {
        if (couponId == null) return;
        int changed = jdbc.update("""
            UPDATE member_coupon SET status=?
            WHERE member_id=? AND coupon_id=? AND status='RESERVED'
            """, nextStatus, memberId, couponId);
        if (changed != 1) throw new IllegalStateException("reserved coupon state is inconsistent");
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
