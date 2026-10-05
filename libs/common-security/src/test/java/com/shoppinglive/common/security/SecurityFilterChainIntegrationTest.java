package com.shoppinglive.common.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.security.test.JwtTestTokens;
import com.shoppinglive.common.security.test.StubAccessSessionVerifier;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
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
    void setUp() { sessions.reset(); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test
    void publicEndpointAllowsAnonymousAndInvalidCredentialsStillFail() throws Exception {
        mvc.perform(get("/public")).andExpect(status().isOk());
        mvc.perform(get("/public").header("Authorization", "Bearer invalid"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/public//extra", "/admin%2Fextra", "/admin%2fextra", "/admin%252Fextra"})
    void firewallStillRejectsUnsafePathsBeforeAnyAuthenticationChain(String path) throws Exception {
        mvc.perform(get(URI.create(path))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get(URI.create(path)).header("Authorization", adminBearer()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void verifiesRealBearerSignatureAndExposesOnlyDomainPrincipal() throws Exception {
        mvc.perform(get("/member").header("Authorization", userBearer())).andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value(JwtTestTokens.MEMBER_A))
            .andExpect(jsonPath("$.roles[0]").value("USER"));
        mvc.perform(get("/member").header("Authorization", "Bearer " + new JwtTestTokens()
                .token(JwtTestTokens.MEMBER_A, Set.of("USER"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void revokedSessionRejectsAnAlreadyIssuedTokenOnItsNextRequest() throws Exception {
        String bearer = userBearer();
        mvc.perform(get("/member").header("Authorization", bearer)).andExpect(status().isOk());
        sessions.revoke();
        mvc.perform(get("/member").header("Authorization", bearer)).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void authorityOutageIs503WhileAnonymousPublicAndServiceCallersStayIndependent() throws Exception {
        sessions.fail();
        mvc.perform(get("/member").header("Authorization", userBearer())).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/public").header("Authorization", userBearer())).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/public")).andExpect(status().isOk());
        mvc.perform(get("/member")).andExpect(status().isUnauthorized());
        mvc.perform(get("/member").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sales").header("X-Service-Token", SHOPPING_KEY)).andExpect(status().isOk());
    }

    @Test
    void missingTokenReturns401AndInsufficientRoleReturns403WithCommonEnvelope() throws Exception {
        mvc.perform(get("/member")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mvc.perform(get("/admin").header("Authorization", userBearer())).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/admin").header("Authorization", adminBearer())).andExpect(status().isOk());
    }

    @Test
    void serviceKeyCannotBecomeMemberOrAdministratorAndUserTokenCannotBecomeCaller() throws Exception {
        mvc.perform(get("/member").header("X-Service-Token", SHOPPING_KEY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin").header("X-Service-Token", SHOPPING_KEY)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sales").header("Authorization", adminBearer()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceIdentityComesFromKeyAndDomainOperationAllowlistIsEnforced() throws Exception {
        mvc.perform(get("/internal/sales").header("X-Service-Token", SHOPPING_KEY)
                .header("X-Service-Caller", "admin"))
            .andExpect(status().isOk()).andExpect(content().string("shopping"));
        mvc.perform(get("/internal/sales").header("X-Service-Token", COMMERCE_KEY))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get("/internal/unknown").header("X-Service-Token", SHOPPING_KEY)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/sales").header("X-Service-Token", SHOPPING_KEY, COMMERCE_KEY))
            .andExpect(status().isUnauthorized());
    }

    private String userBearer() { return "Bearer " + TOKENS.token(JwtTestTokens.MEMBER_A, Set.of("USER")); }
    private String adminBearer() { return "Bearer " + TOKENS.token(JwtTestTokens.SELLER, Set.of("SELLER")); }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class Config {
        @Bean StubAccessSessionVerifier sessions() { return new StubAccessSessionVerifier(); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
        @Bean JsonSecurityErrorHandler errors() { return new JsonSecurityErrorHandler(new ObjectMapper()); }

        @Bean @Order(1)
        SecurityFilterChain internal(HttpSecurity http, JsonSecurityErrorHandler errors) throws Exception {
            return http.securityMatcher("/internal/**").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/internal/sales").hasAuthority("SERVICE_shopping")
                    .anyRequest().denyAll())
                .addFilterBefore(new ServiceTokenAuthenticationFilter(new ServiceCallerTokenValidator(
                    Map.of("shopping", SHOPPING_KEY, "commerce", COMMERCE_KEY)), errors), AnonymousAuthenticationFilter.class)
                .build();
        }

        @Bean @Order(2)
        SecurityFilterChain members(HttpSecurity http, JsonSecurityErrorHandler errors, StubAccessSessionVerifier sessions) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/public").permitAll()
                    .requestMatchers("/member").authenticated().requestMatchers("/admin").hasRole("SELLER")
                    .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                    .decoder(MemberJwtDecoderFactory.create(TOKENS.publicJwkSet(), JwtTestTokens.ISSUER, JwtTestTokens.AUDIENCE))
                    .jwtAuthenticationConverter(new MemberJwtAuthenticationConverter(sessions)))
                    .authenticationEntryPoint(errors).accessDeniedHandler(errors)
                    .withObjectPostProcessor(errors.bearerFailureHandler()))
                .build();
        }
    }

    @RestController
    static class Endpoints {
        @GetMapping("/public") String publicData() { return "public"; }
        @GetMapping("/member") AuthenticatedUser member(@AuthenticationPrincipal AuthenticatedUser user) { return user; }
        @GetMapping("/admin") String admin() { return "admin"; }
        @GetMapping("/internal/sales") String caller(@AuthenticationPrincipal AuthenticatedServiceCaller caller) {
            return caller.serviceId();
        }
    }
}
