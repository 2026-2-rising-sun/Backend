package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.product.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"commercecore.product.lookup.mode=local"})
@AutoConfigureMockMvc
class LegacyOrderSellerSnapshotPostgresTest extends OrderSheetPostgresSupport {
    @Autowired OrderCreationService creation;
    @Autowired ProductLifecycleService products;
    @Autowired ProductLookup lookup;
    @Autowired ProductQueryService productQuery;

    @BeforeEach
    void singleSku() {
        jdbc.update("DELETE FROM sku WHERE id=503");
        assertThat(lookup).isSameAs(productQuery);
    }

    @ParameterizedTest
    @ValueSource(strings = {"single", "cart-single", "cart-group", "historical-null", "normal"})
    @DisplayName("구 API 단건·Cart 주문은 판매자 스냅샷을 저장하고 실제 상품 삭제 후에도 주문 행을 바꾸지 않는다")
    void preservesSellerSnapshotAfterDeletion(String source) {
        if (source.equals("cart-group")) {
            long cart = cartItem(101, 501);
            legacyGroups.create(
                    MEMBER_A,
                    List.of(new PaymentGroupService.Selection(cart, 0L)),
                    "구매자",
                    "01012345678",
                    4000,
                    "group");
        } else if (source.equals("cart-single")) {
            creation.createFromCart(
                    MEMBER_A,
                    cartItem(101, 501),
                    new CartOrderCommand("구매자", "01012345678", 4000L),
                    "cart");
        } else {
            creation.create(
                    new CreateOrderCommand(101L, 1, "구매자", "01012345678", MEMBER_A, 4000L),
                    "single");
        }
        var before =
                jdbc.queryForMap(
                        "SELECT order_number,product_name_snapshot,total_amount,expires_at,source_cart_item_id,seller_id_snapshot FROM orders");
        assertThat(before.get("seller_id_snapshot")).isEqualTo(SELLER);
        boolean historical = source.equals("historical-null");
        if (historical) jdbc.update("UPDATE orders SET seller_id_snapshot=NULL");
        if (!source.equals("normal")) {
            products.delete(101L, version(), SELLER);
            assertThat(lookup.findProducts(List.of(101L))).isEmpty();
        }
        var after =
                jdbc.queryForMap(
                        "SELECT order_number,product_name_snapshot,total_amount,expires_at,source_cart_item_id,seller_id_snapshot FROM orders");
        assertThat(after.get("seller_id_snapshot")).isEqualTo(historical ? null : SELLER);
        before.remove("seller_id_snapshot");
        after.remove("seller_id_snapshot");
        assertThat(after).isEqualTo(before);
    }

    private long cartItem(long product, long sku) {
        return jdbc.queryForObject(
                "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES(?,?,?,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                Long.class,
                MEMBER_A,
                product,
                sku);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("상품 중지·삭제는 신규 주문만 차단하고 기존 선점 주문의 기한과 선점을 보존한다")
    void reservedOrdersKeepDeadlineAndReservationAfterProductClosure(boolean deleted) {
        var original =
                creation.create(
                                new CreateOrderCommand(
                                        101L, 1, "구매자", "01012345678", MEMBER_A, 4000L),
                                "reserved")
                        .order();
        var expires = original.getExpiresAt();
        if (deleted) products.delete(101L, version(), SELLER);
        else products.changeSaleState(101L, false, version(), SELLER);
        assertThatThrownBy(
                        () ->
                                creation.create(
                                        new CreateOrderCommand(
                                                101L, 1, "구매자", "01012345678", MEMBER_B, 4000L),
                                        "new"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT expires_at FROM orders", java.sql.Timestamp.class)
                                .toInstant())
                .isEqualTo(expires);
        assertThat(available(501)).isEqualTo(19);
        assertThat(reserved(501)).isEqualTo(1);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM product WHERE id=101", Long.class);
    }
}
