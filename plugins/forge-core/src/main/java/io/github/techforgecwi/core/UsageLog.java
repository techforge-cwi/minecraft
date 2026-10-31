package io.github.techforgecwi.core;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** One line per AI request in plugins/ForgeCore/usage.log, so officers can see who used what. */
final class UsageLog {

    private final Path file;
    private final Logger logger;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ForgeCore-usage-log");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean logContent;

    UsageLog(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    void setLogContent(boolean logContent) {
        this.logContent = logContent;
    }

    void record(Plugin caller, Player player, String status, String prompt, String reply) {
        StringBuilder line = new StringBuilder()
                .append(Instant.now()).append(' ')
                .append("plugin=").append(caller.getName()).append(' ')
                .append("player=").append(player.getName()).append('(').append(player.getUniqueId()).append(") ")
                .append("status=").append(status).append(' ')
                .append("prompt_chars=").append(prompt == null ? 0 : prompt.length()).append(' ')
                .append("reply_chars=").append(reply == null ? 0 : reply.length());
        if (logContent) {
            line.append(" prompt=").append(quote(prompt)).append(" reply=").append(quote(reply));
        }
        line.append('\n');
        writer.execute(() -> {
            try {
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logger.warning("Couldn't write usage.log: " + e.getMessage());
            }
        });
    }

    void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String quote(String text) {
        return text == null ? "\"\"" : '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + '"';
    }
}
