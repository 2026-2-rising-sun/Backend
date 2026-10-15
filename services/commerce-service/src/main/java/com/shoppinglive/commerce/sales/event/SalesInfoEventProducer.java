package com.shoppinglive.commerce.sales.event;

import com.shoppinglive.common.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** DB 커밋 후 직접 발행한다. 발행 내구성은 별도 Outbox 작업에서 다룬다. */
@Component
public class SalesInfoEventProducer {
    private static final Logger log = LoggerFactory.getLogger(SalesInfoEventProducer.class);
    private final KafkaTemplate<String, EventEnvelope<SalesInfoSnapshot>> kafka;
    private final boolean enabled;

    public SalesInfoEventProducer(
            KafkaTemplate<String, EventEnvelope<SalesInfoSnapshot>> kafka,
            @Value("${commerce.sales.events.enabled:true}") boolean enabled) {
        this.kafka = kafka;
        this.enabled = enabled;
    }

    public void send(EventEnvelope<SalesInfoSnapshot> event) {
        if (!enabled) return;
        try {
            kafka.send(
                            SalesInfoEventContract.TOPIC_NAME,
                            SalesInfoEventContract.messageKey(event.payload()),
                            event)
                    .whenComplete(
                            (result, failure) -> {
                                if (failure != null) failed(event, failure);
                                else
                                    log.debug(
                                            "sales event published: eventId={} salesId={} snapshotVersion={} topic={}",
                                            event.eventId(),
                                            event.payload().salesId(),
                                            event.payload().snapshotVersion(),
                                            SalesInfoEventContract.TOPIC_NAME);
                            });
        } catch (RuntimeException failure) {
            // 이미 커밋된 주문을 전송 실패 때문에 실패 응답으로 바꾸지 않는다.
            failed(event, failure);
        }
    }

    private void failed(EventEnvelope<SalesInfoSnapshot> event, Throwable failure) {
        log.error(
                "SALES_EVENT_PUBLISH_FAILED eventId={} salesId={} snapshotVersion={} topic={}",
                event.eventId(),
                event.payload().salesId(),
                event.payload().snapshotVersion(),
                SalesInfoEventContract.TOPIC_NAME,
                failure);
    }
}
