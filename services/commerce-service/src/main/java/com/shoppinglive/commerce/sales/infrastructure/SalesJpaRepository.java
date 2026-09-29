package com.shoppinglive.commerce.sales.infrastructure;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesLookup;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     * 상품에 이미 판매정보가 등록되어 있는지 확인한다 (판매 1).
     *
     * <p>"한 상품의 판매정보는 하나만 관리한다" 는 완료 기준의 선제 검사다. 최종 방어선은
     * {@code uk_sales_info_product_id} UNIQUE 제약이므로, 이 조회를 통과했더라도 동시 요청이
     * 겹치면 INSERT 단계에서 제약 위반이 날 수 있다. 서비스 계층은 두 경우를 같은 409 로
     * 매핑한다.
     */
    boolean existsByProductId(Long productId);

    /**
     * 상품 식별자로 판매정보를 조회한다.
     *
     * <p>구매자는 상품 페이지·방송 화면에서 주문 흐름으로 들어오므로 {@code productId} 만 알고
     * {@code salesId} 는 모른다. 주문서 조회(주문 1)·주문 생성(주문 2)이 이 메서드로 판매정보를
     * 찾는다.
     */
    Optional<Sales> findByProductId(Long productId);

    /**
     * 여러 상품의 판매정보와 재고를 한 번에 조회한다 (서비스 간 배치 조회).
     *
     * <p>Shopping 상품 목록과 Live 방송 상품 카드는 한 화면에 상품 수십 개를 띄우고 각각 가격·
     * 판매상태·재고를 표시한다. 상품마다 단건 조회를 부르면 N+1 호출이 되므로 한 번에 묻는다.
     *
     * <p>{@code sales_info} 와 {@code sales_stock} 은 shared PK 로 1:1 이지만 JPA 연관관계를
     * 두지 않았으므로 {@code st.salesInfoId = s.id} 로 직접 이어 붙인다. 판매정보만 있고 재고
     * 행이 없는 상품은 결과에서 빠지는데, 판매 1 이 둘을 같은 트랜잭션에서 만들기 때문에 정상
     * 데이터에서는 생기지 않는 경우다.
     *
     * <p>판매정보가 등록되지 않은 상품은 결과에 담기지 않는다. 호출한 쪽은 "응답에 없음" 을
     * 미등록으로 해석한다 (계약: 판매정보 배치 조회).
     *
     * @param productIds 조회할 상품 식별자. 중복은 호출 전에 제거되어 있어야 한다
     * @return 판매정보가 있는 상품만 담긴 목록. 순서는 보장하지 않는다
     */
    @Query("""
        SELECT new com.shoppinglive.commerce.sales.domain.SalesLookup(
                   s.productId, s.id, s.price, s.status, st.available)
          FROM Sales s, SalesStock st
         WHERE st.salesInfoId = s.id
           AND s.productId IN :productIds
        """)
    List<SalesLookup> findLookupsByProductIds(@Param("productIds") Collection<Long> productIds);

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
    /** 복원/보충된 재고가 있는 품절 판매만 재개한다. 관리자 비공개 상태는 보존한다. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE sales_info SET status = 'ON_SALE', version = version + 1, "
        + "updated_at = CURRENT_TIMESTAMP WHERE id = :salesId AND status = 'SOLD_OUT' "
        + "AND EXISTS (SELECT 1 FROM sales_stock WHERE sales_info_id = :salesId AND available > 0)",
        nativeQuery = true)
    int reopenIfStockAvailable(@Param("salesId") Long salesId);

}
