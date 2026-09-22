package com.shoppinglive.live.integration.ivs;

public interface IvsReadinessClient {
    boolean isReady(String channelArn);

    IvsPlaybackInfo getPlaybackInfo(String channelArn);
}
