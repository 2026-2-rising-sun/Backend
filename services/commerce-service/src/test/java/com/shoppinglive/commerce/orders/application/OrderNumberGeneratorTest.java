package com.shoppinglive.commerce.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderNumberGeneratorTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    void generate_포맷은_OD_날짜8자리_일련번호6자리() {
        OrderNumberGenerator generator = new OrderNumberGenerator(
            Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"), KST));

        assertThat(generator.generate()).matches("OD-20260920-\\d{6}");
    }

    /**
     * 날짜는 UTC 가 아니라 한국 시간 기준이어야 한다. UTC 09-19 23:30 은 KST 로 09-20 08:30
     * 이므로 주문번호의 날짜는 20 일이 되어야 사용자가 보는 주문 일자와 맞는다.
     */
    @Test
    void generate_날짜는_UTC가_아니라_한국시간_기준() {
        OrderNumberGenerator generator = new OrderNumberGenerator(
            Clock.fixed(Instant.parse("2026-09-19T23:30:00Z"), KST));

        assertThat(generator.generate()).startsWith("OD-20260920-");
    }

    /**
     * 일련번호는 6 자리로 zero-padding 된다. 자리수가 들쭉날쭉하면 주문번호 길이가 달라져
     * 화면·로그에서 다루기 번거롭다.
     */
    @Test
    void generate_일련번호는_항상_6자리로_채워진다() {
        OrderNumberGenerator generator = new OrderNumberGenerator(
            Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"), KST));

        for (int i = 0; i < 500; i++) {
            assertThat(generator.generate()).hasSize("OD-20260920-000000".length());
        }
    }

    /**
     * 유일성 자체는 DB UNIQUE 제약이 보장하지만, 생성기가 사실상 같은 값만 뱉으면 재시도가
     * 무한 반복된다. 난수 분포가 살아있는지만 확인한다.
     */
    @Test
    void generate_반복_호출시_사실상_서로_다른_값() {
        OrderNumberGenerator generator = new OrderNumberGenerator(
            Clock.fixed(Instant.parse("2026-09-20T03:00:00Z"), KST));

        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            generated.add(generator.generate());
        }

        assertThat(generated).as("1000 회 생성 중 중복은 극소수").hasSizeGreaterThan(990);
    }
}
