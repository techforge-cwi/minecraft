package io.github.techforgecwi.core;

import io.github.techforgecwi.core.provider.ChatProvider;
import io.github.techforgecwi.core.provider.MockProvider;
import io.github.techforgecwi.core.provider.OpenAICompatibleProvider;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Loads config, builds the provider, and offers {@link ForgeAI} to every other plugin. */
public final class ForgeCorePlugin extends JavaPlugin {

    static final String API_KEY_ENV = "FORGE_AI_API_KEY";
    static final String API_KEY_FILE = "api-key.txt";

    private UsageLog usageLog;
    private ForgeAIService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(dataPath("blocked-words.txt"))) {
            saveResource("blocked-words.txt", false);
        }
        usageLog = new UsageLog(dataPath("usage.log"), getLogger());
        UsageLimiter limiter = new UsageLimiter(new UsageLimiter.Limits(Duration.ZERO, 0, 0), Clock.systemDefaultZone());
        service = new ForgeAIService(this, usageLog, limiter);
        applyConfig();

        getServer().getServicesManager().register(ForgeAI.class, service, this, ServicePriority.Normal);
        registerCommand("forgeai", "Check, reload, or turn off TechForge's AI.", new AdminCommand());
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (usageLog != null) {
            usageLog.close();
        }
    }

    /** Reads config.yml and blocked-words.txt. Safe to call again for /forgeai reload. */
    void applyConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();

        String mode = config.getString("mode", "mock").toLowerCase(Locale.ROOT);
        boolean enabled = config.getBoolean("enabled", true);
        String disabledReason = "The AI is turned off right now.";
        ChatProvider provider = new MockProvider();

        if (mode.equals("openai")) {
            String baseUrl = config.getString("provider.base-url", "").strip();
            String model = config.getString("provider.model", "").strip();
            if (baseUrl.isEmpty() || model.isEmpty()) {
                getLogger().severe("mode is 'openai' but provider.base-url or provider.model is empty. AI is OFF until you fix config.yml.");
                enabled = false;
                disabledReason = "The AI isn't set up yet. Tell an officer.";
            } else {
                provider = new OpenAICompatibleProvider(new OpenAICompatibleProvider.Settings(
                        baseUrl,
                        model,
                        readApiKey(),
                        Duration.ofSeconds(Math.max(1, config.getInt("provider.timeout-seconds", 20))),
                        Math.max(16, config.getInt("provider.max-output-tokens", 200)),
                        config.getString("provider.token-limit-field", "max_tokens")));
            }
        } else if (!mode.equals("mock")) {
            getLogger().severe("Unknown mode '" + mode + "'. Use 'mock' or 'openai'. Using mock.");
        }

        ForgeAIService.Settings settings = new ForgeAIService.Settings(
                enabled,
                disabledReason,
                Math.max(1, config.getInt("limits.max-prompt-chars", 300)),
                Math.max(40, config.getInt("limits.max-reply-chars", 500)),
                Math.max(0, config.getInt("limits.max-history-turns", 8)),
                config.getString("safety.preamble", "").strip());
        UsageLimiter.Limits limits = new UsageLimiter.Limits(
                Duration.ofSeconds(Math.max(0, config.getInt("limits.player-cooldown-seconds", 8))),
                Math.max(0, config.getInt("limits.player-daily-requests", 40)),
                Math.max(0, config.getInt("limits.server-daily-requests", 800)));

        usageLog.setLogContent(config.getBoolean("safety.log-content", false));
        service.configure(settings, provider, new TextSafety(readBlockedWords(config.getString("safety.blocked-words-file", "blocked-words.txt"))), limits);
        getLogger().info("AI " + (enabled ? "ON" : "OFF") + " using " + provider.describe());
    }

    private String readApiKey() {
        String fromEnv = System.getenv(API_KEY_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.strip();
        }
        Path file = dataPath(API_KEY_FILE);
        if (Files.exists(file)) {
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                        .map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
            } catch (IOException e) {
                getLogger().severe("Couldn't read " + API_KEY_FILE + ": " + e.getMessage());
            }
        }
        getLogger().warning("No API key found (set " + API_KEY_ENV + " or create plugins/ForgeCore/" + API_KEY_FILE
                + "). That's fine for Ollama and other local servers.");
        return "";
    }

    private List<String> readBlockedWords(String fileName) {
        try {
            return Files.readAllLines(dataPath(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Couldn't read " + fileName + ", so no words are blocked: " + e.getMessage());
            return List.of();
        }
    }

    private Path dataPath(String name) {
        return getDataFolder().toPath().resolve(name);
    }

    private final class AdminCommand implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "status" -> {
                    ForgeAIService.Settings s = service.settings();
                    sender.sendMessage(Component.text("ForgeCore AI: " + (s.enabled() ? "ON" : "OFF"),
                            s.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
                    sender.sendMessage(Component.text("Provider: " + service.providerDescription(), NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("Requests today: " + service.requestsToday() + " / "
                            + getConfig().getInt("limits.server-daily-requests", 800), NamedTextColor.GRAY));
                }
                case "reload" -> {
                    applyConfig();
                    sender.sendMessage(Component.text("Reloaded ForgeCore config.", NamedTextColor.GREEN));
                }
                case "on", "off" -> {
                    getConfig().set("enabled", sub.equals("on"));
                    saveConfig();
                    applyConfig();
                    sender.sendMessage(Component.text("AI turned " + sub + ".", NamedTextColor.YELLOW));
                }
                default -> sender.sendMessage(Component.text("Usage: /forgeai <status|reload|on|off>", NamedTextColor.GRAY));
            }
        }

        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            return args.length <= 1 ? List.of("status", "reload", "on", "off") : List.of();
        }

        @Override
        public String permission() {
            return "techforge.forgeai.admin";
        }
    }
}
