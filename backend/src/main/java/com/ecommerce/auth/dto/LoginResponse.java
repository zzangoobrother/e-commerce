package com.ecommerce.auth.dto;

import java.time.Instant;

// 로그인 응답 — JWT 토큰과 만료 시각
public record LoginResponse(
        String token,
        Instant expiresAt
) {
}
