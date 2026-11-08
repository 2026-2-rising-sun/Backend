package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.service.*;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.entity.*;
import com.shoppinglive.commercecore.payment.exception.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
    private final PaymentGroupService groups;
    private final OrderService orders;
    private final OrderJpaRepository repository;
    private final PaymentDeliveryStore deliveries;
    private final PaymentDispatcher dispatcher;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public PaymentService(
            PaymentGroupService groups,
            OrderService orders,
            OrderJpaRepository repository,
            PaymentDeliveryStore deliveries,
            PaymentDispatcher dispatcher,
            TransactionTemplate tx,
            JdbcTemplate jdbc) {
        this.groups = groups;
        this.orders = orders;
        this.repository = repository;
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public PaymentExecution startPayment(String number, String member) {
        Order order = orders.findByOrderNumberAndMemberId(number, member);
        if (order.getPaymentGroup() != null) {
            if (repository.findByPaymentGroupIdOrderByIdAsc(order.getPaymentGroup().getId()).size()
                    != 1) throw new OrderNotEligibleForPaymentException(number);
            return groups.start(
                    member,
                    order.getPaymentGroup().getGroupNumber(),
                    "legacy-payment-" + order.getId());
        }
        PaymentExecution accepted =
                tx.execute(
                        t -> {
                            jdbc.queryForObject(
                                    "SELECT id FROM orders WHERE id=? FOR UPDATE",
                                    Long.class,
                                    order.getId());
                            if (repository.transitionStatus(
                                            order.getId(), "PENDING_PAYMENT", "PAYMENT_CONFIRMING")
                                    != 1) throw new OrderNotEligibleForPaymentException(number);
                            return deliveries.payment(
                                    order.getId(),
                                    null,
                                    number,
                                    order.getPayableAmount(),
                                    "legacy-payment-" + order.getId(),
                                    null);
                        });
        return dispatcher.paymentAfterCommit(accepted.paymentId());
    }

    public PaymentExecution getPayment(String number, String member, Long id) {
        Order order = orders.findByOrderNumberAndMemberId(number, member);
        PaymentExecution payment = deliveries.payment(id);
        if (!payment.orderId().equals(order.getId()))
            throw new PaymentNotFoundException("해당 주문의 결제를 찾을 수 없습니다.");
        return dispatcher.payment(id);
    }

    public boolean refresh(Long id) {
        boolean previous = deliveries.payment(id).status().isConfirmedOutcome();
        return dispatcher.payment(id).status().isConfirmedOutcome() && !previous;
    }
}
