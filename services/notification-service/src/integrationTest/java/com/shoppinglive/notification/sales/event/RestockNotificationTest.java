package com.shoppinglive.notification.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import com.shoppinglive.notification.sales.repository.RestockAlertRepository;
import com.shoppinglive.notification.sales.service.RestockNotificationService;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 품절에서 판매중으로 바뀐 것만 재입고로 보는지 확인한다. */
@DisplayName("재입고 알림 생성")
class RestockNotificationTest {

    private static final long PRODUCT = 501L;
    private static final long SALES = 9001L;
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";

    private NamedParameterJdbcTemplate jdbc;
    private RestockAlertRepository alerts;
    private SalesInfoEventListener listener;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:restock_notification_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().update("DELETE FROM notification");
        jdbc.getJdbcTemplate().update("DELETE FROM restock_alert");
        jdbc.getJdbcTemplate().update("DELETE FROM product_sales_status");
        alerts = new RestockAlertRepository(jdbc);
        listener =
                new SalesInfoEventListener(
                        new ProductSalesStatusRepository(jdbc),
                        new RestockNotificationService(
                                alerts, new NotificationWriteRepository(jdbc)));
        alerts.register(MEMBER, PRODUCT);
    }

    @Test
    @DisplayName("품절에서 판매중으로 바뀌면 신청자에게 알림을 만들고 신청을 지운다")
    void notifiesRegistrantsOnRestock() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));
        listener.consume(record(snapshot(2, "ON_SALE")));

        Map<String, Object> row = notifications().get(0);
        assertThat(row.get("member_id")).isEqualTo(MEMBER);
        assertThat(row.get("title")).isEqualTo("재입고되었습니다");
        assertThat(row.get("link_type")).isEqualTo("PRODUCT");
        assertThat(row.get("link_value")).isEqualTo("501");
        // 재입고 알림은 한 번만 간다. 다음 재입고에도 받으려면 다시 신청해야 한다.
        assertThat(alerts.exists(MEMBER, PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("계속 판매중이면 알림을 만들지 않는다")
    void doesNotNotifyWhenAlreadyInStock() {
        listener.consume(record(snapshot(1, "ON_SALE")));
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).isEmpty();
    }

    // Commerce 의 SalesStatus 는 READY, ON_SALE, SOLD_OUT, PRIVATE 넷이다.
    // 품절이 아닌 둘과, 아직 없는 값이 생겼을 때를 함께 본다.
    @ParameterizedTest(name = "{0} 에서 판매중으로 바뀌어도 재입고가 아니다")
    @ValueSource(strings = {"READY", "PRIVATE", "NEW_STATE"})
    @DisplayName("품절이 아니었던 상품이 판매중이 되는 것은 재입고가 아니다")
    void doesNotNotifyWhenPreviousStatusWasNotSoldOut(String previousStatus) {
        listener.consume(record(snapshot(1, previousStatus)));
        listener.consume(record(snapshot(2, "ON_SALE")));

        // 판매 준비에서 판매 시작으로 바뀐 것까지 재입고로 보면, 품절된 적이 없는 상품에
        // "재입고되었습니다" 가 간다. 상태를 참거짓으로 줄였을 때 실제로 그랬다.
        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("사본에 없던 상품의 첫 이벤트는 재입고가 아니다")
    void firstEventIsNotRestock() {
        listener.consume(record(snapshot(1, "ON_SALE")));

        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("같은 재입고 이벤트가 두 번 와도 알림은 하나다")
    void doesNotDuplicateOnRedelivery() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));
        listener.consume(record(snapshot(2, "ON_SALE")));
        // 재전달. 사본이 이미 판매중이라 전이가 보이지 않는다.
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("알림 만들기가 실패하면 재시도에서 다시 만든다 — 사본을 먼저 갱신하면 전이가 사라져 유실된다")
    void recoversRestockNotificationAfterFailure() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));

        RestockNotificationService failing = mock(RestockNotificationService.class);
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(failing)
                .create(any(), anyLong(), any());
        SalesInfoEventListener failingListener =
                new SalesInfoEventListener(new ProductSalesStatusRepository(jdbc), failing);

        assertThatThrownBy(() -> failingListener.consume(record(snapshot(2, "ON_SALE"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(notifications()).isEmpty();

        // Kafka 가 같은 메시지를 다시 준다. 사본이 아직 품절이라 전이가 그대로 보인다.
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("알림은 만들어졌는데 신청 정리가 실패하면, 재처리에서 알림은 늘지 않고 신청만 지워진다")
    void cleansUpRegistrationsOnRetryAfterDeleteFailure() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));

        RestockAlertRepository failingAlerts = spy(new RestockAlertRepository(jdbc));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(failingAlerts)
                .deleteByProductId(PRODUCT);
        SalesInfoEventListener failingListener =
                new SalesInfoEventListener(
                        new ProductSalesStatusRepository(jdbc),
                        new RestockNotificationService(
                                failingAlerts, new NotificationWriteRepository(jdbc)));

        assertThatThrownBy(() -> failingListener.consume(record(snapshot(2, "ON_SALE"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        // 알림은 이미 만들어졌고 신청은 남아 있다. 둘이 어긋난 상태다.
        assertThat(notifications()).hasSize(1);
        assertThat(alerts.exists(MEMBER, PRODUCT)).isTrue();

        // 재처리. 사본이 아직 품절이라 전이가 그대로 보인다.
        listener.consume(record(snapshot(2, "ON_SALE")));

        // 알림은 유일 제약이 막아서 늘지 않고, 신청은 이번에 정리된다.
        assertThat(notifications()).hasSize(1);
        assertThat(alerts.exists(MEMBER, PRODUCT)).isFalse();
    }

    @Test
    @DisplayName("품절, 판매중, 품절 순으로 바뀐 뒤 가운데 이벤트가 늦게 와도 알림을 만들지 않는다")
    void doesNotNotifyOnStaleOnSaleEvent() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));
        listener.consume(record(snapshot(3, "SOLD_OUT")));
        // 순서 역전: 가운데 판매중 이벤트가 지금 도착했다. 지금 이 상품은 품절이다.
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).isEmpty();
        assertThat(alerts.exists(MEMBER, PRODUCT)).isTrue();
    }

    @Test
    @DisplayName("신청자가 없으면 아무것도 만들지 않는다")
    void createsNothingWithoutRegistrants() {
        jdbc.getJdbcTemplate().update("DELETE FROM restock_alert");

        listener.consume(record(snapshot(1, "SOLD_OUT")));
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).isEmpty();
    }

    private List<Map<String, Object>> notifications() {
        return jdbc.queryForList(
                "SELECT member_id, title, body, link_type, link_value FROM notification", Map.of());
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(
                SalesInfoEventContract.TOPIC_NAME, 0, 0L, Long.toString(SALES), value);
    }

    private static String snapshot(long version, String status) {
        return "{\"eventId\":\"74b623b8-90ee-4f65-919c-d9ad2ea46fb"
                + version
                + "\",\"eventType\":\"SalesStatusChanged\",\"schemaVersion\":1,"
                + "\"source\":\"commerce-service\",\"aggregateId\":\""
                + SALES
                + "\",\"occurredAt\":\"2026-10-15T00:00:00Z\",\"payload\":{\"salesId\":"
                + SALES
                + ",\"productId\":"
                + PRODUCT
                + ",\"snapshotVersion\":"
                + version
                + ",\"price\":27000,\"status\":\""
                + status
                + "\",\"available\":0,\"currency\":\"KRW\"}}";
    }
}
