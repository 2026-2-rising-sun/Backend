package com.shoppinglive.live.viewer.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ViewerHeartbeat(@NotNull UUID viewerId) {}
