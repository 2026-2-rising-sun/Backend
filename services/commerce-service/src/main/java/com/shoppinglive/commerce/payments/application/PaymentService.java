package com.shoppinglive.commerce.payments.application;

import com.shoppinglive.commerce.orders.application.OrderService;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentScenario;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 도메인 유스케이스 서비스.
 *
 * <p>결제 1 (시작) 만 구현. 결과 확정 (결제 2) · 지연 재확인 (결제 3) · 시나리오 설정 (결제 4) 은
 * 후속 이슈에서 추가한다.
 */
@Service
public class PaymentService {

    private final OrderService orderService;
    private final OrderJpaRepository orderRepository;
    private final PaymentAttemptJpaRepository paymentAttemptRepository;

    public PaymentService(
        OrderService orderService,
        OrderJpaRepository orderRepository,
        PaymentAttemptJpaRepository paymentAttemptRepository) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    /**
     * 결제를 시작한다 (결제 1).
     *
     * <p>흐름:
     * <ol>
     *   <li>비밀번호 검증 (실패 시 404)</li>
     *   <li>Order 상태 PENDING_PAYMENT → PAYMENT_CONFIRMING 조건부 UPDATE (실패 시 409)</li>
     *   <li>PaymentAttempt INSERT (상태 = PROCESSING, scheduled_resolve_at = 시나리오 delay 반영)</li>
     * </ol>
     *
     * <p>실제 결과 확정 (SUCCESS/FAILED) 은 이 메서드에서 하지 않는다. 결제 2 이슈의 Mock
     * 엔진 · 결제 3 의 Reconciler 가 담당.
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

        PaymentAttempt attempt = new PaymentAttempt(order.getId(), effectiveScenario, Instant.now());
        return paymentAttemptRepository.save(attempt);
    }
}
