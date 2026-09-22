package com.shoppinglive.live.integration.ivs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
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

@DisplayName("AWS IVS 영상 준비 상태·재생 정보 조회와 stub 프로파일 경계")
class IvsReadinessTest {
    private static final String ARN = "arn:aws:ivs:ap-northeast-2:123456789012:channel/test";
    private final IvsClient sdk = mock(IvsClient.class);
    private final AwsIvsReadinessClient client = new AwsIvsReadinessClient(sdk);
    private final GetStreamRequest request = GetStreamRequest.builder().channelArn(ARN).build();
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(IvsConfiguration.class);

    @DisplayName("LIVE 스트림은 준비 완료, 방송 중이 아니면 미준비로 판정한다")
    @Test
    void liveStreamIsReadyAndOfflineStreamIsNot() {
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder()
                .stream(Stream.builder().state(StreamState.LIVE).health(StreamHealth.HEALTHY).build()).build());
        assertThat(client.isReady(ARN)).isTrue();
        when(sdk.getStream(request)).thenThrow(ChannelNotBroadcastingException.builder().build());
        assertThat(client.isReady(ARN)).isFalse();
    }

    @DisplayName("스트림 품질이 STARVING이거나 알 수 없으면 미준비로 판정한다")
    @Test
    void starvingOrUnknownHealthIsNotReady() {
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder()
                .stream(Stream.builder().state(StreamState.LIVE).health(StreamHealth.STARVING).build()).build());
        assertThat(client.isReady(ARN)).isFalse();
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder()
                .stream(Stream.builder().state(StreamState.LIVE).build()).build());
        assertThat(client.isReady(ARN)).isFalse();
    }

    @DisplayName("스트림 정보가 없으면 미준비로 판정한다")
    @Test
    void missingStreamIsNotReady() {
        when(sdk.getStream(request)).thenReturn(GetStreamResponse.builder().build());
        assertThat(client.isReady(ARN)).isFalse();
    }

    @DisplayName("네트워크·권한 오류는 내부 진단 정보를 지운 채 UNAVAILABLE로 변환한다")
    @Test
    void networkAndPermissionFailuresAreUnavailableAndSanitized() {
        when(sdk.getStream(request)).thenThrow(SdkClientException.create("private diagnostic"));
        assertThatThrownBy(() -> client.isReady(ARN)).isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.").hasNoCause();
        doThrow(AccessDeniedException.builder().message("private diagnostic").build()).when(sdk).getStream(request);
        assertThatThrownBy(() -> client.isReady(ARN)).isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.").hasNoCause();
    }

    @DisplayName("local stub 모드는 SDK 없이 동작하며 준비 완료를 흉내 낼 수 있다")
    @Test
    void explicitLocalStubRequiresNoSdkAndCanSimulateReady() {
        context.withPropertyValues("spring.profiles.active=local", "live.ivs.mode=stub", "live.ivs.stub-ready=true")
                .run(application -> {
                    assertThat(application).hasNotFailed().doesNotHaveBean(IvsClient.class);
                    assertThat(application.getBean(IvsReadinessClient.class).isReady(ARN)).isTrue();
                });
    }

    @DisplayName("test stub 모드는 기본적으로 미준비를 반환한다")
    @Test
    void testStubDefaultsToNotReady() {
        context.withPropertyValues("spring.profiles.active=test", "live.ivs.mode=stub")
                .run(application -> assertThat(application.getBean(IvsReadinessClient.class).isReady(ARN)).isFalse());
    }

    @DisplayName("local·test 이외의 프로파일에서 stub 모드는 기동을 거절한다")
    @Test
    void stubIsRejectedOutsideLocalAndTest() {
        context.withPropertyValues("spring.profiles.active=dev", "live.ivs.mode=stub")
                .run(application -> assertThat(application).hasFailed());
    }

    @DisplayName("알 수 없는 IVS 모드는 기동을 실패시킨다")
    @Test
    void unknownModeFailsClosed() {
        context.withPropertyValues("live.ivs.mode=typo")
                .run(application -> assertThat(application).hasFailed());
    }

    @DisplayName("기본 모드는 기동 중 AWS를 호출하지 않고 AWS client를 구성한다")
    @Test
    void defaultModeUsesAwsWithoutCallingAwsDuringStartup() {
        context.run(application -> assertThat(application).hasNotFailed()
                .hasSingleBean(IvsClient.class).hasSingleBean(IvsReadinessClient.class));
    }

    @DisplayName("채널의 재생 URL을 조회해 반환한다")
    @Test
    void getPlaybackUrlReturnsChannelPlaybackUrl() {
        final String playbackUrl = "https://fcc3ddae59ed.us-west-2.playback.live-video.net/api/video/v1/abc123";
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenReturn(GetChannelResponse.builder()
                .channel(Channel.builder().playbackUrl(playbackUrl).build()).build());
        assertThat(client.getPlaybackInfo(ARN)).isEqualTo(new IvsPlaybackInfo(playbackUrl));
    }

    @DisplayName("채널에 재생 URL이 없으면 UNAVAILABLE로 실패한다")
    @Test
    void getPlaybackUrlThrowsWhenChannelHasNoPlaybackUrl() {
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenReturn(GetChannelResponse.builder()
                .channel(Channel.builder().build()).build());
        assertThatThrownBy(() -> client.getPlaybackInfo(ARN))
                .isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.");
    }

    @DisplayName("채널 응답이 비어 있으면 UNAVAILABLE로 실패한다")
    @Test
    void getPlaybackUrlThrowsWhenChannelResponseIsNull() {
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenReturn(GetChannelResponse.builder().build());
        assertThatThrownBy(() -> client.getPlaybackInfo(ARN))
                .isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.");
    }

    @DisplayName("채널 조회 오류는 진단 정보 없이 UNAVAILABLE로 변환한다")
    @Test
    void getPlaybackUrlHandlesGetChannelErrors() {
        final GetChannelRequest channelRequest = GetChannelRequest.builder().arn(ARN).build();
        when(sdk.getChannel(channelRequest)).thenThrow(SdkClientException.create("private diagnostic"));
        assertThatThrownBy(() -> client.getPlaybackInfo(ARN))
                .isInstanceOf(IvsUnavailableException.class)
                .hasMessage("영상 준비 상태를 확인할 수 없습니다.")
                .hasNoCause();
    }

    @DisplayName("stub 모드는 SDK 없이 고정 재생 URL을 제공한다")
    @Test
    void stubProvidesPlaybackUrlWithoutSdk() {
        context.withPropertyValues("spring.profiles.active=local", "live.ivs.mode=stub")
                .run(application -> {
                    final IvsReadinessClient stubClient = application.getBean(IvsReadinessClient.class);
                    assertThat(application).doesNotHaveBean(IvsClient.class);
                    // stub 은 채널마다 다른 URL 을 돌려주어야 #65 의 채널-URL 동일성 검사를
                    // stub 에서도 실제로 검증할 수 있다.
                    assertThat(stubClient.getPlaybackInfo(ARN).playbackUrl())
                            .isEqualTo(IvsConfiguration.stubPlaybackUrl(ARN))
                            .isNotEqualTo(IvsConfiguration.stubPlaybackUrl("arn:other/x"));
                });
    }
}
