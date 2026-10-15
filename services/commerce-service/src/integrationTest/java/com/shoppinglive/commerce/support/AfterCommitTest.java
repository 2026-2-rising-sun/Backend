package com.shoppinglive.commerce.support;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

class AfterCommitTest {
    @DisplayName("커밋 후 한 번만 실행하고 롤백 후에는 실행하지 않는다")
    @Test
    void runsOnceAfterCommitAndNeverAfterRollback() {
        var database =
                new EmbeddedDatabaseBuilder()
                        .generateUniqueName(true)
                        .setType(EmbeddedDatabaseType.H2)
                        .build();
        try {
            var tx = new TransactionTemplate(new DataSourceTransactionManager(database));
            var calls = new AtomicInteger();
            tx.executeWithoutResult(
                    status -> {
                        AfterCommit.run(calls::incrementAndGet);
                        assertThat(calls).hasValue(0);
                    });
            assertThat(calls).hasValue(1);
            tx.executeWithoutResult(
                    status -> {
                        AfterCommit.run(calls::incrementAndGet);
                        status.setRollbackOnly();
                    });
            assertThat(calls).hasValue(1);
        } finally {
            database.shutdown();
        }
    }

    @DisplayName("트랜잭션 밖에서는 즉시 실행하고 실패를 전파한다")
    @Test
    void runsImmediatelyOutsideTransactionAndPropagatesFailure() {
        var calls = new AtomicInteger();
        AfterCommit.run(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
        assertThatThrownBy(
                        () ->
                                AfterCommit.run(
                                        () -> {
                                            throw new IllegalStateException("failure");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failure");
    }
}
