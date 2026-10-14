package com.shoppinglive.common.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 자동 설정에서 {@code @EnableJpaAuditing}이 실행되면 JPA가 너무 일찍 초기화되므로 의도적으로 자동 설정하지 않는다. 각 서비스가
 * {@code @Import}로 명시적으로 가져온다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
