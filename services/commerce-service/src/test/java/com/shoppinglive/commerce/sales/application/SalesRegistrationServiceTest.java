package com.shoppinglive.commerce.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매 1 · 판매정보 최초 설정 유스케이스 검증.
 *
 * <p>{@link TransactionTemplate} 은 mock 으로 두되 콜백을 그대로 실행하게 해서, 트랜잭션 경계
 * 자체가 아니라 그 안에서 무엇을 저장하는지를 본다. 실제 커밋·롤백 동작은 JPA 가 보장한다.
 */
@ExtendWith(MockitoExtension.class)
class SalesRegistrationServiceTest {

    private static final Long PRODUCT_ID = 100L;

    @Mock
    private SalesJpaRepository salesRepository;

    @Mock
    private SalesStockJpaRepository salesStockRepository;

    @Mock
    private ShoppingClient shoppingClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SalesRegistrationService salesRegistrationService;

    @BeforeEach
    void setUp() {
        salesRegistrationService = new SalesRegistrationService(
            salesRepository, salesStockRepository, shoppingClient, transactionTemplate);
    }

    /**
     * mock TransactionTemplate 이 콜백을 실제로 실행하도록 스텁한다. 이 스텁이 필요한 테스트
     * (저장까지 도달하는 경우) 에서만 호출해 불필요한 스텁 경고를 피한다.
     */
    @SuppressWarnings("unchecked")
    private void executeTransactionInline() {
        given(transactionTemplate.execute(any()))
            .willAnswer(invocation -> ((TransactionCallback<Object>) invocation.getArgument(0))
                .doInTransaction(new SimpleTransactionStatus()));
    }

    /**
     * 서비스는 {@link ShoppingClient#exists(Long)} 로 존재만 묻는다. {@code exists} 는 default
     * 메서드지만 mock 은 default 구현을 타지 않으므로 {@code findProduct} 가 아니라 이 쪽을
     * 스텁해야 한다.
     */
    private void productExists() {
        given(shoppingClient.exists(PRODUCT_ID)).willReturn(true);
    }

    /**
     * {@code saveAndFlush} 가 식별자를 채워 돌려주도록 스텁한다. 실제로는 JPA 가 flush 시점에
     * IDENTITY 값을 주입하는데, mock 은 인자를 그대로 돌려주므로 id 가 {@code null} 로 남는다.
     * 재고({@code sales_stock})는 판매정보의 PK 를 그대로 쓰는 구조라 id 없이는 만들 수 없다.
     */
    private void saveAssignsId(long assignedId) {
        given(salesRepository.saveAndFlush(any(Sales.class))).willAnswer(invocation -> {
            Sales sales = invocation.getArgument(0);
            ReflectionTestUtils.setField(sales, "id", assignedId);
            return sales;
        });
    }

    // ----- 정상 등록 -----

