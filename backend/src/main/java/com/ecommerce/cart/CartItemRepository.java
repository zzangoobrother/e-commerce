package com.ecommerce.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByCustomerIdAndProductId(Long customerId, Long productId);

    // 장바구니 조회 — 상품을 fetch join해 N+1 방지, 담은 순서 유지
    @Query("select c from CartItem c join fetch c.product "
            + "where c.customerId = :customerId order by c.id")
    List<CartItem> findAllByCustomerId(@Param("customerId") Long customerId);

    void deleteByCustomerIdAndProductId(Long customerId, Long productId);
}
