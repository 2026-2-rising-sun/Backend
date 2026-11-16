package com.shoppinglive.commercecore.coupon.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 호출자의 구매 트랜잭션 안에서 쿠폰 예약 상태를 변경한다. */
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
        reserveAt(memberId, couponId, Instant.now(clock));
    }

    public void reserveAt(String memberId, String couponId, Instant at) {
        if (couponId == null) {
            return;
        }
        Instant now = at.truncatedTo(ChronoUnit.MICROS);
        int changed =
                jdbc.update(
                        """
            UPDATE member_coupon m SET status='RESERVED'
            WHERE m.member_id=? AND m.coupon_id=? AND m.status='AVAILABLE'
              AND EXISTS (SELECT 1 FROM coupon_definition c WHERE c.id=m.coupon_id
                AND c.starts_at<=? AND c.expires_at>?)
            """,
                        memberId,
                        couponId,
                        Timestamp.from(now),
                        Timestamp.from(now));
        if (changed != 1) {
            throw conflict("쿠폰을 예약할 수 없습니다. 주문서를 다시 확인해 주세요.");
        }
    }

    public void confirm(String memberId, String couponId) {
        transition(memberId, couponId, "USED");
    }

    public void confirmForOrder(String memberId, String couponId, long groupId) {
        if (couponId == null) return;
        if (jdbc.update(
                        "UPDATE member_coupon SET status='USED',used_payment_group_id=? WHERE member_id=? AND coupon_id=? AND status='RESERVED'",
                        groupId,
                        memberId,
                        couponId)
                != 1) throw new IllegalStateException("예약된 쿠폰의 상태가 일치하지 않습니다.");
    }

    public void restoreForOrder(String memberId, String couponId, long groupId) {
        if (couponId == null) return;
        // 과거 환불 결과가 이미 다른 주문에서 재사용한 쿠폰을 다시 풀지 못하게 한다.
        jdbc.update(
                "UPDATE member_coupon SET status='AVAILABLE',used_payment_group_id=NULL WHERE member_id=? AND coupon_id=? AND status='USED' AND used_payment_group_id=?",
                memberId,
                couponId,
                groupId);
    }

    public void release(String memberId, String couponId) {
        transition(memberId, couponId, "AVAILABLE");
    }

    private void transition(String memberId, String couponId, String nextStatus) {
        if (couponId == null) {
            return;
        }
        int changed =
                jdbc.update(
                        """
            UPDATE member_coupon SET status=?
            WHERE member_id=? AND coupon_id=? AND status='RESERVED'
            """,
                        nextStatus,
                        memberId,
                        couponId);
        if (changed != 1) {
            throw new IllegalStateException("예약된 쿠폰의 상태가 일치하지 않습니다.");
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
