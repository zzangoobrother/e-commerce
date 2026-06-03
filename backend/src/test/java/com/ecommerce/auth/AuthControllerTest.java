package com.ecommerce.auth;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminRepository adminRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 데이터 정리 — 다른 테스트와의 격리
        adminRepository.deleteAll();
    }

    @Test
    void 올바른_계정으로_로그인하면_토큰을_발급한다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));

        String body = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "admin1234"));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void 잘못된_비밀번호로_로그인하면_401을_반환한다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));

        String body = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "wrong-password"));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 존재하지_않는_아이디로_로그인하면_401을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "nobody", "password", "whatever"));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 빈_입력으로_로그인하면_400을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "", "password", ""));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
