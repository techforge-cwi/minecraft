package io.github.techforgecwi.core.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Something that turns a list of chat messages into a reply. */
public interface ChatProvider {

    record Message(String role, String content) {}

    /** Completes off the main thread. Fails with ForgeAIException for expected problems. */
    CompletableFuture<String> complete(List<Message> messages);

    /** Short description for /forgeai status. Never includes the API key. */
    String describe();
}
