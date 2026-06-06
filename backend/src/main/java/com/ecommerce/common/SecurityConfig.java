package com.ecommerce.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 보안 설정 — JWT 발급/검증 빈 + 보안 필터 체인
@Configuration
public class SecurityConfig {

    // HS256 서명용 시크릿 (최소 32바이트)
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 토큰 기반 stateless API — CSRF/세션 불필요
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 로그인/리프레시/로그아웃은 인증 없이 허용 (refresh 토큰이 자격 증명)
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/login", "/api/admin/refresh", "/api/admin/logout").permitAll()
                        // 나머지 어드민 API는 JWT 필수
                        .requestMatchers("/api/admin/**").authenticated()
                        // 스토어 API 등 그 외는 모두 개방
                        .anyRequest().permitAll())
                // Bearer 토큰(JWT) 검증
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint()))
                // 미인증 접근(토큰 없음)에도 동일한 401 JSON 응답
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(jsonAuthenticationEntryPoint()));
        return http.build();
    }

    // 미인증/무효 토큰 요청에 기존 에러 형식({"message": ...})으로 401 응답
    private AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\": \"인증이 필요합니다.\"}");
        };
    }

    // 프론트(localhost:3000) → 백엔드 CORS 허용
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // 비밀번호 해싱 (BCrypt)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // JWT 발급 (HS256 대칭키)
    @Bean
    public JwtEncoder jwtEncoder() {
        SecretKey key = secretKey();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    // JWT 검증 (HS256 대칭키)
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private SecretKey secretKey() {
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
