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

// 회전·만료·폐기 "로직" 검증 전용 — @DataJpaTest는 각 테스트를 바깥 트랜잭션으로 감싸
// 커밋 경계가 프로덕션과 다르다(내부 예외 후에도 변경이 보임).
// 재사용 탐지 폐기가 실제로 "커밋"되는지는 RefreshTokenReuseDetectionTest(@SpringBootTest)가 검증한다.
@DataJpaTest
@ActiveProfiles("test")
class RefreshTokenServiceTest {

    private static final long SEVEN_DAYS = 604800L;
    private static final TokenOwner ADMIN_1 = new TokenOwner(OwnerType.ADMIN, 1L);
    private static final TokenOwner CUSTOMER_1 = new TokenOwner(OwnerType.CUSTOMER, 1L);

    @Autowired RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService service(Supplier<Instant> clock) {
        // 프록시 없는 직접 생성 — REQUIRES_NEW가 적용되지 않고 테스트 트랜잭션에 참여한다(로직 검증엔 충분)
        return new RefreshTokenService(refreshTokenRepository,
                new TokenTheftResponder(refreshTokenRepository), SEVEN_DAYS, clock);
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

        service.revoke(token.token(), OwnerType.ADMIN);

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent", OwnerType.ADMIN);
    }

    @Test
    void revoke는_타입이_불일치하면_폐기하지_않는다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken token = service.issue(ADMIN_1);

        // 어드민 토큰을 고객 타입으로 폐기 시도 → no-op
        service.revoke(token.token(), OwnerType.CUSTOMER);

        // 여전히 유효 → 회전 성공
        RotationResult result = service.rotate(token.token());
        assertThat(result.owner()).isEqualTo(ADMIN_1);
    }
}
