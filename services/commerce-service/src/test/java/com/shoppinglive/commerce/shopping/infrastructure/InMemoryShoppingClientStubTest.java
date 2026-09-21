package com.shoppinglive.commerce.shopping.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryShoppingClientStub} 단위 테스트.
 */
class InMemoryShoppingClientStubTest {

    private InMemoryShoppingClientStub stub;

    @BeforeEach
    void setUp() {
        stub = new InMemoryShoppingClientStub();
    }

    @Test
    void register_후_findProduct_로_같은_스냅샷_반환() {
        ProductSnapshot p = new ProductSnapshot(1L, "테스트 상품", "https://img/1.png");
        stub.register(p);

        assertThat(stub.findProduct(1L)).contains(p);
    }

    @Test
    void 미등록_상품은_Optional_empty() {
        assertThat(stub.findProduct(999L)).isEmpty();
    }

    @Test
    void exists_는_findProduct_위임() {
        stub.register(new ProductSnapshot(1L, "상품", null));

        assertThat(stub.exists(1L)).isTrue();
        assertThat(stub.exists(999L)).isFalse();
    }

    @Test
    void clear_는_모든_스냅샷_제거() {
        stub.register(new ProductSnapshot(1L, "a", null));
        stub.register(new ProductSnapshot(2L, "b", null));
        assertThat(stub.size()).isEqualTo(2);

        stub.clear();
        assertThat(stub.size()).isZero();
    }

    @Test
    void register_null_은_IllegalArgumentException() {
        assertThatThrownBy(() -> stub.register(null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> stub.register(new ProductSnapshot(null, "x", null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interface_로_참조해도_동작() {
        ShoppingClient client = stub;
        stub.register(new ProductSnapshot(1L, "a", null));

        assertThat(client.findProduct(1L)).isPresent();
        assertThat(client.exists(1L)).isTrue();
    }
}
