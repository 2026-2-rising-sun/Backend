package com.shoppinglive.common.observability;

import com.shoppinglive.common.events.EventEnvelope;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class DomainEventObservation {
    private final BusinessTelemetry telemetry;

    public DomainEventObservation(BusinessTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around(
            "execution(* com.shoppinglive.common.outbox.EventOutbox.append(..)) && args(topic,event)")
    public Object appended(ProceedingJoinPoint call, String topic, EventEnvelope<?> event)
            throws Throwable {
        Object result = call.proceed();
        telemetry.count(
                "shoppinglive.domain.events",
                Tags.of("event_type", event.eventType(), "topic", topic),
                Map.of("eventId", event.eventId(), "aggregateId", event.aggregateId()));
        return result;
    }
}
