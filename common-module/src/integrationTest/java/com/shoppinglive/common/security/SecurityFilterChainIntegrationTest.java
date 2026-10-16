package com.shoppinglive.common.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.common.security.test.StubAccessSessionVerifier;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Bearer 헤더에 실제 서명 JWT를 넣는다. security-test의 jwt() 인증 우회는 사용하지 않는다. */
@SpringJUnitConfig(SecurityFilterChainIntegrationTest.Config.class)
@WebAppConfiguration
class SecurityFilterChainIntegrationTest {
    private static final JwtTestTokens TOKENS = new JwtTestTokens();
    private static final String SHOPPING_KEY = "test-shopping-credential-32-characters-minimum";
    private static final String COMMERCE_KEY = "test-commerce-credential-32-characters-minimum";
    @Autowired WebApplicationContext context;
    @Autowired StubAccessSessionVerifier sessions;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessions.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @DisplayName("공개 경로는 익명 접근을 허용하되 잘못된 인증 정보는 거절한다")
    @Test
    void publicEndpointAllowsAnonymousAndInvalidCredentialsStillFail() throws Exception {
        mvc.perform(get("/public")).andExpect(status().isOk());
        mvc.perform(get("/public").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @DisplayName("안전하지 않은 경로는 인증 체인에 진입하기 전에 방화벽에서 거절한다")
    @ParameterizedTest
    @ValueSource(
            strings = {"/public//extra", "/seller%2Fextra", "/seller%2fextra", "/seller%252Fextra"})
    void firewallStillRejectsUnsafePathsBeforeAnyAuthenticationChain(String path) throws Exception {
        mvc.perform(get(URI.create(path)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get(URI.create(path)).header("Authorization", sellerBearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @DisplayName("실제 Bearer 서명을 검증하고 도메인 사용자 정보만 노출한다")
    @Test
    void verifiesRealBearerSignatureAndExposesOnlyDomainPrincipal() throws Exception {
        mvc.perform(get("/member").header("Authorization", userBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(JwtTestTokens.MEMBER_A))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(
                        get("/member")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + new JwtTestTokens()
                                                        .token(
                                                                JwtTestTokens.MEMBER_A,
                                                                Set.of("USER"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @DisplayName("세션이 폐기되면 이미 발급된 토큰도 다음 요청부터 거절한다")
    @Test
    void revokedSessionRejectsAnAlreadyIssuedTokenOnItsNextRequest() throws Exception {
        String bearer = userBearer();
        mvc.perform(get("/member").header("Authorization", bearer)).andExpect(status().isOk());
        sessions.revoke();
        mvc.perform(get("/member").header("Authorization", bearer))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @DisplayName("인증 기관 장애는 503이며 익명 공개 요청과 서비스 호출에는 영향을 주지 않는다")
    @Test
    void authorityOutageIs503WhileAnonymousPublicAndServiceCallersStayIndependent()
            throws Exception {
        sessions.fail();
        mvc.perform(get("/member").header("Authorization", userBearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/public").header("Authorization", userBearer()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/public")).andExpect(status().isOk());
        mvc.perform(get("/member")).andExpect(status().isUnauthorized());
        mvc.perform(get("/member").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sales").header("X-Service-Token", SHOPPING_KEY))
                .andExpect(status().isOk());
    }

    @DisplayName("토큰 누락은 401이고 역할 부족은 공통 오류 형식의 403이다")
    @Test
    void missingTokenReturns401AndInsufficientRoleReturns403WithCommonEnvelope() throws Exception {
        mvc.perform(get("/member"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/seller").header("Authorization", userBearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/seller").header("Authorization", sellerBearer()))
                .andExpect(status().isOk());
    }

    @DisplayName("서비스 키와 사용자 토큰은 서로의 인증 주체로 사용할 수 없다")
    @Test
    void serviceKeyCannotBecomeMemberOrSellerAndUserTokenCannotBecomeCaller() throws Exception {
        mvc.perform(get("/member").header("X-Service-Token", SHOPPING_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/seller").header("X-Service-Token", SHOPPING_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sales").header("Authorization", sellerBearer()))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("서비스 키로 호출자를 식별하고 도메인별 허용 작업을 적용한다")
    @Test
    void serviceIdentityComesFromKeyAndDomainOperationAllowlistIsEnforced() throws Exception {
        mvc.perform(
                        get("/internal/sales")
                                .header("X-Service-Token", SHOPPING_KEY)
                                .header("X-Service-Caller", "seller"))
                .andExpect(status().isOk())
                .andExpect(content().string("shopping"));
        mvc.perform(get("/internal/sales").header("X-Service-Token", COMMERCE_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/internal/unknown").header("X-Service-Token", SHOPPING_KEY))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/sales").header("X-Service-Token", SHOPPING_KEY, COMMERCE_KEY))
                .andExpect(status().isUnauthorized());
    }

    private String userBearer() {
        return "Bearer " + TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER"));
    }

    private String sellerBearer() {
        return "Bearer " + TOKENS.token(JwtTestTokens.SELLER, Set.of("SELLER"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class Config {
        @Bean
        StubAccessSessionVerifier sessions() {
            return new StubAccessSessionVerifier();
        }

        @Bean
        Endpoints endpoints() {
            return new Endpoints();
        }

        @Bean
        JsonSecurityErrorHandler errors() {
            return new JsonSecurityErrorHandler(new ObjectMapper());
        }

        @Bean
        @Order(1)
        SecurityFilterChain internal(HttpSecurity http, JsonSecurityErrorHandler errors)
                throws Exception {
            return http.securityMatcher("/internal/**")
                    .csrf(csrf -> csrf.disable())
                    .sessionManagement(
                            session ->
                                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(
                            exceptions ->
                                    exceptions
                                            .authenticationEntryPoint(errors)
                                            .accessDeniedHandler(errors))
                    .authorizeHttpRequests(
                            auth ->
                                    auth.requestMatchers("/internal/sales")
                                            .hasAuthority("SERVICE_shopping")
                                            .anyRequest()
                                            .denyAll())
                    .addFilterBefore(
                            new ServiceTokenAuthenticationFilter(
                                    new ServiceCallerTokenValidator(
                                            Map.of(
                                                    "shopping",
                                                    SHOPPING_KEY,
                                                    "commerce",
                                                    COMMERCE_KEY)),
                                    errors),
                            AnonymousAuthenticationFilter.class)
                    .build();
        }

        @Bean
        @Order(2)
        SecurityFilterChain members(
                HttpSecurity http,
                JsonSecurityErrorHandler errors,
                StubAccessSessionVerifier sessions)
                throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .sessionManagement(
                            session ->
                                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(
                            exceptions ->
                                    exceptions
                                            .authenticationEntryPoint(errors)
                                            .accessDeniedHandler(errors))
                    .authorizeHttpRequests(
                            auth ->
                                    auth.requestMatchers("/public")
                                            .permitAll()
                                            .requestMatchers("/member")
                                            .authenticated()
                                            .requestMatchers("/seller")
                                            .hasRole("SELLER")
                                            .anyRequest()
                                            .denyAll())
                    .oauth2ResourceServer(
                            oauth ->
                                    oauth.jwt(
                                                    jwt ->
                                                            jwt.decoder(
                                                                            MemberJwtDecoderFactory
                                                                                    .create(
                                                                                            TOKENS
                                                                                                    .publicJwkSet(),
                                                                                            JwtTestTokens
                                                                                                    .ISSUER,
                                                                                            JwtTestTokens
                                                                                                    .AUDIENCE))
                                                                    .jwtAuthenticationConverter(
                                                                            new MemberJwtAuthenticationConverter(
                                                                                    sessions)))
                                            .authenticationEntryPoint(errors)
                                            .accessDeniedHandler(errors)
                                            .withObjectPostProcessor(errors.bearerFailureHandler()))
                    .build();
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/public")
        String publicData() {
            return "public";
        }

        @GetMapping("/member")
        AuthenticatedUser member(@AuthenticationPrincipal AuthenticatedUser user) {
            return user;
        }

        @GetMapping("/seller")
        String seller() {
            return "seller";
        }

        @GetMapping("/internal/sales")
        String caller(@AuthenticationPrincipal AuthenticatedServiceCaller caller) {
            return caller.serviceId();
        }
    }
}
