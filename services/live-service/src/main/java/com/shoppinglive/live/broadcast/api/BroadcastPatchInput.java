package com.shoppinglive.live.broadcast.api;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Optional;

/**
 * 생략된 필드는 null(=기존 값 유지), 본문에 명시적으로 null 을 준 필드는
 * Optional.empty() 로 역직렬화되어 400 으로 거절된다.
 */
public record BroadcastPatchInput(
    Optional<@Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.") String> title,

    Optional<Instant> scheduledAt,

    Optional<@Size(min = 1, max = 255, message = "채널 ARN은 최대 255자입니다.") String> channelArn,

    Optional<@Size(min = 1, max = 2048, message = "재생 URL은 최대 2048자입니다.") String> playbackUrl
) {
}
