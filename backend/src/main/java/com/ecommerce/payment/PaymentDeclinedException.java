package com.ecommerce.payment;

// 카드 거절(한도 초과 등) — GlobalExceptionHandler에서 402 Payment Required로 매핑.
public class PaymentDeclinedException extends RuntimeException {
    public PaymentDeclinedException(String message) {
        super(message);
    }
}
