package com.ecommerce.cart.dto;

import com.ecommerce.cart.CartItem;
import com.ecommerce.product.Product;

import java.math.BigDecimal;
import java.util.List;

// 장바구니 응답 — 항목 목록과 합계 (가격은 현재가 — 고정은 주문 생성 시점)
public record CartResponse(List<CartItemResponse> items, BigDecimal totalPrice) {

    public static CartResponse from(List<CartItem> cartItems) {
        List<CartItemResponse> items = cartItems.stream().map(CartItemResponse::from).toList();
        BigDecimal total = items.stream()
                .map(CartItemResponse::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(items, total);
    }

    public record CartItemResponse(Long productId, String productName, BigDecimal price,
                                   int quantity, BigDecimal lineTotal) {

        public static CartItemResponse from(CartItem item) {
            Product product = item.getProduct();
            BigDecimal lineTotal = product.getPrice()
                    .multiply(BigDecimal.valueOf(item.getQuantity()));
            return new CartItemResponse(product.getId(), product.getName(),
                    product.getPrice(), item.getQuantity(), lineTotal);
        }
    }
}
