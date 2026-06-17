package com.ecommerce.cart;

import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.common.BadRequestException;
import com.ecommerce.common.NotFoundException;
import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.product.ProductStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 장바구니 서비스 — 담기(수량 가산)/수량 변경/삭제/조회.
// 재고는 차감하지 않고 상한만 검증한다 — 차감·가격 고정·구매 가능 최종 재검증은 주문 생성(사이클 10)의 책임.
@Service
@Transactional(readOnly = true)
public class CartService {

    private static final String NOT_ON_SALE = "판매 중인 상품이 아닙니다.";

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    public CartService(CartItemRepository cartItemRepository, ProductRepository productRepository) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    public CartResponse getCart(Long customerId) {
        return CartResponse.from(cartItemRepository.findAllByCustomerId(customerId));
    }

    // 담기 — 같은 상품이 이미 있으면 수량 가산 (가산 결과로 재고 상한 검증)
    @Transactional
    public CartResponse addItem(Long customerId, Long productId, int quantity) {
        Product product = loadProduct(productId);
        validateOnSale(product);

        CartItem existing = cartItemRepository
                .findByCustomerIdAndProductId(customerId, productId).orElse(null);
        int newQuantity = (existing == null ? 0 : existing.getQuantity()) + quantity;
        validateStock(product, newQuantity);

        if (existing == null) {
            cartItemRepository.save(new CartItem(customerId, product, quantity));
        } else {
            existing.changeQuantity(newQuantity);
        }
        return getCart(customerId);
    }

    // 수량 변경 — 절대값 설정
    @Transactional
    public CartResponse updateQuantity(Long customerId, Long productId, int quantity) {
        CartItem item = cartItemRepository.findByCustomerIdAndProductId(customerId, productId)
                .orElseThrow(() -> new NotFoundException("장바구니에 없는 상품입니다: " + productId));
        Product product = item.getProduct();
        validateOnSale(product);
        validateStock(product, quantity);
        item.changeQuantity(quantity);
        return getCart(customerId);
    }

    // 삭제 — 미존재여도 멱등
    @Transactional
    public void removeItem(Long customerId, Long productId) {
        cartItemRepository.deleteByCustomerIdAndProductId(customerId, productId);
    }

    private Product loadProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("상품을 찾을 수 없습니다: " + productId));
    }

    private void validateOnSale(Product product) {
        if (product.getStatus() != ProductStatus.ON_SALE) {
            throw new BadRequestException(NOT_ON_SALE);
        }
    }

    private void validateStock(Product product, int requestedQuantity) {
        if (requestedQuantity > product.getStockQuantity()) {
            throw new BadRequestException(
                    "재고가 부족합니다. (재고: " + product.getStockQuantity() + "개)");
        }
    }
}
