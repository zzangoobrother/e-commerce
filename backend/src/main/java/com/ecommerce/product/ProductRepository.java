package com.ecommerce.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // 공급사를 함께 로딩하여 LazyInitializationException 방지
    @EntityGraph(attributePaths = "supplier")
    List<Product> findBySupplierId(Long supplierId);

    @EntityGraph(attributePaths = "supplier")
    List<Product> findByStatus(ProductStatus status);

    @EntityGraph(attributePaths = "supplier")
    Optional<Product> findWithSupplierById(Long id);

    @EntityGraph(attributePaths = "supplier")
    List<Product> findAll();

    // 주문 생성·취소의 재고 변경 구간 잠금(SELECT ... FOR UPDATE).
    // 데드락 예방: order by로 잠금 획득 순서를 productId 오름차순으로 고정 — 호출부도 정렬해 전달한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllForUpdate(@Param("ids") Collection<Long> ids);
}
