package com.shoppinglive.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/** MVC advice 전에 발생하는 인증·인가 및 방화벽 오류도 같은 API 봉투로 보낸다. */
public final class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler, RequestRejectedHandler {
    private final ObjectMapper mapper;

    public JsonSecurityErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
        throws IOException {
        if (exception instanceof AccessSessionUnavailableException) {
            write(response, ErrorCode.SERVICE_UNAVAILABLE);
            return;
        }
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, ErrorCode.UNAUTHORIZED);
    }

    /** Spring's default failure handler rethrows AuthenticationServiceException before this entry point. */
    public ObjectPostProcessor<BearerTokenAuthenticationFilter> bearerFailureHandler() {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                filter.setAuthenticationFailureHandler((request, response, exception) -> {
                    if (exception instanceof AuthenticationServiceException
                        && !(exception instanceof AccessSessionUnavailableException)) throw exception;
                    commence(request, response, exception);
                });
                return filter;
            }
        };
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException exception) throws IOException {
        write(response, ErrorCode.FORBIDDEN);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       RequestRejectedException exception) throws IOException {
        // WebSecurity discovers this handler bean. Keep StrictHttpFirewall's rejection and avoid
        // sendError: its ERROR dispatch can replace the original 400 with an authentication error.
        write(response, ErrorCode.INVALID_REQUEST);
    }

    private void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(),
            ApiResponse.fail(code, code.defaultMessage(), CorrelationId.current()));
    }
}
