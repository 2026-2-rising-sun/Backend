package com.shoppinglive.shopping.sales.event;

import com.shoppinglive.shopping.sales.repository.ProductSalesRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code commerce.sales-info.v1} 을 받아 replica 에 반영한다. 메시지 원문(JSON 문자열)을 {@link
 * SalesInfoEventMessage} 로 해석·검증하고, {@code SalesInfo} 로 바꿔 {@link ProductSalesRepository#apply} 에
 * 넘긴다.
 *
 * <p>해석·검증 실패는 {@link SalesInfoEventMessage.InvalidMessageException} 으로 던진다. 건너뛸지 다시 할지는 error
 * handler 가 정한다 ({@code EventConsumerConfiguration}).
 */
@Component
public class SalesInfoEventListener {

    private final ProductSalesRepository repository;

    public SalesInfoEventListener(ProductSalesRepository repository) {
        this.repository = repository;
    }

    @KafkaListener(topics = SalesInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        SalesInfoEventMessage message = SalesInfoEventMessage.parse(record.value());
        if (!Long.toString(message.salesId()).equals(record.key())) {
            throw new SalesInfoEventMessage.InvalidMessageException(
                    "Kafka key와 판매 식별자가 일치하지 않습니다.");
        }
        repository.apply(message.toSalesInfo(), message.snapshotVersion());
    }
}
