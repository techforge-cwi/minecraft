package io.github.techforgecwi.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextSafetyTest {

    private final TextSafety safety = new TextSafety(List.of("# comment line", "", "creeper", "dark magic"));

    @Test
    void matchesWholeWordsIgnoringCaseAndAccents() {
        assertTrue(safety.containsBlocked("Watch out, a CREEPER!"));
        assertTrue(safety.containsBlocked("crééper"));
        assertTrue(safety.containsBlocked("teach me dark magic"));
        assertFalse(safety.containsBlocked("creepers"));
        assertFalse(safety.containsBlocked("dark chocolate magic"));
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        assertFalse(safety.containsBlocked("# comment line"));
        assertFalse(new TextSafety(List.of("", "  ", "# nothing")).containsBlocked("anything at all"));
    }

    @Test
    void cleanStripsFormattingCodesControlCharsAndMarkdown() {
        assertEquals("Hello brave player", TextSafety.clean("§cHello §l**brave**\u0000 `player`", 100));
        assertEquals("R&D stays", TextSafety.clean("R&D stays", 100));
        assertEquals("Title line two", TextSafety.clean("## Title\n\nline   two", 100));
    }

    @Test
    void cleanTruncatesAtAWordBoundary() {
        String cleaned = TextSafety.clean("the quick brown fox jumps over the lazy dog", 20);
        assertEquals("the quick brown fox…", cleaned);
        assertTrue(cleaned.length() <= 20);
    }
}
