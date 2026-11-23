package com.shoppinglive.payment.refund.service.compensation;

import com.shoppinglive.payment.refund.entity.Refund;

/**
 * 시스템 보상(SYSTEM_COMPENSATION) 실행. #386이 구현한다. 트랜잭션 밖에서 불리고, 결과는 {@code RefundResultService.record}로
 * 남긴다.
 */
public interface CompensationExecutor {
    void execute(Refund refund);
}
