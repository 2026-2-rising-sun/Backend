package com.shoppinglive.commercecore.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shoppinglive.commercecore.order.dto.*;
import com.shoppinglive.commercecore.payment.service.RefundIntakeService;
import com.shoppinglive.commercecore.product.service.*;
import java.time.Duration;
import java.util.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(
        properties = {
            "commercecore.product.lookup.mode=local",
            "commercecore.refunds.api-enabled=true",
            "commercecore.refunds.execution-enabled=true"
        })
@AutoConfigureMockMvc
class LegacyOrderSellerSnapshotPostgresTest extends OrderSheetPostgresSupport {
    @Autowired OrderCreationService creation;
    @Autowired RefundIntakeService intake;
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
    @DisplayName("구 API 단건·Cart 주문은 실제 상품 삭제 후에도 원래 판매자의 환불 조회를 보존한다")
    void preservesSellerAfterDeletion(String source) throws Exception {
        String number;
        if (source.equals("cart-group")) {
            long cart = cartItem(101, 501);
            number =
                    legacyGroups
                            .create(
                                    MEMBER_A,
                                    List.of(new PaymentGroupService.Selection(cart, 0L)),
                                    "구매자",
                                    "01012345678",
                                    4000,
                                    "group")
                            .group()
                            .groupNumber();
        } else if (source.equals("cart-single")) {
            var order =
                    creation.createFromCart(
                                    MEMBER_A,
                                    cartItem(101, 501),
                                    new CartOrderCommand("구매자", "01012345678", 4000L),
                                    "cart")
                            .order();
            number = order.getPaymentGroup().getGroupNumber();
        } else {
            number =
                    creation.create(
                                    new CreateOrderCommand(
                                            101L, 1, "구매자", "01012345678", MEMBER_A, 4000L),
                                    "single")
                            .order()
                            .getPaymentGroup()
                            .getGroupNumber();
        }
        var before =
                jdbc.queryForMap(
                        "SELECT order_number,product_name_snapshot,total_amount,expires_at,source_cart_item_id,seller_id_snapshot FROM orders");
        assertThat(before.get("seller_id_snapshot")).isEqualTo(SELLER);
        boolean historical = source.equals("historical-null");
        if (historical) jdbc.update("UPDATE orders SET seller_id_snapshot=NULL");
        payLegacy(number);
        if (!source.equals("normal")) {
            products.delete(101L, version(), SELLER);
            assertThat(lookup.findProducts(List.of(101L))).isEmpty();
        }
        var accepted = intake.request(MEMBER_A, number, "refund", List.of()).refund();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT seller_id_snapshot FROM refund_target_order WHERE refund_request_id=?",
                                String.class,
                                accepted.id()))
                .isEqualTo(SELLER);
        refundDriver.execute(accepted.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM refund_request WHERE id=?",
                                String.class,
                                accepted.id()))
                .isEqualTo("SUCCESS");
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT refund_amount FROM refund_execution", Long.class))
                .isEqualTo(4000);
        assertThat(
                        paymentServer.jdbc.queryForObject(
                                "SELECT status FROM refund_execution", String.class))
                .isEqualTo("SUCCESS");
        assertThat(available(501)).isEqualTo(20);
        assertThat(reserved(501)).isZero();
        mvc.perform(get("/v1/seller/refunds").header("Authorization", sellerBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(accepted.id()));
        mvc.perform(
                        get("/v1/seller/refunds/" + accepted.id())
                                .header("Authorization", sellerBearer()))
                .andExpect(status().isOk());
        String other = "Bearer " + TOKENS.token(MEMBER_B, Set.of("USER", "SELLER"));
        mvc.perform(get("/v1/seller/refunds").header("Authorization", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/v1/seller/refunds/" + accepted.id()).header("Authorization", other))
                .andExpect(status().isNotFound());
        var after =
                jdbc.queryForMap(
                        "SELECT order_number,product_name_snapshot,total_amount,expires_at,source_cart_item_id,seller_id_snapshot FROM orders");
        assertThat(after.get("seller_id_snapshot")).isEqualTo(historical ? null : SELLER);
        before.remove("seller_id_snapshot");
        after.remove("seller_id_snapshot");
        assertThat(after).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("상품 중지·삭제는 신규 주문만 차단하고 기존 선점 주문의 기한과 결제 권한을 보존한다")
    void reservedOrdersSurviveProductClosure(boolean deleted) {
        var sheet = create(1);
        if (deleted) products.delete(101L, version(), SELLER);
        else products.changeSaleState(101L, false, version(), SELLER);
        assertThatThrownBy(
                        () ->
                                sheets.create(
                                        MEMBER_B,
                                        "new",
                                        new CreateOrderSheetRequest(
                                                List.of(new CreateOrderSheetRequest.Item(501L, 1)),
                                                null,
                                                "구매자",
                                                "01012345678")))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        assertThatThrownBy(
                        () ->
                                creation.create(
                                        new CreateOrderCommand(
                                                101L, 1, "구매자", "01012345678", MEMBER_B, 4000L),
                                        "new-legacy"))
                .isInstanceOf(com.shoppinglive.common.core.BusinessException.class);
        var payment = start(sheet, false);
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            dispatcher.payment(payment.paymentId());
                            assertThat(sheets.get(MEMBER_A, sheet.orderNumber()).status())
                                    .isEqualTo("PAID");
                        });
        assertThat(sheets.get(MEMBER_A, sheet.orderNumber()).expiresAt())
                .isEqualTo(sheet.expiresAt());
        assertThat(available(501)).isEqualTo(19);
        assertThat(reserved(501)).isZero();
    }

    @Test
    @DisplayName("신규 주문서 Cart 선택100개는 수락하고101개는 계약대로 거절한다")
    void acceptsHundredCartItemsButRejectsHundredOne() throws Exception {
        var selections = new ArrayList<Map<String, Long>>();
        for (long i = 0; i < 101; i++) selections.add(Map.of("itemId", i + 1, "version", 0L));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        mvc.perform(
                        post("/v1/order-sheets")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "limit101")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "cartItems",
                                                        selections,
                                                        "buyerName",
                                                        "구매자",
                                                        "buyerPhone",
                                                        "01012345678"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_group", Integer.class))
                .isZero();
        selections.clear();
        for (long i = 0; i < 100; i++) {
            long product = 201 + i, sku = 601 + i;
            jdbc.update(
                    "INSERT INTO product(id,name,description,main_image_id,seller_id,category,sale_enabled,version,created_at,updated_at) VALUES (?,?,'설명',1,?,'FASHION',TRUE,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                    product,
                    "상품" + product,
                    SELLER);
            seedSku(sku, product, 1, 1);
            selections.add(Map.of("itemId", cartItem(product, sku), "version", 0L));
        }
        mvc.perform(
                        post("/v1/order-sheets")
                                .header("Authorization", bearer(MEMBER_A))
                                .header("X-Idempotency-Key", "limit100")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "cartItems",
                                                        selections,
                                                        "buyerName",
                                                        "구매자",
                                                        "buyerPhone",
                                                        "01012345678"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(100));
    }

    private long cartItem(long product, long sku) {
        return jdbc.queryForObject(
                "INSERT INTO cart_item(member_id,product_id,sku_id,quantity,version,created_at,updated_at) VALUES(?,?,?,1,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
                Long.class,
                MEMBER_A,
                product,
                sku);
    }

    private long version() {
        return jdbc.queryForObject("SELECT version FROM product WHERE id=101", Long.class);
    }

    private void payLegacy(String number) {
        var payment = legacyGroups.start(MEMBER_A, number, "pay");
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            dispatcher.payment(payment.paymentId());
                            assertThat(legacyGroups.get(MEMBER_A, number).status().name())
                                    .isEqualTo("PAID");
                        });
    }
}
