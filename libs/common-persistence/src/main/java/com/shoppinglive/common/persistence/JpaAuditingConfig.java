package com.shoppinglive.common.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Import this from a service configuration once the service has a real DataSource.
 * It is deliberately not auto-configured: enabling JPA auditing in a service without a
 * DataSource would break application startup.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
