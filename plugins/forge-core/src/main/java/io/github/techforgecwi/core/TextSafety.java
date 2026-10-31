package io.github.techforgecwi.core;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Cleans AI output before players see it, and checks text against the officers' blocked-words list. */
final class TextSafety {

    // The client still renders old §-codes inside plain text, so strip them (and any stray §).
    private static final Pattern LEGACY_FORMATTING = Pattern.compile("§[0-9a-fk-orx]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n]]");
    private static final Pattern MARKDOWN = Pattern.compile("(\\*\\*|__|`+|^#+\\s*)", Pattern.MULTILINE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final List<Pattern> blocked;

    TextSafety(List<String> blockedWords) {
        this.blocked = blockedWords.stream()
                .map(String::trim)
                .filter(word -> !word.isEmpty() && !word.startsWith("#"))
                .map(word -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalize(word)) + "(?![\\p{L}\\p{N}])"))
                .toList();
    }

    /** True if the text contains a blocked word or phrase (case- and accent-insensitive, whole words only). */
    boolean containsBlocked(String text) {
        String normalized = normalize(text);
        return blocked.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }

    /** Strips formatting codes, control characters and markdown, collapses whitespace, and truncates. */
    static String clean(String text, int maxChars) {
        String cleaned = LEGACY_FORMATTING.matcher(text).replaceAll("");
        cleaned = CONTROL_CHARS.matcher(cleaned).replaceAll("");
        cleaned = MARKDOWN.matcher(cleaned).replaceAll("");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").strip();
        if (cleaned.length() > maxChars) {
            int cut = cleaned.lastIndexOf(' ', maxChars - 1);
            cleaned = cleaned.substring(0, cut > maxChars / 2 ? cut : maxChars - 1).stripTrailing() + "…";
        }
        return cleaned;
    }

    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT);
    }
}
