package com.ecommerce.order;

import com.ecommerce.order.dto.AdminOrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 어드민 주문 관리 API — /api/admin/**는 SecurityConfig에서 hasRole('ADMIN')로 보호된다(별도 매처 불필요).
// 상태 전이가 POST /ship·/deliver·/cancel인 이유: 주문은 상태 전이(이력 보존), 부수효과(취소=재고복원)를 엔드포인트로 명시.
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<AdminOrderResponse> list(@RequestParam(required = false) OrderStatus status) {
        return orderService.getAllOrders(status);
    }

    @PostMapping("/{orderId}/ship")
    public AdminOrderResponse ship(@PathVariable Long orderId) {
        return orderService.shipOrder(orderId);
    }

    @PostMapping("/{orderId}/deliver")
    public AdminOrderResponse deliver(@PathVariable Long orderId) {
        return orderService.deliverOrder(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public AdminOrderResponse cancel(@PathVariable Long orderId) {
        return orderService.cancelOrderByAdmin(orderId);
    }
}
