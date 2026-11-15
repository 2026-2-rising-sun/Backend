package com.shoppinglive.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApprovingSweeperPostgresTest extends RecoveryPostgresTestSupport {

    void approvingFor(ApproveCommand c, int seconds) {
        jdbc.update(
                "UPDATE payment SET approving_at = now() - ? * INTERVAL '1' SECOND WHERE order_ref = ?",
                seconds,
                c.orderRef());
    }

    @Test
    @DisplayName(
            "[PAY-RECOVER-01] APPROVING이 3분 넘게 남으면 UNKNOWN으로 옮겨 바로 조회하고 마감은 30분 뒤. 3분이 안 된 결제는 그대로")
    void movesStuckApprovingToUnknown() {
        ApproveCommand stuck = approving();
        ApproveCommand fresh = approving();
        approvingFor(stuck, 185);
        approvingFor(fresh, 175);

        assertThat(sweeper.sweep()).isEqualTo(1);

        Map<String, Object> row = payment(stuck.orderRef());
        assertThat(row.get("status")).isEqualTo("UNKNOWN");
        assertThat(secondsToNextCheck(stuck.orderRef())).isCloseTo(0.0, within(2.0));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT EXTRACT(EPOCH FROM check_deadline_at - now()) FROM payment WHERE order_ref = ?",
                                Double.class,
                                stuck.orderRef()))
                .isCloseTo(1800.0, within(2.0));
        assertThat(status(fresh.orderRef())).isEqualTo("APPROVING");
        assertThat(payment(fresh.orderRef()).get("next_check_at")).isNull();
    }

    @Test
    @DisplayName("[PAY-RECOVER-01][PAY-RECOVER-05] 스위퍼를 두 번(두 인스턴스) 돌려도 한 번만 옮긴다")
    void sweepIsIdempotent() {
        ApproveCommand stuck = approving();
        approvingFor(stuck, 600);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(sweeper.sweep()).isZero();
        assertThat(status(stuck.orderRef())).isEqualTo("UNKNOWN");
    }
}
