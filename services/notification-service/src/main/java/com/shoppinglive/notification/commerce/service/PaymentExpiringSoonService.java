package com.shoppinglive.notification.commerce.service;

import com.shoppinglive.notification.commerce.entity.PaymentGroup;
import com.shoppinglive.notification.commerce.repository.PaymentGroupRepository;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 만료 전에 묶음을 만든 사람에게 알림을 만든다.
 *
 * <p>만료된 뒤에 오는 "주문 결제 시간이 만료되었습니다" 만으로는 늦다. 사용자가 할 수 있는 일이 없는 통보다. 만료 전에 한 번 알려야 결제를 마칠 수 있다.
 *
 * <p><b>이 알림은 이벤트가 오는 순간 만들어지지 않는다.</b> 묶음 생성 이벤트로 "언제 보낼지" 만 알아내고, 그 시각이 되면 만든다. 그래서 받는 쪽이 이벤트가
 * 아니라 스케줄러다. 방송 시작 임박 알림과 같은 구조다.
 *
 * <p>얼마나 전에 보낼지는 알림이 정한다. Commerce 의 만료 시간(현재 15분)은 설정값이고 바뀔 수 있어서, 받은 {@code expiresAt} 을 기준으로만
 * 계산한다. Commerce 가 값을 바꿔도 이 코드는 그대로다.
 */
@Service
public class PaymentExpiringSoonService {

    /** 이벤트에서 온 이름이 아니라 알림 종류 이름이다. 화면이 아이콘을 고르는 근거다. */
    static final String EVENT_TYPE = "PaymentExpiringSoon";

    private static final String TITLE = "결제 시간이 곧 만료됩니다";
    private static final int BATCH_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(PaymentExpiringSoonService.class);

    private final PaymentGroupRepository groups;
    private final NotificationWriteRepository notifications;
    private final Duration leadTime;
    private final TransactionTemplate transaction;

    public PaymentExpiringSoonService(
            PaymentGroupRepository groups,
            NotificationWriteRepository notifications,
            @Value("${notification.payment-expiry-lead:PT5M}") Duration leadTime,
            PlatformTransactionManager transactionManager) {
        this.groups = groups;
        this.notifications = notifications;
        this.leadTime = leadTime;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 곧 만료되는 묶음을 찾아 알림을 만든다.
     *
     * <p>한 묶음이 실패해도 나머지는 계속한다. 한 건 때문에 그 주기 전체가 멈추면 다른 묶음의 알림까지 늦어진다.
     *
     * @return 이번 호출로 새로 만들어진 알림 수
     */
    public int notifyExpiringSoon(Instant now) {
        List<PaymentGroup> expiringSoon =
                groups.findExpiringSoon(now, now.plus(leadTime), BATCH_LIMIT);
        int created = 0;
        for (PaymentGroup group : expiringSoon) {
            try {
                created += notifyOne(group, now);
            } catch (RuntimeException e) {
                log.warn("payment expiry alert failed groupNumber={}", group.groupNumber(), e);
            }
        }
        return created;
    }

    /** 조건부 갱신과 알림 저장을 한 트랜잭션으로 묶어 종료 이벤트와 직렬화하고, 저장 실패 시 발송 표시도 되돌린다. */
    private int notifyOne(PaymentGroup group, Instant now) {
        return transaction.execute(
                status -> {
                    if (!groups.markExpiringSoonSent(group.groupNumber(), now)) return 0;
                    return notifications.createAll(
                            toNotification(group, now), List.of(group.memberId()));
                });
    }

    private NewNotification toNotification(PaymentGroup group, Instant now) {
        return new NewNotification(
                eventKey(group.groupNumber()),
                EVENT_TYPE,
                TITLE,
                body(group.productSummary()),
                NotificationLinkType.PAYMENT_GROUP,
                group.groupNumber(),
                // 만료 시각은 아직 오지 않은 시각이다. 그 값을 쓰면 미래 알림이 목록 맨 위에 박힌다.
                now);
    }

    /**
     * 묶음마다 늘 같은 값이다. 이벤트에서 온 ID 가 아니라 알림이 만든 키다.
     *
     * <p>같은 묶음 생성 이벤트가 다시 와도 이 키는 그대로여서, 임박 알림은 묶음당 한 번만 간다.
     */
    private static String eventKey(String groupNumber) {
        return "payment-expiry-" + groupNumber;
    }

    private String body(String productSummary) {
        return productSummary + ", " + leadTime.toMinutes() + "분 뒤 결제가 만료됩니다";
    }
}
