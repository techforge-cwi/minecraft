package io.github.techforgecwi.oraclenpc;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/** /oracle spawn and /oracle remove. Officers only (see plugin.yml permissions). */
final class OracleCommand implements BasicCommand {

    private final OracleNpcPlugin plugin;

    OracleCommand(OracleNpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Run this in game.", NamedTextColor.RED));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "spawn" -> {
                plugin.spawnOracle(player);
                player.sendMessage(Component.text("The Oracle appears. Right-click it to talk.", NamedTextColor.LIGHT_PURPLE));
            }
            case "remove" -> {
                List<Entity> nearby = player.getNearbyEntities(5, 5, 5).stream().filter(plugin::isOracle).toList();
                nearby.forEach(Entity::remove);
                player.sendMessage(Component.text("Removed " + nearby.size() + " Oracle(s) within 5 blocks.", NamedTextColor.YELLOW));
            }
            default -> player.sendMessage(Component.text("Usage: /oracle <spawn|remove>", NamedTextColor.GRAY));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("spawn", "remove") : List.of();
    }

    @Override
    public String permission() {
        return "techforge.oracle.manage";
    }
}
