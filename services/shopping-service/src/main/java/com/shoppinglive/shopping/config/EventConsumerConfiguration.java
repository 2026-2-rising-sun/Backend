package com.shoppinglive.shopping.config;

import com.shoppinglive.shopping.coupon.event.CouponInfoEventMessage;
import com.shoppinglive.shopping.sales.event.SalesInfoEventMessage;
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
 * Shopping 이 받는 Commerce 이벤트(판매정보, 쿠폰)의 실패 처리. error handler bean 하나가 기본 listener factory 에 붙어 두
 * listener 에 같이 적용된다.
 *
 * <p>실패를 셋으로 나눈다.
 *
 * <ul>
 *   <li><b>DB 연결 끊김·timeout</b>: 잠시 뒤 성공한다. offset 을 넘기지 않고 계속 재시도한다. 여기서 DLT 로 보내면 DB 장애 동안 이벤트가
 *       대량으로 빠진다.
 *   <li><b>형식 오류</b> ({@code InvalidMessageException}): 다시 읽어도 같다. 재시도 없이 바로 DLT 로 보낸다.
 *   <li><b>그 밖의 예외</b> (코드 버그 등): 몇 번 재시도한 뒤 DLT 로 보낸다. 이 메시지 하나 때문에 partition 전체가 멈추지 않게 한다.
 * </ul>
 *
 * <p>DLT 로 보낸 상품·쿠폰은 다음 이벤트가 오면 version 비교로 최신 상태가 된다. 쿠폰은 주기 동기화도 맞춘다. 판매정보는 주기 대조가 없어서, 그 판매정보가
 * 다시 바뀌지 않으면 DLT 로 간 직전 상태가 반영되지 않은 채 남는다. DLT 메시지 수가 0 이 아니면 사람이 확인해야 한다.
 */
@Configuration(proxyBeanMethods = false)
class EventConsumerConfiguration {

    static final String DLT_SUFFIX = ".shopping.dlt";
    static final String DLT_MARKER = "EVENT_SENT_TO_DLT";
    private static final int MAX_RETRIES = 5;
    private static final Duration MAX_RETRY_INTERVAL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(EventConsumerConfiguration.class);

    @Bean
    DefaultKafkaConsumerFactoryCustomizer eventConsumerDefaults() {
        return factory ->
                factory.updateConfigs(
                        Map.of(
                                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                                false,
                                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                                "earliest"));
    }

    @Bean
    DefaultErrorHandler eventErrorHandler(
            KafkaOperations<?, ?> kafka,
            @Value("${shopping.events.consumer.retry-interval:1s}") Duration retryInterval) {
        // 원문 key·value 를 그대로 보낸다 (consumer·producer 모두 String). partition 은 Kafka 가 key 로 고른다.
        // 원본 partition 을 그대로 쓰면 DLT partition 수가 원본보다 적을 때 전송이 계속 실패한다. 원본 위치는 header 에 남는다.
        DeadLetterPublishingRecoverer dlt =
                new DeadLetterPublishingRecoverer(
                        kafka, (record, e) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        // DLT 전송이 실패하면 원본 offset 을 넘기지 않는다. 다시 처리하고 다시 보낸다.
        dlt.setFailIfSendResultIsError(true);

        ExponentialBackOffWithMaxRetries limited =
                new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        limited.setInitialInterval(retryInterval.toMillis());
        limited.setMaxInterval(Math.max(retryInterval.toMillis(), MAX_RETRY_INTERVAL.toMillis()));

        DefaultErrorHandler handler =
                new DefaultErrorHandler(
                        (record, e) -> {
                            log.error(
                                    "{} topic={} partition={} offset={} cause={}",
                                    DLT_MARKER,
                                    record.topic(),
                                    record.partition(),
                                    record.offset(),
                                    rootCause(e).toString());
                            dlt.accept(record, e);
                        },
                        limited);
        FixedBackOff unlimited =
                new FixedBackOff(retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS);
        handler.setBackOffFunction((record, e) -> isDbOutage(e) ? unlimited : null);
        // 기본 즉시 포기 목록(ClassCastException 등)을 끈다. 버그성 예외도 몇 번은 다시 해 본다.
        handler.setClassifications(Map.of(), true);
        handler.addNotRetryableExceptions(
                SalesInfoEventMessage.InvalidMessageException.class,
                CouponInfoEventMessage.InvalidMessageException.class);
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
     *       {@link DataAccessResourceFailureException} 으로 바꿔 준다. (예: 판매정보 저장)
     *   <li>{@code @Transactional} 이 transaction 을 시작하며 연결을 얻지 못함: Spring 이 바꿔 주지 않는다. 실제 체인은
     *       {@code CannotCreateTransactionException →
     *       org.hibernate.exception.JDBCConnectionException →
     *       java.sql.SQLTransientConnectionException} 이다. (예: 쿠폰 저장, 커넥션 풀 고갈·DB 접속 불가)
     * </ul>
     *
     * <p>감수한 비용: 커넥션 풀 고갈은 DB 가 멀쩡해도(예: 앱 안의 연결 누수, 오래 걸리는 transaction) 같은 예외로 온다. 원인이 풀리지 않으면 해당
     * partition 의 소비가 DLT 없이 멈춘다. 이벤트를 버리지 않는 쪽을 택했으므로 consumer lag 로 정지를 알아채야 한다.
     *
     * <p>{@code SQLException} 이나 {@code TransactionException} 전체를 넣지 않는다. 제약 위반·SQL 문법 오류처럼 다시 해도
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
