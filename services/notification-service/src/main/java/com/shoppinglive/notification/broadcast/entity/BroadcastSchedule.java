package com.shoppinglive.notification.broadcast.entity;

import java.time.Instant;

/**
 * 방송 일정 사본 한 행. Live 가 발행한 일정을 알림이 그대로 들고 있는 값이다.
 *
 * <p>JPA 엔티티가 아니다 — 이 표는 알림이 직접 쓰는 사본이라 조건부 INSERT·UPDATE 로만 바뀐다. 영속성 컨텍스트가 끼면 "저장된 version 보다 클 때만
 * 반영" 규칙을 SQL 한 문장으로 쓸 수 없다.
 *
 * @param broadcastVersion 이벤트 시점의 방송 version. 저장된 값보다 클 때만 반영한다
 */
public record BroadcastSchedule(
        long broadcastId, long broadcastVersion, String title, Instant scheduledAt) {}
