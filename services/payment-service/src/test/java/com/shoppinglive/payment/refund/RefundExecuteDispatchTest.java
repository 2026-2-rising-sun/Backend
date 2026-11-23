package com.shoppinglive.payment.refund;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundPurpose;
import com.shoppinglive.payment.refund.service.RefundIntakeService;
import com.shoppinglive.payment.refund.service.compensation.CompensationExecutor;
import com.shoppinglive.payment.refund.service.user.UserRefundExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundExecuteDispatchTest {
    final UserRefundExecutor user = mock(UserRefundExecutor.class);
    final CompensationExecutor compensation = mock(CompensationExecutor.class);
    final RefundIntakeService intake =
            new RefundIntakeService(null, null, null, null, user, compensation);

    @Test
    @DisplayName("[REF-ACCEPT-01] 새로 접수한 환불은 목적별 실행기 하나만 부른다")
    void dispatchesByPurpose() {
        Refund userRefund = refund(RefundPurpose.USER_REFUND);
        Refund systemRefund = refund(RefundPurpose.SYSTEM_COMPENSATION);

        intake.execute(userRefund);
        verify(user).execute(userRefund);
        verify(compensation, never()).execute(userRefund);

        intake.execute(systemRefund);
        verify(compensation).execute(systemRefund);
        verify(user, never()).execute(systemRefund);
    }

    private static Refund refund(RefundPurpose purpose) {
        Refund refund = mock(Refund.class);
        when(refund.getPurpose()).thenReturn(purpose);
        return refund;
    }
}
