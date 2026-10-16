package com.shoppinglive.commerce.coupon.event;

import com.shoppinglive.commerce.coupon.service.CouponClaimService.MemberCoupon;
import com.shoppinglive.commerce.coupon.service.CouponSnapshotService;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CouponDisplayEventPublisher {
    public static final String TOPIC = "commerce.coupon-info.v1";

    public enum Type {
        CouponCreated,
        CouponUpdated,
        CouponSoldOut
    }

    private final CouponSnapshotService snapshots;
    private final EventOutbox outbox;

    public CouponDisplayEventPublisher(CouponSnapshotService snapshots, EventOutbox outbox) {
        this.snapshots = snapshots;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void issued(String memberId, MemberCoupon coupon) {
        var snapshot = snapshots.get(coupon.couponId());
        // 기존 Shopping 소비자가 동일 토픽의 표시 사본을 계속 읽을 수 있도록 전체 사본을 유지한다.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("couponId", snapshot.couponId());
        payload.put("snapshotVersion", snapshot.snapshotVersion());
        payload.put("name", snapshot.name());
        payload.put("fixedDiscount", snapshot.fixedDiscount());
        payload.put("productIds", snapshot.productIds());
        payload.put("startsAt", snapshot.startsAt());
        payload.put("endsAt", snapshot.endsAt());
        payload.put("expiresAt", snapshot.expiresAt());
        payload.put("soldOut", snapshot.soldOut());
        payload.put("memberId", memberId);
        payload.put("memberCouponId", coupon.id());
        payload.put("claimedAt", coupon.claimedAt());
        outbox.append(
                TOPIC,
                EventEnvelope.create(
                        "CouponIssued",
                        1,
                        "commerce-service",
                        coupon.couponId(),
                        Instant.now(),
                        payload));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Type type, String couponId) {
        outbox.append(
                TOPIC,
                EventEnvelope.create(
                        type.name(),
                        1,
                        "commerce-service",
                        couponId,
                        Instant.now(),
                        snapshots.get(couponId)));
    }
}
