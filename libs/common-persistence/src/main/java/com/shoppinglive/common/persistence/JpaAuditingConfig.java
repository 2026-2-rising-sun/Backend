package com.shoppinglive.common.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Not auto-configured on purpose: {@code @EnableJpaAuditing} forces early JPA bootstrapping when
 * it runs from an auto-configuration. Services pull it in with {@code @Import} instead.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
