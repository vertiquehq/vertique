// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MessageSourceOptions} builder shape and compact-constructor validation
 * per FR-LOC-030..036. The duplicate-basename check (FR-LOC-035) MUST be insensitive to
 * builder call order.
 */
class MessageSourceOptionsBuilderTest {

    @Test
    @DisplayName("FR-LOC-036: basename only builds successfully")
    void basenameOnly() {
        MessageSourceOptions options =
                MessageSourceOptions.builder().basename("messages").build();

        assertEquals("messages", options.basename());
        assertEquals(List.of(), options.fallbackBasenames());
        assertNull(options.classLoader());
        assertNull(options.caller());
    }

    @Test
    @DisplayName("FR-LOC-036: basename + fallbacks via singular adder")
    void basenameWithFallbacks() {
        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("customer-messages")
                .fallbackBasename("common-messages")
                .fallbackBasename("framework-messages")
                .build();

        assertEquals("customer-messages", options.basename());
        assertEquals(List.of("common-messages", "framework-messages"), options.fallbackBasenames());
    }

    @Test
    @DisplayName("FR-LOC-033: null basename throws IAE")
    void nullBasenameRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageSourceOptions.builder().basename(null).build());
    }

    @Test
    @DisplayName("FR-LOC-033: blank basename throws IAE")
    void blankBasenameRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MessageSourceOptions.builder().basename("   ").build());
    }

    @Test
    @DisplayName("FR-LOC-034: blank fallback throws IAE")
    void blankFallbackRejected() {
        assertThrows(IllegalArgumentException.class, () -> MessageSourceOptions.builder()
                .basename("messages")
                .fallbackBasename("")
                .build());
    }

    @Test
    @DisplayName("FR-LOC-035: duplicate primary+fallback (.basename then .fallbackBasename) throws IAE")
    void duplicateBasenameOrderOne() {
        assertThrows(IllegalArgumentException.class, () -> MessageSourceOptions.builder()
                .basename("x")
                .fallbackBasename("x")
                .build());
    }

    @Test
    @DisplayName("FR-LOC-035: duplicate primary+fallback (.fallbackBasename then .basename) throws IAE")
    void duplicateBasenameOrderTwo() {
        assertThrows(IllegalArgumentException.class, () -> MessageSourceOptions.builder()
                .fallbackBasename("x")
                .basename("x")
                .build());
    }

    @Test
    @DisplayName("FR-LOC-035: duplicate fallback-fallback throws IAE")
    void duplicateFallbackFallback() {
        assertThrows(IllegalArgumentException.class, () -> MessageSourceOptions.builder()
                .basename("a")
                .fallbackBasename("b")
                .fallbackBasename("b")
                .build());
    }

    @Test
    @DisplayName("Direct constructor with null element in fallback list throws IAE")
    void directConstructorNullElementRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MessageSourceOptions("a", Arrays.asList("b", null), null, null));
    }

    @Test
    @DisplayName("Direct constructor with null fallbackBasenames list normalizes to empty list")
    void directConstructorNullListNormalized() {
        MessageSourceOptions options = new MessageSourceOptions("a", null, null, null);
        assertEquals(List.of(), options.fallbackBasenames());
    }

    @Test
    @DisplayName("fallbackBasenames accessor returns immutable list")
    void fallbackBasenamesImmutable() {
        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("a")
                .fallbackBasename("b")
                .build();

        assertThrows(UnsupportedOperationException.class, () -> options.fallbackBasenames()
                .add("c"));
    }

    @Test
    @DisplayName("classLoader and caller honored when set explicitly")
    void classLoaderAndCallerSet() {
        ClassLoader cl = getClass().getClassLoader();

        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("a")
                .classLoader(cl)
                .caller(MessageSourceOptionsBuilderTest.class)
                .build();

        assertEquals(cl, options.classLoader());
        assertEquals(MessageSourceOptionsBuilderTest.class, options.caller());
    }

    @Test
    @DisplayName("Fallback order is preserved in declared order (matching policy)")
    void fallbackOrderPreserved() {
        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("primary")
                .fallbackBasename("first")
                .fallbackBasename("second")
                .fallbackBasename("third")
                .build();

        assertTrue(options.fallbackBasenames().equals(List.of("first", "second", "third")));
    }
}
