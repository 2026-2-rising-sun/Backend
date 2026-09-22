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
                    // ponytail: stub playback URL is static, real URL from GetChannel when aws mode
                    return new IvsPlaybackInfo("https://fcc3ddae59ed.us-west-2.playback.live-video.net/api/video/v1/stub");
                }
            };
        }
        if (!"aws".equals(properties.mode())) {
            throw new IllegalStateException("live.ivs.mode must be aws or stub");
        }
        return new AwsIvsReadinessClient(client.getObject());
    }
}
