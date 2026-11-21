package com.shoppinglive.payment.refund.service.compensation;

import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundPurpose;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 끝나지 못한 시스템 보상을 다시 집는다(REF-EXEC-01). 선점과 예외 처리는 실행기가 건별로 한다. */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(CompensationProperties.class)
public class CompensationRetryJob {
    private final RefundRepository refunds;
    private final CompensationExecutor executor;
    private final CompensationProperties properties;

    public void run() {
        for (Refund refund :
                refunds.findDue(
                        RefundPurpose.SYSTEM_COMPENSATION.name(),
                        Instant.now(),
                        properties.batchSize())) {
            executor.execute(refund);
        }
    }
}
