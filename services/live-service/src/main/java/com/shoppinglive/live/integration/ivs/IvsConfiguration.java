package com.shoppinglive.live.integration.ivs;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ivs.IvsClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IvsProperties.class)
public class IvsConfiguration {

    /** stub 모드에서 channelArn 에 대응하는 결정적 시청 URL. */
    public static String stubPlaybackUrl(final String channelArn) {
        final int slash = channelArn.lastIndexOf('/');
        return "https://stub.live-video.net/" + channelArn.substring(slash + 1) + ".m3u8";
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "live.ivs.mode", havingValue = "aws", matchIfMissing = true)
    IvsClient ivsClient(final IvsProperties properties) {
        return IvsClient.builder().region(Region.of(properties.region()))
                .overrideConfiguration(builder -> builder.apiCallTimeout(Duration.ofSeconds(3))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2)))
                .build();
    }

    @Bean
    IvsReadinessClient ivsReadinessClient(final IvsProperties properties,
            final org.springframework.beans.factory.ObjectProvider<IvsClient> client, final Environment environment) {
        if ("stub".equals(properties.mode())) {
            if (!environment.acceptsProfiles(Profiles.of("local", "test"))) {
                throw new IllegalStateException("IVS stub requires local or test profile");
            }
            return new IvsReadinessClient() {
                @Override
                public boolean isReady(final String channelArn) {
                    return properties.stubReady();
                }

                @Override
                public IvsPlaybackInfo getPlaybackInfo(final String channelArn) {
                    // 채널마다 다른 URL 을 돌려주어야 #65 의 채널-URL 동일성 검사를 stub 에서도
                    // 실제로 검증할 수 있다. 실제 모드의 URL 은 GetChannel 이 준다.
                    return new IvsPlaybackInfo(stubPlaybackUrl(channelArn));
                }
            };
        }
        if (!"aws".equals(properties.mode())) {
            throw new IllegalStateException("live.ivs.mode must be aws or stub");
        }
        return new AwsIvsReadinessClient(client.getObject());
    }
}
