package com.ecommerce.payment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 카드 결제 요청 — 어디에도 영속화하지 않는다(처리 중에만 존재). last4·brand만 Payment에 남는다.
public record CardPaymentRequest(
        @NotBlank String cardNumber,
        @NotNull @Min(1) @Max(12) Integer expiryMonth,
        @NotNull Integer expiryYear,
        @NotBlank String cvc,
        @NotBlank String cardholderName) {
}
