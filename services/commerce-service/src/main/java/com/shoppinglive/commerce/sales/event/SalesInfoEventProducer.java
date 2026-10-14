package com.shoppinglive.commerce.sales.event;

import com.shoppinglive.common.events.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Direct publication after DB commit; durable delivery belongs to the separate Outbox work. */
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
            // Commit already succeeded. Do not turn a transport failure into a failed order
            // response.
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
