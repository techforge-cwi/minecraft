package io.github.techforgecwi.core;

import io.github.techforgecwi.core.provider.ChatProvider.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildMessagesTest {

    private final ForgeAIService.Settings settings =
            new ForgeAIService.Settings(true, "", 300, 500, 2, "SAFETY RULES");

    @Test
    void preambleAlwaysComesFirstAndCantBeDropped() {
        List<Message> messages = ForgeAIService.buildMessages(settings, "You are a pirate.", List.of(), "hello");
        assertEquals("system", messages.getFirst().role());
        assertTrue(messages.getFirst().content().startsWith("SAFETY RULES"));
        assertTrue(messages.getFirst().content().endsWith("You are a pirate."));
        assertEquals(new Message("user", "hello"), messages.getLast());
    }

    @Test
    void keepsOnlyTheMostRecentHistoryTurns() {
        List<ChatTurn> history = List.of(
                ChatTurn.player("one"), ChatTurn.ai("two"), ChatTurn.player("three"), ChatTurn.ai("four"));
        List<Message> messages = ForgeAIService.buildMessages(settings, "", history, "five");
        assertEquals(List.of(
                new Message("system", "SAFETY RULES"),
                new Message("user", "three"),
                new Message("assistant", "four"),
                new Message("user", "five")), messages);
    }
}
