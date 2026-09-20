package com.shoppinglive.commerce.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
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

    @InjectMocks
    private SalesService salesService;

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
}
