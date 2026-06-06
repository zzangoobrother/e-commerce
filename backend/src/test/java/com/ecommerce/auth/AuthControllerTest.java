package com.ecommerce.auth;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LoginAttemptService loginAttemptService;

    @BeforeEach
    void resetAttempts() {
        // 싱글톤 LoginAttemptService의 상태가 다른 테스트로 누적되지 않도록 초기화
        loginAttemptService.clearAll();
    }

    @AfterEach
    void cleanup() {
        // refresh_tokens → admins 순서로 삭제 (FK 제약 위반 방지)
        refreshTokenRepository.deleteAll();
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
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.accessExpiresAt").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
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

    @Test
    void 동일_IP에서_연속_5회_실패하면_6회째_429를_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "nobody", "password", "wrong"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/admin/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }
}
