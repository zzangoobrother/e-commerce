package com.ecommerce.supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void 동일한_이름의_공급사는_저장할_수_없다() {
        supplierRepository.saveAndFlush(
                new Supplier("중복공급사", "a@example.com"));

        assertThatThrownBy(() -> supplierRepository.saveAndFlush(
                new Supplier("중복공급사", "b@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 이름으로_공급사_존재_여부를_확인한다() {
        supplierRepository.save(new Supplier("존재공급사", "exist@example.com"));

        assertThat(supplierRepository.existsByName("존재공급사")).isTrue();
        assertThat(supplierRepository.existsByName("없는공급사")).isFalse();
    }
}
