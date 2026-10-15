package com.shoppinglive.shopping.client.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoppinglive.shopping.sales.repository.ProductSalesRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ReplicaSalesInfoClientTest {

    private final ProductSalesRepository repository = mock(ProductSalesRepository.class);
    private final ReplicaSalesInfoClient client = new ReplicaSalesInfoClient(repository);

    @Test
    @DisplayName("사본에 행이 있는 상품만 결과에 담고, 행이 없는 상품은 결과에서 빠진다")
    void mapsStoredRowsAndLeavesProductsWithoutRowsOut() {
        // given: 1번 상품만 사본에 있다
        SalesInfo stored = new SalesInfo(1L, 10L, 1_000L, SalesStatus.ON_SALE, 3);
        when(repository.findByProductIds(anyCollection())).thenReturn(Map.of(1L, stored));

        Map<Long, SalesInfo> result = client.findByProductIds(List.of(1L, 2L));

        assertThat(result).containsExactly(Map.entry(1L, stored));
    }

    @Test
    @DisplayName("조회할 상품이 없으면 빈 결과를 돌려준다")
    void emptyInputReturnsEmpty() {
        assertThat(client.findByProductIds(List.of())).isEmpty();
    }

    @Test
    @DisplayName("상품 id 목록이 null 이거나 null 원소를 포함하면 사본을 읽기 전에 거부한다")
    void nullInputIsRejectedBeforeReadingReplica() {
        assertThatThrownBy(() -> client.findByProductIds(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.findByProductIds(Arrays.asList(1L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("사본 DB 조회가 실패하면 판매정보 사용 불가 예외로 바꿔 전달한다")
    void replicaReadFailureBecomesSalesInfoUnavailable() {
        when(repository.findByProductIds(anyCollection()))
                .thenThrow(new DataAccessResourceFailureException("replica down"));

        assertThatThrownBy(() -> client.findByProductIds(List.of(1L)))
                .isInstanceOf(SalesInfoUnavailableException.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
