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
class CustomerAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @Autowired AdminRepository adminRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LoginAttemptService loginAttemptService;

    @BeforeEach
    void resetAttempts() {
        loginAttemptService.clearAll();
    }

    @AfterEach
    void cleanup() {
        refreshTokenRepository.deleteAll();
        customerRepository.deleteAll();
        adminRepository.deleteAll();
    }

    @Test
    void 회원가입하면_201과_토큰을_발급한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "user@example.com", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andExpect(jsonPath("$.accessExpiresAt").exists())
                .andExpect(jsonPath("$.refreshExpiresAt").exists());
    }

    @Test
    void 중복_이메일로_가입하면_409를_반환한다() throws Exception {
        customerRepository.save(new Customer("dup@example.com", passwordEncoder.encode("pw12345678")));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "dup@example.com", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 이메일_형식이_아니면_400을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "not-an-email", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 가입한_계정으로_로그인하면_토큰을_발급한다() throws Exception {
        customerRepository.save(new Customer("user@example.com", passwordEncoder.encode("pw12345678")));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "user@example.com", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void 잘못된_비밀번호로_로그인하면_401을_반환한다() throws Exception {
        customerRepository.save(new Customer("user@example.com", passwordEncoder.encode("pw12345678")));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "user@example.com", "password", "wrong-password"));

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 존재하지_않는_이메일로_로그인하면_401을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "nobody@example.com", "password", "whatever12"));

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 동일_IP에서_연속_5회_로그인_실패하면_6회째_429를_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "nobody@example.com", "password", "whatever12"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/store/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 리프레시하면_새_토큰을_발급하고_옛_refresh는_무효화된다() throws Exception {
        String refreshToken = registerAndGetRefreshToken("user@example.com", "pw12345678");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());

        // 옛 refresh 재사용 → 401
        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_refresh가_폐기되어_이후_리프레시가_거부된다() throws Exception {
        String refreshToken = registerAndGetRefreshToken("user@example.com", "pw12345678");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/store/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 가입으로_받은_access로는_어드민_API에_접근할_수_없다() throws Exception {
        String accessToken = registerAndGetAccessToken("user@example.com", "pw12345678");

        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void 어드민_refresh_토큰을_고객_리프레시_경로에_쓰면_401이고_토큰은_소비되지_않는다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String adminLoginBody = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "admin1234"));
        String adminResp = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(adminLoginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminRefresh = objectMapper.readTree(adminResp).get("refreshToken").asString();
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", adminRefresh));

        // 고객 리프레시 경로에 어드민 토큰 → 401
        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // 롤백으로 어드민 토큰은 아직 유효 → 어드민 리프레시 경로에서 정상 동작
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void 회전으로_발급된_새_refresh_토큰은_다시_사용할_수_있다() throws Exception {
        String refreshToken = registerAndGetRefreshToken("user@example.com", "pw12345678");
        String firstBody = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        String refreshed = mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(firstBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefreshToken = objectMapper.readTree(refreshed).get("refreshToken").asString();

        String secondBody = objectMapper.writeValueAsString(Map.of("refreshToken", newRefreshToken));
        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(secondBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void 존재하지_않는_refresh로_로그아웃해도_204를_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "nonexistent-token"));
        mockMvc.perform(post("/api/store/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private JsonNode register(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        String response = mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String registerAndGetRefreshToken(String email, String password) throws Exception {
        return register(email, password).get("refreshToken").asString();
    }

    private String registerAndGetAccessToken(String email, String password) throws Exception {
        return register(email, password).get("accessToken").asString();
    }
}
