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
     * 주문 생성 시 재고를 배정한다 (주문 2). {@code available -= :qty, reserved += :qty}.
     *
     * <p>{@link #restoreReserved} · {@link #consumeReserved} 의 짝이 되는 유일한 배정 경로다.
     * 재고 총량({@code available + reserved})은 이 쿼리로 변하지 않고 소유만 옮겨간다.
     *
     * <p><b>초과 판매 방지의 핵심:</b> {@code WHERE available >= :qty} 조건으로 compare-and-swap
     * 을 표현한다. Postgres 는 이 UPDATE 에서 행 단위 X-lock 을 자동 획득하므로, 동시에 들어온
     * 요청들은 순차적으로 조건을 재평가한다. 재고가 모자란 순간부터 대상 행이 0 이 되어 서비스
     * 계층이 재고 부족(409)으로 판정한다. P1 통합 완료 기준 "재고 5개에 수량 1개 주문 10건 동시
     * 요청 → 성공 최대 5건" 이 이 조건 하나로 보장된다.
     *
     * <p>Java 계층에서 {@code findById} 후 {@code if (available >= qty)} 로 검사하면 조회와
     * 갱신 사이의 갭에서 초과 판매가 발생하므로 절대 사용하지 않는다.
     *
     * @param salesInfoId 판매정보 식별자
     * @param qty 배정할 수량 (양수)
     * @return 0 이면 배정 실패 (available 부족 또는 row 없음), 1 이면 성공
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value =
            "UPDATE sales_stock "
                + "   SET available = available - :qty, "
                + "       reserved  = reserved  + :qty, "
                + "       updated_at = CURRENT_TIMESTAMP "
                + " WHERE sales_info_id = :salesInfoId "
                + "   AND available >= :qty",
        nativeQuery = true)
    int reserve(@Param("salesInfoId") Long salesInfoId, @Param("qty") int qty);

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
