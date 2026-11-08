package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.payment.dto.RefundCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            "commercecore.refunds.api-enabled=true",
            "commercecore.refunds.execution-enabled=false",
            "commercecore.payment.delivery-recovery-enabled=true"
        })
@AutoConfigureMockMvc
@Tag("postgres")
@EnabledIfEnvironmentVariable(named = "COMMERCECORE_TEST_POSTGRES_URL", matches = ".+")
class RefundDispatchDisabledPostgresTest extends RefundTestSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired PaymentResultHandler handler;

    @MockitoSpyBean(name = "boundaryPaymentClient")
    PaymentClient client;

    @ParameterizedTest
    @ValueSource(longs = {1000, 25000})
    @DisplayName("Core 환불 비활성은 양수·0원 자동 전달을 막고 재활성 후 기존 원문과 결과 반영을 복구한다")
    void disabledCoreKeepsDurableRequestUntilEnabled(long discount) throws Exception {
        var group = createAndPayWithCoupon(discount);
        clearInvocations(client);
        var response =
                mvc.perform(
                                post("/v1/payment-groups/{number}/refunds", group.groupNumber())
                                        .header("Authorization", bearer(MEMBER_A))
                                        .header("Idempotency-Key", "disabled-refund"))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id =
                mapper.readTree(response.getResponse().getContentAsString())
                        .path("data")
                        .path("id")
                        .asLong();
        var accepted = deliveries.byRefund(id);
        dispatcher.recover();
        dispatcher.recover();
        verify(client, never()).refund(any(RefundCommand.class));
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM refund_execution", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM refund_request WHERE id=?", String.class, id))
                .isEqualTo("PROCESSING");
        // 재활성된 relay는 새 UUID를 만들지 않고 이미 저장한 요청을 그대로 전송한다.
        var enabled =
                new PaymentDispatcher(
                        deliveries,
                        client,
                        context.getBeanProvider(PaymentResultHandler.class),
                        true,
                        true);
        enabled.recover();
        assertThat(deliveries.byRefund(id).requestId()).isEqualTo(accepted.requestId());
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT request_id FROM refund_execution WHERE id=?",
                                String.class,
                                id))
                .isEqualTo(accepted.requestId());
        assertThat(
                        paymentServer
                                .context
                                .getBean(
                                        com.shoppinglive.payment.service.RefundExecutionService
                                                .class)
                                .execute(id))
                .isTrue();
        // 완료된 결과의 적용은 현재 Core 실행 flag가 false여도 허용한다.
        assertThat(handler.refund(client.refund(id))).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM refund_request WHERE id=?", String.class, id))
                .isEqualTo("SUCCESS");
        assertThat(paymentGroups.get(MEMBER_A, group.groupNumber()).status().name())
                .isEqualTo("REFUNDED");
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mock_refund_result", Long.class))
                .isEqualTo(discount == 25000 ? 0 : 1);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("비활성 환불 100건이 앞서 있어도 후속 결제 재전송을 막지 않고 환불 원문을 보존한다")
    void disabledRefundBacklogDoesNotStarvePaymentDelivery() {
        var paid = createAndPayWithCoupon(25000);
        var group = groups.findByGroupNumberAndMemberId(paid.groupNumber(), MEMBER_A).orElseThrow();
        var original = orders.findByPaymentGroupIdOrderByIdAsc(group.getId()).getFirst();
        var ids =
                jdbc.query(
                        """
            INSERT INTO refund_request(payment_group_id,member_id,idempotency_key,request_fingerprint,
              refund_amount,status,requested_at,created_at,updated_at)
            SELECT ?,?,'disabled-backlog-'||n,'queue-fixture',0,
              CASE WHEN n%2=0 THEN 'UNKNOWN' ELSE 'PROCESSING' END,
              CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM generate_series(1,100) n
            RETURNING id
            """,
                        (r, n) -> r.getLong(1), group.getId(), MEMBER_A);
        var tx = context.getBean(org.springframework.transaction.support.TransactionTemplate.class);
        for (Long id : ids)
            tx.executeWithoutResult(
                    t ->
                            deliveries.refund(
                                    id,
                                    group.getPaymentId(),
                                    original.getId(),
                                    group.getId(),
                                    paid.groupNumber(),
                                    0));
        jdbc.update(
                "UPDATE payment_delivery_request SET status='UNKNOWN' WHERE kind='REFUND' AND core_refund_request_id%2=0");
        var before =
                jdbc.queryForList(
                        "SELECT * FROM payment_delivery_request WHERE kind='REFUND' ORDER BY id");
        var item = cart.add(MEMBER_B, 81001L, 1);
        var later =
                paymentGroups
                        .create(
                                MEMBER_B,
                                java.util.List.of(
                                        new com.shoppinglive.commercecore.order.service
                                                .PaymentGroupService.Selection(
                                                item.getId(), item.getVersion())),
                                "후속구매자",
                                "01012345678",
                                10000,
                                "later-order")
                        .group();
        doThrow(new org.springframework.web.client.ResourceAccessException("최초 전송 중단"))
                .when(client)
                .start(any());
        var pending = paymentGroups.start(MEMBER_B, later.groupNumber(), "later-payment");
        doCallRealMethod().when(client).start(any());
        var relay =
                new PaymentDispatcher(
                        deliveries,
                        client,
                        context.getBeanProvider(PaymentResultHandler.class),
                        true,
                        false);
        for (int i = 0; i < 2; i++) {
            jdbc.update(
                    "UPDATE payment_delivery_request SET next_action_at=clock_timestamp()-INTERVAL '1 second' WHERE kind='PAYMENT' AND payment_id=?",
                    pending.paymentId());
            relay.recover();
        }
        assertThat(paymentGroups.get(MEMBER_B, later.groupNumber()).status().name())
                .isEqualTo("PAID");
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT request_id FROM payment_attempt WHERE payment_id=?",
                                String.class,
                                pending.paymentId()))
                .isEqualTo(pending.requestId());
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM payment_delivery_request WHERE kind='REFUND' ORDER BY id"))
                .isEqualTo(before);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM refund_execution", Long.class))
                .isZero();
        assertThat(deliveries.due(true)).hasSize(100);
        assertThat(deliveries.due(false)).isEmpty();
    }
}
