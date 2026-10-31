package io.github.techforgecwi.core.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.techforgecwi.core.ForgeAIException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAICompatibleProviderTest {

    private final OpenAICompatibleProvider.Settings settings = new OpenAICompatibleProvider.Settings(
            "https://example.test/v1/", "tiny-model", "", Duration.ofSeconds(5), 150, "max_completion_tokens");

    @Test
    void buildsChatCompletionsBody() {
        String body = OpenAICompatibleProvider.requestBody(settings, List.of(
                new ChatProvider.Message("system", "be nice"),
                new ChatProvider.Message("user", "hi \"there\"")));
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertEquals("tiny-model", json.get("model").getAsString());
        assertEquals(150, json.get("max_completion_tokens").getAsInt());
        assertEquals("hi \"there\"", json.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void parsesFirstChoice() {
        String body = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Greetings, traveler."}}]}""";
        assertEquals("Greetings, traveler.", OpenAICompatibleProvider.parseReply(body));
    }

    @Test
    void emptyOrBrokenRepliesBecomeFriendlyErrors() {
        ForgeAIException empty = assertThrows(ForgeAIException.class, () ->
                OpenAICompatibleProvider.parseReply("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"));
        assertEquals("The AI didn't have anything to say. Try asking differently.", empty.playerMessage());
        assertThrows(ForgeAIException.class, () -> OpenAICompatibleProvider.parseReply("<html>502</html>"));
        assertThrows(ForgeAIException.class, () -> OpenAICompatibleProvider.parseReply("{\"error\":{\"message\":\"bad key\"}}"));
    }
}
