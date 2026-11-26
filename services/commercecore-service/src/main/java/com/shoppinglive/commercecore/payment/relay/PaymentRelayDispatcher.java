package com.shoppinglive.commercecore.payment.relay;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;

import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.service.OrderSheetLifecycleService;
import com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
public class PaymentRelayDispatcher {
    private final PaymentRelayClient client;
    private final PaymentRelayService service;
    private final PaymentDeliveryStore deliveries;
    private final PaymentRelayStore store;
    private final OrderSheetLifecycleService lifecycle;
    private final boolean enabled;

    public PaymentRelayDispatcher(
            PaymentRelayClient client,
            PaymentRelayService service,
            PaymentDeliveryStore deliveries,
            PaymentRelayStore store,
            OrderSheetLifecycleService lifecycle,
            @Value("${commercecore.payment-relay.recovery-enabled:true}") boolean enabled) {
        this.client = client;
        this.service = service;
        this.deliveries = deliveries;
        this.store = store;
        this.lifecycle = lifecycle;
        this.enabled = enabled;
    }

    public void dispatch(PaymentDeliveryStore.Delivery delivery) {
        if (delivery == null) return;
        // 호출자가 든 스냅샷은 이미 끝난 시도일 수 있다. 저장된 현재 상태로 판정한다.
        delivery = deliveries.get(delivery.requestId());
        if (delivery.terminal()) return;
        try {
            if ("PREPARE".equals(delivery.kind())) prepare(delivery);
            else if ("APPROVAL".equals(delivery.kind())) approval(delivery);
            else throw new IllegalArgumentException("지원하지 않는 중계 요청입니다.");
        } catch (RestClientException unavailable) {
            service.pending(delivery, Map.of("code", "TRANSPORT_UNKNOWN"), null);
            log.warn("Payment 중계 재확인 예정: requestId={}", delivery.requestId());
        }
    }

    private void prepare(PaymentDeliveryStore.Delivery delivery) {
        var group = service.deliveryGroup(delivery);
        lifecycle.expire(group.getId());
        group = service.deliveryGroup(delivery);
        if (group.getStatus() != OrderStatus.PENDING_PAYMENT) {
            service.stopPreparation(delivery, Map.of("code", "ORDER_CLOSED"));
            return;
        }
        var result = client.create(deliveries.read(delivery.command(), Create.class));
        if ((result.httpStatus() == 200 || result.httpStatus() == 201) && result.success())
            service.prepared(delivery, result.data());
        else if (result.httpStatus() >= 400 && result.httpStatus() < 500)
            service.stopPreparation(delivery, Map.of("code", error(result)));
        else service.pending(delivery, Map.of("code", error(result)), null);
    }

