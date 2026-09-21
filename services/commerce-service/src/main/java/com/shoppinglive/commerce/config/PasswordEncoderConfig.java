package com.shoppinglive.commerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비회원 주문 조회 비밀번호 hash·검증을 위한 {@link PasswordEncoder} 빈.
 *
 * <p>Spring Security 의 인증 프레임워크는 안 쓰고 {@code spring-security-crypto} 만 의존.
 * bcrypt 기본 strength (10) 사용.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
