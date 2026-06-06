package com.ecommerce.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// 로그인 시도 제한 — IP 기준 인메모리 고정 윈도우 카운터.
// 윈도우(기본 15분) 내 maxAttempts회 실패 시 윈도우가 끝날 때까지 차단하고, 윈도우 만료 시 자동 리셋한다.
// 단일 인스턴스 한정(다중 인스턴스 배포 시 인스턴스별로 카운트됨 — README 보안 한계 참고).
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final long windowSeconds;
    private final Supplier<Instant> clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    // 현재 윈도우의 실패 누적 횟수와 윈도우 만료 시각
    private record Attempt(int count, Instant windowExpiry) {}

    @Autowired
    public LoginAttemptService(
            @Value("${login.max-attempts:5}") int maxAttempts,
            @Value("${login.window-seconds:900}") long windowSeconds) {
        this(maxAttempts, windowSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자를 주입해 윈도우 만료를 결정적으로 검증
    LoginAttemptService(int maxAttempts, long windowSeconds, Supplier<Instant> clock) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    // 현재 해당 IP가 차단 상태인지 — 윈도우가 살아있고 실패 누적이 임계값 이상
    public boolean isBlocked(String ip) {
        Attempt attempt = attempts.get(ip);
        return attempt != null
                && clock.get().isBefore(attempt.windowExpiry())
                && attempt.count() >= maxAttempts;
    }

    // 실패 1회 기록. 윈도우가 없거나 만료됐으면 새 윈도우를 시작하고, 아니면 같은 윈도우에서 누적한다.
    public void recordFailure(String ip) {
        attempts.compute(ip, (key, prev) -> {
            Instant now = clock.get();
            boolean newWindow = prev == null || !now.isBefore(prev.windowExpiry());
            if (newWindow) {
                return new Attempt(1, now.plusSeconds(windowSeconds));
            }
            return new Attempt(prev.count() + 1, prev.windowExpiry());
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
