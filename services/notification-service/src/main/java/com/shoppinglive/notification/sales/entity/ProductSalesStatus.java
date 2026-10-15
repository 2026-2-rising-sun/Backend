package com.shoppinglive.notification.sales.entity;

/**
 * 판매 상태 사본 한 행.
 *
 * <p>JPA 엔티티가 아니다. 이 표는 조건부 INSERT 와 UPDATE 로만 바뀌어서, 영속성 컨텍스트가 끼면 "저장된 버전보다 클 때만 반영" 규칙을 SQL 한 문장으로
 * 쓸 수 없다.
 *
 * @param inStock 판매 중인지. <b>판단에 쓰는 값은 이것뿐이다.</b> 받을 때 한 번 참거짓으로 바꿔 두면, Commerce 가 상태 값을 추가해도 이쪽 판단이
 *     흔들리지 않는다
 * @param rawStatus 받은 상태 값 원본. 진단용으로만 둔다. 이 값으로 분기하지 않는다
 */
public record ProductSalesStatus(
        long productId, long salesId, long snapshotVersion, boolean inStock, String rawStatus) {}
