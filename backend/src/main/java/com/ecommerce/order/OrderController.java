package com.ecommerce.order;

import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.common.UnauthorizedException;
import com.ecommerce.order.dto.CreateOrderResponse;
import com.ecommerce.order.dto.OrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 주문 API — /api/store/orders/**는 SecurityConfig에서 hasRole('CUSTOMER')로 보호된다.
// 고객 식별: 고객 access JWT의 subject(email) → Customer 조회 (CartController와 동일 패턴).
// 취소가 DELETE가 아닌 POST /cancel인 이유: 주문은 삭제가 아니라 상태 전이(이력 보존).
@RestController
@RequestMapping("/api/store/orders")
public class OrderController {

    private final OrderService orderService;
    private final CustomerRepository customerRepository;

    public OrderController(OrderService orderService, CustomerRepository customerRepository) {
        this.orderService = orderService;
        this.customerRepository = customerRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateOrderResponse create(@AuthenticationPrincipal Jwt jwt) {
        return orderService.createOrder(customerId(jwt));
    }

    @GetMapping
    public List<OrderResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return orderService.getOrders(customerId(jwt));
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long orderId) {
        return orderService.cancelOrder(customerId(jwt), orderId);
    }

    // 토큰은 유효하지만 고객 행이 없는 경우(탈퇴 등) 401
    private Long customerId(Jwt jwt) {
        return customerRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("고객 정보를 찾을 수 없습니다."))
                .getId();
    }
}
