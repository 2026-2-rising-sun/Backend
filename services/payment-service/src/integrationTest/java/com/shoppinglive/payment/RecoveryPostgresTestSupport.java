package com.shoppinglive.payment;

import com.shoppinglive.payment.client.toss.TossPayment;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import com.shoppinglive.payment.payment.service.ApprovingSweeper;
import com.shoppinglive.payment.payment.service.PaymentExpiryJob;
import com.shoppinglive.payment.payment.service.UnknownPaymentPoller;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 복구·만료 작업(#349)을 실제 PostgreSQL에서 돌린다. 스케줄러는 끄고({@code application-test.yml}) 작업 메서드를 직접 부른다. 시각은
 * DB {@code now()} 기준이라 시각 컬럼을 과거 값으로 넣어 만든다.
 */
abstract class RecoveryPostgresTestSupport extends ApprovalPostgresTestSupport {
    @Autowired ApprovingSweeper sweeper;
    @Autowired UnknownPaymentPoller poller;
    @Autowired PaymentExpiryJob expiry;

    /** 같은 스키마를 쓰는 다른 테스트가 남긴 행이 이 테스트의 작업 대상이 되지 않게 한다. */
    @BeforeEach
    void isolateOtherRows() {
        jdbc.update("UPDATE payment SET next_check_at = NULL WHERE status = 'UNKNOWN'");
        jdbc.update("UPDATE payment SET approving_at = now() WHERE status = 'APPROVING'");
        jdbc.update(
                "UPDATE payment SET expires_at = now() + INTERVAL '1' DAY WHERE status = 'CREATED'");
    }

    /** 승인 ①까지만 한 APPROVING 결제. 토스는 부르지 않는다. */
    ApproveCommand approving() {
        ApproveCommand c = newPayment();
        approvals.start(c);
        return c;
    }

    /** 결과 불명이 된 지 {@code elapsed}가 지났고 지금 조회할 차례인 결제. 마감은 불명 시각 + 30분이다. */
    ApproveCommand unknown(Duration elapsed) {
        ApproveCommand c = approving();
        jdbc.update(
                "UPDATE payment SET status = 'UNKNOWN', next_check_at = now(),"
                        + " check_deadline_at = now() + INTERVAL '30' MINUTE - ? * INTERVAL '1' SECOND"
                        + " WHERE order_ref = ?",
                elapsed.toSeconds(),
                c.orderRef());
        return c;
    }

    /** 다음 조회까지 남은 초. 조회 대상에서 빠졌으면 null. */
    Double secondsToNextCheck(String orderRef) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM next_check_at - now()) FROM payment WHERE order_ref = ?",
                Double.class,
                orderRef);
    }

    static TossPayment withStatus(ApproveCommand c, String status) {
        return new TossPayment(
                c.paymentKey(),
                c.pgOrderId(),
                status,
                c.amount(),
                OffsetDateTime.parse("2026-11-10T14:02:11+09:00"),
                true);
    }
}
