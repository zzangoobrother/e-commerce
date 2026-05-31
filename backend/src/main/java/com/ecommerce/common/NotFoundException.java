package com.ecommerce.common;

// 리소스를 찾지 못했을 때 던지는 예외 (404 매핑)
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
