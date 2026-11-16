package com.shoppinglive.notification.inbox.repository;

import com.shoppinglive.notification.inbox.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 내 알림 목록의 최신순 첫 구간. 인덱스 ix_notification_member_occurred_at_id 순서와 같다. */
    @Query(
            """
            select n from Notification n
            where n.memberId = :memberId
            order by n.occurredAt desc, n.id desc""")
    List<Notification> findLatest(@Param("memberId") String memberId, Limit limit);

    /**
     * {@code (occurredAt, id)} 가 주어진 위치보다 앞선(오래된) 구간. offset 대신 키셋으로 넘겨 페이지가 깊어져도 비용이 같고, 조회 사이에
     * 알림이 새로 쌓여도 중복·누락이 없다. occurredAt 이 같은 알림은 id 로 순서를 정한다 (방송 시작 알림은 등록자 전원에게 같은 시각으로 만들어진다).
     *
     * <p>앞의 {@code occurredAt <= :occurredAt} 은 결과를 바꾸지 않지만, OR 조건만으로는 PostgreSQL 이 인덱스 탐색 시작점을 잡지
     * 못해 가장 최신 행부터 훑으므로 인덱스 범위를 좁히려고 둔다.
     */
    @Query(
            """
            select n from Notification n
            where n.memberId = :memberId
              and n.occurredAt <= :occurredAt and (n.occurredAt < :occurredAt or n.id < :id)
            order by n.occurredAt desc, n.id desc""")
    List<Notification> findLatestBefore(
            @Param("memberId") String memberId,
            @Param("occurredAt") Instant occurredAt,
            @Param("id") Long id,
            Limit limit);

    /** 안 읽은 알림 개수. 인덱스 ix_notification_member_read_at 로 처리된다. */
    long countByMemberIdAndReadAtIsNull(String memberId);

    /**
     * 소유자 조건을 조회에 함께 넣는다. 남의 알림은 "권한 없음"(403)이 아니라 없는 것으로 다뤄야 한다 — 403 을 주면 그 번호의 알림이 존재한다는 사실이
     * 새어나간다.
     */
    Optional<Notification> findByIdAndMemberId(Long id, String memberId);

    @org.springframework.data.jpa.repository.Modifying(
            clearAutomatically = true,
            flushAutomatically = true)
    @Query(
            "UPDATE Notification n SET n.readAt=:at, n.updatedAt=:at WHERE n.memberId=:member AND n.readAt IS NULL")
    int markAllReadIfUnread(@Param("member") String member, @Param("at") Instant at);

    @org.springframework.data.jpa.repository.Modifying(
            clearAutomatically = true,
            flushAutomatically = true)
    @Query(
            "UPDATE Notification n SET n.readAt=:at, n.updatedAt=:at WHERE n.id=:id AND n.memberId=:member AND n.readAt IS NULL")
    int markReadIfUnread(
            @Param("id") Long id, @Param("member") String member, @Param("at") Instant at);
}
