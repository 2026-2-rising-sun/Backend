package com.shoppinglive.shopping.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ReplicaSalesInfoClientTest {

    private final ProductSalesRepository repository = mock(ProductSalesRepository.class);
    private final ReplicaSalesInfoClient client = new ReplicaSalesInfoClient(repository);

    @Test
    void mapsStoredRowsAndLeavesProductsWithoutRowsOut() {
        // given: 1번 상품만 사본에 있다
        SalesInfo stored = new SalesInfo(1L, 10L, 1_000L, SalesStatus.ON_SALE, 3);
        when(repository.findByProductIds(anyCollection())).thenReturn(Map.of(1L, stored));

        // when
        Map<Long, SalesInfo> result = client.findByProductIds(List.of(1L, 2L));

        // then
        assertThat(result).containsExactly(Map.entry(1L, stored));
    }

    @Test
    void emptyInputReturnsEmptyWithoutReadingReplica() {
        assertThat(client.findByProductIds(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void nullInputIsRejectedBeforeReadingReplica() {
        assertThatThrownBy(() -> client.findByProductIds(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.findByProductIds(Arrays.asList(1L, null)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void replicaReadFailureBecomesSalesInfoUnavailable() {
        when(repository.findByProductIds(anyCollection()))
            .thenThrow(new DataAccessResourceFailureException("replica down"));

        assertThatThrownBy(() -> client.findByProductIds(List.of(1L)))
            .isInstanceOf(SalesInfoUnavailableException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
