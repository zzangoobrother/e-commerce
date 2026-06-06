package com.ecommerce.common;

import com.ecommerce.auth.Admin;
import com.ecommerce.auth.AdminRepository;
import com.ecommerce.auth.LoginAttemptService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 어드민 API 보호 규칙 검증 — 토큰 없으면 401, 있으면 통과, 스토어는 항상 개방
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityProtectionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminRepository adminRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired LoginAttemptService loginAttemptService;

    @BeforeEach
    void resetAttempts() {
        // 테스트 간 로그인 시도 카운터 격리 — 싱글톤 빈이라 이전 테스트 상태가 누적될 수 있음
        loginAttemptService.clearAll();
    }

    @AfterEach
    void cleanup() {
        adminRepository.deleteAll();
    }

    @Test
    void 토큰_없이_어드민_API_호출시_401과_메시지를_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/suppliers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 토큰_없이_스토어_API는_정상_접근된다() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    @Test
    void 모의_JWT로_어드민_API에_접근한다() throws Exception {
        mockMvc.perform(get("/api/admin/suppliers").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void 만료된_토큰으로_어드민_API_호출시_401을_반환한다() throws Exception {
        // 이미 만료된 exp(현재 -1시간)를 가진 JWT를 직접 발급해 요청
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin")
                .issuedAt(past.minusSeconds(3600))
                .expiresAt(past)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 로그인으로_발급받은_실제_토큰으로_어드민_API에_접근한다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));

        // 로그인 → 토큰 추출
        String loginBody = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "admin1234"));
        String response = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("token").asText();

        // 발급받은 실제 토큰으로 보호된 API 접근 (발급→검증 왕복 검증)
        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
