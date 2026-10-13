package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.contracts.events.SalesInfoEventContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code commerce.sales-info.v1} 을 받아 replica 에 반영한다. 메시지 원문(JSON 문자열)을
 * {@link SalesInfoEventMessage} 로 해석·검증하고, {@code SalesInfo} 로 바꿔 {@link ProductSalesRepository#apply} 에 넘긴다.
 *
 * <p>실패는 둘로 나눈다. 해석·검증 실패는 다시 읽어도 같은 결과라 log 를 남기고 건너뛴다.
 * 그 밖의 예외(DB 오류 등)는 잠시 뒤 성공할 수 있으므로 밖으로 던져 error handler 가 같은 offset 을 재시도하게 한다
 * ({@link SalesInfoConsumerConfiguration}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesInfoEventListener {

    static final String SKIP_MARKER = "SALES_EVENT_SKIPPED";

    private final ProductSalesRepository repository;

    @KafkaListener(topics = SalesInfoEventContract.TOPIC_NAME)
    public void consume(ConsumerRecord<String, String> record) {
        SalesInfoEventMessage message;
        try {
            message = SalesInfoEventMessage.parse(record.value());
        } catch (SalesInfoEventMessage.InvalidMessageException e) {
            // 원문은 다시 읽어도 같은 결과다. 여기서 멈추면 뒤의 정상 이벤트가 영원히 밀리므로 건너뛴다.
            log.error("{} topic={} partition={} offset={} reason={} raw={}", SKIP_MARKER,
                record.topic(), record.partition(), record.offset(), e.getMessage(), record.value());
            return;
        }
        repository.apply(message.toSalesInfo(), message.snapshotVersion());
    }
}
