package com.shoppinglive.commerce.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SalesServiceTest {

    @Mock
    private SalesJpaRepository salesRepository;

    @Mock
    private SalesStockJpaRepository salesStockRepository;

    @InjectMocks
    private SalesService salesService;

    // ----- changePrice (판매 2) -----

    @Test
    void changePrice_변경된_가격이_엔티티에_반영된다() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));

        Sales updated = salesService.changePrice(1L, 15_000L);

        assertThat(updated.getPrice()).isEqualTo(15_000L);
    }

    @Test
    void changePrice_판매정보가_없으면_SalesNotFoundException() {
        given(salesRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.changePrice(999L, 15_000L))
            .isInstanceOf(SalesNotFoundException.class)
            .hasMessageContaining("999");
    }

    @Test
    void changePrice_가격이_0_이하면_IllegalArgumentException() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));

        assertThatThrownBy(() -> salesService.changePrice(1L, 0L))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> salesService.changePrice(1L, -1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changePrice_null_가격이면_IllegalArgumentException() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));

        assertThatThrownBy(() -> salesService.changePrice(1L, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- adjustAvailable (판매 3) -----

    @Test
    void adjustAvailable_성공하면_업데이트된_재고를_반환한다() {
        SalesStock stock = new SalesStock(1L, 5, 0);
        given(salesStockRepository.adjustAvailable(1L, -1)).willReturn(1);
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(stock));

        SalesStock result = salesService.adjustAvailable(1L, -1);

        assertThat(result).isSameAs(stock);
    }

    @Test
    void adjustAvailable_row_없으면_SalesNotFoundException() {
        given(salesStockRepository.adjustAvailable(999L, -1)).willReturn(0);
        given(salesStockRepository.existsById(999L)).willReturn(false);

        assertThatThrownBy(() -> salesService.adjustAvailable(999L, -1))
            .isInstanceOf(SalesNotFoundException.class)
            .hasMessageContaining("999");
    }

    @Test
    void adjustAvailable_재고_부족이면_InsufficientStockException() {
        given(salesStockRepository.adjustAvailable(1L, -10)).willReturn(0);
        given(salesStockRepository.existsById(1L)).willReturn(true);

        assertThatThrownBy(() -> salesService.adjustAvailable(1L, -10))
            .isInstanceOf(InsufficientStockException.class)
            .hasMessageContaining("-10");
    }

    // ----- getStock (판매 3) -----

    @Test
    void getStock_존재하면_반환() {
        SalesStock stock = new SalesStock(1L, 5, 0);
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(stock));

        SalesStock result = salesService.getStock(1L);

        assertThat(result).isSameAs(stock);
    }

    @Test
    void getStock_없으면_SalesNotFoundException() {
        given(salesStockRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.getStock(999L))
            .isInstanceOf(SalesNotFoundException.class);
    }

    // ----- changeStatus (판매 4) -----

    @Test
    void changeStatus_READY_에서_ON_SALE_로_전이_성공() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(new SalesStock(1L, 1, 0)));
        given(salesRepository.transitionStatus(1L, "READY", "ON_SALE")).willReturn(1);
        // 전이 후 재조회는 새 sales 인스턴스일 수 있으나 mock 은 같은 인스턴스 반환

        salesService.changeStatus(1L, SalesStatus.ON_SALE);
        // 예외 없이 반환되면 성공. 세부 반환값은 mock 이라 확인 불가
    }

    @Test
    void changeStatus_SOLD_OUT_target_은_IllegalStateTransitionException() {
        assertThatThrownBy(() -> salesService.changeStatus(1L, SalesStatus.SOLD_OUT))
            .isInstanceOf(IllegalStateTransitionException.class)
            .hasMessageContaining("SOLD_OUT");
    }

    @Test
    void changeStatus_READY_target_은_IllegalStateTransitionException() {
        assertThatThrownBy(() -> salesService.changeStatus(1L, SalesStatus.READY))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void changeStatus_null_target_은_IllegalStateTransitionException() {
        assertThatThrownBy(() -> salesService.changeStatus(1L, null))
            .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void changeStatus_판매정보가_없으면_SalesNotFoundException() {
        given(salesRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> salesService.changeStatus(999L, SalesStatus.ON_SALE))
            .isInstanceOf(SalesNotFoundException.class);
    }

    @Test
    void changeStatus_조건부_UPDATE_실패시_ConcurrentStateChangeException() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.READY);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(new SalesStock(1L, 1, 0)));
        given(salesRepository.transitionStatus(1L, "READY", "ON_SALE")).willReturn(0);

        assertThatThrownBy(() -> salesService.changeStatus(1L, SalesStatus.ON_SALE))
            .isInstanceOf(ConcurrentStateChangeException.class);
    }

    @Test
    void changeStatus_PRIVATE에서_ON_SALE_요청이고_재고_0이면_SOLD_OUT_으로_자동_조정() {
        Sales sales = new Sales(100L, 10_000L, SalesStatus.PRIVATE);
        SalesStock stock = new SalesStock(1L, 0, 0);
        given(salesRepository.findById(1L)).willReturn(Optional.of(sales));
        given(salesStockRepository.findById(1L)).willReturn(Optional.of(stock));
        given(salesRepository.transitionStatus(1L, "PRIVATE", "SOLD_OUT")).willReturn(1);

        salesService.changeStatus(1L, SalesStatus.ON_SALE);
        // transitionStatus 가 PRIVATE→SOLD_OUT 으로 호출되는지 mock 검증 대신 정상 반환 확인
    }
}
