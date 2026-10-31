package io.github.techforgecwi.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The only way TechForge plugins talk to an AI model.
 *
 * <p>ForgeCore holds the API key, enforces rate limits and adds the server's safety rules, so your plugin
 * never touches any of that. Add {@code depend: [ForgeCore]} to your plugin.yml, then:
 *
 * <pre>{@code
 * ForgeAI.get().ask(this, player, "You are a grumpy blacksmith.", "Can you fix my sword?")
 *     .thenAccept(reply -> player.sendMessage(Component.text(reply)))
 *     .exceptionally(error -> { player.sendMessage(ForgeAI.friendlyError(error)); return null; });
 * }</pre>
 *
 * <p>Callbacks run on the main server thread, so it's safe to change the world (spawn things, move
 * entities, give items) inside {@code thenAccept}.
 */
public interface ForgeAI {

    /**
     * Asks the model one question.
     *
     * @param caller  your plugin ({@code this})
     * @param player  who is asking; their rate limit is used
     * @param persona who the AI should be, e.g. "You are Oracle, a wise old librarian."
     * @param prompt  what the player said
     */
    default CompletableFuture<String> ask(Plugin caller, Player player, String persona, String prompt) {
        return ask(caller, player, persona, List.of(), prompt);
    }

    /**
     * Same as above, with earlier turns of the conversation so the AI remembers context.
     * Keep history short (a few turns); ForgeCore trims it if it gets long.
     */
    CompletableFuture<String> ask(Plugin caller, Player player, String persona, List<ChatTurn> history, String prompt);

    /** Finds the running ForgeCore. Throws if ForgeCore isn't installed or your plugin.yml is missing depend. */
    static ForgeAI get() {
        ForgeAI service = Bukkit.getServicesManager().load(ForgeAI.class);
        if (service == null) {
            throw new IllegalStateException("ForgeCore isn't enabled. Add 'depend: [ForgeCore]' to your plugin.yml.");
        }
        return service;
    }

    /** Turns any error from {@link #ask} into a short message that's safe to show the player. */
    static Component friendlyError(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String message = cause instanceof ForgeAIException forge
                ? forge.playerMessage()
                : "The AI is having trouble right now. Try again in a bit.";
        return Component.text(message, NamedTextColor.GRAY);
    }
}
