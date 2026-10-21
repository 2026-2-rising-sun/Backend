package com.shoppinglive.notification.observability;

import com.shoppinglive.common.observability.BusinessTelemetry;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class ConsumerObservation {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final BusinessTelemetry telemetry;

    public ConsumerObservation(BusinessTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around(
            "(execution(* com.shoppinglive.notification..event.*EventListener.consume(..)) || execution(* com.shoppinglive.notification.commerce.event.CommerceNotificationListener.consume(..))) && args(record)")
    public Object delivery(
            ProceedingJoinPoint call,
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record)
            throws Throwable {
        String previous = org.slf4j.MDC.get("eventId");
        String eventId = "unavailable";
        try {
            var root = MAPPER.readTree(record.value());
            String raw = root.path("eventId").asText();
            eventId = java.util.UUID.fromString(raw).toString();
        } catch (Exception ignored) {
            /* 잘못된 메시지 판단과 재시도는 기존 listener에 맡긴다. */
        }
        org.slf4j.MDC.put("eventId", eventId);
        try {
            return call.proceed();
        } catch (Throwable failure) {
            telemetry.failure(
                    "shoppinglive.consumer.failures",
                    Tags.of("result", "failed"),
                    Map.of(
                            "eventId",
                            eventId,
                            "topic",
                            record.topic(),
                            "partition",
                            record.partition(),
                            "offset",
                            record.offset()));
            throw failure;
        } finally {
            if (previous == null) org.slf4j.MDC.remove("eventId");
            else org.slf4j.MDC.put("eventId", previous);
        }
    }

    @Around(
            "execution(* com.shoppinglive.notification.inbox.repository.NotificationWriteRepository.createAll(..))")
    public Object consume(ProceedingJoinPoint call) throws Throwable {
        NewNotification notification = (NewNotification) call.getArgs()[0];
        if (!java.util.Set.of(
                        "PaymentSucceeded",
                        "PaymentFailed",
                        "OrderCancelled",
                        "OrderExpired",
                        "RefundCompleted",
                        "CouponIssued",
                        "SalesInfoChanged")
                .contains(notification.eventType())) return call.proceed();
        Map<String, Object> ids =
                Map.of(
                        "eventId",
                        notification.eventId(),
                        "eventType",
                        notification.eventType(),
                        "businessId",
                        notification.linkValue());
        long started = System.nanoTime();
        try {
            Object value = call.proceed();
            telemetry.duration(
                    "shoppinglive.consumer",
                    Tags.of(
                            "result",
                            ((Number) value).intValue() > 0
                                    ? "applied"
                                    : "duplicate_or_no_recipient"),
                    ids,
                    started);
            return value;
        } catch (Throwable failure) {
            throw failure;
        }
    }
}
