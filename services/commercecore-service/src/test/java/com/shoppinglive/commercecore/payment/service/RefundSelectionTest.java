package com.shoppinglive.commercecore.payment.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.commercecore.payment.dto.RefundSelection;
import com.shoppinglive.common.core.BusinessException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefundSelectionTest {
    final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "{\"items\":[]}",
                "{\"items\":null}",
                "{\"items\":[{\"orderId\":1,\"quantity\":0}]}",
                "{\"items\":[{\"orderId\":1.0}]}",
                "{\"items\":[{\"orderId\":1,\"quantity\":2147483648}]}",
                "{\"items\":[{\"orderId\":1,\"unknown\":2}]}",
                "{\"items\":[{\"orderId\":1},{\"orderId\":1}]}",
                "{\"items\":[{\"orderId\":1}],\"cartItemIds\":[1]}",
                "{\"cartItemIds\":[1,1]}",
                "{\"items\":[{\"orderId\":1}],\"expectedAmount\":-1}",
                "{\"items\":[{\"orderId\":1}],\"expectedAmount\":1.5}",
                "{\"expectedAmount\":0}"
            })
    @DisplayName("유효하지 않은 항목·수량·금액 본문을 전체 환불로 해석하지 않는다")
    void rejectsInvalidInput(String json) throws Exception {
        var node = mapper.readTree(json);
        assertThatThrownBy(() -> RefundSelection.parse(node)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("본문 생략은 전체 환불이며 항목 순서만 다른 입력은 같은 정규 원문이다")
    void normalizesOnlyArrayOrder() throws Exception {
        assertThat(RefundSelection.parse(null).mode()).isEqualTo("ALL");
        var a =
                RefundSelection.parse(
                        mapper.readTree(
                                "{\"items\":[{\"orderId\":2,\"quantity\":1},{\"orderId\":1}],\"expectedAmount\":0}"));
        var b =
                RefundSelection.parse(
                        mapper.readTree(
                                "{\"expectedAmount\":0,\"items\":[{\"orderId\":1},{\"orderId\":2,\"quantity\":1}]}"));
        assertThat(a).isEqualTo(b);
        assertThat(a.items().getFirst().quantity()).isNull();
    }
}
