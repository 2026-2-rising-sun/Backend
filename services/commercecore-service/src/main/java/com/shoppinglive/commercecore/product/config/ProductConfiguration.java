package com.shoppinglive.commercecore.product.config;

import com.shoppinglive.commercecore.storage.ImageStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageStorageProperties.class)
public class ProductConfiguration {}
