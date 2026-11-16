package com.shoppinglive.notification.commerce.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분마다 곧 만료되는 결제 묶음을 확인한다.
 *
 * <p>보낼 시각을 메모리 타이머로 재지 않는다. 서버를 재시작하면 예약이 사라져서 <b>배포할 때마다 알림이 누락된다.</b> 보낼 시각의 근거는 DB(결제 묶음 사본)에
 * 있고, 이 스케줄러는 그걸 주기적으로 훑는다.
 *
 * <p>Commerce 의 주문 만료 정리, 방송 시작 임박 알림과 같은 주기, 같은 방식이다.
 */
@Component
public class PaymentExpiringSoonScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiringSoonScheduler.class);

    private final PaymentExpiringSoonService service;

    public PaymentExpiringSoonScheduler(PaymentExpiringSoonService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void run() {
        try {
            int created = service.notifyExpiringSoon(Instant.now());
            if (created > 0) {
                log.info("결제 만료 임박 알림 생성 완료: 생성 건수={}", created);
            }
        } catch (RuntimeException e) {
            // 던지면 다음 주기가 돌지 않는다. 한 주기가 실패해도 1분 뒤 다시 시도해야 한다.
            log.error("결제 만료 임박 알림 일괄 처리 실패", e);
        }
    }
}
