package com.ecommerce.common;

// 인증 실패(로그인 실패 등) 시 던지는 예외 (401 매핑)
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
