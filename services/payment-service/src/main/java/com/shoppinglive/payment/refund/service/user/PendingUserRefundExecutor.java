package com.shoppinglive.payment.refund.service.user;

import com.shoppinglive.payment.refund.entity.Refund;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 실행 전 자리. #370이 이 클래스를 실제 실행으로 바꾼다. 그 전까지 접수만 하고 결과를 보내지 않는다. */
@Slf4j
@Component
class PendingUserRefundExecutor implements UserRefundExecutor {
    @Override
    public void execute(Refund refund) {
        log.info("고객 환불 실행은 아직 없어 접수만 했다: refundRequestId={}", refund.getRefundRequestId());
    }
}
