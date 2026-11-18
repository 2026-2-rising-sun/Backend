package com.shoppinglive.payment.payment.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** CREATED 만료 설정(#349). */
@ConfigurationProperties("payment.expiry")
public record ExpiryProperties(
        @DefaultValue("1m") Duration interval, @DefaultValue("100") int batchSize) {}
