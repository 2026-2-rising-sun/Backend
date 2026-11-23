package com.shoppinglive.common.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 소비 오류 처리기를 만든다. Payment 환불 소비자({@code RefundConsumerConfiguration})와 같은 규칙이다.
 *
 * <ul>
 *   <li><b>DB 연결 장애</b>: offset을 넘기지 않고 복구될 때까지 재시도한다.
 *   <li><b>역직렬화 실패·계약 위반</b>({@link ContractViolationException} 등): 재시도 없이 {@code
 *       {topic}{dltSuffix}}로 보낸다.
 *   <li><b>그 밖의 실패</b>: 제한 횟수만큼 간격을 늘려 재시도한 뒤 DLT로 보낸다.
 * </ul>
 *
 * <p>DLT partition은 -1로 두어 Kafka가 key로 고른다. DLT 전송이 실패하면 원본 offset을 넘기지 않는다. 원인과 원본 위치는 로그와 DLT
 * 헤더({@code kafka_dlt-original-*})에 남는다. DLT 토픽은 호출하는 쪽이 미리 만들어 둔다.
 */
public final class ConsumerErrorHandlers {
    /** 로그에서 DLT 전송을 찾는 표식. */
    public static final String DLT_MARKER = "EVENT_SENT_TO_DLT";

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandlers.class);
    private static final int MAX_RETRIES = 5;
    private static final Duration MAX_RETRY_INTERVAL = Duration.ofSeconds(30);

    private ConsumerErrorHandlers() {}

    /**
     * @param kafka DLT 전송에 쓸 템플릿. 원문 key·value를 그대로 보낸다(String).
     * @param dltSuffix 원본 토픽 뒤에 붙일 접미사. 예: {@code ".commercecore.dlt"}
     * @param retryInterval 재시도 첫 간격. DB 장애 재시도의 고정 간격이기도 하다.
     * @param contractViolations 계약 위반으로 볼 추가 예외. {@link ContractViolationException}은 항상 포함된다.
     */
    @SafeVarargs
    public static DefaultErrorHandler deadLettering(
            KafkaOperations<?, ?> kafka,
            String dltSuffix,
            Duration retryInterval,
            Class<? extends Exception>... contractViolations) {
        DeadLetterPublishingRecoverer deadLetters =
                new DeadLetterPublishingRecoverer(
                        kafka, (record, e) -> new TopicPartition(record.topic() + dltSuffix, -1));
        // DLT 전송이 실패하면 예외를 던져 원본 offset을 넘기지 않는다. 이게 없으면 보관 실패가 메시지 유실로 이어진다.
        deadLetters.setFailIfSendResultIsError(true);

        ExponentialBackOffWithMaxRetries limited =
                new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        limited.setInitialInterval(retryInterval.toMillis());
        limited.setMaxInterval(Math.max(retryInterval.toMillis(), MAX_RETRY_INTERVAL.toMillis()));

        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        (record, e) -> {
                            // 원문과 예외 메시지는 로그에 남기지 않는다. 원문은 DLT 메시지에만 있다.
                            // 예외 메시지(Jackson 등)에는 입력 조각이 들어갈 수 있으므로 클래스 이름만 쓴다.
                            log.error(
                                    "{} topic={} partition={} offset={} cause={}",
                                    DLT_MARKER,
                                    record.topic(),
                                    record.partition(),
                                    record.offset(),
                                    rootCause(e).getClass().getName());
                            deadLetters.accept(record, e);
                        },
                        limited);
        FixedBackOff unlimited =
                new FixedBackOff(retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS);
        handler.setBackOffFunction((record, e) -> isDbOutage(e) ? unlimited : null);
        // Spring의 기본 즉시 포기 목록을 끄고, 아래 목록만 재시도 없이 보낸다.
        handler.setClassifications(Map.of(), true);
        handler.addNotRetryableExceptions(
                DeserializationException.class, JsonProcessingException.class);
        handler.addNotRetryableExceptions(ContractViolationException.class);
        for (Class<? extends Exception> type : contractViolations) {
            handler.addNotRetryableExceptions(type);
        }
        return handler;
    }

    /**
     * DB에 잠시 닿지 못한 실패인지 본다. listener 예외는 감싸여 오므로 원인 체인을 끝까지 따라간다.
     *
     * <p>{@code SQLException}이나 {@code TransactionException} 전체를 넣지 않는다. 제약 위반이나 SQL 문법 오류는 다시 해도
     * 같으므로 무한 재시도하면 partition이 멈춘다.
     */
    public static boolean isDbOutage(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientConnectionException
                    || cause instanceof JDBCConnectionException) {
                return true;
            }
        }
        return false;
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
