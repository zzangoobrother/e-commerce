package com.ecommerce.cart;

import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.cart.dto.AddCartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.dto.UpdateCartItemRequest;
import com.ecommerce.common.UnauthorizedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 장바구니 API — /api/store/cart/**는 SecurityConfig에서 hasRole('CUSTOMER')로 보호된다.
// 고객 식별: 고객 access JWT의 subject(email) → Customer 조회.
@RestController
@RequestMapping("/api/store/cart")
public class CartController {

    private final CartService cartService;
    private final CustomerRepository customerRepository;

    public CartController(CartService cartService, CustomerRepository customerRepository) {
        this.cartService = cartService;
        this.customerRepository = customerRepository;
    }

    @GetMapping
    public CartResponse cart(@AuthenticationPrincipal Jwt jwt) {
        return cartService.getCart(customerId(jwt));
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addItem(@AuthenticationPrincipal Jwt jwt,
                                @Valid @RequestBody AddCartItemRequest request) {
        return cartService.addItem(customerId(jwt), request.productId(), request.quantity());
    }

    @PatchMapping("/items/{productId}")
    public CartResponse updateQuantity(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable Long productId,
                                       @Valid @RequestBody UpdateCartItemRequest request) {
        return cartService.updateQuantity(customerId(jwt), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@AuthenticationPrincipal Jwt jwt, @PathVariable Long productId) {
        cartService.removeItem(customerId(jwt), productId);
    }

    // 토큰은 유효하지만 고객 행이 없는 경우(탈퇴 등) 401
    private Long customerId(Jwt jwt) {
        return customerRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("고객 정보를 찾을 수 없습니다."))
                .getId();
    }
}
