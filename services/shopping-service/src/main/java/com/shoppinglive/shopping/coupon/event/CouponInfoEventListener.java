package com.shoppinglive.shopping.coupon.event;

import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code commerce.coupon-info.v1} 을 받아 쿠폰 replica 에 반영한다.
 *
 * <p>실패 처리는 판매정보 listener 와 같다. 해석·검증 실패는 log 를 남기고 건너뛴다. 그 밖의 예외(DB 오류, 신규 쿠폰 동시 INSERT 의 키 충돌)는
 * 밖으로 던져 error handler 가 같은 offset 을 다시 처리하게 한다.
 */
@Component
public class CouponInfoEventListener {

    static final String SKIP_MARKER = "COUPON_EVENT_SKIPPED";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CouponInfoEventListener.class);
    private final CouponDisplayRepository repository;

    public CouponInfoEventListener(CouponDisplayRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = CouponInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        CouponSnapshot snapshot;
        try {
            snapshot = CouponInfoEventMessage.parse(record.value());
            if (!snapshot.couponId().equals(record.key())) {
                throw new CouponInfoEventMessage.InvalidMessageException(
                        "Kafka key와 쿠폰 식별자가 일치하지 않습니다.");
            }
        } catch (CouponInfoEventMessage.InvalidMessageException e) {
            log.error(
                    "{} topic={} partition={} offset={} reason={} raw={}",
                    SKIP_MARKER,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e.getMessage(),
                    record.value());
            return;
        }
        repository.apply(snapshot);
    }
}
