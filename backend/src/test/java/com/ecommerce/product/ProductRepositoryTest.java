package com.ecommerce.product;

import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;

    @Test
    void 공급사별로_상품을_조회한다() {
        Supplier a = supplierRepository.save(new Supplier("공급사A", "a@example.com"));
        Supplier b = supplierRepository.save(new Supplier("공급사B", "b@example.com"));
        productRepository.save(new Product(a, "사과", "맛있는 사과", new BigDecimal("3000"), 10));
        productRepository.save(new Product(a, "배", "달콤한 배", new BigDecimal("5000"), 5));
        productRepository.save(new Product(b, "감자", "포슬포슬", new BigDecimal("2000"), 20));

        List<Product> aProducts = productRepository.findBySupplierId(a.getId());

        assertThat(aProducts).hasSize(2);
        assertThat(aProducts).extracting(Product::getName)
                .containsExactlyInAnyOrder("사과", "배");
    }

    @Test
    void 상태로_상품을_조회한다() {
        Supplier a = supplierRepository.save(new Supplier("공급사A", "a@example.com"));
        productRepository.save(new Product(a, "사과", "설명", new BigDecimal("3000"), 10));

        List<Product> onSale = productRepository.findByStatus(ProductStatus.ON_SALE);

        assertThat(onSale).hasSize(1);
    }
}
