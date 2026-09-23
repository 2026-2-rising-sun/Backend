package com.shoppinglive.commerce.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesLookup;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 판매정보 배치 조회의 동작을 검증한다.
 *
 * <p>Mockito 대신 {@link SpringBootTest} 를 쓰는 이유: 이 기능의 핵심은 판단 로직이 아니라
 * {@code sales_info} 와 {@code sales_stock} 을 이어 붙이는 JPQL 이다. 리포지토리를 mock 으로
 * 바꾸면 정작 확인해야 할 쿼리가 실행되지 않는다.
 */
@SpringBootTest
class SalesLookupServiceTest {

    private static final long ON_SALE_PRODUCT = 9_101L;
    private static final long SOLD_OUT_PRODUCT = 9_102L;
    private static final long READY_PRODUCT = 9_103L;
    private static final long UNREGISTERED_PRODUCT = 9_999L;

    @Autowired
    private SalesLookupService salesLookupService;

    @Autowired
    private SalesJpaRepository salesRepository;

    @Autowired
    private SalesStockJpaRepository salesStockRepository;

    @BeforeEach
    void setUp() {
        clean();
        register(ON_SALE_PRODUCT, 15_000L, SalesStatus.ON_SALE, 7, 2);
        register(SOLD_OUT_PRODUCT, 20_000L, SalesStatus.SOLD_OUT, 0, 0);
        register(READY_PRODUCT, 30_000L, SalesStatus.READY, 3, 0);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
    }

    private void register(
        long productId, long price, SalesStatus status, int available, int reserved) {
        Sales sales = salesRepository.save(new Sales(productId, price, status));
        salesStockRepository.save(new SalesStock(sales.getId(), available, reserved));
    }

    @Test
    void 요청한_상품들의_가격_상태_재고를_한_번에_돌려준다() {
        List<SalesLookup> found = salesLookupService.findByProductIds(
            List.of(ON_SALE_PRODUCT, SOLD_OUT_PRODUCT, READY_PRODUCT));

        assertThat(found).hasSize(3);
        assertThat(found).extracting(SalesLookup::productId)
            .containsExactlyInAnyOrder(ON_SALE_PRODUCT, SOLD_OUT_PRODUCT, READY_PRODUCT);
    }

    @Test
    void 각_필드는_판매정보와_재고에서_그대로_온다() {
        SalesLookup lookup = onlyOne(ON_SALE_PRODUCT);

        assertThat(lookup.price()).isEqualTo(15_000L);
        assertThat(lookup.status()).isEqualTo(SalesStatus.ON_SALE);
        assertThat(lookup.salesId()).isNotNull();
        assertThat(salesRepository.findById(lookup.salesId())).isPresent();
    }

    /**
     * {@code available} 은 결제 대기로 잡힌 {@code reserved} 를 뺀 수량이다. 이 상품은 총 9개
     * 중 2개가 결제 대기라 지금 살 수 있는 건 7개다. {@code reserved} 를 더해서 9를 내보내면
     * 부르는 쪽 화면에 살 수 없는 재고까지 있는 것처럼 보인다.
     */
    @Test
    void available_은_결제_대기분을_뺀_수량이다() {
        assertThat(onlyOne(ON_SALE_PRODUCT).available()).isEqualTo(7);
    }

    /**
     * 미등록 상품은 배열에서 빠진다. 404 로 전체를 실패시키면 상품 50개 중 하나가 미등록일 때
     * 목록 화면 전체가 못 뜬다. 빈 원소로 채우면 부르는 쪽이 "조회 실패" 와 구분할 수 없다.
     */
    @Test
    void 판매정보가_없는_상품은_결과에서_빠진다() {
        List<SalesLookup> found = salesLookupService.findByProductIds(
            List.of(ON_SALE_PRODUCT, UNREGISTERED_PRODUCT));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).productId()).isEqualTo(ON_SALE_PRODUCT);
    }

    @Test
    void 전부_미등록이면_빈_배열이다() {
        assertThat(salesLookupService.findByProductIds(List.of(UNREGISTERED_PRODUCT))).isEmpty();
    }

    /**
     * 방송 화면이 같은 상품을 여러 슬롯에 걸 수 있어 중복 요청을 거절하지 않는다. 대신 결과에는
     * 한 번만 담아, 부르는 쪽이 "상품당 판매정보 1개" 계약을 그대로 믿을 수 있게 한다.
     */
    @Test
    void 같은_상품을_여러_번_요청해도_결과는_한_건이다() {
        List<SalesLookup> found = salesLookupService.findByProductIds(
            List.of(ON_SALE_PRODUCT, ON_SALE_PRODUCT, ON_SALE_PRODUCT));

        assertThat(found).hasSize(1);
    }

    @Test
    void 조회는_재고를_건드리지_않는다() {
        Long salesId = onlyOne(ON_SALE_PRODUCT).salesId();

        salesLookupService.findByProductIds(List.of(ON_SALE_PRODUCT));
        salesLookupService.findByProductIds(List.of(ON_SALE_PRODUCT));

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(7);
        assertThat(stock.getReserved()).isEqualTo(2);
    }

    @Test
    void 상한인_100개까지는_받는다() {
        List<Long> productIds = new ArrayList<>();
        productIds.add(ON_SALE_PRODUCT);
        for (long id = 1L; productIds.size() < SalesLookupService.MAX_PRODUCT_IDS; id++) {
            productIds.add(id);
        }

        assertThat(salesLookupService.findByProductIds(productIds)).hasSize(1);
    }

    @Test
    void 상한을_넘으면_400이다() {
        List<Long> tooMany = new ArrayList<>();
        for (long id = 1L; id <= SalesLookupService.MAX_PRODUCT_IDS + 1; id++) {
            tooMany.add(id);
        }

        assertThatInvalidRequest(() -> salesLookupService.findByProductIds(tooMany));
    }

    @Test
    void 비어_있으면_400이다() {
        assertThatInvalidRequest(() -> salesLookupService.findByProductIds(List.of()));
    }

    @Test
    void null_이면_400이다() {
        assertThatInvalidRequest(() -> salesLookupService.findByProductIds(null));
    }

    @Test
    void 원소에_null_이_섞이면_400이다() {
        assertThatInvalidRequest(
            () -> salesLookupService.findByProductIds(Collections.singletonList(null)));
    }

    private SalesLookup onlyOne(long productId) {
        List<SalesLookup> found = salesLookupService.findByProductIds(List.of(productId));
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static void assertThatInvalidRequest(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
