package com.shoppinglive.commerce.observability;

import com.shoppinglive.commerce.order.dto.OrderCreationResult;
import com.shoppinglive.commerce.order.service.PaymentGroupService;
import com.shoppinglive.commerce.payment.service.*;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.observability.BusinessTelemetry;
import io.micrometer.core.instrument.Tags;
import java.util.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 지정한 업무 경계의 반환값만 관측한다. 요청 인자·결제수단·회원 정보는 기록하지 않는다. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(name = "shoppinglive.observability.enabled", havingValue = "true")
public class CommerceObservation {
    private final BusinessTelemetry telemetry;

    public CommerceObservation(BusinessTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around(
            "execution(* com.shoppinglive.commerce.order.service.OrderCreationService.create*(..)) || execution(* com.shoppinglive.commerce.order.service.PaymentGroupService.create(..))")
    public Object order(ProceedingJoinPoint call) throws Throwable {
        try {
            Object value = call.proceed();
            boolean created;
            Map<String, Object> ids;
            String unit;
            if (value instanceof OrderCreationResult result) {
                created = result.created();
                ids = Map.of("orderNumber", result.order().getOrderNumber());
                unit = "order";
            } else if (value instanceof PaymentGroupService.Creation result) {
                created = result.created();
                ids = Map.of("groupNumber", result.group().groupNumber());
                unit = "group";
            } else return value;
            telemetry.count(
                    "shoppinglive.order.requests",
                    Tags.of(
                            "unit",
                            unit,
                            "result",
                            created ? "created" : "replayed",
                            "reason",
                            "none"),
                    ids);
            return value;
        } catch (Throwable failure) {
            String reason =
                    failure instanceof BusinessException business
                            ? business.errorCode().name()
                            : "unexpected";
            String unit = call.getTarget() instanceof PaymentGroupService ? "group" : "order";
            telemetry.failure(
                    "shoppinglive.order.requests",
                    Tags.of("unit", unit, "result", "rejected", "reason", reason),
                    Map.of());
            throw failure;
        }
    }

    @Around(
            "execution(* com.shoppinglive.commerce.payment.service.RefundIntakeService.request(..))")
    public Object refundRequested(ProceedingJoinPoint call) throws Throwable {
        var result = (RefundIntakeService.RequestResult) call.proceed();
        telemetry.count(
                "shoppinglive.payment.operations",
                Tags.of(
                        "operation",
                        "refund_intake",
                        "result",
                        result.created() ? "accepted" : "replayed"),
                Map.of(
                        "refundRequestId",
                        result.refund().id(),
                        "groupNumber",
                        result.refund().paymentGroupNumber()));
        return result;
    }

    @Around(
            "execution(* com.shoppinglive.commerce.payment.service.PaymentRecoveryStore.beginInvocation(..)) || execution(* com.shoppinglive.commerce.payment.service.PaymentRecoveryStore.unknown(..)) || execution(* com.shoppinglive.commerce.payment.service.PaymentRecoveryStore.complete(..)) || execution(* com.shoppinglive.commerce.payment.service.RefundRecoveryStore.beginInvocation(..)) || execution(* com.shoppinglive.commerce.payment.service.RefundRecoveryStore.unknown(..)) || execution(* com.shoppinglive.commerce.payment.service.RefundOutcomeService.apply(..))")
    public Object paymentOperation(ProceedingJoinPoint call) throws Throwable {
        Object lease = call.getArgs()[0];
        boolean payment = lease instanceof PaymentRecoveryStore.Lease;
        long id =
                payment
                        ? ((PaymentRecoveryStore.Lease) lease).id()
                        : ((RefundRecoveryStore.Lease) lease).requestId();
        String name = call.getSignature().getName();
        String operation = (payment ? "payment_" : "refund_") + name;
        Map<String, Object> ids = Map.of(payment ? "paymentAttemptId" : "refundRequestId", id);
        try {
            Object value = call.proceed();
            String result;
            if (value instanceof Optional<?> invocation)
                result =
                        invocation.isEmpty()
                                ? "skipped"
                                : (((Number) invocation.get()).intValue() == 0
                                        ? "first_attempt"
                                        : "retry");
            else if (Boolean.TRUE.equals(value))
                result =
                        name.equals("complete") || name.equals("apply")
                                ? call.getArgs()[1].toString()
                                : "unknown";
            else result = "skipped";
            if (name.equals("beginInvocation") || name.equals("unknown")) {
                telemetry.committedAttempt(
                        "shoppinglive.payment.operations",
                        Tags.of("operation", operation, "result", result),
                        ids);
            } else {
                telemetry.count(
                        "shoppinglive.payment.operations",
                        Tags.of("operation", operation, "result", result),
                        ids);
            }
            return value;
        } catch (Throwable failure) {
            telemetry.failure(
                    "shoppinglive.payment.operations",
                    Tags.of("operation", operation, "result", "error"),
                    ids);
            throw failure;
        }
    }

    @Around(
            "execution(* com.shoppinglive.commerce.order.service.PaymentGroupService.cancel(..)) || execution(* com.shoppinglive.commerce.order.service.PaymentGroupService.expire(..))")
    public Object lifecycle(ProceedingJoinPoint call) throws Throwable {
        try {
            return call.proceed();
        } catch (Throwable failure) {
            telemetry.failure(
                    "shoppinglive.lifecycle.failures",
                    Tags.of("operation", call.getSignature().getName()),
                    Map.of());
            throw failure;
        }
    }
}
