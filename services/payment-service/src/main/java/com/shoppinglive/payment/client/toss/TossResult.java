package com.shoppinglive.payment.client.toss;

/**
 * 토스 호출 결과. 분류 규칙은 실행 계획 #338 설계서의 "결과 분류" 표를 따른다. {@link #httpStatus()}는 받은 응답의 HTTP 상태이고, 응답을 받지
 * 못했으면(연결 실패·타임아웃·키 미설정) null이다. 분류에는 쓰지 않고 호출 기록(pg_call)에만 남긴다.
 */
public sealed interface TossResult {
    Integer httpStatus();

    /** 토스가 처리했고 200 본문이 호출별 기대 상태다. */
    record Success(TossPayment payment) implements TossResult {
        @Override
        public Integer httpStatus() {
            return 200;
        }
    }

    /** 토스가 처리하고 오류 코드로 거절했다. */
    record Declined(String code, String message, Integer httpStatus) implements TossResult {
        public Declined(String code, String message) {
            this(code, message, null);
        }
    }

    /** 토스가 요청을 처리하지 않았다. 다시 보내도 돈이 두 번 움직이지 않는다. */
    record NotSent(String reason, Integer httpStatus) implements TossResult {
        public NotSent(String reason) {
            this(reason, null);
        }
    }

    /** 처리 여부를 모른다. 조회로 확정한다. */
    record Unknown(String reason, Integer httpStatus) implements TossResult {
        public Unknown(String reason) {
            this(reason, null);
        }
    }
}
