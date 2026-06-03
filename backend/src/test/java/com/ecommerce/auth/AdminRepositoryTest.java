package com.ecommerce.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class AdminRepositoryTest {

    @Autowired
    AdminRepository adminRepository;

    @Test
    void 어드민_계정을_저장하고_아이디로_조회한다() {
        adminRepository.save(new Admin("admin", "encoded-password-hash"));

        Admin found = adminRepository.findByUsername("admin").orElseThrow();

        assertThat(found.getUsername()).isEqualTo("admin");
        assertThat(found.getPassword()).isEqualTo("encoded-password-hash");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 동일한_아이디의_어드민은_저장할_수_없다() {
        adminRepository.saveAndFlush(new Admin("admin", "hash1"));

        assertThatThrownBy(() -> adminRepository.saveAndFlush(
                new Admin("admin", "hash2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 아이디로_어드민_존재_여부를_확인한다() {
        adminRepository.save(new Admin("admin", "hash"));

        assertThat(adminRepository.existsByUsername("admin")).isTrue();
        assertThat(adminRepository.existsByUsername("nobody")).isFalse();
    }
}
