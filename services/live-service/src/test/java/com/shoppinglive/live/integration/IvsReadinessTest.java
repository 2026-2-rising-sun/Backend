package com.shoppinglive.live.integration.ivs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ivs.IvsClient;
import software.amazon.awssdk.services.ivs.model.AccessDeniedException;
import software.amazon.awssdk.services.ivs.model.Channel;
import software.amazon.awssdk.services.ivs.model.ChannelNotBroadcastingException;
import software.amazon.awssdk.services.ivs.model.GetChannelRequest;
import software.amazon.awssdk.services.ivs.model.GetChannelResponse;
import software.amazon.awssdk.services.ivs.model.GetStreamRequest;
import software.amazon.awssdk.services.ivs.model.GetStreamResponse;
import software.amazon.awssdk.services.ivs.model.Stream;
import software.amazon.awssdk.services.ivs.model.StreamHealth;
import software.amazon.awssdk.services.ivs.model.StreamState;

class IvsReadinessTest {
    private static final String ARN = "arn:aws:ivs:ap-northeast-2:123456789012:channel/test";
    private final IvsClient sdk = mock(IvsClient.class);
    private final AwsIvsReadinessClient client = new AwsIvsReadinessClient(sdk);
    private final GetStreamRequest request = GetStreamRequest.builder().channelArn(ARN).build();
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(IvsConfiguration.class);

    @Test
    void liveStreamIsReadyAndOfflineStreamIsNot() {
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder()
                .stream(Stream.builder().state(StreamState.LIVE).health(StreamHealth.HEALTHY).build()).build());
        assertThat(client.isReady(ARN)).isTrue();
        when(sdk.getStream(request)).thenThrow(ChannelNotBroadcastingException.builder().build());
        assertThat(client.isReady(ARN)).isFalse();
    }

    @Test
    void starvingOrUnknownHealthIsNotReady() {
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder()
                .stream(Stream.builder().state(StreamState.LIVE).health(StreamHealth.STARVING).build()).build());
        assertThat(client.isReady(ARN)).isFalse();
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder()
                .stream(Stream.builder().state(StreamState.LIVE).build()).build());
        assertThat(client.isReady(ARN)).isFalse();
    }

    @Test
    void missingStreamIsNotReady() {
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder().build());
        assertThat(client.isReady(ARN)).isFalse();
    }

    @Test
    void networkAndPermissionFailuresAreUnavailableAndSanitized() {
        when(sdk.getStream(request)).thenThrow(SdkClientException.create("private diagnostic"));
        assertThatThrownBy(() -> client.isReady(ARN)).isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.").hasNoCause();
        doThrow(AccessDeniedException.builder().message("private diagnostic").build()).when(sdk).getStream(request);
        assertThatThrownBy(() -> client.isReady(ARN)).isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.").hasNoCause();
    }

    @Test
    void explicitLocalStubRequiresNoSdkAndCanSimulateReady() {
        context.withPropertyValues("spring.profiles.active=local", "live.ivs.mode=stub", "live.ivs.stub-ready=true")
                .run(application -> {
                    assertThat(application).hasNotFailed().doesNotHaveBean(IvsClient.class);
                    assertThat(application.getBean(IvsReadinessClient.class).isReady(ARN)).isTrue();
                });
    }

    @Test
    void testStubDefaultsToNotReady() {
        context.withPropertyValues("spring.profiles.active=test", "live.ivs.mode=stub")
                .run(application -> assertThat(application.getBean(IvsReadinessClient.class).isReady(ARN)).isFalse());
    }

    @Test
    void stubIsRejectedOutsideLocalAndTest() {
        context.withPropertyValues("spring.profiles.active=dev", "live.ivs.mode=stub")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void unknownModeFailsClosed() {
        context.withPropertyValues("live.ivs.mode=typo")
                .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void defaultModeUsesAwsWithoutCallingAwsDuringStartup() {
        context.run(application -> assertThat(application).hasNotFailed()
                .hasSingleBean(IvsClient.class).hasSingleBean(IvsReadinessClient.class));
    }

    @Test
    void getPlaybackUrlReturnsChannelPlaybackUrl() {
        final String playbackUrl = "https://fcc3ddae59ed.us-west-2.playback.live-video.net/api/video/v1/abc123";
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenReturn(GetChannelResponse.builder()
                .channel(Channel.builder().playbackUrl(playbackUrl).build()).build());
        assertThat(client.getPlaybackInfo(ARN)).isEqualTo(new IvsPlaybackInfo(playbackUrl));
    }

    @Test
    void getPlaybackUrlThrowsWhenChannelHasNoPlaybackUrl() {
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenReturn(GetChannelResponse.builder()
                .channel(Channel.builder().build()).build());
        assertThatThrownBy(() -> client.getPlaybackInfo(ARN))
                .isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.");
    }

    @Test
    void getPlaybackUrlThrowsWhenChannelResponseIsNull() {
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenReturn(GetChannelResponse.builder().build());
        assertThatThrownBy(() -> client.getPlaybackInfo(ARN))
                .isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.");
    }

    @Test
    void getPlaybackUrlHandlesGetChannelErrors() {
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenThrow(SdkClientException.create("private diagnostic"));
        assertThatThrownBy(() -> client.getPlaybackInfo(ARN))
                .isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.")
                .hasNoCause();
    }

    @Test
    void stubProvidesPlaybackUrlWithoutSdk() {
        context.withPropertyValues("spring.profiles.active=local", "live.ivs.mode=stub")
                .run(application -> {
                    final IvsReadinessClient stubClient = application.getBean(IvsReadinessClient.class);
                    assertThat(application).doesNotHaveBean(IvsClient.class);
                    assertThat(stubClient.getPlaybackInfo(ARN).playbackUrl())
                            .isEqualTo("https://fcc3ddae59ed.us-west-2.playback.live-video.net/api/video/v1/stub");
                });
    }
}
