package com.ecommerce.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void 최대_시도_횟수_미만이면_차단되지_않는다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 최대_시도_횟수에_도달하면_차단된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
    }

    @Test
    void 잠금_시간이_지나면_다시_허용된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-04T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, 900, now::get);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
        now.set(now.get().plusSeconds(901));
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
