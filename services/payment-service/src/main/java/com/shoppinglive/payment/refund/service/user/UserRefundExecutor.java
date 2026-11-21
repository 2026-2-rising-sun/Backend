package com.shoppinglive.payment.refund.service.user;

import com.shoppinglive.payment.refund.entity.Refund;

/** 고객 환불(USER_REFUND) 실행. #370이 구현한다. 트랜잭션 밖에서 불리고, 결과는 {@code RefundResultService.record}로 남긴다. */
public interface UserRefundExecutor {
    void execute(Refund refund);
}
