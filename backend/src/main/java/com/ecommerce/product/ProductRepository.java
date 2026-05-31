package com.ecommerce.product;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
