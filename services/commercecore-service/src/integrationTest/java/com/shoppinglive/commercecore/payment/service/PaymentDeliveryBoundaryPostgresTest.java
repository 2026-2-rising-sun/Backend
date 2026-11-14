package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.common.core.BusinessException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class PaymentDeliveryBoundaryPostgresTest extends RefundTestSupport {
    @MockitoSpyBean(name = "boundaryPaymentClient")
    PaymentClient client;

    @Autowired PaymentResultHandler handler;
    @Autowired RefundIntakeService refunds;

    com.shoppinglive.commercecore.order.service.PaymentGroupService.GroupResponse create() {
        return paymentGroups
                .create(
                        MEMBER_A,
                        List.of(
                                new com.shoppinglive.commercecore.order.service.PaymentGroupService
                                        .Selection(a.getId(), a.getVersion())),
                        "구매자",
                        "01012345678",
                        20000,
                        "boundary-order")
                .group();
    }

    @Test
    @DisplayName("결과 선도착은 requestId로 적용하고 늦은 PROCESSING HTTP 응답은 무시한다")
    void earlyResultBeforeHttpResponseIsAppliedOnce() {
        var group = create();
        doAnswer(
                        i -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var pending = (PaymentExecution) i.callRealMethod();
                            assertThat(
                                            paymentServer
                                                    .context
                                                    .getBean(
                                                            com.shoppinglive.payment.service
                                                                    .PaymentService.class)
                                                    .resolvePayment(pending.paymentId()))
                                    .isTrue();
                            assertThat(handler.payment(client.get(pending.paymentId()))).isTrue();
                            return pending;
                        })
                .when(client)
                .start(any());
        var result = paymentGroups.start(MEMBER_A, group.groupNumber(), "early");
        assertThat(result.status().name()).isEqualTo("SUCCESS");
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status().name())
                .isEqualTo("PAID");
        assertThat(paymentGroups.start(MEMBER_A, group.groupNumber(), "early").paymentId())
                .isEqualTo(result.paymentId());
        assertThat(legacyStock(orders.findAll().getFirst().getTradeSkuId()).getReserved()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM event_outbox WHERE event_type='PaymentSucceeded'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("HTTP 응답 유실 후 같은 공개 키로 재요청하면 영속 requestId와 Payment 원본을 재사용한다")
    void lostHttpResponseReusesDurableOriginal() {
        var group = create();
        doAnswer(
                        i -> {
                            i.callRealMethod();
                            throw new ResourceAccessException("응답 유실");
                        })
                .doCallRealMethod()
                .when(client)
                .start(any());
        var pending = paymentGroups.start(MEMBER_A, group.groupNumber(), "lost");
        assertThat(pending.status().name()).isEqualTo("PROCESSING");
        var recovered = paymentGroups.start(MEMBER_A, group.groupNumber(), "lost");
        assertThat(recovered.paymentId()).isEqualTo(pending.paymentId());
        assertThat(recovered.requestId()).isEqualTo(pending.requestId());
        assertThat(recovered.status().name()).isEqualTo("SUCCESS");
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_attempt", Long.class))
                .isEqualTo(1);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_gateway_result", Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Payment가 불가용해도 같은 환불 키의 기존 접수 결과를 원격 조회 없이 반환한다")
    void refundReplayDoesNotDependOnRemotePaymentAvailability() {
        var group = createAndPay();
        var first =
                refunds.request(MEMBER_A, group.groupNumber(), "refund-replay", List.of(a.getId()))
                        .refund();
        clearInvocations(client);
        doThrow(new ResourceAccessException("Payment 조회 불가")).when(client).get(anyLong());
        var replay =
                refunds.request(MEMBER_A, group.groupNumber(), "refund-replay", List.of(a.getId()));
        assertThat(replay.created()).isFalse();
        assertThat(replay.refund().id()).isEqualTo(first.id());
        verify(client, never()).get(anyLong());
        assertThatThrownBy(
                        () ->
                                refunds.request(
                                        MEMBER_A,
                                        group.groupNumber(),
                                        "refund-replay",
                                        List.of(b.getId())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("환불 원결제 HTTP 조회는 Core 트랜잭션 밖이며 저장 직전 변경된 원결제 참조를 거절한다")
    void refundRemoteLookupIsOutsideTransactionAndReferenceIsRevalidated() {
        var group = createAndPay();
        doAnswer(
                        i -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var payment = (PaymentExecution) i.callRealMethod();
                            jdbc.update(
                                    "UPDATE payment_group SET payment_id=payment_id+10000 WHERE id=?",
                                    payment.paymentGroupId());
                            return payment;
                        })
                .when(client)
                .get(anyLong());
        assertThatThrownBy(
                        () ->
                                refunds.request(
                                        MEMBER_A,
                                        group.groupNumber(),
                                        "changed-reference",
                                        List.of(a.getId())))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request", Long.class)).isZero();
    }
}
