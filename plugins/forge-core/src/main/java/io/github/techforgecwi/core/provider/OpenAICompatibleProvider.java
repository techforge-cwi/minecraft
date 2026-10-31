package io.github.techforgecwi.core.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.techforgecwi.core.ForgeAIException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Any server that speaks the OpenAI /chat/completions format: OpenAI, OpenRouter, Groq, Google Gemini's
 * OpenAI endpoint, Ollama, LM Studio, llama.cpp and many more.
 */
public final class OpenAICompatibleProvider implements ChatProvider {

    public record Settings(String baseUrl, String model, String apiKey, Duration timeout,
                           int maxOutputTokens, String tokenLimitField) {}

    private final Settings settings;
    private final HttpClient http;

    public OpenAICompatibleProvider(Settings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public CompletableFuture<String> complete(List<Message> messages) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(stripSlash(settings.baseUrl()) + "/chat/completions"))
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(settings, messages)));
        if (!settings.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + settings.apiKey());
        }
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        String player = cause instanceof HttpTimeoutException
                                ? "The AI took too long to answer. Try again."
                                : "Couldn't reach the AI right now. Try again in a bit.";
                        throw new ForgeAIException(player, "Request to " + settings.baseUrl() + " failed: " + cause, cause);
                    }
                    if (response.statusCode() == 429) {
                        throw new ForgeAIException("The AI is busy. Try again in a minute.",
                                "Provider rate limit (429): " + truncate(response.body()), null);
                    }
                    if (response.statusCode() / 100 != 2) {
                        throw new ForgeAIException("The AI is having trouble right now. Try again in a bit.",
                                "Provider returned HTTP " + response.statusCode() + ": " + truncate(response.body()), null);
                    }
                    return parseReply(response.body());
                });
    }

    @Override
    public String describe() {
        return "openai-compatible " + settings.model() + " @ " + settings.baseUrl();
    }

    static String requestBody(Settings settings, List<Message> messages) {
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.add("messages", array);
        body.addProperty(settings.tokenLimitField(), settings.maxOutputTokens());
        return body.toString();
    }

    static String parseReply(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
                throw new ForgeAIException("The AI didn't have anything to say. Try asking differently.",
                        "Provider returned an empty reply: " + truncate(body), null);
            }
            return content.getAsString();
        } catch (ForgeAIException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ForgeAIException("The AI is having trouble right now. Try again in a bit.",
                    "Couldn't parse provider reply: " + truncate(body), e);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String truncate(String text) {
        return text == null ? "" : text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
