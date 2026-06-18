package com.ecommerce.order.dto;

import com.ecommerce.order.Order;
import com.ecommerce.order.OrderStatus;
import com.ecommerce.order.dto.OrderResponse.OrderItemResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 어드민 주문 응답 — 고객 이메일을 포함(어드민은 주문 주체를 식별해야 한다). 항목 응답은 고객용과 재사용.
public record AdminOrderResponse(Long id, String customerEmail, OrderStatus status,
                                 BigDecimal totalPrice, LocalDateTime createdAt,
                                 List<OrderItemResponse> items) {

    public static AdminOrderResponse from(Order order, String customerEmail) {
        return new AdminOrderResponse(order.getId(), customerEmail, order.getStatus(),
                order.getTotalPrice(), order.getCreatedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
