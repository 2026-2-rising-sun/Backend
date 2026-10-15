package com.shoppinglive.notification.broadcast.service;

import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 방송이 시작되면 신청자 전원에게 알림을 만든다.
 *
 * <p>이 알림이 필요한 이유는 받을 사람이 <b>방송 화면에 없기</b> 때문이다. Live 의 실시간 전달은 그 방송을 보고 있는 사람에게만 닿는다. 신청만 해두고 다른
 * 일을 하는 사람에게 닿을 길은 알림뿐이다.
 *
 * <p>7종 중 유일하게 <b>한 이벤트로 여러 명에게</b> 알림이 간다. 중복은 {@code (이벤트ID, 회원ID)} 로 막으므로, 같은 이벤트가 두 번 와도 이미 만든
 * 사람은 건너뛰고 못 만든 사람만 만든다.
 */
@Service
public class BroadcastStartNotificationService {

    private static final String TITLE = "방송이 시작되었습니다";

    private final BroadcastAlertRepository alerts;
    private final NotificationWriteRepository notifications;

    public BroadcastStartNotificationService(
            BroadcastAlertRepository alerts, NotificationWriteRepository notifications) {
        this.alerts = alerts;
        this.notifications = notifications;
    }

    /**
     * @param occurredAt 방송이 실제로 시작한 시각. 이벤트를 받은 시각이 아니라 이 값으로 정렬된다
     * @return 이번 호출로 새로 만들어진 알림 수
     */
    public int create(
            String eventId, String eventType, long broadcastId, String title, Instant occurredAt) {
        List<String> memberIds = alerts.findMemberIds(broadcastId);
        NewNotification notification =
                new NewNotification(
                        eventId,
                        eventType,
                        TITLE,
                        body(title),
                        NotificationLinkType.BROADCAST,
                        Long.toString(broadcastId),
                        occurredAt);
        return notifications.createAll(notification, memberIds);
    }

    /** 방송 제목은 100자까지라 알림 제목(100자)에 넣으면 넘칠 수 있다. 그래서 본문에 넣는다. */
    private static String body(String broadcastTitle) {
        return "등록하신 '" + broadcastTitle + "' 방송이 시작되었습니다";
    }
}
