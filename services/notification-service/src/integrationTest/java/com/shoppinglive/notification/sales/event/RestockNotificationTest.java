package com.shoppinglive.notification.sales.event;

import static org.assertj.core.api.Assertions.assertThat;

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
        // 재전달. 사본 갱신이 0건으로 끝나므로 알림도 다시 만들지 않는다.
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("신청자가 없으면 아무것도 만들지 않는다")
    void createsNothingWithoutRegistrants() {
        jdbc.getJdbcTemplate().update("DELETE FROM restock_alert");

        listener.consume(record(snapshot(1, "SOLD_OUT")));
        listener.consume(record(snapshot(2, "ON_SALE")));

        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("판매 이벤트의 상품 이름 사본을 재입고 알림 본문에 표시한다")
    void includesProductNameWithoutRemoteLookup() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));
        listener.consume(
                record(
                        snapshot(2, "ON_SALE")
                                .replace(
                                        "\"currency\"", "\"productName\":\"가을 니트\",\"currency\"")));
        assertThat(notifications().getFirst().get("body")).isEqualTo("가을 니트 상품이 재입고되었습니다");
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
