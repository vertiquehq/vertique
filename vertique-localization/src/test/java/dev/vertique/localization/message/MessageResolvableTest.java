// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Constructor invariants for {@link MessageResolvable} per FR-LOC-022..024.
 */
class MessageResolvableTest {

    @Test
    @DisplayName("FR-LOC-024: null codes list throws NullPointerException")
    void nullCodesListThrowsNpe() {
        assertThrows(NullPointerException.class, () -> new MessageResolvable(null, List.of(), null));
    }

    @Test
    @DisplayName("FR-LOC-024: empty codes list throws IllegalArgumentException")
    void emptyCodesListThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> new MessageResolvable(List.of(), List.of(), null));
    }

    @Test
    @DisplayName("FR-LOC-024: codes containing null element throws NullPointerException")
    void nullCodeElementThrowsNpe() {
        List<String> codes = Arrays.asList("a", null, "b");
        assertThrows(NullPointerException.class, () -> new MessageResolvable(codes, List.of(), null));
    }

    @Test
    @DisplayName("FR-LOC-024: codes containing blank element throws IllegalArgumentException")
    void blankCodeElementThrowsIae() {
        assertThrows(
                IllegalArgumentException.class, () -> new MessageResolvable(List.of("a", "  ", "b"), List.of(), null));
    }

    @Test
    @DisplayName("FR-LOC-023: codes are defensively copied")
    void codesDefensivelyCopied() {
        List<String> input = new ArrayList<>();
        input.add("code1");
        input.add("code2");

        MessageResolvable r = new MessageResolvable(input, null, null);

        // Mutating the input list MUST NOT affect the resolvable's codes view
        input.add("code3");
        assertEquals(List.of("code1", "code2"), r.codes());
    }

    @Test
    @DisplayName("Accessor returns immutable codes view")
    void codesImmutable() {
        MessageResolvable r = new MessageResolvable(List.of("a"), null, null);
        assertThrows(UnsupportedOperationException.class, () -> r.codes().add("b"));
    }

    @Test
    @DisplayName("args == null normalizes to empty list (FR-LOC-083 alignment)")
    void nullArgsNormalizesToEmpty() {
        MessageResolvable r = new MessageResolvable(List.of("a"), null, null);
        assertEquals(List.of(), r.args());
    }

    @Test
    @DisplayName("args containing null element is preserved (MessageFormat-friendly)")
    void nullArgElementPreserved() {
        MessageResolvable r = new MessageResolvable(List.of("code"), Arrays.asList("foo", null, "bar"), null);

        assertEquals(3, r.args().size());
        assertEquals("foo", r.args().get(0));
        assertNull(r.args().get(1));
        assertEquals("bar", r.args().get(2));
    }

    @Test
    @DisplayName("args is defensively copied")
    void argsDefensivelyCopied() {
        List<Object> input = new ArrayList<>();
        input.add("first");

        MessageResolvable r = new MessageResolvable(List.of("a"), input, null);

        input.add("second");
        assertEquals(1, r.args().size());
        assertEquals("first", r.args().get(0));
    }

    @Test
    @DisplayName("Accessor returns immutable args view")
    void argsImmutable() {
        MessageResolvable r = new MessageResolvable(List.of("a"), List.of("x"), null);
        assertThrows(UnsupportedOperationException.class, () -> r.args().add("y"));
    }

    @Test
    @DisplayName("defaultMessage is preserved verbatim including null")
    void defaultMessagePreserved() {
        assertSame("fallback", new MessageResolvable(List.of("a"), null, "fallback").defaultMessage());
        assertNull(new MessageResolvable(List.of("a"), null, null).defaultMessage());
    }

    @Test
    @DisplayName("Constructor accepts valid input and copies preserve insertion order")
    void happyPath() {
        MessageResolvable r =
                new MessageResolvable(List.of("checkout.success", "generic.success"), List.of(1, "two"), "default");

        assertEquals(2, r.codes().size());
        assertEquals("checkout.success", r.codes().get(0));
        assertEquals("generic.success", r.codes().get(1));
        assertEquals(2, r.args().size());
        assertEquals(1, r.args().get(0));
        assertEquals("two", r.args().get(1));
        assertTrue(r.defaultMessage().startsWith("default"));

        // Defensive copy implies new collection identity
        assertNotSame(r.codes(), r.args());
    }
}
