package com.shoppinglive.notification.subscription.dto;

import java.time.Instant;

public record AlertSubscription(long targetId, Instant createdAt) {}
