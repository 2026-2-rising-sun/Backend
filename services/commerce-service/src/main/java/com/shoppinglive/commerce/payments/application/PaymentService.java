package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 도메인 유스케이스 서비스.
 *
 * <p>결제 1 (시작) · 결제 2 (결과 확정 + Order 전이 + 재고 처리) 구현. 지연 재확인 (결제 3) 은
 * 후속 이슈.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final OrderService orderService;
    private final OrderJpaRepository orderRepository;
    private final PaymentAttemptJpaRepository paymentAttemptRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final MockPaymentEngine mockPaymentEngine;

    public PaymentService(
        OrderService orderService,
        OrderJpaRepository orderRepository,
        PaymentAttemptJpaRepository paymentAttemptRepository,
        SalesStockJpaRepository salesStockRepository,
        MockPaymentEngine mockPaymentEngine) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.salesStockRepository = salesStockRepository;
        this.mockPaymentEngine = mockPaymentEngine;
    }

    /**
     * 결제를 시작한다 (결제 1). Mock 엔진에 결과 확정 예약 걸림.
     */
    @Transactional
    public PaymentAttempt startPayment(
        String orderNumber, String rawPassword, PaymentScenario scenario) {
        PaymentScenario effectiveScenario =
            scenario != null ? scenario : PaymentScenario.INSTANT_SUCCESS;

        Order order = orderService.findByOrderNumberAndPassword(orderNumber, rawPassword);

        int updated = orderRepository.transitionStatus(
            order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING");
        if (updated == 0) {
            throw new OrderNotEligibleForPaymentException(orderNumber);
        }

        PaymentAttempt saved = paymentAttemptRepository
            .save(new PaymentAttempt(order.getId(), effectiveScenario, Instant.now()));

        // 트랜잭션 커밋 후에 Mock 엔진이 확정 호출을 하도록 커밋 전에 예약. 커밋 실패 시에도
        // 엔진은 attemptId 로만 조회하므로 조회 실패 → no-op.
        mockPaymentEngine.schedule(saved.getId(), effectiveScenario);

        return saved;
    }

    /**
     * 결제 결과를 확정한다 (결제 2). Mock 엔진 또는 Reconciler 가 호출.
     *
     * <p>흐름:
     * <ol>
     *   <li>PaymentAttempt 조회. 이미 terminal 상태면 no-op (idempotent)</li>
     *   <li>시나리오의 outcome (SUCCESS/FAILED) 을 조건부 UPDATE 로 확정. 실패 시 이미 다른
     *       경로가 확정한 것이므로 no-op</li>
     *   <li>SUCCESS: Order PAYMENT_CONFIRMING → PAID, 재고 consumeReserved
     *       FAILED: Order PAYMENT_CONFIRMING → FAILED, 재고 restoreReserved</li>
     * </ol>
     */
    @Transactional
    public void resolvePayment(Long paymentAttemptId) {
        PaymentAttempt attempt = paymentAttemptRepository.findById(paymentAttemptId).orElse(null);
        if (attempt == null || attempt.getStatus().isTerminal()) {
            return;
        }

        PaymentStatus outcome = attempt.getScenario().getOutcome();
        int updated = paymentAttemptRepository
            .resolveIfProcessing(paymentAttemptId, outcome.name());
        if (updated == 0) {
            // 이미 다른 흐름 (Reconciler race 등) 이 확정. 후속 처리 스킵
            return;
        }

        Order order = orderRepository.findById(attempt.getOrderId()).orElse(null);
        if (order == null) {
            log.warn("order missing for resolved payment: attemptId={}", paymentAttemptId);
            return;
        }

        if (outcome == PaymentStatus.SUCCESS) {
            orderRepository.transitionStatus(order.getId(), "PAYMENT_CONFIRMING", "PAID");
            salesStockRepository.consumeReserved(order.getSalesInfoId(), order.getQuantity());
        } else {
            orderRepository.transitionStatus(order.getId(), "PAYMENT_CONFIRMING", "FAILED");
            salesStockRepository.restoreReserved(order.getSalesInfoId(), order.getQuantity());
        }
    }
}
