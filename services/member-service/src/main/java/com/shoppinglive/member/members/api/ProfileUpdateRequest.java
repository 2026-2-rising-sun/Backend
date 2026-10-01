package com.shoppinglive.member.members.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(@NotBlank @Size(max = 80) String displayName) {
    public ProfileUpdateRequest { displayName = displayName == null ? null : displayName.strip(); }
}
