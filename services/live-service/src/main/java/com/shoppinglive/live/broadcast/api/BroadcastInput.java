package com.shoppinglive.live.broadcast.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record BroadcastInput(
    @NotNull(message = "제목은 필수입니다.")
    @Size(min = 1, max = 100, message = "제목은 1~100자여야 합니다.")
    String title,

    @NotNull(message = "예정 시각은 필수입니다.")
    Instant scheduledAt,

    @NotNull(message = "채널 ARN은 필수입니다.")
    @Size(min = 1, max = 255, message = "채널 ARN은 최대 255자입니다.")
    String channelArn,

    @NotNull(message = "재생 URL은 필수입니다.")
    @Size(min = 1, max = 2048, message = "재생 URL은 최대 2048자입니다.")
    String playbackUrl
) {
}
