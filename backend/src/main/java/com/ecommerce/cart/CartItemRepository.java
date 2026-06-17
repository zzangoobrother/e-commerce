package com.ecommerce.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByCustomerIdAndProductId(Long customerId, Long productId);

    // 장바구니 조회 — 상품을 fetch join해 N+1 방지, 담은 순서 유지
    @Query("select c from CartItem c join fetch c.product "
            + "where c.customerId = :customerId order by c.id")
    List<CartItem> findAllByCustomerId(@Param("customerId") Long customerId);

    void deleteByCustomerIdAndProductId(Long customerId, Long productId);

    // 상품 삭제 전파 — 장바구니는 임시 데이터라 상품과 함께 제거 (주문 이력과 다른 점)
    void deleteByProductId(Long productId);

    // 주문 전환된 항목 일괄 제거 — 제외(구매 불가) 항목은 남긴다
    void deleteByCustomerIdAndProductIdIn(Long customerId, Collection<Long> productIds);
}
