package com.shoppinglive.common.web;

import static org.assertj.core.api.Assertions.*;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.junit.jupiter.api.Test;

class QuantityParserTest {
    @Test
    void 수량의_양끝값을_허용한다() {
        assertThat(QuantityParser.parse("1")).isEqualTo(1);
        assertThat(QuantityParser.parse("999999999")).isEqualTo(999999999);
    }

    @Test
    void 누락과_잘못된_문자열은_기존_오류로_거절한다() {
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
