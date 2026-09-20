package com.shoppinglive.commerce.sales.infrastructure;

import com.shoppinglive.commerce.sales.domain.Sales;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 판매정보 JPA 리포지토리.
 *
 * <p>가격 변경(판매 2)은 낙관적 락(`@Version`)과 dirty checking, 상태 전이(판매 4)는 조건부
 * UPDATE 네이티브 쿼리로 처리한다. 재고 변경은 별도 {@link SalesStockJpaRepository} 담당.
 */
public interface SalesJpaRepository extends JpaRepository<Sales, Long> {

    /**
     * 판매 상태를 {@code expectedStatus} 에서 {@code nextStatus} 로 전이한다.
     *
     * <p>{@code WHERE status = :expectedStatus} 조건으로 compare-and-swap 을 표현한다.
     * 다른 트랜잭션이 먼저 전이했으면 이 UPDATE 는 대상 0 행을 반환한다. {@code version} 은
     * 함께 증가시켜 JPA 낙관적 락 관용구와 호환.
     *
     * <p>{@code clearAutomatically = true}: 네이티브 UPDATE 이후 영속성 컨텍스트에 남아 있을
     * 수 있는 stale {@link Sales} 를 evict.
     *
     * @param salesId 판매정보 식별자
     * @param expectedStatus 현재 상태 (문자열: SalesStatus.name())
     * @param nextStatus 다음 상태 (문자열: SalesStatus.name())
     * @return 갱신된 row 수. 0 이면 전이 실패 (다른 트랜잭션이 먼저 바꿈 또는 row 없음), 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE sales_info "
                + "   SET status = :nextStatus, "
                + "       version = version + 1, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE id = :salesId "
                + "   AND status = :expectedStatus",
        nativeQuery = true)
    int transitionStatus(
        @Param("salesId") Long salesId,
        @Param("expectedStatus") String expectedStatus,
        @Param("nextStatus") String nextStatus);
}
