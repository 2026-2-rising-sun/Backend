package com.shoppinglive.notification.sales.event;

import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code commerce.sales-info.v1} 을 받아 판매 상태 사본을 갱신한다.
 *
 * <p>Shopping 도 같은 토픽을 구독하지만 consumer group 이 달라서 서로 영향이 없다. 각자 자기 사본을 가진다.
 *
 * <p>실패를 직접 다루지 않는다. 해석과 검증 실패는 {@code InvalidEventMessageException} 으로 올라가 <b>보관함 토픽</b>으로 가고, DB
 * 장애 같은 일시 실패는 복구될 때까지 재시도된다. 분류는 {@code NotificationConsumerConfiguration} 에 한 곳으로 모아 뒀다.
 */
@Component
public class SalesInfoEventListener {

    private final ProductSalesStatusRepository statuses;

    public SalesInfoEventListener(ProductSalesStatusRepository statuses) {
        this.statuses = statuses;
    }

    @KafkaListener(topics = SalesInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        statuses.apply(SalesInfoEventMessage.parse(record.value(), record.key()).status());
    }
}
