package __PACKAGE__;

import io.github.techforgecwi.core.ForgeAI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** /__ID__ <question> asks the AI something. Start here, then make it your own. */
public final class __MAIN__ extends JavaPlugin {

    // Who the AI pretends to be. ForgeCore adds the server's safety rules in front of this.
    private static final String PERSONA = "You are a helpful guide on a Minecraft server. Answer in one or two sentences.";

    @Override
    public void onEnable() {
        ForgeAI ai = ForgeAI.get();

        registerCommand("__ID__", "Ask the AI something.", (source, args) -> {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Run this in game.");
                return;
            }
            String question = String.join(" ", args);
            if (question.isBlank()) {
                player.sendMessage(Component.text("Usage: /__ID__ <question>", NamedTextColor.GRAY));
                return;
            }

            ai.ask(this, player, PERSONA, question)
                    .thenAccept(reply -> player.sendMessage(Component.text(reply, NamedTextColor.AQUA)))
                    .exceptionally(error -> {
                        player.sendMessage(ForgeAI.friendlyError(error));
                        return null;
                    });
        });
    }
}
