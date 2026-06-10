package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.auth.RefreshTokenService.TokenOwner;
import com.ecommerce.common.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재사용 탐지의 "커밋 경계" 검증 — @DataJpaTest는 각 테스트를 바깥 트랜잭션으로 감싸
// 같은-트랜잭션 롤백 결함을 가리므로(RefreshTokenServiceTest 주석 참조),
// 여기서는 테스트 트랜잭션 없이 실제 스프링 빈(프록시)으로 호출해
// 각 서비스 호출이 프로덕션과 동일하게 커밋되는 환경에서 폐기가 persist되는지 본다.
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenReuseDetectionTest {

    private static final TokenOwner ADMIN_1 = new TokenOwner(OwnerType.ADMIN, 1L);
    private static final TokenOwner CUSTOMER_1 = new TokenOwner(OwnerType.CUSTOMER, 1L);

    @Autowired RefreshTokenService refreshTokenService;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @AfterEach
    void cleanup() {
        // 테스트 트랜잭션이 없어 자동 롤백이 없다 — 커밋된 행을 직접 정리
        refreshTokenRepository.deleteAll();
    }

    @Test
    void 재사용_탐지의_일괄_폐기는_예외_롤백과_무관하게_커밋된다() {
        IssuedToken first = refreshTokenService.issue(ADMIN_1);
        RotationResult rotated = refreshTokenService.rotate(first.token());
        String sibling = rotated.refresh().token();

        // 폐기된 토큰 재제출 = 탈취 정황 → 401 경로
        assertThatThrownBy(() -> refreshTokenService.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 핵심: 형제 토큰 폐기가 커밋되어 남아야 한다
        // (결함: 같은 트랜잭션이 예외로 롤백되며 폐기 UPDATE가 유실)
        assertThat(refreshTokenRepository.findAll())
                .isNotEmpty()
                .allMatch(RefreshToken::isRevoked);

        // 폐기가 남았으므로 형제 토큰으로도 회전 불가
        assertThatThrownBy(() -> refreshTokenService.rotate(sibling))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 재사용_탐지_커밋은_다른_owner의_토큰에_영향을_주지_않는다() {
        IssuedToken customerToken = refreshTokenService.issue(CUSTOMER_1);
        IssuedToken adminFirst = refreshTokenService.issue(ADMIN_1);
        refreshTokenService.rotate(adminFirst.token());

        // ADMIN_1 재사용 탐지 발동
        assertThatThrownBy(() -> refreshTokenService.rotate(adminFirst.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 다른 owner(CUSTOMER_1) 토큰은 여전히 정상 회전된다
        RotationResult result = refreshTokenService.rotate(customerToken.token());
        assertThat(result.owner()).isEqualTo(CUSTOMER_1);
    }
}
