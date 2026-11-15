package com.shoppinglive.payment.payment.service;

import com.shoppinglive.payment.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 시각이 지난 CREATED 결제를 CANCELED로 닫는다(PAY-EXPIRE-01). 돈이 나가지 않은 결제라 분개·이벤트는 없다. 승인 시작과 같은 행을 노려도 둘
 * 다 이전 상태 조건부 UPDATE라 한쪽만 1행이다.
 */
@Service
@EnableConfigurationProperties({RecoveryProperties.class, ExpiryProperties.class})
public class PaymentExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryJob.class);

    private final PaymentRepository payments;
    private final ExpiryProperties properties;

    public PaymentExpiryJob(PaymentRepository payments, ExpiryProperties properties) {
        this.payments = payments;
        this.properties = properties;
    }

    /**
     * @return 닫은 결제 수
     */
    @Transactional
    public int expire() {
        int closed = payments.expireCreated(properties.batchSize());
        if (closed > 0) {
            log.info("만료된 결제 {}건을 CANCELED로 닫았다", closed);
        }
        return closed;
    }
}
