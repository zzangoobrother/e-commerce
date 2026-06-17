package com.ecommerce.order.dto;

import java.util.List;

// 주문 생성 응답 — 부분 주문은 실패가 아니므로 제외 항목을 201 응답의 일부로 전달
public record CreateOrderResponse(OrderResponse order, List<ExcludedItemResponse> excludedItems) {

    public record ExcludedItemResponse(Long productId, String productName, String reason) {
    }
}
