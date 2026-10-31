package io.github.techforgecwi.core;

import java.util.Objects;

/** One message in a conversation: something the player said, or something the AI replied. */
public record ChatTurn(Role role, String text) {

    public enum Role { PLAYER, AI }

    public ChatTurn {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    public static ChatTurn player(String text) {
        return new ChatTurn(Role.PLAYER, text);
    }

    public static ChatTurn ai(String text) {
        return new ChatTurn(Role.AI, text);
    }
}
