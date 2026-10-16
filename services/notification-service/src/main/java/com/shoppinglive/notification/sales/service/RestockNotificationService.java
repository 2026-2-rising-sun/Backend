package com.shoppinglive.notification.sales.service;

import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import com.shoppinglive.notification.sales.repository.RestockAlertRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 품절이 풀리면 신청자 전원에게 재입고 알림을 만든다.
 *
 * <p>알림을 보낸 뒤 그 상품의 신청을 지운다. 재입고 알림은 한 번만 간다.
 *
 * <p>판매 등록 시 저장된 상품 이름 사본을 사용하며, 이전 이벤트는 기존 문구로 표시한다.
 */
@Service
public class RestockNotificationService {

    /** 이벤트에서 온 이름이 아니라 알림 종류 이름이다. 화면이 아이콘을 고르는 근거다. */
    static final String EVENT_TYPE = "Restocked";

    private static final String TITLE = "재입고되었습니다";
    private static final String BODY = "등록하신 상품이 재입고되었습니다";

    private final RestockAlertRepository alerts;
    private final NotificationWriteRepository notifications;

    public RestockNotificationService(
            RestockAlertRepository alerts, NotificationWriteRepository notifications) {
        this.alerts = alerts;
        this.notifications = notifications;
    }

    /**
     * @return 이번 호출로 새로 만들어진 알림 수
     */
    public int create(String eventId, long productId, Instant occurredAt) {
        return create(eventId, productId, occurredAt, "");
    }

    public int create(String eventId, long productId, Instant occurredAt, String productName) {
        List<String> memberIds = alerts.findMemberIds(productId);
        if (memberIds.isEmpty()) {
            return 0;
        }
        NewNotification notification =
                new NewNotification(
                        eventId,
                        EVENT_TYPE,
                        TITLE,
                        productName == null || productName.isBlank()
                                ? BODY
                                : productName + " 상품이 재입고되었습니다",
                        NotificationLinkType.PRODUCT,
                        Long.toString(productId),
                        occurredAt);
        int created = notifications.createAll(notification, memberIds);
        alerts.deleteByProductId(productId);
        return created;
    }
}
