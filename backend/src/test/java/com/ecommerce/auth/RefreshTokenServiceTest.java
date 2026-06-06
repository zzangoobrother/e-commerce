package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.common.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class RefreshTokenServiceTest {

    private static final long SEVEN_DAYS = 604800L;

    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AdminRepository adminRepository;

    private Admin admin;

    @BeforeEach
    void setUp() {
        admin = adminRepository.save(new Admin("admin", "encoded-pw"));
    }

    private RefreshTokenService service(Supplier<Instant> clock) {
        return new RefreshTokenService(refreshTokenRepository, SEVEN_DAYS, clock);
    }

    @Test
    void 재사용_탐지는_다른_admin의_토큰을_무효화하지_않는다() {
        RefreshTokenService service = service(Instant::now);
        Admin other = adminRepository.save(new Admin("other", "encoded-pw"));
        IssuedToken otherToken = service.issue(other);

        // admin의 토큰을 회전시킨 뒤 옛 토큰을 재사용해 admin 전체 무효화를 트리거한다
        IssuedToken first = service.issue(admin);
        service.rotate(first.token());
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 다른 admin(other)의 토큰은 영향받지 않고 정상 회전된다
        RotationResult result = service.rotate(otherToken.token());
        assertThat(result.admin().getId()).isEqualTo(other.getId());
    }

    @Test
    void 발급한_토큰의_만료시각은_발급시각에_7일을_더한_값이다() {
        Instant fixed = Instant.parse("2026-06-06T00:00:00Z");
        RefreshTokenService service = service(() -> fixed);

        IssuedToken token = service.issue(admin);

        assertThat(token.expiresAt()).isEqualTo(fixed.plusSeconds(SEVEN_DAYS));
    }

    @Test
    void 회전_결과의_admin은_토큰을_발급한_admin과_동일하다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(admin);

        RotationResult result = service.rotate(first.token());

        assertThat(result.admin().getId()).isEqualTo(admin.getId());
    }

    @Test
    void 발급한_토큰으로_회전하면_새_토큰을_반환하고_옛_토큰은_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(admin);

        RotationResult result = service.rotate(first.token());

        assertThat(result.refresh().token()).isNotEqualTo(first.token());
        // 옛 토큰 재제출은 재사용으로 거부된다
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 만료된_토큰으로_회전하면_거부된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        RefreshTokenService service = service(now::get);
        IssuedToken token = service.issue(admin);

        now.set(now.get().plusSeconds(SEVEN_DAYS + 1));

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 폐기된_토큰_재사용시_해당_admin의_모든_토큰이_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(admin);
        RotationResult rotated = service.rotate(first.token()); // first revoked, second 발급

        // 폐기된 first 재제출 → 재사용 탐지 → second까지 전부 무효
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 살아있던 second 토큰도 이제 거부되어야 한다
        assertThatThrownBy(() -> service.rotate(rotated.refresh().token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 존재하지_않는_토큰으로_회전하면_거부된다() {
        RefreshTokenService service = service(Instant::now);
        assertThatThrownBy(() -> service.rotate("nonexistent-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 폐기_후에는_회전이_거부된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken token = service.issue(admin);

        service.revoke(token.token());

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent"); // 예외 없이 통과
    }
}
