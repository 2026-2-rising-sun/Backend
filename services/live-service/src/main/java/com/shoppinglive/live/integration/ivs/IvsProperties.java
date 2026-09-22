package com.shoppinglive.live.integration.ivs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("live.ivs")
public record IvsProperties(
        @DefaultValue("aws") String mode,
        @DefaultValue("ap-northeast-2") String region,
        @DefaultValue("false") boolean stubReady) {
}
