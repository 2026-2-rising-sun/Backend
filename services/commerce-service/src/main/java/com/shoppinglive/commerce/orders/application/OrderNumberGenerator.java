package com.shoppinglive.commerce.orders.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 주문번호 생성기 (주문 2).
 *
 * <p>포맷은 {@code OD-yyyyMMdd-NNNNNN} (예: {@code OD-20260920-000123}). 날짜는 한국 시간
 * 기준이라 국내 사용자가 보는 주문 일자와 번호의 날짜가 어긋나지 않는다.
 *
 * <p><b>왜 DB 시퀀스가 아닌 난수인가:</b> 주문번호는 {@code orders.id} 와 달리 외부에 노출되는
 * 식별자다. 연속된 값이면 주문번호를 하나 알 때 이웃 주문번호를 유추해 조회를 시도할 수 있다
 * (조회 비밀번호가 막아주지만 존재 여부 자체가 새어나간다). 뒤 6 자리를 난수로 두어 열거를
 * 어렵게 만든다. 하루 단위로 공간이 초기화되므로 100 만 개 중 선택이다.
 *
 * <p><b>충돌 처리:</b> 난수라 같은 날 같은 값이 나올 수 있다. 최종 유일성은
 * {@code uk_orders_order_number} UNIQUE 제약이 보장하고, 호출자인 주문 생성 유스케이스가
 * 제약 위반을 잡아 재생성·재시도한다. 이 클래스는 유일성을 약속하지 않는다.
 */
@Component
public class OrderNumberGenerator {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PREFIX = "OD";
    private static final int SEQUENCE_BOUND = 1_000_000;

    private final Clock clock;

    public OrderNumberGenerator() {
        this(Clock.system(KST));
    }

    /**
     * 테스트에서 날짜를 고정하기 위한 생성자.
     */
    OrderNumberGenerator(Clock clock) {
        this.clock = clock;
    }

    /**
     * 새 주문번호를 만든다. 호출마다 다른 값을 돌려주지만 유일성은 보장하지 않는다
     * (클래스 주석의 충돌 처리 참고).
     */
    public String generate() {
        String date = LocalDate.now(clock).format(DATE_PART);
        int sequence = ThreadLocalRandom.current().nextInt(SEQUENCE_BOUND);
        return "%s-%s-%06d".formatted(PREFIX, date, sequence);
    }
}