    @Test
    void register_판매정보와_재고를_함께_저장한다() {
        productExists();
        executeTransactionInline();
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(false);
        saveAssignsId(1L);

        Sales registered = salesRegistrationService.register(PRODUCT_ID, 15_000L, 10);

        assertThat(registered.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(registered.getPrice()).isEqualTo(15_000L);

        ArgumentCaptor<SalesStock> stockCaptor = ArgumentCaptor.forClass(SalesStock.class);
        verify(salesStockRepository).save(stockCaptor.capture());
        assertThat(stockCaptor.getValue().getAvailable()).isEqualTo(10);
        assertThat(stockCaptor.getValue().getReserved())
            .as("최초 등록 시 배정된 수량은 없다")
            .isZero();
    }

    /**
     * 등록만으로 판매가 시작되면 안 된다. 관리자가 가격·재고를 준비하는 동안 상품이 공개 목록에
     * 뜨거나 주문을 받아버린다.
     */
    @Test
    void register_등록_직후_상태는_판매준비다() {
        productExists();
        executeTransactionInline();
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(false);
        saveAssignsId(1L);

        Sales registered = salesRegistrationService.register(PRODUCT_ID, 15_000L, 10);

        assertThat(registered.getStatus()).isEqualTo(SalesStatus.READY);
    }

    /**
     * 재고 0 으로 먼저 등록해 두고 판매 3 으로 채우는 운영 흐름을 허용한다.
     */
    @Test
    void register_초기_재고가_0이어도_등록된다() {
        productExists();
        executeTransactionInline();
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(false);
        saveAssignsId(1L);

        salesRegistrationService.register(PRODUCT_ID, 15_000L, 0);

        ArgumentCaptor<SalesStock> stockCaptor = ArgumentCaptor.forClass(SalesStock.class);
        verify(salesStockRepository).save(stockCaptor.capture());
        assertThat(stockCaptor.getValue().getAvailable()).isZero();
    }

    // ----- 상품 확인 -----

    @Test
    void register_상품이_없으면_ProductNotFoundException이고_저장하지_않는다() {
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(false);
        given(shoppingClient.exists(PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 15_000L, 10))
            .isInstanceOf(ProductNotFoundException.class);

        verify(salesRepository, never()).saveAndFlush(any());
        verify(salesStockRepository, never()).save(any());
    }

    /**
     * 판매 1 완료 기준: "상품이 없거나 <b>존재 여부를 확인하지 못하면</b> 설정을 완료하지 않는다".
     * Shopping 장애를 "상품 없음" 으로 단정하지 않고 그대로 전파해, 호출자가 재시도를 안내할 수
     * 있게 한다.
     */
    @Test
    void register_Shopping_장애는_상품없음으로_단정하지_않고_전파한다() {
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(false);
        given(shoppingClient.exists(PRODUCT_ID))
            .willThrow(new ShoppingUnavailableException("shopping timeout"));

        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 15_000L, 10))
            .isInstanceOf(ShoppingUnavailableException.class)
            .isNotInstanceOf(ProductNotFoundException.class);

        verify(salesRepository, never()).saveAndFlush(any());
    }

    // ----- 중복 등록 -----

    @Test
    void register_이미_판매정보가_있으면_409이고_Shopping을_부르지_않는다() {
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(true);

        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 15_000L, 10))
            .isInstanceOf(SalesAlreadyRegisteredException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.CONFLICT);

        verify(shoppingClient, never()).exists(any());
        verify(salesRepository, never()).saveAndFlush(any());
    }

    /**
     * 중복 검사를 동시에 통과한 두 요청 중 한쪽은 UNIQUE 제약에 걸린다. 그 DB 예외를 그대로
     * 500 으로 흘리지 않고 중복 등록과 같은 409 로 되돌린다.
     */
    @Test
    void register_UNIQUE_제약_위반도_중복등록과_같은_409로_바꾼다() {
        productExists();
        given(salesRepository.existsByProductId(PRODUCT_ID)).willReturn(false);
        given(transactionTemplate.execute(any()))
            .willThrow(new DataIntegrityViolationException("uk_sales_info_product_id"));

        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 15_000L, 10))
            .isInstanceOf(SalesAlreadyRegisteredException.class)
            .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    // ----- 입력 검증 -----

    @Test
    void register_가격이_0이하면_400() {
        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 0L, 10))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, -1L, 10))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void register_재고가_음수면_400() {
        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 15_000L, -1))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).errorCode())
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void register_필수값이_null이면_400() {
        assertThatThrownBy(() -> salesRegistrationService.register(null, 15_000L, 10))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, null, 10))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, 15_000L, null))
            .isInstanceOf(BusinessException.class);
    }

    /**
     * 검증 실패는 DB·Shopping 어디에도 닿기 전에 끝나야 한다.
     */
    @Test
    void register_검증_실패시_DB와_Shopping에_접근하지_않는다() {
        assertThatThrownBy(() -> salesRegistrationService.register(PRODUCT_ID, -1L, 10))
            .isInstanceOf(BusinessException.class);

        verify(salesRepository, never()).existsByProductId(any());
        verify(shoppingClient, never()).exists(any());
    }
}
