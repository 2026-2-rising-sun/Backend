package com.shoppinglive.member.members.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @NotBlank(message = "비어 있을 수 없습니다.") @Size(max = 80, message = "크기는 {min}~{max} 범위여야 합니다.")
                String displayName) {
    public ProfileUpdateRequest {
        displayName = displayName == null ? null : displayName.strip();
    }
}
