package com.shoppinglive.notification.sales.entity;

/**
 * 판매 상태 사본 한 행.
 *
 * <p>JPA 엔티티가 아니다. 이 표는 조건부 INSERT 와 UPDATE 로만 바뀌어서, 영속성 컨텍스트가 끼면 "저장된 버전보다 클 때만 반영" 규칙을 SQL 한 문장으로
 * 쓸 수 없다.
 *
 * @param status 받은 상태 값 원본. 참거짓으로 줄이지 않는다. 줄이면 판매 중이 아닌 상태가 모두 한 값으로 뭉쳐서, 품절과 판매 준비를 구분해야 하는 재입고
 *     판별이 틀린다. 아는 값({@code SOLD_OUT}, {@code ON_SALE})만 판단에 쓰고 나머지는 어느 판단에도 걸리지 않는다
 */
public record ProductSalesStatus(
        long productId, long salesId, long snapshotVersion, String status) {}
