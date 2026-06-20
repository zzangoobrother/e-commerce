package com.ecommerce.order.dto;

import com.ecommerce.order.Order;
import com.ecommerce.order.OrderStatus;
import com.ecommerce.order.dto.OrderResponse.OrderItemResponse;
import com.ecommerce.payment.dto.PaymentSummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 어드민 주문 응답 — 고객 이메일·결제 요약 포함. 항목 응답은 고객용과 재사용.
public record AdminOrderResponse(Long id, String customerEmail, OrderStatus status,
                                 BigDecimal totalPrice, LocalDateTime createdAt,
                                 List<OrderItemResponse> items, PaymentSummary payment) {

    public static AdminOrderResponse from(Order order, String customerEmail, PaymentSummary payment) {
        return new AdminOrderResponse(order.getId(), customerEmail, order.getStatus(),
                order.getTotalPrice(), order.getCreatedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList(), payment);
    }
}
