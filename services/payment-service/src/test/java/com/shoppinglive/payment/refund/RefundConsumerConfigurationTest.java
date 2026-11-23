package com.shoppinglive.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoppinglive.common.kafka.ConsumerErrorHandlers;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

class RefundConsumerConfigurationTest {
    @Test
    @DisplayName("[REF-ACCEPT-07] DB에 닿지 못한 실패만 무한 재시도 대상이고, 제약 위반·코드 오류는 횟수 제한 뒤 DLT로 간다")
    void onlyDatabaseOutageRetriesForever() {
        assertThat(
                        ConsumerErrorHandlers.isDbOutage(
                                new ListenerExecutionFailedException(
                                        "x",
                                        new CannotCreateTransactionException(
                                                "x", new SQLTransientConnectionException()))))
                .isTrue();
        assertThat(ConsumerErrorHandlers.isDbOutage(new QueryTimeoutException("x"))).isTrue();
        assertThat(ConsumerErrorHandlers.isDbOutage(new DataIntegrityViolationException("x")))
                .isFalse();
        assertThat(ConsumerErrorHandlers.isDbOutage(new IllegalStateException("x"))).isFalse();
    }
}
