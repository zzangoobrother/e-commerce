package com.ecommerce.common;

// 리소스 충돌(이메일 중복 등) 시 던지는 예외 (409 매핑)
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
