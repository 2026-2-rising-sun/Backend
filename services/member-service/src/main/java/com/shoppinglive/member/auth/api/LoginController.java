package com.shoppinglive.member.auth.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.auth.application.MemberLoginService;
import com.shoppinglive.member.auth.application.TokenPair;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {
    private final MemberLoginService login;
    public LoginController(MemberLoginService login) { this.login = login; }

    @PostMapping(value = "/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "password는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        return ApiResponse.ok(login.login(request.email(), request.password()));
    }
}
