package com.shoppinglive.commerce.support;

import static org.assertj.core.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

class AfterCommitTest {
    @Test
    void 커밋후에만_한번_실행하고_롤백에서는_실행하지_않는다() {
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

    @Test
    void 트랜잭션_밖에서는_즉시_실행하고_실패를_전파한다() {
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
