package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.sales.application.SalesNotFoundException;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCheckoutServiceTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long SALES_ID = 10L;

    @Mock
    private SalesJpaRepository salesRepository;

    @Mock
    private SalesStockJpaRepository salesStockRepository;

    @Mock
    private ShoppingClient shoppingClient;

    private OrderCheckoutService orderCheckoutService;

    @BeforeEach
    void setUp() {
        orderCheckoutService = new OrderCheckoutService(
            salesRepository, salesStockRepository, shoppingClient);
    }

    /** 판매정보·재고·상품명이 모두 준비된 기본 상태를 만든다. */
    private void salesExists(long price, int available, SalesStatus status) {
        Sales sales = new Sales(PRODUCT_ID, price, status);
        ReflectionTestUtils.setField(sales, "id", SALES_ID);
        given(salesRepository.findByProductId(PRODUCT_ID)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(SALES_ID))
            .willReturn(Optional.of(new SalesStock(SALES_ID, available, 0)));
    }

    private void productExists() {
        given(shoppingClient.findProduct(PRODUCT_ID))
            .willReturn(Optional.of(new ProductSnapshot(PRODUCT_ID, "테스트 상품", null)));
    }

    // ----- 정상 주문서 -----

    @Test
    void preview_상품명과_단가_총액을_조합해_돌려준다() {
        salesExists(15_000L, 10, SalesStatus.ON_SALE);
        productExists();

        OrderCheckout checkout = orderCheckoutService.preview(PRODUCT_ID, 2);

        assertThat(checkout.productName()).isEqualTo("테스트 상품");
        assertThat(checkout.unitPrice()).isEqualTo(15_000L);
        assertThat(checkout.quantity()).isEqualTo(2);
        assertThat(checkout.totalAmount()).isEqualTo(30_000L);
        assertThat(checkout.salesId()).isEqualTo(SALES_ID);
        assertThat(checkout.orderable()).isTrue();
        assertThat(checkout.reason()).isNull();
    }

    /**
     * 주문 1 완료 기준: "주문서 확인 중에는 재고가 줄지 않는다".
     *
     * <p>{@code only()} 로 재고 리포지토리에 조회 말고는 어떤 호출도 없었음을 못 박는다. 개별
     * 변경 메서드를 하나씩 {@code never()} 로 적으면 나중에 새 변경 쿼리가 생겼을 때 검증이
     * 조용히 비게 된다.
     */
    @Test
    void preview_조회만으로는_재고를_건드리지_않는다() {
        salesExists(15_000L, 10, SalesStatus.ON_SALE);
        productExists();

        orderCheckoutService.preview(PRODUCT_ID, 2);

        verify(salesStockRepository, only()).findById(SALES_ID);
    }

    @Test
    void preview_재고와_정확히_같은_수량도_주문_가능이다() {
        salesExists(15_000L, 3, SalesStatus.ON_SALE);
        productExists();

        assertThat(orderCheckoutService.preview(PRODUCT_ID, 3).orderable()).isTrue();
    }

    // ----- 주문 불가 사유 -----

    @Test
    void preview_재고보다_많은_수량이면_EXCEEDS_STOCK() {
        salesExists(15_000L, 3, SalesStatus.ON_SALE);
        productExists();

        OrderCheckout checkout = orderCheckoutService.preview(PRODUCT_ID, 4);

        assertThat(checkout.orderable()).isFalse();
        assertThat(checkout.reason()).isEqualTo(OrderCheckout.Reason.EXCEEDS_STOCK);
        assertThat(checkout.available()).as("얼마까지 살 수 있는지 알려준다").isEqualTo(3);
    }

    @Test
    void preview_품절이면_SOLD_OUT() {
        salesExists(15_000L, 0, SalesStatus.SOLD_OUT);
        productExists();

        assertThat(orderCheckoutService.preview(PRODUCT_ID, 1).reason())
            .isEqualTo(OrderCheckout.Reason.SOLD_OUT);
    }

    /**
     * 재고가 0 이 됐지만 아직 SOLD_OUT 으로 전이되기 전의 찰나. 사용자 입장에선 품절이므로
     * "재고 부족" 이 아니라 품절로 안내한다.
     */
    @Test
    void preview_판매중인데_재고가_0이면_SOLD_OUT() {
        salesExists(15_000L, 0, SalesStatus.ON_SALE);
        productExists();

        assertThat(orderCheckoutService.preview(PRODUCT_ID, 1).reason())
            .isEqualTo(OrderCheckout.Reason.SOLD_OUT);
    }

    /**
     * 비공개 상품에 "재고 부족" 이라고 안내하면 곧 재입고될 것처럼 읽힌다. 상태를 먼저 본다.
     */
    @Test
    void preview_비공개나_판매준비면_NOT_ON_SALE() {
        salesExists(15_000L, 10, SalesStatus.PRIVATE);
        productExists();

        assertThat(orderCheckoutService.preview(PRODUCT_ID, 1).reason())
            .isEqualTo(OrderCheckout.Reason.NOT_ON_SALE);
    }

    /**
     * 살 수 없는 상태여도 404 가 아니라 200 + 사유다. 구매자는 방금까지 상품 페이지를 보고
     * 있었으므로 "왜 못 사는지" 를 알아야 한다.
     */
    @Test
    void preview_주문_불가여도_예외가_아니라_사유를_담아_돌려준다() {
        salesExists(15_000L, 0, SalesStatus.SOLD_OUT);
        productExists();

        OrderCheckout checkout = orderCheckoutService.preview(PRODUCT_ID, 1);

        assertThat(checkout.productName()).isEqualTo("테스트 상품");
        assertThat(checkout.orderable()).isFalse();
    }

    // ----- 조회 실패 -----

    @Test
    void preview_판매정보가_없으면_404() {
        given(salesRepository.findByProductId(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCheckoutService.preview(PRODUCT_ID, 1))
            .isInstanceOf(SalesNotFoundException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void preview_Shopping에_상품이_없으면_404() {
        salesExists(15_000L, 10, SalesStatus.ON_SALE);
        given(shoppingClient.findProduct(PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCheckoutService.preview(PRODUCT_ID, 1))
            .isInstanceOf(ProductNotFoundException.class);
    }

    /**
     * 상품명을 확인하지 못한 채 주문서를 그리면 무엇을 사는지 모르는 화면이 된다. 장애를
     * "상품 없음" 으로 단정하지도 않는다.
     */
    @Test
    void preview_Shopping_장애는_404로_바꾸지_않고_전파한다() {
        salesExists(15_000L, 10, SalesStatus.ON_SALE);
        given(shoppingClient.findProduct(PRODUCT_ID))
            .willThrow(new ShoppingUnavailableException("shopping timeout"));

        assertThatThrownBy(() -> orderCheckoutService.preview(PRODUCT_ID, 1))
            .isInstanceOf(ShoppingUnavailableException.class)
            .isNotInstanceOf(ProductNotFoundException.class);
    }

    // ----- 입력 검증 -----

    @Test
    void preview_수량이_0이하면_400이고_조회도_하지_않는다() {
        assertThatThrownBy(() -> orderCheckoutService.preview(PRODUCT_ID, 0))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> orderCheckoutService.preview(PRODUCT_ID, -1))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void preview_productId가_null이면_400() {
        assertThatThrownBy(() -> orderCheckoutService.preview(null, 1))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }
}
