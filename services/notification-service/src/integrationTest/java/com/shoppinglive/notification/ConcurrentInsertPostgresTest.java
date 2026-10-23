package com.shoppinglive.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoppinglive.notification.broadcast.repository.BroadcastAlertRepository;
import com.shoppinglive.notification.inbox.entity.NotificationLinkType;
import com.shoppinglive.notification.inbox.repository.NewNotification;
import com.shoppinglive.notification.inbox.repository.NotificationWriteRepository;
import com.shoppinglive.notification.sales.repository.RestockAlertRepository;
import com.shoppinglive.notification.security.NotificationSecuritySupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 행을 동시에 INSERT 할 때 예외 대신 0건으로 끝나는지 확인한다.
 *
 * <p>대상은 유일 제약이 걸린 표 셋이다. {@code uk_notification_event_member}, {@code
 * uk_restock_alert_member_product}, {@code uk_broadcast_alert_member_broadcast}.
 *
 * <p><b>왜 H2 로는 확인할 수 없나</b> — 이 결함은 서로 다른 transaction 이 아직 커밋하지 않은 행을 두고 경쟁할 때만 드러난다. {@code WHERE
 * NOT EXISTS} 는 커밋되지 않은 행을 보지 못해서 양쪽 다 "없다" 로 통과하고, 늦은 쪽이 유일 제약에 걸려 터진다. H2 인메모리에서는 그 경쟁 구간이 생기지 않아
 * 결함이 있는 동안에도 테스트가 전부 통과했다.
 *
 * <p><b>왜 스레드 두 개를 동시에 출발시키지 않나</b> — 그 방식으로도 재현되지만 세 번에 한 번만 걸렸다. 회귀 테스트가 그러면 결함이 돌아와도 대부분 통과한다.
 * 그래서 <b>커밋하지 않은 transaction 을 붙잡아</b> 두 번째 요청이 반드시 유일 인덱스에서 대기하게 만든다. 대기하지 않으면 그 자체로 실패한다.
 *
 * <p>{@code NOTIFICATION_TEST_POSTGRES_URL} 이 실제 PostgreSQL 을 가리킬 때만 돈다. 알림 서비스의 테스트 설정이 이미 그 환경변수로
 * DB 를 고르므로, 값을 주면 이 테스트도 같은 DB 에서 함께 돈다. 값이 없으면 건너뛴다. 띄우는 방법은 {@code
 * my-study/13-local-verification.md} 에 있다.
 *
 * <p><b>{@code postgres} 태그를 붙이지 않았다.</b> 그 태그는 {@code integrationTest} 에서 제외되고 {@code
 * migrationTest} 는 {@code migration} 까지 함께 요구해서, 태그만 붙이면 어느 Gradle 작업도 이 테스트를 실행하지 않는다. H2 가 못 잡은
 * 결함의 회귀 테스트가 아예 돌지 않으면 같은 실수를 반복한다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(
        named = "NOTIFICATION_TEST_POSTGRES_URL",
        matches = "jdbc:postgresql:.*")
@DisplayName("동시 쓰기 충돌")
class ConcurrentInsertPostgresTest extends NotificationSecuritySupport {

    private static final long PRODUCT = 501L;
    private static final long BROADCAST = 42L;
    private static final String MEMBER = "11111111-1111-4111-8111-111111111111";
    private static final String EVENT_ID = "74b623b8-90ee-4f65-919c-d9ad2ea46fb2";

    /** 두 번째 요청이 막혀 있는지 확인하는 시간. 이 시간 안에 끝나면 대기하지 않은 것이다. */
    private static final long BLOCKED_CHECK_MILLIS = 500;

    @Autowired RestockAlertRepository restockAlerts;
    @Autowired BroadcastAlertRepository broadcastAlerts;
    @Autowired NotificationWriteRepository notifications;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM restock_alert");
        jdbc.update("DELETE FROM broadcast_alert");
        jdbc.update("DELETE FROM notification");
    }

    @Test
    @DisplayName("다른 요청이 같은 재입고 신청을 넣고 아직 커밋하지 않았어도 터지지 않는다")
    void restockRegistrationSurvivesUncommittedDuplicate() throws Exception {
        boolean registered =
                whileUncommitted(
                        """
                        INSERT INTO restock_alert (member_id, product_id, created_at, updated_at)
                        VALUES (?, ?, now(), now())
                        """,
                        MEMBER,
                        PRODUCT,
                        () -> restockAlerts.register(MEMBER, PRODUCT));

        // 먼저 들어간 쪽이 있으므로 이쪽은 0건이다. 예외가 아니라 0건이어야 한다.
        assertThat(registered).isFalse();
        assertThat(count("restock_alert")).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 요청이 같은 방송 신청을 넣고 아직 커밋하지 않았어도 터지지 않는다")
    void broadcastRegistrationSurvivesUncommittedDuplicate() throws Exception {
        boolean registered =
                whileUncommitted(
                        """
                        INSERT INTO broadcast_alert
                            (member_id, broadcast_id, created_at, updated_at)
                        VALUES (?, ?, now(), now())
                        """,
                        MEMBER,
                        BROADCAST,
                        () -> broadcastAlerts.register(MEMBER, BROADCAST));

        assertThat(registered).isFalse();
        assertThat(count("broadcast_alert")).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 instance 가 같은 알림을 넣고 아직 커밋하지 않았어도 터지지 않는다")
    void notificationCreationSurvivesUncommittedDuplicate() throws Exception {
        boolean created =
                whileUncommitted(
                        """
                        INSERT INTO notification
                            (member_id, event_id, event_type, title, body, link_type, link_value,
                             occurred_at, created_at, updated_at)
                        VALUES (?, ?, 'Restocked', '재입고되었습니다', '등록하신 상품이 재입고되었습니다',
                                'PRODUCT', '501', now(), now(), now())
                        """,
                        MEMBER,
                        EVENT_ID,
                        () -> notifications.createAll(restocked(), List.of(MEMBER)) == 1);

        assertThat(created).isFalse();
        assertThat(count("notification")).isEqualTo(1);
    }

    /**
     * 커밋하지 않은 INSERT 를 붙잡은 상태에서 {@code work} 를 다른 연결로 실행한다.
     *
     * <p>{@code work} 는 같은 행을 넣으려다 유일 인덱스에서 대기한다. 붙잡은 쪽을 커밋하면 깨어나는데, 그때 유일 제약 위반을 예외로 받으면 결함이고 0건으로
     * 끝나야 맞다.
     */
    private boolean whileUncommitted(
            String blockingInsert, Object first, Object second, Callable<Boolean> work)
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insert(holder, blockingInsert, first, second);

            Future<Boolean> blocked = executor.submit(work);
            // 대기하지 않고 끝났다면 경쟁 구간을 만들지 못한 것이다. 그대로 두면 결함을 못 잡는다.
            assertThatThrownBy(() -> blocked.get(BLOCKED_CHECK_MILLIS, TimeUnit.MILLISECONDS))
                    .as("두 번째 요청이 유일 인덱스에서 대기해야 한다")
                    .isInstanceOf(TimeoutException.class);

            holder.commit();
            return blocked.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void insert(Connection connection, String sql, Object first, Object second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, first);
            statement.setObject(2, second);
            statement.executeUpdate();
        }
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static NewNotification restocked() {
        return new NewNotification(
                EVENT_ID,
                "Restocked",
                "재입고되었습니다",
                "등록하신 상품이 재입고되었습니다",
                NotificationLinkType.PRODUCT,
                Long.toString(PRODUCT),
                Instant.parse("2026-10-15T02:00:00Z"));
    }
}
