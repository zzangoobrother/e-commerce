package com.ecommerce.address;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    // 상한(10개) 검사용
    long countByCustomerId(Long customerId);

    // 기본배송지 해제(불변식 유지)용 — 현재 기본 1건 조회
    Optional<CustomerAddress> findByCustomerIdAndIsDefaultTrue(Long customerId);

    // 목록: 기본배송지 먼저, 이후 최신순
    List<CustomerAddress> findByCustomerIdOrderByIsDefaultDescCreatedAtDesc(Long customerId);
}
