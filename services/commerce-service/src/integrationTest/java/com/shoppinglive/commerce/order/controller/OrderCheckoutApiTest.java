package com.shoppinglive.commerce.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shoppinglive.commerce.client.shopping.InMemoryShoppingClientStub;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 주문 1 주문서 확인 API 의 HTTP 계약 검증.
 *
 * <p>서비스 단위 테스트가 이미 판정 규칙을 덮으므로, 여기서는 상태코드와 라우팅처럼 서비스 계층에서 확인할 수 없는 것만 본다.
 */
@SpringBootTest(
        properties =
                "spring.datasource.url=jdbc:h2:mem:order_checkout_coupon_http;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Sql(
        scripts = {
            "/db/migration/V5__coupon_definitions.sql",
            "/db/migration/V17__coupon_display_snapshot_version.sql",
            "/db/migration/V6__member_coupons.sql"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class OrderCheckoutApiTest extends com.shoppinglive.commerce.support.CommerceSecurityTestSupport {

    private static final long PRODUCT_ID = 700L;

    @Autowired private MockMvc mockMvc;

    @Autowired private InMemoryShoppingClientStub shoppingClientStub;

    @Autowired private SalesJpaRepository salesRepository;

    @Autowired private SalesStockJpaRepository salesStockRepository;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private com.shoppinglive.commerce.order.repository.OrderJpaRepository orders;

    @Autowired private com.shoppinglive.commerce.order.repository.PaymentGroupRepository groups;

    @Autowired
    private com.shoppinglive.commerce.payment.repository.PaymentAttemptJpaRepository payments;

    private static final String COUPON_ID = "44444444-4444-4444-8444-444444444444";

    @BeforeEach
    void setUp() {
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
        shoppingClientStub.register(new ProductSnapshot(PRODUCT_ID, "테스트 상품", null));

        Sales sales = salesRepository.save(new Sales(PRODUCT_ID, 15_000L, SalesStatus.ON_SALE));
        salesStockRepository.save(new SalesStock(sales.getId(), 5, 0));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update(
                """
            INSERT INTO coupon_definition(id,seller_id,name,fixed_discount,issuance_limit,issued_count,
                starts_at,ends_at,expires_at,created_at) VALUES (?,?,?,5000,2,1,?,?,?,?)
            """,
                COUPON_ID,
                SELLER,
                "주문서 쿠폰",
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.plusSeconds(7200)),
                Timestamp.from(now));
        jdbc.update(
                "INSERT INTO coupon_target(coupon_id,product_id) VALUES (?,?)",
                COUPON_ID,
                PRODUCT_ID);
        jdbc.update(
                "INSERT INTO member_coupon(id,coupon_id,member_id,status,claimed_at) VALUES (?,?,?,'AVAILABLE',?)",
                "55555555-5555-4555-8555-555555555555",
                COUPON_ID,
                MEMBER_A,
                Timestamp.from(now));
    }

    @AfterEach
    void tearDown() {
        payments.deleteAll();
        orders.deleteAll();
        groups.deleteAll();
        jdbc.update("DELETE FROM member_coupon WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_target WHERE coupon_id=?", COUPON_ID);
        jdbc.update("DELETE FROM coupon_definition WHERE id=?", COUPON_ID);
        salesStockRepository.deleteAll();
        salesRepository.deleteAll();
        shoppingClientStub.clear();
    }

    @DisplayName("단건 주문서는 본인 쿠폰의 할인액을 계산하고 예약하지 않는다")
    @Test
    void previewsOwnedCouponDiscountWithoutReservation() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=2&couponId=" + COUPON_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponId").value(COUPON_ID))
                .andExpect(jsonPath("$.totalAmount").value(30_000))
                .andExpect(jsonPath("$.discountAmount").value(5_000))
                .andExpect(jsonPath("$.payableAmount").value(25_000));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE coupon_id=?",
                                String.class,
                                COUPON_ID))
                .isEqualTo("AVAILABLE");
    }

    @DisplayName("단건 주문은 미리보기와 같은 할인액을 저장하고 쿠폰을 예약한다")
    @Test
    void storesPreviewedDiscountAndReservesCouponForSingleOrder() throws Exception {
        String body =
                """
            {"productId":%d,"quantity":2,"buyerName":"구매자","buyerPhone":"010-1234-5678",
             "expectedTotalAmount":30000,"couponId":"%s"}
            """
                        .formatted(PRODUCT_ID, COUPON_ID);

        mockMvc.perform(
                        post("/v1/orders")
                                .header("Authorization", bearer(MEMBER_A))
                                .header(
                                        OrderCreationController.IDEMPOTENCY_KEY_HEADER,
                                        "single-coupon-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(30_000))
                .andExpect(jsonPath("$.discountAmount").value(5_000))
                .andExpect(jsonPath("$.payableAmount").value(25_000))
                .andExpect(jsonPath("$.groupNumber").exists());

        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE coupon_id=?",
                                String.class,
                                COUPON_ID))
                .isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT coupon_id FROM payment_group", String.class))
                .isEqualTo(COUPON_ID);
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM payment_group", Long.class))
                .isEqualTo(5_000L);
        assertThat(jdbc.queryForObject("SELECT payable_amount FROM payment_group", Long.class))
                .isEqualTo(25_000L);

        mockMvc.perform(
                        post("/v1/orders")
                                .header("Authorization", bearer(MEMBER_A))
                                .header(
                                        OrderCreationController.IDEMPOTENCY_KEY_HEADER,
                                        "single-coupon-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountAmount").value(5_000));
        mockMvc.perform(
                        post("/v1/orders")
                                .header("Authorization", bearer(MEMBER_A))
                                .header(
                                        OrderCreationController.IDEMPOTENCY_KEY_HEADER,
                                        "single-coupon-order")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body.replace(",\"couponId\":\"" + COUPON_ID + "\"", "")))
                .andExpect(status().isConflict());
        assertThat(orders.count()).isEqualTo(1);
    }

    @DisplayName("대상이 아닌 쿠폰으로 단건 주문을 요청하면 주문·재고·쿠폰을 변경하지 않는다")
    @Test
    void rejectsIneligibleCouponWithoutChangingOrderStockOrCoupon() throws Exception {
        jdbc.update(
                "UPDATE coupon_target SET product_id=? WHERE coupon_id=?",
                PRODUCT_ID + 1,
                COUPON_ID);
        String body =
                """
            {"productId":%d,"quantity":2,"buyerName":"구매자","buyerPhone":"010-1234-5678",
             "expectedTotalAmount":30000,"couponId":"%s"}
            """
                        .formatted(PRODUCT_ID, COUPON_ID);

        mockMvc.perform(
                        post("/v1/orders")
                                .header("Authorization", bearer(MEMBER_A))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isConflict());

        assertThat(orders.count()).isZero();
        assertThat(groups.count()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM member_coupon WHERE coupon_id=?",
                                String.class,
                                COUPON_ID))
                .isEqualTo("AVAILABLE");
        assertThat(salesStockRepository.findAll())
                .extracting(SalesStock::getAvailable)
                .containsExactly(5);
        assertThat(salesStockRepository.findAll())
                .extracting(SalesStock::getReserved)
                .containsExactly(0);
    }

    @DisplayName("구매할 수 없는 상품에는 쿠폰을 적용할 수 없다")
    @Test
    void rejectsCouponForUnorderableProduct() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=6&couponId=" + COUPON_ID)
                .andExpect(status().isConflict());
    }

    private ResultActions checkout(String query) throws Exception {
        return mockMvc.perform(
                get("/v1/orders/checkout" + query).header("Authorization", bearer(MEMBER_A)));
    }

    @DisplayName("주문 가능한 상품은 200과 총액을 반환한다")
    @Test
    void returns200AndTotalForOrderableProduct() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("테스트 상품"))
                .andExpect(jsonPath("$.unitPrice").value(15_000))
                .andExpect(jsonPath("$.totalAmount").value(30_000))
                .andExpect(jsonPath("$.orderable").value(true))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @DisplayName("재고보다 많은 수량을 조회하면 200과 주문 불가 사유를 반환한다")
    @Test
    void returns200WithReasonWhenQuantityExceedsStock() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=6")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderable").value(false))
                .andExpect(jsonPath("$.reason").value("EXCEEDS_STOCK"))
                .andExpect(jsonPath("$.available").value(5));
    }

    /**
     * 주문 1 완료 기준: "주문서 확인 중에는 재고가 줄지 않고, 실제 주문 생성 때만 재고를 확보한다". 주문서를 여러 번 열어봐도 DB 재고가 그대로여야 한다. 그렇지
     * 않으면 결제까지 가지 않고 이탈한 사람들 때문에 팔 수 있는 물건이 묶인다.
     */
    @DisplayName("주문서를 반복 조회해도 재고가 줄지 않는다")
    @Test
    void keepsStockUnchangedAcrossRepeatedCheckoutViews() throws Exception {
        Long salesId = salesRepository.findByProductId(PRODUCT_ID).orElseThrow().getId();

        for (int i = 0; i < 3; i++) {
            checkout("?productId=" + PRODUCT_ID + "&quantity=2").andExpect(status().isOk());
        }

        SalesStock stock = salesStockRepository.findById(salesId).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(5);
        assertThat(stock.getReserved()).isZero();
    }

    @DisplayName("판매정보가 없는 상품의 주문서는 404로 응답한다")
    @Test
    void returns404WhenSalesInformationIsMissing() throws Exception {
        checkout("?productId=999999&quantity=1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @DisplayName("잘못된 주문 수량은 400으로 응답한다")
    @Test
    void returns400ForInvalidQuantity() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=0").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID + "&quantity=-1").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID + "&quantity=1.5").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID + "&quantity=abc").andExpect(status().isBadRequest());
        checkout("?productId=" + PRODUCT_ID).andExpect(status().isBadRequest());
    }

    /**
     * {@code productId} 를 {@code Long} 으로 받으면 숫자가 아닌 값은 타입 변환 실패가 된다. 공통 예외 핸들러에 맡기면 500 이 되므로 전용
     * 매핑으로 400 을 보장한다.
     */
    @DisplayName("숫자가 아닌 상품 ID는 400으로 응답한다")
    @Test
    void returns400ForNonNumericProductId() throws Exception {
        checkout("?productId=abc&quantity=1").andExpect(status().isBadRequest());
    }

    @DisplayName("상품 ID가 없으면 400으로 응답한다")
    @Test
    void returns400ForMissingProductId() throws Exception {
        checkout("?quantity=1").andExpect(status().isBadRequest());
    }

    /**
     * {@code /v1/orders/checkout} 은 {@code /v1/orders/&#123;orderNumber&#125;} 와 경로가 겹칠 수 있다. 리터럴이
     * 먼저 매칭되는 것이 Spring 의 규칙이지만, 주문 조회 컨트롤러로 잘못 흘러가면 비밀번호 헤더가 없다는 이유로 엉뚱한 응답이 나가므로 회귀를 막아둔다.
     */
    @DisplayName("checkout 경로를 주문번호 조회로 처리하지 않는다")
    @Test
    void keepsCheckoutPathSeparateFromOrderNumberLookup() throws Exception {
        checkout("?productId=" + PRODUCT_ID + "&quantity=1")
                .andExpect(status().isOk())
                .andExpect(handler().handlerType(OrderCheckoutController.class));
    }
}
