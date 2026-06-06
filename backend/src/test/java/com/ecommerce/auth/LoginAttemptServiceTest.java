package com.ecommerce.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void 윈도우_내_최대_시도_미만이면_차단되지_않는다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 윈도우_내_최대_시도에_도달하면_차단된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
    }

    @Test
    void 윈도우가_만료되면_자동으로_다시_허용된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, 900, now::get);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
        now.set(now.get().plusSeconds(901));
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 실패가_윈도우_경계를_넘으면_카운트가_리셋된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, 900, now::get);
        // 윈도우 내 4회 실패(미차단)
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        // 윈도우가 지난 뒤 1회 더 실패 → 새 윈도우 count=1이라 차단되지 않아야 한다
        now.set(now.get().plusSeconds(901));
        service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 성공_리셋_후에는_차단_카운트가_사라진다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        service.reset("1.1.1.1");
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 서로_다른_IP는_독립적으로_카운트된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("2.2.2.2")).isFalse();
    }
}
