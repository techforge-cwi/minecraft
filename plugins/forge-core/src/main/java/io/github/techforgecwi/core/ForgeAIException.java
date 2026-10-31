package io.github.techforgecwi.core;

/** An expected failure (rate limit, AI turned off, provider error) with a message that's safe to show players. */
public class ForgeAIException extends RuntimeException {

    private final String playerMessage;

    public ForgeAIException(String playerMessage) {
        this(playerMessage, playerMessage, null);
    }

    public ForgeAIException(String playerMessage, String logMessage, Throwable cause) {
        super(logMessage, cause);
        this.playerMessage = playerMessage;
    }

    public String playerMessage() {
        return playerMessage;
    }
}
