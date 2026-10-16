package com.shoppinglive.live.viewer.dto;

import java.time.Instant;

public record ViewerTotal(
        long broadcastId, long total, Instant measuredAt, int expiresAfterSeconds) {}
