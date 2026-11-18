package com.shoppinglive.payment.payment.service;

import com.shoppinglive.payment.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** APPROVING에 멈춘 결제(승인 처리 중 서버 종료 등)를 UNKNOWN으로 옮겨 바로 조회하게 한다(PAY-RECOVER-01). */
@Service
public class ApprovingSweeper {
    private static final Logger log = LoggerFactory.getLogger(ApprovingSweeper.class);

    private final PaymentRepository payments;
    private final RecoveryProperties properties;

    public ApprovingSweeper(PaymentRepository payments, RecoveryProperties properties) {
        this.payments = payments;
        this.properties = properties;
    }

    /**
     * @return 옮긴 결제 수
     */
    @Transactional
    public int sweep() {
        int moved =
                payments.sweepStuckApproving(
                        properties.approvingStuckAfter().toSeconds(),
                        properties.checkDeadline().toSeconds(),
                        properties.sweepBatchSize());
        if (moved > 0) {
            log.warn("APPROVING에 멈춘 결제 {}건을 UNKNOWN으로 옮겼다", moved);
        }
        return moved;
    }
}
