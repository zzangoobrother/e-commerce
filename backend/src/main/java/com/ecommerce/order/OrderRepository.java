package com.ecommerce.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 주문 목록 — 항목을 함께 로딩(N+1 방지), 최신 주문 먼저
    @EntityGraph(attributePaths = "items")
    List<Order> findAllByCustomerIdOrderByIdDesc(Long customerId);

    // 취소용 — 본인 주문만 (타인 주문은 빈 Optional → 404).
    // 동시 이중 취소 방지 — Order 행을 PESSIMISTIC_WRITE로 잠가 상태 전이를 직렬화한다.
    // @Query + @Lock 조합에서는 @EntityGraph가 무시될 수 있으므로 items는 호출부의 같은 트랜잭션 내에서 lazy 초기화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.customerId = :customerId")
    Optional<Order> findByIdAndCustomerIdForUpdate(@Param("id") Long id,
                                                   @Param("customerId") Long customerId);

    // 어드민 전이용 — Order 행을 PESSIMISTIC_WRITE로 잠가 상태 전이를 직렬화(동시 전이 경합 방지).
    // 고객 취소와 동일 패턴: @Query + @Lock에서 @EntityGraph는 무시될 수 있어 items는 같은 트랜잭션 내 lazy 초기화.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // 어드민 목록 — 항목을 함께 로딩(N+1 방지), 최신 주문 먼저
    @EntityGraph(attributePaths = "items")
    List<Order> findAllByOrderByIdDesc();

    // 어드민 목록(상태 필터)
    @EntityGraph(attributePaths = "items")
    List<Order> findAllByStatusOrderByIdDesc(OrderStatus status);
}
