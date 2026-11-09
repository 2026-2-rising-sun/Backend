package com.shoppinglive.payment.observability;

import com.shoppinglive.common.observability.BusinessTelemetry;
import com.shoppinglive.payment.service.PaymentRecoveryStore;
import com.shoppinglive.payment.service.RefundRecoveryStore;
import io.micrometer.core.instrument.Tags;
import java.util.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;

@Aspect
public class PaymentObservation {
    private final BusinessTelemetry telemetry;

    public PaymentObservation(BusinessTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around(
            "execution(* com.shoppinglive.payment.service.PaymentRecoveryStore.beginInvocation(..)) || execution(* com.shoppinglive.payment.service.PaymentRecoveryStore.unknown(..)) || execution(* com.shoppinglive.payment.service.PaymentRecoveryStore.complete(..)) || execution(* com.shoppinglive.payment.service.RefundRecoveryStore.beginInvocation(..)) || execution(* com.shoppinglive.payment.service.RefundRecoveryStore.unknown(..)) || execution(* com.shoppinglive.payment.service.RefundOutcomeService.apply(..))")
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
}
