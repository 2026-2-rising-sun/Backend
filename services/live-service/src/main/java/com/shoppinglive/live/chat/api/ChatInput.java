package com.shoppinglive.live.chat.api;

import jakarta.validation.constraints.NotNull;

/** 길이는 앞뒤 공백을 제거한 뒤 code point 로 세므로 서비스에서 검사한다. */
public record ChatInput(@NotNull(message = "content는 필수입니다.") String content) {
}
