package io.github.techforgecwi.core;

import io.github.techforgecwi.core.provider.ChatProvider;
import io.github.techforgecwi.core.provider.ChatProvider.Message;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/** The ForgeAI that other plugins get. Every request goes through the same checks, in this order. */
final class ForgeAIService implements ForgeAI {

    record Settings(boolean enabled, String disabledReason, int maxPromptChars, int maxReplyChars,
                    int maxHistoryTurns, String preamble) {}

    private static final int MAX_PERSONA_CHARS = 1500;

    private final JavaPlugin core;
    private final UsageLog usageLog;
    private final UsageLimiter limiter;
    private volatile Settings settings;
    private volatile ChatProvider provider;
    private volatile TextSafety safety;

    ForgeAIService(JavaPlugin core, UsageLog usageLog, UsageLimiter limiter) {
        this.core = core;
        this.usageLog = usageLog;
        this.limiter = limiter;
    }

    void configure(Settings settings, ChatProvider provider, TextSafety safety, UsageLimiter.Limits limits) {
        this.provider = provider;
        this.safety = safety;
        this.limiter.setLimits(limits);
        this.settings = settings;
    }

    Settings settings() {
        return settings;
    }

    String providerDescription() {
        return provider.describe();
    }

    int requestsToday() {
        return limiter.serverCountToday();
    }

    @Override
    public CompletableFuture<String> ask(Plugin caller, Player player, String persona, List<ChatTurn> history, String prompt) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(player, "player");
        CompletableFuture<String> result = new CompletableFuture<>();
        Settings s = settings;
        ChatProvider p = provider;
        TextSafety filter = safety;
        String question = prompt == null ? "" : prompt.strip();
        String who = caller.getName() + " / " + player.getName();

        try {
            if (!s.enabled()) {
                throw new ForgeAIException(s.disabledReason());
            }
            if (question.isEmpty()) {
                throw new ForgeAIException("Ask something first!");
            }
            if (question.length() > s.maxPromptChars()) {
                throw new ForgeAIException("That's too long. Keep it under " + s.maxPromptChars() + " characters.");
            }
            if (filter.containsBlocked(question) || filter.containsBlocked(persona == null ? "" : persona)) {
                usageLog.record(caller, player, "blocked-input", question, null);
                throw new ForgeAIException("Let's keep it school-friendly.");
            }
            limiter.tryAcquire(player.getUniqueId()).ifPresent(reason -> {
                throw new ForgeAIException(reason);
            });
        } catch (ForgeAIException denied) {
            finishOnMainThread(result, null, denied);
            return result;
        }

        p.complete(buildMessages(s, persona, history, question)).whenComplete((reply, error) -> {
            if (error != null) {
                ForgeAIException failure = asForgeException(error);
                core.getLogger().log(Level.WARNING, "AI request failed (" + who + "): " + failure.getMessage());
                usageLog.record(caller, player, "error", question, null);
                finishOnMainThread(result, null, failure);
                return;
            }
            String cleaned = TextSafety.clean(reply, s.maxReplyChars());
            if (cleaned.isEmpty()) {
                usageLog.record(caller, player, "empty", question, reply);
                finishOnMainThread(result, null, new ForgeAIException("The AI didn't have anything to say. Try asking differently."));
                return;
            }
            if (filter.containsBlocked(cleaned)) {
                core.getLogger().warning("Filtered an AI reply that contained a blocked word (" + who + ").");
                usageLog.record(caller, player, "blocked-reply", question, reply);
                cleaned = "(That reply got filtered. Try asking something else.)";
            } else {
                usageLog.record(caller, player, "ok", question, cleaned);
            }
            finishOnMainThread(result, cleaned, null);
        });
        return result;
    }

    static List<Message> buildMessages(Settings s, String persona, List<ChatTurn> history, String question) {
        String role = persona == null ? "" : persona.strip();
        if (role.length() > MAX_PERSONA_CHARS) {
            role = role.substring(0, MAX_PERSONA_CHARS);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", role.isEmpty() ? s.preamble() : s.preamble() + "\n\n" + role));

        List<ChatTurn> turns = history == null ? List.of() : history;
        int from = Math.max(0, turns.size() - s.maxHistoryTurns());
        for (ChatTurn turn : turns.subList(from, turns.size())) {
            String text = turn.text().length() > s.maxReplyChars() ? turn.text().substring(0, s.maxReplyChars()) : turn.text();
            messages.add(new Message(turn.role() == ChatTurn.Role.PLAYER ? "user" : "assistant", text));
        }
        messages.add(new Message("user", question));
        return messages;
    }

    /** Always completes on a later server tick, so callers' callbacks run on the main thread. */
    private void finishOnMainThread(CompletableFuture<String> result, String value, Throwable error) {
        Runnable complete = () -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
        };
        if (core.isEnabled()) {
            core.getServer().getScheduler().runTask(core, complete);
        } else {
            complete.run();
        }
    }

    private static ForgeAIException asForgeException(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof ForgeAIException forge) {
            return forge;
        }
        return new ForgeAIException("The AI is having trouble right now. Try again in a bit.", cause.toString(), cause);
    }
}
