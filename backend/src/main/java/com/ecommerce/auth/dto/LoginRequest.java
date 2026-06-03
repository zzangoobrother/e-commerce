package com.ecommerce.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 로그인 요청
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
