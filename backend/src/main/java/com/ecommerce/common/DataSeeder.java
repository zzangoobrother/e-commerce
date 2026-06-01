package com.ecommerce.common;

import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// 앱 시작 시 샘플 데이터 시드 (이미 있으면 건너뜀 — 멱등)
@Component
@Profile("!test")
public class DataSeeder implements CommandLineRunner {

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;

    public DataSeeder(SupplierRepository supplierRepository,
                      ProductRepository productRepository) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (supplierRepository.count() > 0) {
            return; // 이미 시드됨
        }

        Supplier fresh = supplierRepository.save(
                new Supplier("신선식품 주식회사", "fresh@example.com"));
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
