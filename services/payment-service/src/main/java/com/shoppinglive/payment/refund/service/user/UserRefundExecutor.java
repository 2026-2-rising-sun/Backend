package com.shoppinglive.payment.refund.service.user;

import com.shoppinglive.payment.refund.entity.Refund;

/** 고객 환불(USER_REFUND) 실행. 트랜잭션 밖에서 불리고, 토스도 트랜잭션 밖에서 부른다. */
public interface UserRefundExecutor {
    /** 접수 직후 실행한다. 다른 실행이 선점했으면 아무것도 하지 않는다. */
    void execute(Refund refund);

    /** 다음 처리 시각이 지난 PROCESSING·UNKNOWN 고객 환불을 한 묶음 다시 처리한다(REF-EXEC-01·04). */
    void retryDue();
}
