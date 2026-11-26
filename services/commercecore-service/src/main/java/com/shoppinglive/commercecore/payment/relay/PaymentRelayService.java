package com.shoppinglive.commercecore.payment.relay;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;

import com.shoppinglive.commercecore.coupon.service.*;
import com.shoppinglive.commercecore.order.dto.OrderSheetResponse;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.order.service.*;
import com.shoppinglive.commercecore.payment.service.PaymentDeliveryStore;
import com.shoppinglive.common.core.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentRelayService {
    private final OrderSheetService sheets;
    private final OrderSheetLifecycleService lifecycle;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PurchaseGuard guard;
    private final OrderTimeSource time;

    private final com.shoppinglive.commercecore.payment.service.KafkaRefundService kafkaRefunds;
    private final com.shoppinglive.commercecore.payment.service.RefundIntentStore refundIntents;

    private final PaymentRelayStore store;
    private final PaymentDeliveryStore deliveries;
    private final OrderPaymentResultApplier orderOutcomes;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String commissionRate;
    @PersistenceContext private EntityManager em;

    public PaymentRelayService(
            OrderSheetService sheets,
            OrderSheetLifecycleService lifecycle,
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PurchaseGuard guard,
            OrderTimeSource time,
            PaymentRelayStore store,
            PaymentDeliveryStore deliveries,
            OrderPaymentResultApplier orderOutcomes,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            com.shoppinglive.commercecore.payment.service.KafkaRefundService kafkaRefunds,
            com.shoppinglive.commercecore.payment.service.RefundIntentStore refundIntents,
            @Value("${commerce.commission-rate:}") String commissionRate) {
        this.sheets = sheets;
        this.lifecycle = lifecycle;
        this.groups = groups;
        this.orders = orders;
        this.guard = guard;
        this.time = time;
        this.store = store;
        this.deliveries = deliveries;
        this.orderOutcomes = orderOutcomes;
        this.jdbc = jdbc;
        this.tx = tx;
        this.kafkaRefunds = kafkaRefunds;
        this.refundIntents = refundIntents;
        this.commissionRate = commissionRate;
    }

    public Creation create(String member, String key, RelayOrderRequest input) {
        uuid(member);
        if (input == null || input.expectedAmount() == null || input.expectedAmount() <= 0)
            throw invalid("예상 실결제액은 양수여야 합니다.");
        guard.ensure(member);
        return tx.execute(
                t -> {
                    var created =
                            sheets.createForPayment(
                                    member,
                                    key,
                                    input.sheet(),
                                    new OrderSheetService.PaymentTerms(
                                            input.couponId(),
                                            input.couponSkuId(),
                                            input.expectedAmount()));
                    var group =
                            groups.findByGroupNumberAndMemberId(
                                            created.order().orderNumber(), member)
                                    .orElseThrow();
                    if (!created.created()) {
                        if (store.binding(group.getId()) == null
                                || !Objects.equals(group.getCouponId(), input.couponId())
                                || !Objects.equals(
                                        group.getPayableAmount(), input.expectedAmount()))
                            throw conflict("기존 주문 요청과 입력이 다릅니다.");
                        return new Creation(group.getGroupNumber(), false);
                    }
                    if (!Objects.equals(group.getPayableAmount(), input.expectedAmount()))
                        throw conflict("주문 실결제액이 변경되었습니다.");
                    var items = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
                    store.snapshot(group, items, rate());
                    deliveries.relay(
                            "PREPARE",
                            items.getFirst().getId(),
                            group.getId(),
                            group.getGroupNumber(),
                            group.getPayableAmount(),
                            "prepare-" + group.getId(),
                            null,
                            store.creation(group));
                    return new Creation(group.getGroupNumber(), true);
                });
    }

    public PaymentDeliveryStore.Delivery prepare(String member, String number) {
        var group = owned(member, number);
        lifecycle.expire(group.getId());
        group = owned(member, number);
        if (group.getStatus() != OrderStatus.PENDING_PAYMENT) throw conflict("결제를 준비할 수 없는 주문입니다.");
        return deliveries.relay(group.getId(), "PREPARE");
    }

    public PaymentDeliveryStore.Delivery acceptApproval(
            String member, String number, String key, Approval request) {
        if (key == null || key.isBlank() || key.length() > 64)
            throw invalid("승인 요청 키는 1~64자여야 합니다.");
        if (request == null
                || !member.equals(request.buyerId())
                || request.paymentKey() == null
                || request.paymentKey().isBlank()
                || request.paymentKey().length() > 200
                || request.pgOrderId() == null
                || request.pgOrderId().isBlank()
                || request.pgOrderId().length() > 64) throw invalid("승인 인증 정보가 올바르지 않습니다.");
        var found = owned(member, number);
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    var group = locked(found.getId());
                    var binding = store.binding(group.getId());
                    require(!refundIntents.fenced(group.getId()), "시스템 보상으로 승인 재개가 차단되었습니다.");
                    // 같은 키 재전송은 그 시도의 원문을 돌려준다. 거절로 결제창이 닫힌 뒤에도 새 승인이 되지 않는다.
                    if (deliveries.approvalKeyUsed(group.getId(), key))
                        return deliveries.relay(
                                "APPROVAL",
                                orders.findByPaymentGroupIdOrderByIdAsc(group.getId())
                                        .getFirst()
                                        .getId(),
                                group.getId(),
                                number,
                                group.getPayableAmount(),
                                key,
                                binding.paymentId(),
                                request);
                    if (binding.paymentId() == null
                            || !Objects.equals(request.pgOrderId(), binding.pgOrderId())
                            || request.amount() != group.getPayableAmount())
                        throw conflict("준비된 결제창 정보와 승인 요청이 다릅니다.");
                    var existing = deliveries.relay(group.getId(), "APPROVAL");
                    if (existing != null && !"FAILED".equals(existing.status()))
                        return deliveries.relay(
                                "APPROVAL",
                                orders.findByPaymentGroupIdOrderByIdAsc(group.getId())
                                        .getFirst()
                                        .getId(),
                                group.getId(),
                                number,
                                group.getPayableAmount(),
                                key,
                                binding.paymentId(),
                                request);
                    var processingStartedAt = time.now();
                    if (group.getStatus() != OrderStatus.PENDING_PAYMENT
                            || group.paymentDeadlineReached(processingStartedAt))
                        throw conflict("승인을 시작할 수 없는 주문입니다.");
                    group.beginProcessing(processingStartedAt);
                    groups.saveAndFlush(group);
                    for (var item : orders.findByPaymentGroupIdOrderByIdAsc(group.getId()))
                        if (orders.transitionStatus(
                                        item.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING")
                                != 1) throw new IllegalStateException("승인 접수 항목 상태가 다릅니다.");
                    return deliveries.relay(
                            "APPROVAL",
                            orders.findByPaymentGroupIdOrderByIdAsc(group.getId())
                                    .getFirst()
                                    .getId(),
                            group.getId(),
                            number,
                            group.getPayableAmount(),
                            key,
                            binding.paymentId(),
                            request);
                });
    }

    public void prepared(PaymentDeliveryStore.Delivery delivery, Created result) {
        long id = store.deliveryGroup(delivery.id());
        var found = groups.findById(id).orElseThrow();
        guard.ensure(found.getMemberId());
        tx.executeWithoutResult(
                t -> {
                    guard.lock(found.getMemberId());
                    var group = locked(id);
                    require(
                            result != null
                                    && result.paymentId() != null
                                    && result.paymentId() > 0
                                    && result.amount() == group.getPayableAmount()
                                    && result.expiresAt() != null
                                    && result.expiresAt().equals(group.getExpiresAt()),
                            "Payment 생성 결과가 저장한 원문과 다릅니다.");
                    require(
                            Set.of("CREATED", "APPROVING", "UNKNOWN", "APPROVED")
                                    .contains(result.status()),
                            "지원하지 않는 결제 준비 상태입니다.");
                    store.prepared(id, result);
                    group.setPaymentId(result.paymentId());
                    groups.saveAndFlush(group);
                    deliveries.record(delivery, result, "SUCCESS");
                });
    }

    public void checkout(String member, String number, Checkout result) {
        var found = owned(member, number);
        guard.ensure(member);
        tx.executeWithoutResult(
                t -> {
                    guard.lock(member);
                    var group = locked(found.getId());
                    if (refundIntents.fenced(group.getId())
                            || group.getStatus() != OrderStatus.PENDING_PAYMENT
                            || group.paymentDeadlineReached(time.now()))
                        throw conflict("결제창을 열 수 없는 주문입니다.");
                    require(
                            result != null
                                    && result.pgOrderId() != null
                                    && result.amount() == group.getPayableAmount()
                                    && result.expiresAt().equals(group.getExpiresAt()),
                            "Payment 결제창 결과가 주문과 다릅니다.");
                    store.checkout(group.getId(), result.pgOrderId());
                });
    }

    public void pending(PaymentDeliveryStore.Delivery delivery, Object reply, String remoteStatus) {
        long id = store.deliveryGroup(delivery.id());
        tx.executeWithoutResult(
                t -> {
                    if (remoteStatus != null) store.status(id, remoteStatus);
                    deliveries.record(delivery, reply, "UNKNOWN");
                });
    }

    public boolean approvalAllowed(PaymentDeliveryStore.Delivery delivery) {
        var found = deliveryGroup(delivery);
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    var group = locked(found.getId());
                    return !refundIntents.fenced(group.getId())
                            && group.getStatus() == OrderStatus.PAYMENT_CONFIRMING;
                });
    }

    public boolean claimApprovalSend(PaymentDeliveryStore.Delivery delivery) {
        var found = deliveryGroup(delivery);
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    var group = locked(found.getId());
                    if (refundIntents.fenced(group.getId())
                            || group.getStatus() != OrderStatus.PAYMENT_CONFIRMING) return false;
                    // 이 커밋이 송신 시작의 직렬화 지점이다. 이후 보상은 이 호출을 이미 진행 중인 호출로 취급한다.
                    return jdbc.update(
                                    "UPDATE order_payment_relay SET approval_send_started_at=CURRENT_TIMESTAMP WHERE payment_group_id=? AND NOT EXISTS(SELECT 1 FROM core_refund_intent WHERE payment_group_id=? AND purpose='SYSTEM_COMPENSATION')",
                                    group.getId(),
                                    group.getId())
                            == 1;
                });
    }

    /** 결과 불명은 처리 기한까지 조회를 이어 간다. 기한 도달 시 보상은 lifecycle.expire가 주문당 한 번 만든다. */
    public void approvalUnknown(PaymentDeliveryStore.Delivery delivery, Object reply) {
        pending(delivery, reply, "UNKNOWN");
    }

    public void stopPreparation(PaymentDeliveryStore.Delivery delivery, Object result) {
        tx.executeWithoutResult(t -> deliveries.record(delivery, result, "FAILED"));
    }

    public boolean approved(Approved result) {
        require(
                result != null
                        && result.approvedAt() != null
                        && result.paymentId() > 0
                        && result.amount() > 0,
                "승인 사실이 올바르지 않습니다.");
        var found =
                groups.findByGroupNumberAndMemberId(result.orderRef(), memberFor(result.orderRef()))
                        .orElseThrow();
        guard.ensure(found.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(found.getMemberId());
                    var group = locked(found.getId());
                    var binding = store.binding(group.getId());
                    require(
                            binding != null
                                    && Objects.equals(binding.paymentId(), result.paymentId())
                                    && result.amount() == group.getPayableAmount(),
                            "승인 대상과 결제 원문이 다릅니다.");
                    if (binding.approvedAt() != null) return false;
                    if (group.getStatus() == OrderStatus.PAYMENT_CONFIRMING
                            && group.paymentDeadlineReached(time.now())) {
                        lifecycle.expire(group.getId());
                        group = locked(group.getId());
                    }
                    if (refundIntents.fenced(group.getId())) {
                        // 늦은 승인은 사실만 보존하고 이미 접수한 보상 ID로 수렴한다.
                    } else if (group.getStatus() == OrderStatus.PAYMENT_CONFIRMING)
                        orderOutcomes.finish(group, true);
                    else if (Set.of(OrderStatus.EXPIRED, OrderStatus.CANCELLED, OrderStatus.FAILED)
                            .contains(group.getStatus()))
                        kafkaRefunds.compensateLocked(group, "LATE_APPROVAL");
                    else throw conflict("승인 결과를 적용할 주문 상태가 아닙니다.");
                    store.approved(group.getId(), result.approvedAt());
                    var delivery = deliveries.relay(group.getId(), "APPROVAL");
                    if (delivery != null) deliveries.record(delivery, result, "SUCCESS");
                    return true;
                });
    }

    /**
     * 승인 거절이 확정된 시도를 닫는다. 주문은 결제 대기로 되돌리고 예약을 유지해 원래 만료 시각 전까지 새 결제창으로 다시 결제하게 한다. 거절마다 결제 실패 알림을
     * 보내지 않는다. 만료 시각이 이미 지났으면 같은 트랜잭션에서 만료한다.
     */
    public void declined(PaymentDeliveryStore.Delivery delivery, Object result) {
        long id = store.deliveryGroup(delivery.id());
        var found = groups.findById(id).orElseThrow();
        guard.ensure(found.getMemberId());
        tx.executeWithoutResult(
                t -> {
                    guard.lock(found.getMemberId());
                    var group = locked(id);
                    if (store.binding(id).approvedAt() != null) return;
                    var current = deliveries.relay(id, "APPROVAL");
                    if (!refundIntents.fenced(id)
                            && group.getStatus() == OrderStatus.PAYMENT_CONFIRMING
                            && current != null
                            && current.id() == delivery.id()) {
                        for (var item : orders.findByPaymentGroupIdOrderByIdAsc(id))
                            if (orders.transitionStatus(
                                            item.getId(), "PAYMENT_CONFIRMING", "PENDING_PAYMENT")
                                    != 1) throw new IllegalStateException("거절 반영 항목 상태가 다릅니다.");
                        group.reopenForPayment();
                        groups.saveAndFlush(group);
                        store.declined(id);
                        deliveries.record(delivery, result, "FAILED");
                        lifecycle.expire(id);
                        return;
                    }
                    store.status(id, "DECLINED");
                    deliveries.record(delivery, result, "FAILED");
                });
    }

    /** 응답 값 불일치 등으로 Payment가 결제를 전액 취소(CANCELED)한 경우다. 재결제 대상이 아니므로 주문을 실패로 끝낸다. */
    public void canceled(PaymentDeliveryStore.Delivery delivery, Object result) {
        long id = store.deliveryGroup(delivery.id());
        var found = groups.findById(id).orElseThrow();
        guard.ensure(found.getMemberId());
        tx.executeWithoutResult(
                t -> {
                    guard.lock(found.getMemberId());
                    var group = locked(id);
                    if (store.binding(id).approvedAt() != null) return;
                    if (group.getStatus() == OrderStatus.PAYMENT_CONFIRMING
                            && group.paymentDeadlineReached(time.now())) {
                        lifecycle.expire(id);
                        group = locked(id);
                    }
                    if (!refundIntents.fenced(group.getId())
                            && group.getStatus() == OrderStatus.PAYMENT_CONFIRMING)
                        orderOutcomes.finish(group, false);
                    store.status(id, "CANCELED");
                    deliveries.record(delivery, result, "FAILED");
                });
    }

    public boolean refundSucceeded(String orderRef, RefundSucceeded result) {
        require(
                result != null
                        && result.refundId() > 0
                        && "LATE_APPROVAL".equals(result.reason())
                        && result.completedAt() != null,
                "환불 성공 결과가 올바르지 않습니다.");
        var group = groupForRefund(orderRef, result.refundRequestId());
        require(result.amount() == group.getPayableAmount(), "환불 금액이 원문과 다릅니다.");
        var expected =
                store.creation(group).sellerAmounts().stream()
                        .map(s -> new RefundItem(s.sellerOrderNumber(), s.amount()))
                        .sorted(Comparator.comparing(RefundItem::sellerOrderNumber))
                        .toList();
        require(
                result.items() != null
                        && expected.equals(
                                result.items().stream()
                                        .sorted(Comparator.comparing(RefundItem::sellerOrderNumber))
                                        .toList()),
                "환불 판매자 항목이 원문과 다릅니다.");
        guard.ensure(group.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(group.getMemberId());
                    locked(group.getId());
                    return store.refund(
                            group.getId(),
                            result.refundRequestId(),
                            "SUCCEEDED",
                            result.completedAt(),
                            null);
                });
    }

    public boolean refundFailed(String orderRef, RefundFailed result) {
        require(
                result != null
                        && result.refundId() > 0
                        && "LATE_APPROVAL".equals(result.reason())
                        && result.failureCode() != null
                        && !result.failureCode().isBlank(),
                "환불 실패 결과가 올바르지 않습니다.");
        var group = groupForRefund(orderRef, result.refundRequestId());
        guard.ensure(group.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(group.getMemberId());
                    locked(group.getId());
                    return store.refund(
                            group.getId(),
                            result.refundRequestId(),
                            "FAILED",
                            null,
                            result.failureCode());
                });
    }

    private PaymentGroup groupForRefund(String ref, String key) {
        var group = groups.findByGroupNumberAndMemberId(ref, memberFor(ref)).orElseThrow();
        var binding = store.binding(group.getId());
        require(
                binding != null && Objects.equals(key, "late-" + binding.paymentId()),
                "환불 요청 참조가 다릅니다.");
        return group;
    }

    public PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .filter(g -> g.isOrderSheet() && store.binding(g.getId()) != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "연동 주문을 찾을 수 없습니다."));
    }

    public PaymentState state(String member, String number) {
        return view(member, number).payment();
    }

    public View view(String member, String number) {
        var found = owned(member, number);
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    var group = locked(found.getId());
                    lifecycle.expire(group.getId());
                    group = locked(group.getId());
                    return viewLocked(group);
                });
    }

    public Optional<View> active(String member) {
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    View active = null;
                    for (var found :
                            groups.findByMemberIdAndStatusIn(
                                    member,
                                    List.of(
                                            OrderStatus.PENDING_PAYMENT,
                                            OrderStatus.PAYMENT_CONFIRMING))) {
                        if (found.usesLegacyExecution()) continue;
                        var group = locked(found.getId());
                        if (!group.isOrderSheet() || store.binding(group.getId()) == null)
                            throw conflict("이전 주문의 결제 연동 상태를 먼저 확인해야 합니다.");
                        lifecycle.expire(group.getId());
                        group = locked(group.getId());
                        if (group.getStatus() != OrderStatus.PENDING_PAYMENT
                                && group.getStatus() != OrderStatus.PAYMENT_CONFIRMING) continue;
                        if (active != null) throw conflict("진행 중인 주문이 여러 건입니다. 기존 주문을 확인해 주세요.");
                        active = viewLocked(group);
                    }
                    return Optional.ofNullable(active);
                });
    }

    private View viewLocked(PaymentGroup group) {
        var binding = store.binding(group.getId());
        var prepare = deliveries.relay(group.getId(), "PREPARE");
        String status =
                binding.paymentId() == null && prepare != null && prepare.terminal()
                        ? "PREPARATION_FAILED"
                        : binding.status();
        var payment =
                new PaymentState(
                        group.getGroupNumber(),
                        group.getStatus() == OrderStatus.PENDING_PAYMENT
                                ? "PENDING"
                                : group.getStatus() == OrderStatus.PAYMENT_CONFIRMING
                                        ? "PROCESSING"
                                        : group.getStatus().name(),
                        binding.paymentId(),
                        status,
                        binding.pgOrderId(),
                        group.getPayableAmount(),
                        group.getExpiresAt(),
                        group.getProcessingExpiresAt(),
                        refundIntents.fenced(group.getId())
                                ? refundIntents.compensation(group.getId()).status()
                                : store.refundStatus(group.getId()),
                        refundIntents.compensation(group.getId()));
        return new View(
                OrderSheetResponse.from(
                        group, orders.findByPaymentGroupIdOrderByIdAsc(group.getId())),
                payment);
    }

    public PaymentGroup deliveryGroup(PaymentDeliveryStore.Delivery delivery) {
        return groups.findById(store.deliveryGroup(delivery.id())).orElseThrow();
    }

    private PaymentGroup locked(long id) {
        var group = groups.lockById(id).orElseThrow();
        em.refresh(group);
        return group;
    }

    private String memberFor(String ref) {
        var rows =
                jdbc.queryForList(
                        "SELECT member_id FROM payment_group WHERE group_number=?",
                        String.class,
                        ref);
        if (rows.isEmpty()) throw conflict("결과의 주문 참조가 없습니다.");
        return rows.getFirst();
    }

    private BigDecimal rate() {
        if (commissionRate.isBlank())
            throw new IllegalStateException("팀원 계약의 commerce.commission-rate 설정이 필요합니다.");
        BigDecimal rate = new BigDecimal(commissionRate);
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0 || rate.scale() > 4)
            throw new IllegalStateException("수수료율 설정은 0~1, 소수4자리 이하여야 합니다.");
        return rate;
    }

    private static void uuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException ex) {
            throw invalid("구매자 ID가 UUID 형식이 아닙니다.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw conflict(message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    public record Creation(String number, boolean created) {}

    public record View(OrderSheetResponse order, PaymentState payment) {}

    public record PaymentState(
            String orderNumber,
            String orderStatus,
            Long paymentId,
            String paymentStatus,
            String pgOrderId,
            long amount,
            Instant expiresAt,
            Instant processingExpiresAt,
            String refundStatus,
            com.shoppinglive.commercecore.payment.service.RefundIntentStore.Compensation
                    compensation) {}
}
