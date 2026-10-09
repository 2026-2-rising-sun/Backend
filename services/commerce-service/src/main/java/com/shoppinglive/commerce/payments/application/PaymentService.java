package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.sales.application.SalesService;
import java.time.Instant;
import java.time.Duration;
import com.shoppinglive.commerce.payments.infrastructure.PaymentRecoveryStore;
import com.shoppinglive.commerce.payments.infrastructure.PaymentRecoveryStore.Lease;
import com.shoppinglive.commerce.purchase.application.DurableMockGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 결제 도메인 유스케이스 서비스.
 */
@Service
public class PaymentService {

    private final com.shoppinglive.commerce.purchase.application.PaymentGroupService groups;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final OrderService orderService;
    private final OrderJpaRepository orderRepository;
    private final PaymentAttemptJpaRepository paymentAttemptRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final SalesService salesService;
    private final MockPaymentEngine mockPaymentEngine;
    private final ObjectProvider<DevPaymentScenarioRegistry> devRegistryProvider;
    private final PaymentRecoveryStore recovery;
    private final DurableMockGateway gateway;
    private final PaymentRetryPolicy retryPolicy;

    public PaymentService(
        org.springframework.transaction.support.TransactionTemplate transaction,
        com.shoppinglive.commerce.purchase.application.PaymentGroupService groups,
        OrderService orderService,
        OrderJpaRepository orderRepository,
        PaymentAttemptJpaRepository paymentAttemptRepository,
        SalesStockJpaRepository salesStockRepository,
        SalesService salesService,
        MockPaymentEngine mockPaymentEngine,
        ObjectProvider<DevPaymentScenarioRegistry> devRegistryProvider,
        PaymentRecoveryStore recovery, DurableMockGateway gateway, PaymentRetryPolicy retryPolicy) {
        this.groups = groups;
        this.transaction = transaction;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.salesStockRepository = salesStockRepository;
        this.salesService = salesService;
        this.mockPaymentEngine = mockPaymentEngine;
        this.devRegistryProvider = devRegistryProvider;
        this.recovery = recovery;
        this.gateway = gateway;
        this.retryPolicy = retryPolicy;
    }

