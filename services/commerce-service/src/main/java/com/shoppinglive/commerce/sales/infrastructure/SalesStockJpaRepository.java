package com.shoppinglive.commerce.sales.infrastructure;

import com.shoppinglive.commerce.sales.domain.SalesStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 판매 재고 JPA 리포지토리.
 *
 * <p>재고 조정은 조건부 UPDATE 네이티브 쿼리로만 수행한다. Java 계층에서 available/reserved
 * 값을 읽고 조건 검사 후 setter · save() 패턴을 쓰면 check-then-act 갭에서 초과 판매가
 * 발생한다.
 */
public interface SalesStockJpaRepository extends JpaRepository<SalesStock, Long> {

    /**
     * {@code available} 을 {@code delta} 만큼 증감한다 (판매 3, 관리자 조정).
     *
     * <p>{@code reserved} 는 손대지 않는다.
     *
     * @return 0 이면 실패 (재고 부족 또는 row 없음), 1 이면 성공
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

    /**
     * 주문에 배정된 재고를 취소·실패·만료 시 복구한다 (주문 4 · 주문 5 · 결제 실패).
     *
     * <p>{@code reserved -= :qty, available += :qty}. 안전 조건 {@code reserved >= :qty} 로
     * 중복 복구를 방지한다. 예: cancelOrder 조건부 UPDATE 가 이미 CANCELLED 로 전이 성공한
     * 트랜잭션만 restoreReserved 를 호출하지만, 만약 다른 경로에서 이미 복구했다면 reserved
     * 값이 낮아져 있어 이 조건부 UPDATE 도 안전하게 실패한다.
     *
     * @return 0 이면 복구 실패 (reserved 부족 · 재고 정합성 문제 또는 이미 복구됨), 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE sales_stock "
                + "   SET reserved = reserved - :qty, "
                + "       available = available + :qty, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE sales_info_id = :salesInfoId "
                + "   AND reserved >= :qty",
        nativeQuery = true)
    int restoreReserved(@Param("salesInfoId") Long salesInfoId, @Param("qty") int qty);

    /**
     * 결제 성공 시 배정된 재고를 소진 (`reserved -= :qty`) 처리한다. 결제 2 이슈에서 사용.
     *
     * <p>재고 총량이 실제로 줄어드는 것 (판매 확정). available 은 이미 주문 생성 시점에
     * 감소했으므로 여기선 손대지 않는다.
     *
     * @return 0 이면 복구 실패, 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE sales_stock "
                + "   SET reserved = reserved - :qty, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE sales_info_id = :salesInfoId "
                + "   AND reserved >= :qty",
        nativeQuery = true)
    int consumeReserved(@Param("salesInfoId") Long salesInfoId, @Param("qty") int qty);
}
