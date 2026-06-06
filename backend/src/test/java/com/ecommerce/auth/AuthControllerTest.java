package com.ecommerce.auth;

import tools.jackson.databind.JsonNode;
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
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.refreshExpiresAt").exists());
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

    @Test
    void 리프레시하면_새_토큰을_발급하고_옛_refresh는_무효화된다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String refreshToken = loginAndGetRefreshToken("admin", "admin1234");

        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());

        // 이미 회전된 이전 refresh 토큰은 재사용 불가 (재사용 탐지)
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_refresh가_폐기되어_이후_리프레시가_거부된다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String refreshToken = loginAndGetRefreshToken("admin", "admin1234");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/admin/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        // 로그아웃 후 동일 refresh 토큰으로 리프레시 시도 → 거부
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 회전으로_발급된_새_refresh_토큰은_다시_사용할_수_있다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String refreshToken = loginAndGetRefreshToken("admin", "admin1234");

        String firstBody = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        String refreshed = mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefreshToken = objectMapper.readTree(refreshed).get("refreshToken").asString();

        // 회전으로 발급된 새 토큰은 정상적으로 다시 리프레시에 사용 가능
        String secondBody = objectMapper.writeValueAsString(Map.of("refreshToken", newRefreshToken));
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void 존재하지_않는_refresh로_로그아웃해도_204를_반환한다() throws Exception {
        // revoke()는 ifPresent로 멱등 — 미존재 토큰에도 예외 없이 204
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "nonexistent-token"));
        mockMvc.perform(post("/api/admin/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    @Test
    void 리프레시로_받은_새_access로_보호된_API에_접근할_수_있다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String refreshToken = loginAndGetRefreshToken("admin", "admin1234");

        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        String refreshed = mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newAccess = objectMapper.readTree(refreshed).get("accessToken").asString();

        // 회전으로 받은 새 access 토큰으로 보호된 어드민 API 접근 성공
        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
    }

    // 로그인 후 응답에서 refreshToken 값을 추출하는 헬퍼
    private String loginAndGetRefreshToken(String username, String password) throws Exception {
        String loginBody = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        String response = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("refreshToken").asString();
    }
}
