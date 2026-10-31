package io.github.techforgecwi.core.provider;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Canned replies, no network. The default, so anyone can test plugins locally without an API key. */
public final class MockProvider implements ChatProvider {

    @Override
    public CompletableFuture<String> complete(List<Message> messages) {
        String last = messages.isEmpty() ? "" : messages.getLast().content();
        String echo = last.length() > 60 ? last.substring(0, 60) + "..." : last;
        return CompletableFuture.completedFuture(
                "(mock reply) You said: \"" + echo + "\". Set mode: openai in plugins/ForgeCore/config.yml for real answers.");
    }

    @Override
    public String describe() {
        return "mock (no AI calls; canned replies)";
    }
}
