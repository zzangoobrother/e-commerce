package com.ecommerce.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 탈취 정황 대응 — 일괄 폐기를 호출자 트랜잭션과 분리해 즉시 커밋한다.
// 별도 빈인 이유: 같은 클래스 내 호출(self-invocation)은 프록시를 우회해 REQUIRES_NEW가 무시된다.
@Component
public class TokenTheftResponder {

    private final RefreshTokenRepository repository;

    public TokenTheftResponder(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    // 해당 owner의 살아있는 토큰 전부 폐기 — 호출자가 이후 예외로 롤백돼도 이 커밋은 남는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllFor(OwnerType type, Long id) {
        repository.revokeAllByOwner(type, id);
    }
}
