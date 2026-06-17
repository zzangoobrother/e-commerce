package com.ecommerce.order;

// 주문 상태 — 결제 없이 주문 즉시 확정(ORDERED) 후 배송 진행.
// 전이: ORDERED → SHIPPING → DELIVERED (어드민), ORDERED → CANCELLED (고객·어드민, 재고 복원).
// DELIVERED·CANCELLED는 종료 상태. 결제 상태는 다음 사이클의 책임.
public enum OrderStatus {
    ORDERED, SHIPPING, DELIVERED, CANCELLED
}
