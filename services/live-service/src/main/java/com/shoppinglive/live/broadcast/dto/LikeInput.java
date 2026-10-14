package com.shoppinglive.live.broadcast.dto;

import jakarta.validation.constraints.NotNull;

public record LikeInput(@NotNull(message = "필수 값입니다.") Boolean liked) {}
