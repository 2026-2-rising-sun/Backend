package com.shoppinglive.member.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MemberTokenProperties.class, MemberLoginProperties.class})
public class MemberAuthConfiguration { }
