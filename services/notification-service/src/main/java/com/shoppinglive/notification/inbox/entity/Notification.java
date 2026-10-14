package com.shoppinglive.notification.inbox.entity;

import com.shoppinglive.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 회원 1명에게 보여줄 알림 1건.
 *
 * <p>만들어진 뒤에는 읽음 여부만 바뀐다. 문구는 만들 때 완성해서 넣으므로 나중에 고치지 않는다 (지난 알림의 문구가 바뀌면 사용자가 받은 내용과 달라진다).
 *
 * <p>알림을 만드는 쪽(이벤트 구독)은 아직 없다. 이벤트 ID·종류와 "눌렀을 때 보낼 곳" 은 이벤트 계약이 확정된 뒤에 추가한다.
 */
@Entity
@Table(name = "notification")
public class Notification extends BaseEntity {

    @Column(nullable = false, length = 36, updatable = false)
    private String memberId;

    @Column(nullable = false, length = 100, updatable = false)
    private String title;

    @Column(nullable = false, length = 500, updatable = false)
    private String body;

    /** 알림이 알리는 일이 실제로 일어난 시각. 목록 정렬 기준이며, 저장한 시각(createdAt)과 다르다. */
    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    /** 읽은 시각. 비어 있으면 안 읽음. */
    @Column private Instant readAt;

    protected Notification() {}

    public Notification(String memberId, String title, String body, Instant occurredAt) {
        this.memberId = memberId;
        this.title = title;
        this.body = body;
        this.occurredAt = occurredAt;
    }

    /**
     * 읽음으로 표시한다. 이미 읽은 알림에 다시 호출해도 <b>처음 읽은 시각을 유지</b>한다.
     *
     * <p>여러 번 호출해도 결과가 같아야 한다. 사용자가 같은 알림을 두 번 눌렀다고 오류를 줄 이유가 없다.
     */
    public void markRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public String getMemberId() {
        return memberId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
