package com.shoppinglive.live.client.ivs;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.GetChannelRequest;
import software.amazon.awssdk.services.ivs.model.GetChannelResponse;
import software.amazon.awssdk.services.ivs.model.GetStreamRequest;
import software.amazon.awssdk.services.ivs.model.GetStreamResponse;
import software.amazon.awssdk.services.ivs.model.StreamHealth;
import software.amazon.awssdk.services.ivs.model.StreamState;

public final class AwsIvsReadinessClient implements IvsReadinessClient {
    private final IvsClient client;

    public AwsIvsReadinessClient(IvsClient client) {
        this.client = client;
    }

    @Override
    public boolean isReady(final String channelArn) {
        try {
            final GetStreamResponse response =
                    client.getStream(GetStreamRequest.builder().channelArn(channelArn).build());
            return response.stream() != null
                    && response.stream().state() == StreamState.LIVE
                    && response.stream().health() == StreamHealth.HEALTHY;
        } catch (final ChannelNotBroadcastingException exception) {
            return false;
        } catch (final SdkException exception) {
            // SDK 진단 정보에는 연결 상세가 포함될 수 있으므로 안정적인 애플리케이션 오류만 노출한다.
            throw new IvsUnavailableException();
        }
    }

    @Override
    public IvsPlaybackInfo getPlaybackInfo(final String channelArn) {
        try {
            final GetChannelResponse response =
                    client.getChannel(GetChannelRequest.builder().arn(channelArn).build());
            final String playbackUrl =
                    response.channel() != null ? response.channel().playbackUrl() : null;
            if (playbackUrl == null || playbackUrl.isBlank()) {
                throw new IvsUnavailableException();
            }
            return new IvsPlaybackInfo(playbackUrl);
        } catch (final SdkException exception) {
            // SDK 진단 정보에는 연결 상세가 포함될 수 있으므로 안정적인 애플리케이션 오류만 노출한다.
            throw new IvsUnavailableException();
        }
    }
}
