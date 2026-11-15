package io.jenkins.plugins.pipelinebuildretry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormTextTest {

    @Test
    void linesDropBlanksAndLineEndings() {
        assertEquals(List.of("a", " b "), FormText.lines("a\r\n\n \n b \n"));
        assertEquals(List.of(), FormText.lines(null));
        assertEquals(List.of(), FormText.lines(""));
    }

    @Test
    void overridesSplitAtTheFirstEquals() {
        Map<String, String> m = FormText.overrides(" A =1\nB=x=y\nC=");
        assertEquals(
                List.of(Map.entry("A", "1"), Map.entry("B", "x=y"), Map.entry("C", "")), new ArrayList<>(m.entrySet()));
        assertThrows(IllegalArgumentException.class, () -> FormText.overrides("novalue"));
        assertThrows(IllegalArgumentException.class, () -> FormText.overrides(" =1"));
    }

    @Test
    void patternCheckNamesThePositionNotTheText() {
        assertEquals(FormValidation.Kind.OK, FormText.checkPatterns(List.of("a+", "b?"), true).kind);
        FormValidation one = FormText.checkPatterns(List.of("tok3n-("), true);
        assertEquals(FormValidation.Kind.ERROR, one.kind);
        assertTrue(one.getMessage().startsWith("This is not a valid regular expression"), one.getMessage());
        FormValidation two = FormText.checkPatterns(List.of("ok", "tok3n-["), true);
        assertTrue(two.getMessage().startsWith("Pattern #2 is not"), two.getMessage());
        assertFalse(two.getMessage().contains("tok3n"), "pattern text not echoed");
        assertEquals(FormValidation.Kind.WARNING, FormText.checkPatterns(List.of("a"), false).kind, "inactive");
        assertEquals(FormValidation.Kind.OK, FormText.checkPatterns(List.of(), false).kind, "empty and inactive");
    }
}