    /**
     * 결제를 시작한다.
     *
     * <p>시나리오 결정 우선순위:
     * <ol>
     *   <li>명시적으로 켠 local/test 레지스트리의 scenario</li>
     *   <li>Default: {@link PaymentScenario#INSTANT_SUCCESS}</li>
     * </ol>
     */
    @Transactional
    public PaymentAttempt startPayment(
        String orderNumber, String memberId) {
        Order order = orderService.findByOrderNumberAndMemberId(orderNumber, memberId);
        if (order.getPaymentGroup() != null) {
            if (orderRepository.findByPaymentGroupIdOrderByIdAsc(order.getPaymentGroup().getId()).size() != 1)
                throw new OrderNotEligibleForPaymentException(orderNumber);
            return groups.start(memberId, order.getPaymentGroup().getGroupNumber(), "legacy-payment-" + order.getId());
        }
        PaymentScenario effectiveScenario = resolveScenario(orderNumber);

        int updated = orderRepository.transitionStatus(
            order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING");
        if (updated == 0) {
            throw new OrderNotEligibleForPaymentException(orderNumber);
        }

        PaymentAttempt saved = paymentAttemptRepository
            .save(new PaymentAttempt(order.getId(), effectiveScenario, Instant.now()));

        scheduleAfterCommit(saved.getId(), effectiveScenario);

        return saved;
    }

    /**
     * 결제 처리 예약을 트랜잭션 커밋 이후로 미룬다.
     *
     * <p><b>왜 바로 예약하면 안 되는가:</b> 이 메서드를 부르는 {@link #startPayment} 는
     * {@code @Transactional} 이라 아직 커밋 전이다. {@code INSTANT_*} 시나리오는 지연 없이 다른
     * 스레드에서 즉시 실행되는데, 그 스레드가 커밋보다 먼저 도착하면 자기 트랜잭션에서
     * {@code payment_attempt} 를 조회해도 아직 보이지 않는다. {@link #resolvePayment} 는 그때
     * 조용히 빠져나가므로 즉시 결제가 다음 Reconciler 실행까지 지연된다.
     * INSTANT 시나리오도 scheduled_resolve_at을 가지므로 재확인 대상이지만,
     * 정상 경로의 즉시 처리를 보장하려면 커밋 이후에 예약해야 한다.
     *
     * <p>덤으로 롤백 시에는 {@code afterCommit} 이 호출되지 않으므로, 존재하지 않는 결제를
     * 처리하려 드는 일도 사라진다.
     *
     * <p>트랜잭션 밖에서 호출된 경우 (단위 테스트 등) 는 미룰 커밋이 없으므로 즉시 예약한다.
     */
    private void scheduleAfterCommit(Long paymentAttemptId, PaymentScenario scenario) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mockPaymentEngine.schedule(paymentAttemptId, scenario);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mockPaymentEngine.schedule(paymentAttemptId, scenario);
                }
            });
    }

    private PaymentScenario resolveScenario(String orderNumber) {
        DevPaymentScenarioRegistry registry = devRegistryProvider.getIfAvailable();
        if (registry != null) {
            return registry.get(orderNumber).orElse(PaymentScenario.INSTANT_SUCCESS);
        }
        return PaymentScenario.INSTANT_SUCCESS;
    }

    /**
     * 결제 결과를 확정한다. Mock 엔진 또는 Reconciler 가 호출. Idempotent.
     *
     * @return 이번 호출에서 결제·주문·재고를 함께 확정했으면 true, 이미 처리된 경우 false
     */
    public boolean resolvePayment(Long paymentAttemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (attempt == null || !attempt.getStatus().isUnconfirmed()) return false;
        var lease = recovery.claim(paymentAttemptId, Duration.ofMinutes(1));
        return lease.isPresent() && resolveClaimed(lease.get());
    }

    public boolean resolveClaimed(Lease lease) {
        int currentCount = lease.retryCount();
        try {
            PaymentStatus result = gateway.find(lease.id());
            if (result == null) {
                var invocation = recovery.beginInvocation(lease);
                if (invocation.isEmpty()) {
                    recovery.unknown(lease, PaymentRetryPolicy.RESULT_QUERY_DELAY);
                    return false;
                }
                currentCount = invocation.get();
                try {
                    result = gateway.authorize(lease.id(), lease.scenario());
                } catch (MockPaymentResultUnknownException unknown) {
                    recovery.unknown(lease, retryPolicy.afterUnknown(currentCount));
                    return false;
                } catch (RuntimeException deliveryFailure) {
                    recovery.unknown(lease, retryPolicy.afterUnknown(currentCount));
                    throw deliveryFailure;
                }
            }
            if (!result.isConfirmedOutcome()) {
                recovery.unknown(lease, retryPolicy.afterUnknown(currentCount));
                return false;
            }
            PaymentAttempt attempt = paymentAttemptRepository.findById(lease.id()).orElseThrow();
            if (attempt.getPaymentGroupId() != null) return groups.applyOutcome(lease, result);
            PaymentStatus confirmed = result;
            return transaction.execute(status -> resolveLegacyPayment(lease, confirmed));
        } catch (RuntimeException failure) {
            // An independent authorization result survives local business rollback.
            recovery.release(lease, currentCount == PaymentRetryPolicy.MAX_RETRIES
                ? PaymentRetryPolicy.RESULT_QUERY_DELAY : Duration.ofSeconds(1));
            throw failure;
        }
    }

    private boolean resolveLegacyPayment(Lease lease, PaymentStatus outcome) {
        Long paymentAttemptId = lease.id();
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (attempt == null || !attempt.getStatus().isUnconfirmed() || !recovery.owns(lease)) return false;

        Order order = orderRepository.findById(attempt.getOrderId()).orElse(null);
        if (order == null) {
            throw new IllegalStateException("order missing for payment: attemptId=" + paymentAttemptId);
        }

        if (outcome == PaymentStatus.SUCCESS) {
            if (orderRepository.transitionStatus(order.getId(), "PAYMENT_CONFIRMING", "PAID") != 1
                || salesStockRepository.consumeReserved(order.getSalesInfoId(), order.getQuantity()) != 1) {
                throw new IllegalStateException("payment stock/order transition failed: attemptId=" + paymentAttemptId);
            }
        } else {
            if (orderRepository.transitionStatus(order.getId(), "PAYMENT_CONFIRMING", "FAILED") != 1) {
                throw new IllegalStateException("payment order transition failed: attemptId=" + paymentAttemptId);
            }
            salesService.restoreReserved(order.getSalesInfoId(), order.getQuantity());
        }
        if (!recovery.complete(lease, outcome.name())) {
            throw new IllegalStateException("payment lease lost during result application");
        }
        return true;
    }

    /**
     * 결제 시도를 조회한다 (결제 3).
     */
    @Transactional(readOnly = true)
    public PaymentAttempt getPayment(String orderNumber, String memberId, Long paymentId) {
        Order order = orderService.findByOrderNumberAndMemberId(orderNumber, memberId);
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentId).orElse(null);
        if (attempt == null || !attempt.getOrderId().equals(order.getId())) {
            throw new PaymentNotFoundException(
                "payment not found: orderNumber=" + orderNumber + ", paymentId=" + paymentId);
        }
        return attempt;
    }
}
