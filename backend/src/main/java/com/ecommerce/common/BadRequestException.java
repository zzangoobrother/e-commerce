package com.ecommerce.common;

// 도메인 검증 실패(재고 초과·미판매 상품 등) 시 던지는 예외 (400 매핑)
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
