package com.ecommerce.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// 로그인 시도 제한 — IP 기준 인메모리 카운터.
// 단일 인스턴스 한정(다중 인스턴스 배포 시 인스턴스별로 카운트됨 — README 보안 한계 참고).
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final long lockoutSeconds;
    private final Supplier<Instant> clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    // 실패 누적 횟수와 잠금 만료 시각(미잠금이면 null)
    private record Attempt(int count, Instant lockedUntil) {}

    @Autowired
    public LoginAttemptService(
            @Value("${login.max-attempts:5}") int maxAttempts,
            @Value("${login.lockout-seconds:900}") long lockoutSeconds) {
        this(maxAttempts, lockoutSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자를 주입해 잠금 만료를 결정적으로 검증
    LoginAttemptService(int maxAttempts, long lockoutSeconds, Supplier<Instant> clock) {
        this.maxAttempts = maxAttempts;
        this.lockoutSeconds = lockoutSeconds;
        this.clock = clock;
    }

    // 현재 해당 IP가 잠금 상태인지
    public boolean isBlocked(String ip) {
        Attempt attempt = attempts.get(ip);
        return attempt != null
                && attempt.lockedUntil() != null
                && clock.get().isBefore(attempt.lockedUntil());
    }

    // 실패 1회 기록. 임계값 도달 시 잠금. 잠금이 이미 만료됐으면 카운트를 새로 시작한다.
    public void recordFailure(String ip) {
        attempts.compute(ip, (key, prev) -> {
            Instant now = clock.get();
            boolean expired = prev != null && prev.lockedUntil() != null && !now.isBefore(prev.lockedUntil());
            int prevCount = (prev == null || expired) ? 0 : prev.count();
            int count = prevCount + 1;
            Instant lockedUntil = count >= maxAttempts ? now.plusSeconds(lockoutSeconds) : null;
            return new Attempt(count, lockedUntil);
        });
    }

    // 로그인 성공 시 해당 IP 카운터 제거
    public void reset(String ip) {
        attempts.remove(ip);
    }

    // 테스트 격리용 — 전체 카운터 초기화(싱글톤 빈이라 테스트 간 상태가 누적되는 것을 방지)
    public void clearAll() {
        attempts.clear();
    }
}
