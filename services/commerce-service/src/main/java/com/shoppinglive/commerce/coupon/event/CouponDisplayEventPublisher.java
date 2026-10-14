package com.shoppinglive.commerce.coupon.event;

import com.shoppinglive.commerce.coupon.service.CouponSnapshotService;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import java.time.Instant;
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
