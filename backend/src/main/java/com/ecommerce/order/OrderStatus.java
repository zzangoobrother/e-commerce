package com.ecommerce.order;

// 주문 상태 — 결제 없이 주문 즉시 확정(ORDERED). 배송 상태는 어드민 주문 관리 사이클의 책임.
public enum OrderStatus {
    ORDERED, CANCELLED
}
