package com.ecommerce.supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class SupplierRepositoryTest {

    @Autowired
    SupplierRepository supplierRepository;

    @Test
    void 공급사를_저장하고_조회한다() {
        Supplier saved = supplierRepository.save(
                new Supplier("바삭공급사", "snack@example.com"));

        Supplier found = supplierRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("바삭공급사");
        assertThat(found.getContactEmail()).isEqualTo("snack@example.com");
        assertThat(found.getStatus()).isEqualTo(SupplierStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
