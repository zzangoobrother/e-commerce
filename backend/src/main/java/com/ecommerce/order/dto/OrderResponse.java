package com.ecommerce.order.dto;

import com.ecommerce.order.Order;
import com.ecommerce.order.OrderItem;
import com.ecommerce.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 주문 응답 — 항목·금액은 모두 주문 시점 스냅샷
public record OrderResponse(Long id, OrderStatus status, BigDecimal totalPrice,
                            LocalDateTime createdAt, List<OrderItemResponse> items) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getStatus(), order.getTotalPrice(),
                order.getCreatedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }

    public record OrderItemResponse(Long productId, String productName, BigDecimal price,
                                    int quantity, BigDecimal lineTotal) {

        public static OrderItemResponse from(OrderItem item) {
            BigDecimal lineTotal = item.getPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            return new OrderItemResponse(item.getProductId(), item.getProductName(),
                    item.getPrice(), item.getQuantity(), lineTotal);
        }
    }
}
