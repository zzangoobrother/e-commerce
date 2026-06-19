package com.ecommerce.payment;

// 결제 상태 — 동기 결제라 PENDING 없음(승인 실패는 롤백되어 Payment 미생성). 환불은 취소에 동반.
public enum PaymentStatus {
    PAID, REFUNDED
}
