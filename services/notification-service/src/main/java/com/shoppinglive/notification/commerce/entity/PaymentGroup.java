package com.shoppinglive.notification.commerce.entity;

import java.time.Instant;

/**
 * 결제 묶음 사본 한 행. 만료 임박 알림을 보낼 근거다.
 *
 * <p>JPA 엔티티가 아니다. 이 표는 조건부 INSERT 와 UPDATE 로만 바뀌어서, 영속성 컨텍스트가 끼면 "아직 안 보냈을 때만 표시" 같은 규칙을 SQL 한
 * 문장으로 쓸 수 없다.
 *
 * @param productSummary 알림 문구에 쓸 "가을 니트 외 2건". 받을 때 한 번 줄여 둔다
 * @param expiresAt 결제 만료 시각. Commerce 가 준 값 그대로다. 몇 분 전에 보낼지는 알림이 따로 정한다
 */
public record PaymentGroup(
        String groupNumber, String memberId, String productSummary, Instant expiresAt) {}