    private void approval(PaymentDeliveryStore.Delivery delivery) {
        var group = service.deliveryGroup(delivery);
        lifecycle.expire(group.getId());
        group = service.deliveryGroup(delivery);
        if (!service.approvalAllowed(delivery)) return;
        var current = client.get(group.getGroupNumber());
        if (current.httpStatus() != 200 || !current.success() || current.data() == null) {
            service.pending(delivery, Map.of("code", error(current)), null);
            return;
        }
        var lookup = current.data();
        var binding = store.binding(group.getId());
        if (!java.util.Objects.equals(lookup.paymentId(), binding.paymentId())
                || lookup.amount() != group.getPayableAmount())
            throw new IllegalArgumentException("Payment 조회 결과가 저장한 결제와 다릅니다.");
        if ("APPROVED".equals(lookup.status())) {
            service.approved(
                    new Approved(
                            lookup.paymentId(),
                            group.getGroupNumber(),
                            lookup.amount(),
                            lookup.approvedAt()));
            return;
        }
        if ("UNKNOWN".equals(lookup.status())) {
            service.approvalUnknown(delivery, lookup);
            return;
        }
        if ("CANCELED".equals(lookup.status())) {
            service.canceled(delivery, lookup);
            return;
        }
        var request = deliveries.read(delivery.command(), Approval.class);
        // Payment는 거절·미전송·미승인 종료 때 결제를 CREATED로 되돌리고 결제창을 비운다. 이 시도는 청구 없이 끝났다.
        if ("CREATED".equals(lookup.status())
                && !java.util.Objects.equals(lookup.pgOrderId(), request.pgOrderId())) {
            service.declined(delivery, lookup);
            return;
        }
        // 조회 중 기한이 지날 수 있으므로 전송 직전 주문 기한을 다시 판정한다.
        lifecycle.expire(group.getId());
        group = service.deliveryGroup(delivery);
        // 종료 주문은 새 승인을 보내지 않는다. 접수 여부 불명·늦은 승인 여부만 계속 조회한다.
        if (group.getStatus() != OrderStatus.PAYMENT_CONFIRMING
                || !"CREATED".equals(lookup.status())) {
            service.pending(delivery, lookup, lookup.status());
            return;
        }
        if (!service.claimApprovalSend(delivery)) return;
        Reply<ApprovalResult> result;
        try {
            result = client.approve(group.getGroupNumber(), request);
        } catch (RestClientException lost) {
            service.pending(delivery, Map.of("code", "TRANSPORT_UNKNOWN"), null);
            return;
        }
        if (result.httpStatus() == 200
                && result.success()
                && result.data() != null
                && "APPROVED".equals(result.data().status())
                && java.util.Objects.equals(result.data().paymentId(), binding.paymentId())) {
            // 승인 응답에는 승인 시각이 없으므로 원결제 조회 또는 이벤트로 사실을 완성한다.
            var approved = client.get(group.getGroupNumber());
            if (approved.httpStatus() == 200
                    && approved.success()
                    && approved.data() != null
                    && "APPROVED".equals(approved.data().status())) {
                var fact = approved.data();
                service.approved(
                        new Approved(
                                fact.paymentId(),
                                group.getGroupNumber(),
                                fact.amount(),
                                fact.approvedAt()));
            } else service.pending(delivery, Map.of("code", "APPROVAL_AWAITING_FACT"), "APPROVING");
        } else if (result.httpStatus() == 422
                && "DECLINED".equals(result.errorCode())
                && result.data() != null
                && "DECLINED".equals(result.data().status()))
            service.declined(delivery, result.data());
        else if (result.httpStatus() == 202
                && result.success()
                && result.data() != null
                && !"UNKNOWN".equals(result.data().status()))
            service.pending(delivery, result.data(), "APPROVING");
        else if (result.httpStatus() >= 400 && result.httpStatus() < 500)
            service.pending(delivery, Map.of("code", error(result)), null);
        else if (result.success()
                && result.data() != null
                && "UNKNOWN".equals(result.data().status())
                && java.util.Objects.equals(result.data().paymentId(), binding.paymentId()))
            service.approvalUnknown(delivery, result.data());
        else
            service.pending(
                    delivery,
                    result.data() == null ? Map.of("code", error(result)) : result.data(),
                    null);
    }

    public PaymentRelayService.PaymentState checkout(String member, String number) {
        var group = service.owned(member, number);
        lifecycle.expire(group.getId());
        group = service.owned(member, number);
        if (service.state(member, number).compensation() != null
                || group.getStatus() != OrderStatus.PENDING_PAYMENT)
            throw new com.shoppinglive.common.core.BusinessException(
                    com.shoppinglive.common.core.ErrorCode.CONFLICT, "결제창을 열 수 없는 주문입니다.");
        if (store.binding(group.getId()).paymentId() == null)
            dispatch(service.prepare(member, number));
        else {
            var result = client.checkout(number);
            if (result.httpStatus() != 201 || !result.success())
                throw new com.shoppinglive.common.core.BusinessException(
                        com.shoppinglive.common.core.ErrorCode.CONFLICT,
                        "Payment 결제창을 열지 못했습니다: " + error(result));
            service.checkout(member, number, result.data());
        }
        return service.state(member, number);
    }

    @Scheduled(
            initialDelayString = "${commercecore.payment-relay.recovery-initial-delay:PT1S}",
            fixedDelayString = "PT1S")
    public void recover() {
        if (!enabled) return;
        for (var delivery : deliveries.relayDue())
            try {
                dispatch(delivery);
            } catch (RuntimeException failure) {
                deliveries.delay(delivery);
                log.warn("Payment 중계 복구 실패: requestId={}", delivery.requestId(), failure);
            }
    }

    private String error(Reply<?> reply) {
        return reply.errorCode() == null ? "HTTP_" + reply.httpStatus() : reply.errorCode();
    }
}
