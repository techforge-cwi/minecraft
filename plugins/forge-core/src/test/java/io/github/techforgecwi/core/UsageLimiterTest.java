package io.github.techforgecwi.core;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageLimiterTest {

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-31T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final TestClock clock = new TestClock();
    private final UUID alex = UUID.randomUUID();
    private final UUID sam = UUID.randomUUID();

    @Test
    void cooldownBlocksUntilItPasses() {
        UsageLimiter limiter = new UsageLimiter(new UsageLimiter.Limits(Duration.ofSeconds(10), 100, 100), clock);
        assertTrue(limiter.tryAcquire(alex).isEmpty());
        assertTrue(limiter.tryAcquire(alex).orElseThrow().contains("10s"));
        clock.advance(Duration.ofSeconds(9));
        assertTrue(limiter.tryAcquire(alex).orElseThrow().contains("1s"));
        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire(alex).isEmpty());
    }

    @Test
    void cooldownIsPerPlayer() {
        UsageLimiter limiter = new UsageLimiter(new UsageLimiter.Limits(Duration.ofSeconds(10), 100, 100), clock);
        assertTrue(limiter.tryAcquire(alex).isEmpty());
        assertTrue(limiter.tryAcquire(sam).isEmpty());
    }

    @Test
    void playerAndServerDailyCaps() {
        UsageLimiter limiter = new UsageLimiter(new UsageLimiter.Limits(Duration.ZERO, 2, 3), clock);
        assertTrue(limiter.tryAcquire(alex).isEmpty());
        assertTrue(limiter.tryAcquire(alex).isEmpty());
        assertTrue(limiter.tryAcquire(alex).orElseThrow().startsWith("You've used all"));
        assertTrue(limiter.tryAcquire(sam).isEmpty());
        assertTrue(limiter.tryAcquire(sam).orElseThrow().startsWith("The server has used all"));
        assertEquals(3, limiter.serverCountToday());
    }

    @Test
    void deniedRequestsDontCount() {
        UsageLimiter limiter = new UsageLimiter(new UsageLimiter.Limits(Duration.ofMinutes(1), 5, 5), clock);
        limiter.tryAcquire(alex);
        limiter.tryAcquire(alex);
        limiter.tryAcquire(alex);
        assertEquals(1, limiter.serverCountToday());
    }

    @Test
    void countsResetAtMidnight() {
        UsageLimiter limiter = new UsageLimiter(new UsageLimiter.Limits(Duration.ZERO, 1, 1), clock);
        assertTrue(limiter.tryAcquire(alex).isEmpty());
        assertTrue(limiter.tryAcquire(alex).isPresent());
        clock.advance(Duration.ofHours(12));
        assertTrue(limiter.tryAcquire(alex).isEmpty());
    }
}
