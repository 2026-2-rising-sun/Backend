package com.shoppinglive.shopping.coupon.event;

import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code commerce.coupon-info.v1} 을 받아 쿠폰 replica 에 반영한다.
 *
 * <p>해석·검증 실패는 {@link CouponInfoEventMessage.InvalidMessageException} 으로 던진다. 건너뛸지 다시 할지는 error
 * handler 가 정한다 ({@code EventConsumerConfiguration}). 신규 쿠폰 동시 INSERT 의 키 충돌도 그대로 던져 다시 처리되게 한다.
 */
@Component
public class CouponInfoEventListener {

    private final CouponDisplayRepository repository;

    public CouponInfoEventListener(CouponDisplayRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = CouponInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        CouponSnapshot snapshot = CouponInfoEventMessage.parse(record.value());
        if (!snapshot.couponId().equals(record.key())) {
            throw new CouponInfoEventMessage.InvalidMessageException(
                    "Kafka key와 쿠폰 식별자가 일치하지 않습니다.");
        }
        repository.apply(snapshot);
    }
}
