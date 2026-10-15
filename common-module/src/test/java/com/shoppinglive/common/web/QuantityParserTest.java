package com.shoppinglive.common.web;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuantityParserTest {
    @DisplayName("수량의 최솟값과 최댓값을 허용한다")
    @Test
    void acceptsQuantityBoundaries() {
        assertThat(QuantityParser.parse("1")).isEqualTo(1);
        assertThat(QuantityParser.parse("999999999")).isEqualTo(999999999);
    }

    @DisplayName("수량 누락과 잘못된 문자열은 기존 입력 오류로 거절한다")
    @Test
    void rejectsMissingAndMalformedQuantityWithExistingError() {
        for (String value :
                new String[] {
                    null, "", "0", "01", "-1", "+1", "1.0", " 1", "1 ", "abc", "1000000000"
                }) {
            assertThatThrownBy(() -> QuantityParser.parse(value))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            error -> {
                                assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                                assertThat(error.getMessage())
                                        .isEqualTo("quantity 는 1~999999999 사이의 정수여야 합니다.");
                            });
        }
    }
}
