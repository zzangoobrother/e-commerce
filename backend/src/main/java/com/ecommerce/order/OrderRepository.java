package com.ecommerce.order;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 주문 목록 — 항목을 함께 로딩(N+1 방지), 최신 주문 먼저
    @EntityGraph(attributePaths = "items")
    List<Order> findAllByCustomerIdOrderByIdDesc(Long customerId);

    // 취소용 — 본인 주문만 (타인 주문은 빈 Optional → 404)
    Optional<Order> findByIdAndCustomerId(Long id, Long customerId);
}
