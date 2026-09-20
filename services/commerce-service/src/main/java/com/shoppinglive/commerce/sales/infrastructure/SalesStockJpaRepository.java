package com.shoppinglive.commerce.sales.infrastructure;

import com.shoppinglive.commerce.sales.domain.SalesStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 판매 재고 JPA 리포지토리.
 *
 * <p>조회는 JPA 기본 CRUD 를 사용하되, 재고 조정은 조건부 UPDATE 네이티브 쿼리로만
 * 수행한다. Java 계층에서 available 값을 읽고 조건 검사 후 setter · save() 패턴을 쓰면
 * check-then-act 갭에서 초과 판매가 발생한다.
 */
public interface SalesStockJpaRepository extends JpaRepository<SalesStock, Long> {

    /**
     * {@code available} 을 {@code delta} 만큼 증감한다.
     *
     * <p>안전 조건 {@code available + delta >= 0} 를 SQL WHERE 절 안에서 평가하므로 다음이
     * 자동 보장된다.
     * <ul>
     *   <li>결과 {@code available} 이 음수가 되지 않는다 (초과 판매 방지)</li>
     *   <li>같은 row 를 동시에 여러 트랜잭션이 노려도 DB row-level lock 이 순차 적용되어
     *       조건을 통과한 트랜잭션만 성공한다</li>
     * </ul>
     *
     * <p>{@code reserved} 는 이 메서드로 손대지 않는다 (관리자 조정은 available 만).
     *
     * <p>{@code clearAutomatically = true}: 네이티브 UPDATE 이후 영속성 컨텍스트에 남아 있을
     * 수 있는 stale 엔티티를 제거해 이후 {@code findById} 가 fresh 값을 반환하도록 강제한다.
     *
     * @param salesInfoId 판매정보 식별자
     * @param delta 증감량 (양수: 재고 추가, 음수: 재고 감소)
     * @return 갱신된 row 수. 0 이면 실패 (재고 부족 또는 row 없음), 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE sales_stock "
                + "   SET available = available + :delta, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE sales_info_id = :salesInfoId "
                + "   AND available + :delta >= 0",
        nativeQuery = true)
    int adjustAvailable(@Param("salesInfoId") Long salesInfoId, @Param("delta") int delta);
}
