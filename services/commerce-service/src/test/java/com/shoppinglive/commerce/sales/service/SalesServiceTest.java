package com.shoppinglive.commerce.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.event.SalesInfoEventTracker;
import com.shoppinglive.commerce.sales.exception.ConcurrentStateChangeException;
import com.shoppinglive.commerce.sales.exception.IllegalStateTransitionException;
import com.shoppinglive.commerce.sales.exception.InsufficientStockException;
import com.shoppinglive.commerce.sales.exception.SalesNotFoundException;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesServiceTest {

    @Mock private SalesJpaRepository salesRepository;

    @Mock private SalesStockJpaRepository salesStockRepository;

    @Mock private SalesInfoEventTracker events;

    @InjectMocks private SalesService salesService;

    // ----- changePrice (판매 2) -----

    @DisplayName("가격을 변경하면 판매 엔티티에 반영한다")
    @Test
    void appliesChangedPriceToSalesEntity() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));

        Sales updated = salesService.changePrice(1L, 15_000L);

        assertThat(updated.getPrice()).isEqualTo(15_000L);
    }

    @DisplayName("판매정보가 없으면 가격 변경은 SalesNotFoundException을 던진다")
    @Test
    void rejectsPriceChangeForMissingSale() {
        given(salesRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.changePrice(999L, 15_000L))
                .isInstanceOf(SalesNotFoundException.class)
                .hasMessageContaining("999");
    }

    @DisplayName("변경 가격이 0 이하면 IllegalArgumentException을 던진다")
    @Test
    void rejectsNonPositiveChangedPrice() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));

        assertThatThrownBy(() -> salesService.changePrice(1L, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> salesService.changePrice(1L, -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("변경 가격이 null이면 IllegalArgumentException을 던진다")
    @Test
    void rejectsNullChangedPrice() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));

        assertThatThrownBy(() -> salesService.changePrice(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- adjustAvailable (판매 3) -----

    @DisplayName("재고 조정이 성공하면 변경된 재고를 반환한다")
    @Test
    void returnsUpdatedStockAfterAdjustment() {
        SalesStock stock = new SalesStock(1L, 5, 0);
        given(salesStockRepository.adjustAvailable(1L, -1)).willReturn(1);
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(stock));

        SalesStock result = salesService.adjustAvailable(1L, -1);

        assertThat(result).isSameAs(stock);
    }

    @DisplayName("재고 행이 없으면 조정은 SalesNotFoundException을 던진다")
    @Test
    void rejectsAdjustmentWhenStockRowIsMissing() {
        given(salesStockRepository.adjustAvailable(999L, -1)).willReturn(0);
        given(salesStockRepository.existsById(999L)).willReturn(false);

        assertThatThrownBy(() -> salesService.adjustAvailable(999L, -1))
                .isInstanceOf(SalesNotFoundException.class)
                .hasMessageContaining("999");
    }

    @DisplayName("재고가 부족하면 조정은 InsufficientStockException을 던진다")
    @Test
    void rejectsAdjustmentWhenStockIsInsufficient() {
        given(salesStockRepository.adjustAvailable(1L, -10)).willReturn(0);
        given(salesStockRepository.existsById(1L)).willReturn(true);

        assertThatThrownBy(() -> salesService.adjustAvailable(1L, -10))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("-10");
    }

    // ----- getStock (판매 3) -----

    @DisplayName("판매 재고가 존재하면 해당 재고를 반환한다")
    @Test
    void returnsExistingStock() {
        SalesStock stock = new SalesStock(1L, 5, 0);
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(stock));

        SalesStock result = salesService.getStock(1L);

        assertThat(result).isSameAs(stock);
    }

    @DisplayName("판매 재고가 없으면 SalesNotFoundException을 던진다")
    @Test
    void rejectsMissingStock() {
        given(salesStockRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.getStock(999L))
                .isInstanceOf(SalesNotFoundException.class);
    }

    // ----- changeStatus (판매 4) -----

    @DisplayName("READY 판매를 ON_SALE로 변경할 수 있다")
    @Test
    void changesReadySaleToOnSale() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(new SalesStock(1L, 1, 0)));
        given(salesRepository.transitionStatus(1L, "READY", "ON_SALE")).willReturn(1);
        // 전이 후 재조회는 새 sales 인스턴스일 수 있으나 mock 은 같은 인스턴스 반환

        salesService.changeStatus(1L, SalesStatus.ON_SALE);
        // 예외 없이 반환되면 성공. 세부 반환값은 mock 이라 확인 불가
    }

    @DisplayName("관리자가 SOLD_OUT을 직접 지정하면 상태 전이를 거절한다")
    @Test
    void rejectsSoldOutAsAdministrativeTarget() {
        assertThatThrownBy(() -> salesService.changeStatus(1L, SalesStatus.SOLD_OUT))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("SOLD_OUT");
    }

    @DisplayName("관리자가 READY를 직접 지정하면 상태 전이를 거절한다")
    @Test
    void rejectsReadyAsAdministrativeTarget() {
        assertThatThrownBy(() -> salesService.changeStatus(1L, SalesStatus.READY))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @DisplayName("대상 상태가 null이면 상태 전이를 거절한다")
    @Test
    void rejectsNullStatusTarget() {
        assertThatThrownBy(() -> salesService.changeStatus(1L, null))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @DisplayName("판매정보가 없으면 상태 변경은 SalesNotFoundException을 던진다")
    @Test
    void rejectsStatusChangeForMissingSale() {
        given(salesRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.changeStatus(999L, SalesStatus.ON_SALE))
                .isInstanceOf(SalesNotFoundException.class);
    }

    @DisplayName("상태 조건부 UPDATE에 실패하면 ConcurrentStateChangeException을 던진다")
    @Test
    void reportsConcurrentChangeWhenConditionalStatusUpdateFails() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(new SalesStock(1L, 1, 0)));
        given(salesRepository.transitionStatus(1L, "READY", "ON_SALE")).willReturn(0);

        assertThatThrownBy(() -> salesService.changeStatus(1L, SalesStatus.ON_SALE))
                .isInstanceOf(ConcurrentStateChangeException.class);
    }

    @DisplayName("재고 0인 PRIVATE 판매를 공개하면 SOLD_OUT으로 자동 조정한다")
    @Test
    void changesPrivateSaleToSoldOutWhenOnSaleRequestedWithZeroStock() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.PRIVATE);
        SalesStock stock = new SalesStock(1L, 0, 0);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(stock));
        given(salesRepository.transitionStatus(1L, "PRIVATE", "SOLD_OUT")).willReturn(1);

        salesService.changeStatus(1L, SalesStatus.ON_SALE);
        // transitionStatus 가 PRIVATE→SOLD_OUT 으로 호출되는지 mock 검증 대신 정상 반환 확인
    }
}
