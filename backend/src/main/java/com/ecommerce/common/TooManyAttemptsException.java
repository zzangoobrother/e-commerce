package com.ecommerce.common;

// 로그인 시도 제한 초과 시 던지는 예외 (429 매핑)
public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
