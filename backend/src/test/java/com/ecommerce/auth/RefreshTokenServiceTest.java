package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.auth.RefreshTokenService.TokenOwner;
import com.ecommerce.common.UnauthorizedException;
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
    private static final TokenOwner ADMIN_1 = new TokenOwner(OwnerType.ADMIN, 1L);
    private static final TokenOwner CUSTOMER_1 = new TokenOwner(OwnerType.CUSTOMER, 1L);

    @Autowired RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService service(Supplier<Instant> clock) {
        return new RefreshTokenService(refreshTokenRepository, SEVEN_DAYS, clock);
    }

    @Test
    void 발급한_토큰의_만료시각은_발급시각에_7일을_더한_값이다() {
        Instant fixed = Instant.parse("2026-06-06T00:00:00Z");
        RefreshTokenService service = service(() -> fixed);

        IssuedToken token = service.issue(ADMIN_1);

        assertThat(token.expiresAt()).isEqualTo(fixed.plusSeconds(SEVEN_DAYS));
    }

    @Test
    void 발급한_토큰으로_회전하면_새_토큰을_반환하고_옛_토큰은_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(ADMIN_1);

        RotationResult result = service.rotate(first.token());

        assertThat(result.refresh().token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 회전_결과의_owner는_토큰을_발급한_owner와_동일하다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(CUSTOMER_1);

        RotationResult result = service.rotate(first.token());

        assertThat(result.owner()).isEqualTo(CUSTOMER_1);
    }

    @Test
    void 만료된_토큰으로_회전하면_거부된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        RefreshTokenService service = service(now::get);
        IssuedToken token = service.issue(ADMIN_1);

        now.set(now.get().plusSeconds(SEVEN_DAYS + 1));

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 폐기된_토큰_재사용시_해당_owner의_모든_토큰이_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(ADMIN_1);
        RotationResult rotated = service.rotate(first.token());

        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.rotate(rotated.refresh().token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 재사용_탐지는_다른_owner의_토큰을_무효화하지_않는다() {
        RefreshTokenService service = service(Instant::now);
        // 같은 id(1)지만 타입이 다른 owner — (ownerType, ownerId) 격리를 검증
        IssuedToken customerToken = service.issue(CUSTOMER_1);
        IssuedToken first = service.issue(ADMIN_1);
        service.rotate(first.token());
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // CUSTOMER_1 토큰은 영향받지 않고 정상 회전된다
        RotationResult result = service.rotate(customerToken.token());
        assertThat(result.owner()).isEqualTo(CUSTOMER_1);
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
        IssuedToken token = service.issue(ADMIN_1);

        service.revoke(token.token());

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent");
    }
}
