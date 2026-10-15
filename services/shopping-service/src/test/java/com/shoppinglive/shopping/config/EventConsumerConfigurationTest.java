package com.shoppinglive.shopping.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTransientConnectionException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

/**
 * DB 장애 판별의 범위를 고정한다. 실제 장애 동작은 {@code CouponInfoEventKafkaTest} 가 확인하고, 여기서는 "무한 재시도로 잘못 분류되면 안 되는
 * 예외"가 넓어지지 않게 막는다.
 */
class EventConsumerConfigurationTest {

    @Test
    @DisplayName("transaction 시작 중 연결을 얻지 못한 실제 예외 체인은 DB 장애다")
    void connectionAcquisitionFailureAtTransactionStartIsDbOutage() {
        // 커넥션 풀이 고갈된 상태에서 @Transactional 메서드를 부를 때 JpaTransactionManager 가 실제로 던지는 체인
        Throwable chain =
                listenerFailure(
                        new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction",
                                new JDBCConnectionException(
                                        "Unable to acquire JDBC Connection",
                                        new SQLTransientConnectionException(
                                                "Connection is not available"))));

        assertThat(EventConsumerConfiguration.isDbOutage(chain)).isTrue();
    }

    @Test
    @DisplayName("다시 해도 같은 결과인 DB 예외는 DB 장애로 보지 않는다")
    void permanentDbErrorsAreNotDbOutage() {
        assertThat(
                        EventConsumerConfiguration.isDbOutage(
                                listenerFailure(new DataIntegrityViolationException("unique 위반"))))
                .isFalse();
        assertThat(
                        EventConsumerConfiguration.isDbOutage(
                                listenerFailure(
                                        new CannotCreateTransactionException(
                                                "다른 원인", new SQLSyntaxErrorException("문법 오류")))))
                .isFalse();
        assertThat(
                        EventConsumerConfiguration.isDbOutage(
                                listenerFailure(
                                        new TransactionSystemException(
                                                "commit 실패", new SQLException("원인 불명")))))
                .isFalse();
    }

    private static Throwable listenerFailure(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }
}
