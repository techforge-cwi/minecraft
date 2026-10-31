package io.github.techforgecwi.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per-player cooldown, per-player daily cap and a server-wide daily cap. Counts reset at local midnight. */
final class UsageLimiter {

    record Limits(Duration cooldown, int playerDaily, int serverDaily) {}

    private final Clock clock;
    private volatile Limits limits;

    private LocalDate day;
    private int serverCount;
    private final Map<UUID, Integer> playerCounts = new HashMap<>();
    private final Map<UUID, Instant> lastRequest = new HashMap<>();

    UsageLimiter(Limits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    void setLimits(Limits limits) {
        this.limits = limits;
    }

    /** Counts one request if it's allowed. Returns a player-facing reason if it isn't. */
    synchronized Optional<String> tryAcquire(UUID player) {
        rollOverIfNewDay();
        Limits current = limits;
        Instant now = clock.instant();

        Instant last = lastRequest.get(player);
        if (last != null) {
            long wait = Duration.between(now, last.plus(current.cooldown())).toSeconds();
            if (now.isBefore(last.plus(current.cooldown()))) {
                return Optional.of("Slow down! Try again in " + Math.max(1, wait) + "s.");
            }
        }
        if (playerCounts.getOrDefault(player, 0) >= current.playerDaily()) {
            return Optional.of("You've used all your AI questions for today. They reset at midnight.");
        }
        if (serverCount >= current.serverDaily()) {
            return Optional.of("The server has used all its AI questions for today. They reset at midnight.");
        }

        lastRequest.put(player, now);
        playerCounts.merge(player, 1, Integer::sum);
        serverCount++;
        return Optional.empty();
    }

    synchronized int serverCountToday() {
        rollOverIfNewDay();
        return serverCount;
    }

    private void rollOverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            serverCount = 0;
            playerCounts.clear();
            lastRequest.clear();
        }
    }
}
