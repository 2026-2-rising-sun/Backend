package com.shoppinglive.notification.event;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 알림이 받는 이벤트(방송, 판매정보)의 실패 처리. error handler bean 하나가 기본 listener factory 에 붙어 모든 listener 에 같이
 * 적용된다.
 *
 * <p>Shopping 의 {@code EventConsumerConfiguration} 과 같은 방식으로 맞췄다. 같은 레포에서 같은 문제를 다르게 풀면 사고가 났을 때 어느
 * 쪽 규칙인지 매번 확인해야 한다. 두 작업이 다 들어가면 공용 모듈로 옮기는 게 맞다.
 *
 * <p>실패를 셋으로 나눈다.
 *
 * <ul>
 *   <li><b>DB 연결 끊김과 timeout</b>: 잠시 뒤 성공한다. offset 을 넘기지 않고 계속 재시도한다. 여기서 보관함으로 보내면 DB 장애 동안의 알림이
 *       대량으로 빠진다.
 *   <li><b>형식 오류</b> ({@link InvalidEventMessageException}): 다시 읽어도 같다. 재시도 없이 바로 보관함으로 보낸다.
 *   <li><b>그 밖의 예외</b> (코드 버그 등): 몇 번 재시도한 뒤 보관함으로 보낸다. 이 메시지 하나 때문에 partition 전체가 멈추지 않게 한다.
 * </ul>
 *
 * <p>Spring 기본값은 {@code FixedBackOff(0, 9)} 로 <b>간격 없이 열 번 연타한 뒤 메시지를 버린다.</b> 그대로 두면 DB 가 잠깐 죽은
 * 동안의 알림이 사라진다.
 *
 * <p>보관함에 쌓인 메시지를 자동으로 다시 처리하지는 않는다. 사람이 원인을 보고 버리거나 되돌린다. 쌓인 걸 알아채는 장치(슬랙 통지 등)는 아직 없고, 지금은
 * <b>유실되지 않게 모아 두는 것</b>까지가 범위다. 보관함 메시지 수가 0 이 아니면 사람이 확인해야 한다.
 */
@Configuration(proxyBeanMethods = false)
class NotificationConsumerConfiguration {

    /**
     * 원본 토픽 이름 뒤에 붙는다. <b>서비스 이름이 들어가는 게 중요하다</b> — {@code commerce.sales-info.v1} 은 Shopping 도
     * 구독하므로, 서비스 이름이 없으면 두 서비스의 실패 메시지가 한 토픽에 섞여 누가 되돌려야 하는지 알 수 없다.
     */
    static final String DLT_SUFFIX = ".notification.dlt";

    static final String DLT_MARKER = "EVENT_SENT_TO_DLT";

    private static final int MAX_RETRIES = 5;
    private static final Duration MAX_RETRY_INTERVAL = Duration.ofSeconds(30);

    private static final Logger log =
            LoggerFactory.getLogger(NotificationConsumerConfiguration.class);

    @Bean
    DefaultKafkaConsumerFactoryCustomizer eventConsumerDefaults() {
        return factory ->
                factory.updateConfigs(
                        Map.of(
                                // 처리가 성공해야 "읽었다" 고 기록한다. 그래서 던지면 같은 메시지를 다시 받는다.
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                // 구독을 시작하기 전에 쌓인 이벤트도 받는다.
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest"));
    }

    @Bean
    DefaultErrorHandler eventErrorHandler(
            KafkaOperations<?, ?> kafka,
            @Value("${notification.events.consumer.retry-interval:1s}") Duration retryInterval) {
        // 원문 key 와 value 를 그대로 보낸다 (consumer 와 producer 모두 String). partition 은 Kafka 가 key 로 고른다.
        // 원본 partition 을 그대로 쓰면 보관함 partition 수가 원본보다 적을 때 전송이 계속 실패한다. 원본 위치는 header 에 남는다.
        DeadLetterPublishingRecoverer deadLetters =
                new DeadLetterPublishingRecoverer(
                        kafka, (record, e) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        // 보관함 전송이 실패하면 원본 offset 을 넘기지 않는다. 다시 처리하고 다시 보낸다.
        // 이게 없으면 "보관함에 넣기" 도 실패했을 때 메시지가 조용히 사라진다.
        deadLetters.setFailIfSendResultIsError(true);

        ExponentialBackOffWithMaxRetries limited =
                new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        limited.setInitialInterval(retryInterval.toMillis());
        limited.setMaxInterval(Math.max(retryInterval.toMillis(), MAX_RETRY_INTERVAL.toMillis()));

        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        (record, e) -> {
                            log.error(
                                    "{} topic={} partition={} offset={} cause={} raw={}",
                                    DLT_MARKER,
                                    record.topic(),
                                    record.partition(),
                                    record.offset(),
                                    rootCause(e).toString(),
                                    record.value());
                            deadLetters.accept(record, e);
                        },
                        limited);
        FixedBackOff unlimited =
                new FixedBackOff(retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS);
        handler.setBackOffFunction((record, e) -> isDbOutage(e) ? unlimited : null);
        // 기본 즉시 포기 목록(ClassCastException 등)을 끈다. 버그성 예외도 몇 번은 다시 해 본다.
        handler.setClassifications(Map.of(), true);
        handler.addNotRetryableExceptions(InvalidEventMessageException.class);
        return handler;
    }

    /**
     * DB 에 잠시 닿지 못한 실패인지 본다. 이 경우만 offset 을 넘기지 않고 끝없이 재시도한다.
     *
     * <p>listener 예외는 {@code ListenerExecutionFailedException} 으로 감싸여 오므로 원인을 끝까지 따라 내려간다. 같은 장애라도
     * 어디서 났는지에 따라 예외 모양이 다르다.
     *
     * <ul>
     *   <li>{@code JdbcTemplate} 으로 SQL 을 실행하다 실패: Spring 이 {@link TransientDataAccessException} 이나
     *       {@link DataAccessResourceFailureException} 으로 바꿔 준다. (예: 사본 갱신, 알림 생성)
     *   <li>{@code @Transactional} 이 transaction 을 시작하며 연결을 얻지 못함: Spring 이 바꿔 주지 않는다. 실제 체인은
     *       {@code CannotCreateTransactionException →
     *       org.hibernate.exception.JDBCConnectionException →
     *       java.sql.SQLTransientConnectionException} 이다.
     * </ul>
     *
     * <p>감수한 비용: 커넥션 풀 고갈은 DB 가 멀쩡해도(앱 안의 연결 누수, 오래 걸리는 transaction) 같은 예외로 온다. 원인이 풀리지 않으면 해당
     * partition 의 소비가 보관함 없이 멈춘다. 이벤트를 버리지 않는 쪽을 택했으므로 consumer lag 로 정지를 알아채야 한다.
     *
     * <p>{@code SQLException} 이나 {@code TransactionException} 전체를 넣지 않는다. 제약 위반이나 SQL 문법 오류처럼 다시 해도
     * 같은 실패까지 무한 재시도하게 되어 partition 이 멈춘다.
     */
    static boolean isDbOutage(Throwable e) {
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
