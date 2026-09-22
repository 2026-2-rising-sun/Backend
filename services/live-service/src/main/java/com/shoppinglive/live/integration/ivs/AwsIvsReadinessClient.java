package com.shoppinglive.live.integration.ivs;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.GetChannelRequest;
import software.amazon.awssdk.services.ivs.model.GetChannelResponse;
import software.amazon.awssdk.services.ivs.model.GetStreamRequest;
import software.amazon.awssdk.services.ivs.model.GetStreamResponse;
import software.amazon.awssdk.services.ivs.model.StreamState;
import software.amazon.awssdk.services.ivs.model.StreamHealth;

@RequiredArgsConstructor
public final class AwsIvsReadinessClient implements IvsReadinessClient {
    private final IvsClient client;

    @Override
    public boolean isReady(final String channelArn) {
        try {
            final GetStreamResponse response = client.getStream(GetStreamRequest.builder()
                    .channelArn(channelArn).build());
            return response.stream() != null && response.stream().state() == StreamState.LIVE
                    && response.stream().health() == StreamHealth.HEALTHY;
        } catch (final ChannelNotBroadcastingException exception) {
            return false;
        } catch (final SdkException exception) {
            // SDK diagnostics may contain connection details; expose only a stable application error.
            throw new IvsUnavailableException();
        }
    }

    @Override
    public IvsPlaybackInfo getPlaybackInfo(final String channelArn) {
        try {
            final GetChannelResponse response = client.getChannel(GetChannelRequest.builder()
                    .arn(channelArn).build());
            final String playbackUrl = response.channel() != null ? response.channel().playbackUrl() : null;
            if (playbackUrl == null || playbackUrl.isBlank()) {
                throw new IvsUnavailableException();
            }
            return new IvsPlaybackInfo(playbackUrl);
        } catch (final SdkException exception) {
            // SDK diagnostics may contain connection details; expose only a stable application error.
            throw new IvsUnavailableException();
        }
    }
}
