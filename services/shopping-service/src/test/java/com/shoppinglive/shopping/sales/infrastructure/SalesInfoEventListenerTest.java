package com.shoppinglive.shopping.sales.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shoppinglive.contracts.events.SalesInfoEvent;
import com.shoppinglive.contracts.events.SalesInfoEventContract;
import com.shoppinglive.contracts.events.SalesInfoEventType;
import com.shoppinglive.contracts.events.SalesInfoSnapshot;
import com.shoppinglive.contracts.events.SalesInfoStatus;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import com.shoppinglive.shopping.sales.domain.SalesStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SalesInfoEventListenerTest {

    private static final long PRODUCT = 501L;
    private static final String EVENT_ID = "74b623b8-90ee-4f65-919c-d9ad2ea46fb2";
    private static final String PAYLOAD = "{\"salesId\":9001,\"productId\":501,\"snapshotVersion\":2,"
        + "\"price\":27000,\"status\":\"ON_SALE\",\"available\":20,\"currency\":\"KRW\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private NamedParameterJdbcTemplate jdbc;
    private ProductSalesRepository repository;
    private SalesInfoEventListener listener;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
            "jdbc:h2:mem:sales_event_listener_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcOperations().update("DELETE FROM product_sales");
        repository = new ProductSalesRepository(jdbc, new DataSourceTransactionManager(dataSource));
        listener = new SalesInfoEventListener(repository);
    }

    @Test
    @DisplayName("계약에 맞는 판매정보 이벤트는 사본 행으로 저장된다")
    void validContractEventIsStoredInReplica() throws Exception {
        listener.consume(record(json(event(2, SalesInfoStatus.ON_SALE, 27000))));

        assertThat(stored()).isEqualTo(Map.of(PRODUCT, new SalesInfo(PRODUCT, 9001L, 27000, SalesStatus.ON_SALE, 20)));
    }

    @Test
    @DisplayName("같은 snapshotVersion 이나 더 오래된 snapshotVersion 의 이벤트가 와도 저장된 행은 바뀌지 않는다")
    void duplicateAndOlderSnapshotVersionLeaveRowUnchanged() throws Exception {
        listener.consume(record(json(event(2, SalesInfoStatus.ON_SALE, 27000))));

        listener.consume(record(json(event(2, SalesInfoStatus.ON_SALE, 99000))));
        listener.consume(record(json(event(1, SalesInfoStatus.SOLD_OUT, 1000))));

        assertThat(stored().get(PRODUCT).price()).isEqualTo(27000);
    }

    @Test
    @DisplayName("계약에 없는 status 값을 받으면 판매 상태는 PRIVATE 으로 저장된다")
    void unknownStatusIsStoredAsPrivate() throws Exception {
        String raw = json(event(2, SalesInfoStatus.ON_SALE, 27000)).replace("\"ON_SALE\"", "\"SCHEDULED\"");

        listener.consume(record(raw));

        assertThat(stored().get(PRODUCT).status()).isEqualTo(SalesStatus.PRIVATE);
    }

    @Test
    @DisplayName("계약에 없는 eventType 이어도 판매정보는 저장된다")
    void unknownEventTypeIsStillStored() throws Exception {
        String raw = json(event(2, SalesInfoStatus.ON_SALE, 27000)).replace("\"PriceChanged\"", "\"FutureChanged\"");

        listener.consume(record(raw));

        assertThat(stored().get(PRODUCT).price()).isEqualTo(27000);
    }

    @ParameterizedTest(name = "{0} is skipped and the next valid event is applied")
    @DisplayName("잘못된 메시지는 예외 없이 건너뛰고 다음 정상 이벤트는 반영된다")
    @MethodSource("invalidMessages")
    void invalidMessageIsSkippedAndNextValidEventIsApplied(String name, String raw) throws Exception {
        assertThatCode(() -> listener.consume(record(raw))).doesNotThrowAnyException();
        assertThat(stored()).isEmpty();

        listener.consume(record(json(event(2, SalesInfoStatus.ON_SALE, 27000))));

        assertThat(stored()).containsKey(PRODUCT);
    }

    @Test
    @DisplayName("사본 저장 중 DB 장애가 나면 예외를 삼키지 않고 호출자에게 전달한다")
    void repositoryFailureIsNotSwallowed() throws Exception {
        ProductSalesRepository failing = mock(ProductSalesRepository.class);
        when(failing.apply(any(), anyLong())).thenThrow(new DataAccessResourceFailureException("db down"));
        String raw = json(event(2, SalesInfoStatus.ON_SALE, 27000));

        assertThatThrownBy(() -> new SalesInfoEventListener(failing).consume(record(raw)))
            .isInstanceOf(DataAccessResourceFailureException.class);
    }

    static Stream<Arguments> invalidMessages() {
        return Stream.of(
            Arguments.of("broken JSON", "{\"schemaVersion\":1,"),
            Arguments.of("payload missing", message(1, null)),
            Arguments.of("productId missing", message(1, PAYLOAD.replace("\"productId\":501,", ""))),
            Arguments.of("price zero", message(1, PAYLOAD.replace("\"price\":27000", "\"price\":0"))),
            Arguments.of("price negative", message(1, PAYLOAD.replace("\"price\":27000", "\"price\":-1"))),
            Arguments.of("currency USD", message(1, PAYLOAD.replace("KRW", "USD"))),
            Arguments.of("schemaVersion 2", message(2, PAYLOAD)));
    }

    private Map<Long, SalesInfo> stored() {
        return repository.findByProductIds(List.of(PRODUCT));
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(SalesInfoEventContract.TOPIC_NAME, 0, 7L, "9001", value);
    }

    private static SalesInfoEvent event(long snapshotVersion, SalesInfoStatus status, long price) {
        return new SalesInfoEvent(EVENT_ID, SalesInfoEventType.PriceChanged, 1, Instant.parse("2026-10-09T06:00:00Z"),
            new SalesInfoSnapshot(9001, PRODUCT, snapshotVersion, price, status, 20, "KRW"));
    }

    private static String json(SalesInfoEvent event) throws Exception {
        return MAPPER.writeValueAsString(event);
    }

    private static String message(int schemaVersion, String payload) {
        String payloadField = payload == null ? "" : ",\"payload\":" + payload;
        return "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"PriceChanged\",\"schemaVersion\":" + schemaVersion
            + ",\"occurredAt\":\"2026-10-09T06:00:00Z\"" + payloadField + "}";
    }
}
