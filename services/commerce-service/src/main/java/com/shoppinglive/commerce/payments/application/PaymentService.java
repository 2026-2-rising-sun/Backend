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

        mockPaymentEngine.schedule(saved.getId(), effectiveScenario);

        return saved;
    }

    /**
     * 결제 결과를 확정한다 (결제 2). Mock 엔진 또는 Reconciler 가 호출. Idempotent.
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

    /**
     * 결제 시도를 조회한다 (결제 3).
     *
     * <p>주문 비밀번호 검증 + 결제 시도가 해당 주문 소속인지 검증. 두 조건 실패 모두 404 로
     * 통합 (leak 방지).
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
