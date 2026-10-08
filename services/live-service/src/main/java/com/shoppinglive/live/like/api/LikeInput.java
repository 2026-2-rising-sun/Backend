package com.shoppinglive.live.like.api;

import jakarta.validation.constraints.NotNull;

public record LikeInput(@NotNull Boolean liked) {}
