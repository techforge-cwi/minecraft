package io.github.techforgecwi.oraclenpc;

import io.github.techforgecwi.core.ChatTurn;
import io.github.techforgecwi.core.ForgeAI;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An AI villager. Right-click it, then type in chat to talk to it. Only you see the conversation.
 *
 * <p>Want your own NPC? Copy this folder, then change NPC_NAME and PERSONA. That's most of it.
 */
public final class OracleNpcPlugin extends JavaPlugin implements Listener {

    private static final String NPC_NAME = "Oracle";
    private static final String PERSONA = """
            You are the Oracle, an old villager librarian who has read every book in the world.
            You answer questions about Minecraft and about AI with short riddles and real, accurate advice.
            You call the player "seeker".""";

    private static final long CONVERSATION_TIMEOUT_MS = 60_000;

    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private NamespacedKey npcTag;
    private ForgeAI ai;

    @Override
    public void onEnable() {
        npcTag = new NamespacedKey(this, "npc");
        ai = ForgeAI.get();
        getServer().getPluginManager().registerEvents(this, this);
        registerCommand("oracle", "Spawn or remove the Oracle NPC.", new OracleCommand(this));
    }

    /** Right-clicking the Oracle starts a conversation instead of opening the trade screen. */
    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isOracle(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        conversations.put(player.getUniqueId(), new Conversation(event.getRightClicked().getUniqueId()));
        player.sendMessage(Component.text(NPC_NAME + " is listening. Type your question in chat, or 'bye' to leave.",
                NamedTextColor.LIGHT_PURPLE));
    }

    /** While you're talking to the Oracle, your chat goes to it instead of to everyone. Runs off the main thread. */
    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Conversation conversation = conversations.get(player.getUniqueId());
        if (conversation == null) {
            return;
        }
        if (conversation.isExpired(CONVERSATION_TIMEOUT_MS)) {
            conversations.remove(player.getUniqueId());
            return; // Let the message go to public chat as normal.
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();

        if (text.equalsIgnoreCase("bye")) {
            conversations.remove(player.getUniqueId());
            player.sendMessage(npcSays("Farewell, seeker."));
            return;
        }
        if (!conversation.tryStartThinking()) {
            player.sendMessage(Component.text(NPC_NAME + " is still thinking...", NamedTextColor.GRAY));
            return;
        }

        player.sendMessage(Component.text("You → " + NPC_NAME + ": " + text, NamedTextColor.GRAY));
        player.sendActionBar(Component.text(NPC_NAME + " is thinking...", NamedTextColor.LIGHT_PURPLE));

        ai.ask(this, player, PERSONA, conversation.history(), text)
                .thenAccept(reply -> {
                    // ForgeAI runs this on the main thread, so touching entities here is safe.
                    conversation.remember(text, reply);
                    player.sendMessage(npcSays(reply));
                    if (getServer().getEntity(conversation.npcId()) instanceof Villager oracle) {
                        oracle.shakeHead();
                        player.playSound(Sound.sound(Key.key("entity.villager.ambient"), Sound.Source.NEUTRAL, 1f, 1f), oracle);
                    }
                })
                .exceptionally(error -> {
                    player.sendMessage(ForgeAI.friendlyError(error));
                    return null;
                })
                .whenComplete((ignored, error) -> conversation.doneThinking());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        conversations.remove(event.getPlayer().getUniqueId());
    }

    Villager spawnOracle(Player where) {
        return where.getWorld().spawn(where.getLocation(), Villager.class, villager -> {
            villager.customName(Component.text(NPC_NAME, NamedTextColor.LIGHT_PURPLE));
            villager.setCustomNameVisible(true);
            villager.setProfession(Villager.Profession.LIBRARIAN);
            villager.setAI(false);
            villager.setInvulnerable(true);
            villager.setSilent(true);
            villager.setPersistent(true);
            villager.setRemoveWhenFarAway(false);
            villager.getPersistentDataContainer().set(npcTag, PersistentDataType.BYTE, (byte) 1);
        });
    }

    boolean isOracle(Entity entity) {
        return entity instanceof Villager && entity.getPersistentDataContainer().has(npcTag, PersistentDataType.BYTE);
    }

    private static Component npcSays(String text) {
        return Component.text("<" + NPC_NAME + "> ", NamedTextColor.LIGHT_PURPLE).append(Component.text(text, NamedTextColor.WHITE));
    }

    /** One player's chat with the Oracle. Touched from the chat thread and the main thread, so it's synchronized. */
    private static final class Conversation {
        private static final int MAX_TURNS = 6;

        private final UUID npcId;
        private final Deque<ChatTurn> turns = new ArrayDeque<>();
        private long lastActive = System.currentTimeMillis();
        private boolean thinking;

        Conversation(UUID npcId) {
            this.npcId = npcId;
        }

        UUID npcId() {
            return npcId;
        }

        synchronized boolean isExpired(long timeoutMs) {
            return !thinking && System.currentTimeMillis() - lastActive > timeoutMs;
        }

        synchronized boolean tryStartThinking() {
            if (thinking) {
                return false;
            }
            thinking = true;
            lastActive = System.currentTimeMillis();
            return true;
        }

        synchronized void doneThinking() {
            thinking = false;
            lastActive = System.currentTimeMillis();
        }

        synchronized List<ChatTurn> history() {
            return List.copyOf(turns);
        }

        synchronized void remember(String question, String reply) {
            turns.addLast(ChatTurn.player(question));
            turns.addLast(ChatTurn.ai(reply));
            while (turns.size() > MAX_TURNS) {
                turns.removeFirst();
            }
        }
    }
}
