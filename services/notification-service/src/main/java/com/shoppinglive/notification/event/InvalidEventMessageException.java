package com.shoppinglive.notification.event;

/**
 * 받은 이벤트를 해석하거나 검증하지 못했다. <b>다시 읽어도 같은 결과인 실패</b>다.
 *
 * <p>깨진 JSON, 계약과 다른 발행 서비스, 맞지 않는 메시지 key 같은 것들이다. 재시도하면 그 메시지에 막혀 뒤의 모든 알림이 멈춘다.
 *
 * <p>구독마다 따로 두지 않고 하나로 모은 이유는, 실패 처리 설정이 이 예외 하나만 "재시도하지 말고 보관함으로" 로 분류하면 되기 때문이다. 구독이 늘어도 설정은
 * 그대로다.
 */
public class InvalidEventMessageException extends RuntimeException {

    public InvalidEventMessageException(String reason) {
        super(reason);
    }
}
