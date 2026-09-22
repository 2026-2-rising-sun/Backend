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

    private final OrderService orderService;
    private final OrderJpaRepository orderRepository;
    private final PaymentAttemptJpaRepository paymentAttemptRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final SalesService salesService;
    private final MockPaymentEngine mockPaymentEngine;
    private final ObjectProvider<DevPaymentScenarioRegistry> devRegistryProvider;

    public PaymentService(
        OrderService orderService,
        OrderJpaRepository orderRepository,
        PaymentAttemptJpaRepository paymentAttemptRepository,
        SalesStockJpaRepository salesStockRepository,
        SalesService salesService,
        MockPaymentEngine mockPaymentEngine,
        ObjectProvider<DevPaymentScenarioRegistry> devRegistryProvider) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.salesStockRepository = salesStockRepository;
        this.salesService = salesService;
        this.mockPaymentEngine = mockPaymentEngine;
        this.devRegistryProvider = devRegistryProvider;
    }

    /**
     * 결제를 시작한다.
     *
     * <p>시나리오 결정 우선순위:
     * <ol>
     *   <li>요청 body 에 명시된 scenario</li>
     *   <li>Dev 레지스트리에 사전 지정된 scenario (결제 4, dev 프로파일만)</li>
     *   <li>Default: {@link PaymentScenario#INSTANT_SUCCESS}</li>
     * </ol>
     */
    @Transactional
    public PaymentAttempt startPayment(
        String orderNumber, String rawPassword, PaymentScenario scenario) {
        PaymentScenario effectiveScenario = resolveScenario(orderNumber, scenario);

        Order order = orderService.findByOrderNumberAndPassword(orderNumber, rawPassword);

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

    private PaymentScenario resolveScenario(String orderNumber, PaymentScenario explicit) {
        if (explicit != null) {
            return explicit;
        }
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
    @Transactional
    public boolean resolvePayment(Long paymentAttemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (attempt == null || attempt.getStatus().isTerminal()) {
            return false;
        }

        PaymentStatus outcome = attempt.getScenario().getOutcome();
        int updated = paymentAttemptRepository
            .resolveIfProcessing(paymentAttemptId, outcome.name());
        if (updated == 0) {
            return false;
        }

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
        return true;
    }

    /**
     * 결제 시도를 조회한다 (결제 3).
     */
    @Transactional(readOnly = true)
    public PaymentAttempt getPayment(String orderNumber, String rawPassword, Long paymentId) {
        Order order = orderService.findByOrderNumberAndPassword(orderNumber, rawPassword);
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentId).orElse(null);
        if (attempt == null || !attempt.getOrderId().equals(order.getId())) {
            throw new PaymentNotFoundException(
                "payment not found: orderNumber=" + orderNumber + ", paymentId=" + paymentId);
        }
        return attempt;
    }
}
