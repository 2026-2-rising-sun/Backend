package com.shoppinglive.notification.inbox.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.notification.inbox.domain.Notification;
import com.shoppinglive.notification.inbox.infrastructure.NotificationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 알림함 조회와 읽음 처리. 알림을 만드는 일(이벤트 구독)은 아직 없다. */
@Service
public class NotificationInboxService {

    private final NotificationRepository notifications;

    public NotificationInboxService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * 최신순 한 구간을 읽는다. {@code position} 이 null 이면 첫 구간이다.
     *
     * <p>다음 페이지가 있는지 알려면 전체 건수를 세는 대신 <b>요청한 개수보다 한 건 더</b> 읽어본다.
     * 세는 비용이 없고, 읽는 중에 알림이 쌓여도 답이 흔들리지 않는다.
     */
    @Transactional(readOnly = true)
    public NotificationPage list(String memberId, NotificationListPosition position, int size) {
        Limit limit = Limit.of(size + 1);
        List<Notification> found = position == null
                ? notifications.findLatest(memberId, limit)
                : notifications.findLatestBefore(memberId, position.occurredAt(), position.id(), limit);
        boolean hasNext = found.size() > size;
        return new NotificationPage(hasNext ? List.copyOf(found.subList(0, size)) : found, hasNext);
    }

    /** 안 읽은 알림 개수. 화면의 배지 숫자다. */
    @Transactional(readOnly = true)
    public long unreadCount(String memberId) {
        return notifications.countByMemberIdAndReadAtIsNull(memberId);
    }

    /**
     * 알림 1건을 읽음으로 표시한다.
     *
     * <p>같은 알림에 여러 번 호출해도 성공하고 처음 읽은 시각이 유지된다. 남의 알림이거나 없는 번호면
     * 둘 다 404 다 — 구분해서 알려주면 그 알림이 존재한다는 사실이 새어나간다.
     *
     * <p>목록 조회({@link #list})는 읽음 처리를 하지 않는다. 조회만으로 읽음이 되면 안 읽은 개수가
     * 항상 0 이 되어 배지가 의미를 잃는다.
     */
    @Transactional
    public void markRead(Long notificationId, String memberId) {
        Notification notification = notifications.findByIdAndMemberId(notificationId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다."));
        notification.markRead(Instant.now());
    }
}
