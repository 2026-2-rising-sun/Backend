package com.shoppinglive.shopping.observability;

import com.shoppinglive.common.observability.BusinessTelemetry;
import com.shoppinglive.shopping.client.commerce.SalesInfo;
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
            "execution(* com.shoppinglive.shopping..event.*EventListener.consume(..)) && args(record)")
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
            "execution(* com.shoppinglive.shopping.sales.repository.ProductSalesRepository.apply(..)) || execution(* com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository.apply(..))")
    public Object consume(ProceedingJoinPoint call) throws Throwable {
        if (org.slf4j.MDC.get("eventId") == null) return call.proceed();
        Map<String, Object> ids;
        if (call.getArgs()[0] instanceof SalesInfo info)
            ids = Map.of("salesId", info.salesId(), "snapshotVersion", call.getArgs()[1]);
        else {
            var snapshot = (com.shoppinglive.shopping.coupon.dto.CouponSnapshot) call.getArgs()[0];
            ids = Map.of("couponId", snapshot.couponId(), "snapshotVersion", snapshot.version());
        }
        long started = System.nanoTime();
        try {
            Object value = call.proceed();
            telemetry.duration(
                    "shoppinglive.consumer",
                    Tags.of(
                            "result",
                            Boolean.TRUE.equals(value) ? "applied" : "duplicate_or_stale"),
                    ids,
                    started);
            return value;
        } catch (Throwable failure) {
            throw failure;
        }
    }
}
