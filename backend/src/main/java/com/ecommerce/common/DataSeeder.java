package com.ecommerce.common;

import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 앱 시작 시 샘플 데이터 시드 (이미 있으면 건너뜀 — 멱등)
@Component
@Profile("!test")
public class DataSeeder implements CommandLineRunner {

    // 시드의 기준이 되는 첫 번째 공급사명 (존재하면 이미 시드된 것으로 간주)
    private static final String FIRST_SUPPLIER_NAME = "신선식품 주식회사";

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;

    public DataSeeder(SupplierRepository supplierRepository,
                      ProductRepository productRepository) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
    }

    // 공급사·상품 시드를 한 트랜잭션으로 묶어 원자적으로 커밋/롤백한다
    // (이름 존재 여부로 멱등성 판단 — 동시 기동 race는 name 유니크 제약이 최종 방어)
    // 주의: race에서 진 인스턴스는 제약 위반으로 기동이 실패할 수 있음 (다중 인스턴스 배포 시 재고려)
    @Override
    @Transactional
    public void run(String... args) {
        if (supplierRepository.existsByName(FIRST_SUPPLIER_NAME)) {
            return; // 이미 시드됨
        }

        Supplier fresh = supplierRepository.save(
                new Supplier(FIRST_SUPPLIER_NAME, "fresh@example.com"));
        Supplier snack = supplierRepository.save(
                new Supplier("바삭과자 주식회사", "snack@example.com"));

        productRepository.save(new Product(fresh, "유기농 사과 1kg",
                "당도 높은 유기농 사과", new BigDecimal("8900"), 50));
        productRepository.save(new Product(fresh, "제철 딸기 500g",
                "신선한 제철 딸기", new BigDecimal("12000"), 30));
        productRepository.save(new Product(snack, "감자칩 오리지널",
                "바삭한 감자칩", new BigDecimal("1500"), 200));
        productRepository.save(new Product(snack, "초코쿠키 12개입",
                "달콤한 초코쿠키", new BigDecimal("3500"), 120));
    }
}
