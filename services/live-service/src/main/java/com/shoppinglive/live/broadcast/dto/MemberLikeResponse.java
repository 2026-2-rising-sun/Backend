package com.shoppinglive.live.broadcast.dto;

public record MemberLikeResponse(
        long broadcastId, boolean liked, long stateVersion, long total, long version) {}
