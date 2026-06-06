package com.ecommerce.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 리프레시/로그아웃 요청 — refresh 토큰 자체가 자격 증명
public record RefreshRequest(
        @NotBlank String refreshToken
) {
}
