package com.shoppinglive.notification.sales.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shoppinglive.notification.event.InvalidEventMessageException;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import com.shoppinglive.notification.sales.entity.ProductSalesStatus;
import com.shoppinglive.notification.sales.repository.ProductSalesStatusRepository;
import com.shoppinglive.notification.sales.repository.RestockAlertRepository;
import com.shoppinglive.notification.sales.service.RestockNotificationService;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 리스너를 직접 불러 "무엇을 저장하고 무엇을 건너뛰는가" 를 검증한다. broker 는 필요하지 않다. */
@DisplayName("판매 상태 수신")
class SalesInfoEventListenerTest {

    private static final long PRODUCT = 501L;
    private static final long SALES = 9001L;
    private static final String EVENT_ID = "74b623b8-90ee-4f65-919c-d9ad2ea46fb2";

    private NamedParameterJdbcTemplate jdbc;
    private ProductSalesStatusRepository statuses;
    private SalesInfoEventListener listener;

    @BeforeEach
    void setUp() {
        DataSource dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:sales_info_listener_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().update("DELETE FROM product_sales_status");
        statuses = new ProductSalesStatusRepository(jdbc);
        listener = new SalesInfoEventListener(statuses, restockNotifications(jdbc));
    }

    @Test
    @DisplayName("판매 상태를 사본에 저장한다")
    void storesSalesStatus() {
        listener.consume(record(snapshot(1, "SOLD_OUT")));

        ProductSalesStatus stored = statuses.findByProductId(PRODUCT).orElseThrow();
        assertThat(stored.inStock()).isFalse();
        assertThat(stored.rawStatus()).isEqualTo("SOLD_OUT");
        assertThat(stored.salesId()).isEqualTo(SALES);
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도, 늦게 온 옛 스냅샷이 새 상태를 덮지 않는다")
    void ignoresDuplicateAndStaleEvents() {
        listener.consume(record(snapshot(2, "ON_SALE")));
        listener.consume(record(snapshot(2, "ON_SALE")));
        // 순서 역전: 먼저 발행된 버전 1 이 늦게 도착했다.
        listener.consume(record(snapshot(1, "SOLD_OUT")));

        ProductSalesStatus stored = statuses.findByProductId(PRODUCT).orElseThrow();
        assertThat(stored.snapshotVersion()).isEqualTo(2);
        assertThat(stored.inStock()).isTrue();
    }

    @Test
    @DisplayName("모르는 상태는 판매 중이 아닌 것으로 본다. 원본은 진단용으로 남긴다")
    void treatsUnknownStatusAsNotInStock() {
        listener.consume(record(snapshot(1, "NEW_STATE")));

        ProductSalesStatus stored = statuses.findByProductId(PRODUCT).orElseThrow();
        // Commerce 가 상태 값을 추가해도 이쪽 판단이 흔들리지 않는다.
        assertThat(stored.inStock()).isFalse();
        assertThat(stored.rawStatus()).isEqualTo("NEW_STATE");
    }

    @Test
    @DisplayName("다시 읽어도 실패할 메시지는 보관함으로 보내도록 던진다")
    void throwsInvalidEventForMessagesThatCanNeverSucceed() {
        assertThatThrownBy(() -> listener.consume(record("깨진 JSON")))
                .isInstanceOf(InvalidEventMessageException.class);
        // 발행 서비스가 계약과 다르다.
        assertThatThrownBy(
                        () ->
                                listener.consume(
                                        record(
                                                snapshot(1, "SOLD_OUT")
                                                        .replace("commerce-service", "x"))))
                .isInstanceOf(InvalidEventMessageException.class);
        // 메시지 key 가 payload 의 판매 ID 와 다르면 파티션이 흩어져 순서가 깨진 것이다.
        assertThatThrownBy(
                        () ->
                                listener.consume(
                                        new ConsumerRecord<>(
                                                SalesInfoEventContract.TOPIC_NAME,
                                                0,
                                                0L,
                                                "999",
                                                snapshot(1, "SOLD_OUT"))))
                .isInstanceOf(InvalidEventMessageException.class);

        assertThat(statuses.findByProductId(PRODUCT)).isEmpty();
    }

    @Test
    @DisplayName("DB 장애는 던져서 재시도한다 — 버리면 사본이 원본과 어긋난 채 남는다")
    void rethrowsTransientFailures() {
        ProductSalesStatusRepository failing = mock(ProductSalesStatusRepository.class);
        when(failing.apply(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(
                        () ->
                                new SalesInfoEventListener(failing, restockNotifications(jdbc))
                                        .consume(record(snapshot(1, "SOLD_OUT"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static RestockNotificationService restockNotifications(
            NamedParameterJdbcTemplate jdbc) {
        return new RestockNotificationService(
                new RestockAlertRepository(jdbc), new NotificationWriteRepository(jdbc));
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(
                SalesInfoEventContract.TOPIC_NAME, 0, 0L, Long.toString(SALES), value);
    }

    private static String snapshot(long version, String status) {
        return "{\"eventId\":\""
                + EVENT_ID
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
