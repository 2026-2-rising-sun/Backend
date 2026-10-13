package com.shoppinglive.common.outbox;

/**
 * {@link EventOutbox#append} 가 호출되면 발행하는 애플리케이션 이벤트. 호출자 트랜잭션이 커밋되면
 * {@link OutboxRelay} 의 {@code @TransactionalEventListener(AFTER_COMMIT)} 가 받아 즉시 drain
 * 한다. 폴링은 fast-path 가 실패한 행만 다시 긁는 안전망 역할이다.
 */
public record OutboxCommittedEvent() {
    public static final OutboxCommittedEvent INSTANCE = new OutboxCommittedEvent();
}
