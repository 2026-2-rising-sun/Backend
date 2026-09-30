package com.shoppinglive.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/** 내부 전용 SecurityFilterChain에만 직접 추가한다. Servlet Filter/Bean 자동등록 금지. */
public final class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Service-Token";
    private final ServiceCallerTokenValidator validator;
    private final AuthenticationEntryPoint errors;

    public ServiceTokenAuthenticationFilter(ServiceCallerTokenValidator validator, AuthenticationEntryPoint errors) {
        this.validator = validator;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            List<String> values = Collections.list(request.getHeaders(HEADER));
            if (values.size() != 1) {
                throw new BadCredentialsException("A single service credential is required");
            }
            var caller = validator.validate(values.getFirst());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new CallerAuthentication(caller));
            SecurityContextHolder.setContext(context);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            errors.commence(request, response, exception);
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class CallerAuthentication extends AbstractAuthenticationToken {
        private final AuthenticatedServiceCaller caller;

        private CallerAuthentication(AuthenticatedServiceCaller caller) {
            super(List.of(new SimpleGrantedAuthority("SERVICE_" + caller.serviceId())));
            this.caller = caller;
            super.setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public AuthenticatedServiceCaller getPrincipal() { return caller; }
        @Override public String getName() { return caller.serviceId(); }
    }
}
