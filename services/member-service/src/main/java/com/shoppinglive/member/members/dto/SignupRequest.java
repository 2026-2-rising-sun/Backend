package com.shoppinglive.member.members.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "비어 있을 수 없습니다.")
                @Email(message = "올바른 이메일 주소 형식이어야 합니다.")
                @Size(max = 254, message = "크기는 {min}~{max} 범위여야 합니다.")
                String email,
        @NotBlank(message = "비어 있을 수 없습니다.")
                @Size(min = 8, max = 72, message = "크기는 {min}~{max} 범위여야 합니다.")
                String password,
        @NotBlank(message = "비어 있을 수 없습니다.") @Size(max = 80, message = "크기는 {min}~{max} 범위여야 합니다.")
                String displayName) {
    public SignupRequest {
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
    }

    @Override
    public String toString() {
        return "SignupRequest[REDACTED]";
    }
}
