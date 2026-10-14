package com.shoppinglive.live.client.ivs;

public interface IvsReadinessClient {
    boolean isReady(String channelArn);

    IvsPlaybackInfo getPlaybackInfo(String channelArn);
}
