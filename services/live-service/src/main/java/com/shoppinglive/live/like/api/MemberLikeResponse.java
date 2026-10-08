package com.shoppinglive.live.like.api;

public record MemberLikeResponse(long broadcastId, boolean liked, long stateVersion, long total, long version) {}
