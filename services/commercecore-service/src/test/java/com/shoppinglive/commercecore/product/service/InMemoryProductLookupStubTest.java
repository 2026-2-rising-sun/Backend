package com.shoppinglive.commercecore.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link InMemoryProductLookupStub} 단위 테스트. */
class InMemoryProductLookupStubTest {

    private InMemoryProductLookupStub stub;

    @BeforeEach
    void setUp() {
        stub = new InMemoryProductLookupStub();
    }

    @DisplayName("등록한 상품을 조회하면 같은 스냅샷을 반환한다")
    @Test
    void returnsRegisteredProductSnapshot() {
        ProductSnapshot p = new ProductSnapshot(1L, "테스트 상품", "https://img/1.png");
        stub.register(p);

        assertThat(stub.findProduct(1L)).contains(p);
    }

    @DisplayName("미등록 상품을 조회하면 빈 Optional을 반환한다")
    @Test
    void returnsEmptyOptionalForMissingProduct() {
        assertThat(stub.findProduct(999L)).isEmpty();
    }

    @DisplayName("상품 존재 확인은 findProduct에 위임한다")
    @Test
    void delegatesExistenceCheckToProductLookup() {
        stub.register(new ProductSnapshot(1L, "상품", null));

        assertThat(stub.exists(1L)).isTrue();
        assertThat(stub.exists(999L)).isFalse();
    }

    @DisplayName("clear를 호출하면 모든 상품 스냅샷을 제거한다")
    @Test
    void removesEverySnapshotOnClear() {
        stub.register(new ProductSnapshot(1L, "a", null));
        stub.register(new ProductSnapshot(2L, "b", null));
        assertThat(stub.size()).isEqualTo(2);

        stub.clear();
        assertThat(stub.size()).isZero();
    }

    @DisplayName("null 상품을 등록하면 IllegalArgumentException을 던진다")
    @Test
    void rejectsNullProductRegistration() {
        assertThatThrownBy(() -> stub.register(null)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> stub.register(new ProductSnapshot(null, "x", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("ProductLookup 인터페이스로 참조해도 상품을 등록·조회할 수 있다")
    @Test
    void supportsProductLookupInterface() {
        ProductLookup client = stub;
        stub.register(new ProductSnapshot(1L, "a", null));

        assertThat(client.findProduct(1L)).isPresent();
        assertThat(client.exists(1L)).isTrue();
    }
}
