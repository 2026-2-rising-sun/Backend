package com.shoppinglive.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shoppinglive.common.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = OutboxTransactionIntegrationTest.Application.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:outbox_transaction;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.open-in-view=false"
        })
class OutboxTransactionIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(OutboxConfiguration.class)
    static class Application {}

    @Autowired EventOutbox outbox;
    @Autowired OutboxMessageRepository repository;
    @Autowired PlatformTransactionManager manager;

    @MockitoBean(name = "outboxKafkaTemplate")
    KafkaTemplate<String, String> kafka;

    @BeforeEach
    void clearOutbox() {
        repository.deleteAll();
    }

    @DisplayName("호출 트랜잭션이 커밋되면 이벤트를 발행하고 별도 트랜잭션에 발행 표시를 저장한다")
    @Test
    void committedAppendPublishesAndPersistsMarkerOutsideCallerTransaction() {
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        new TransactionTemplate(manager).executeWithoutResult(ignored -> append());
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            var rows = repository.findAll();
                            assertThat(rows).hasSize(1);
                            assertThat(rows.getFirst().getPublishedAt()).isNotNull();
                            assertThat(rows.getFirst().getAttempts()).isEqualTo(1);
                        });
    }

    @DisplayName("전송에 실패하면 커밋된 업무를 유지하고 재시도 상태를 저장한다")
    @Test
    void failedSendPersistsRetryStateWithoutFailingCommittedAppend() {
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(
                        CompletableFuture.failedFuture(
                                new IllegalStateException("broker unavailable")));
        new TransactionTemplate(manager).executeWithoutResult(ignored -> append());
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            var row = repository.findAll().getFirst();
                            assertThat(row.getPublishedAt()).isNull();
                            assertThat(row.getAttempts()).isEqualTo(1);
                            assertThat(row.getLastError()).contains("broker unavailable");
                        });
    }

    @DisplayName("호출 트랜잭션이 롤백되면 Outbox 행을 남기지 않는다")
    @Test
    void rollbackDoesNotLeaveAnOutboxRow() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        transaction -> {
                            append();
                            transaction.setRollbackOnly();
                        });
        assertThat(repository.count()).isZero();
    }

    private void append() {
        outbox.append(
                "test.events",
                EventEnvelope.create(
                        "TestChanged", 1, "test-service", "1", Instant.now(), Map.of("id", 1)));
    }
}
