// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeferredExecutionOrigin}.
 *
 * <p>Verifies that both components are validated to the same standard in the strict compact
 * constructor — null is rejected with {@link NullPointerException}, blank / oversized /
 * control-character-bearing {@code kind} or {@code reference} with {@link IllegalArgumentException},
 * where "control character" means the Unicode {@code CONTROL} category (C0, C1, DEL) plus the
 * {@code LINE_SEPARATOR} and {@code PARAGRAPH_SEPARATOR} categories — that a valid construction
 * preserves both accessors, and that the boundary-facing
 * {@link DeferredExecutionOrigin#of(String, String)} factory sanitizes both {@code kind} and
 * {@code reference} (strips control/separator characters, truncates to the length bound without
 * splitting a surrogate pair, falls back to the sanitized {@code kind} for a blank result) rather
 * than throwing — {@code of(...)} throws only when {@code kind} is {@code null} or sanitizes to blank.
 */
class DeferredExecutionOriginTest {

    @Test
    @DisplayName("null kind throws NullPointerException")
    void nullKindRejected() {
        assertThrows(NullPointerException.class, () -> new DeferredExecutionOrigin(null, "orders"));
    }

    @Test
    @DisplayName("blank kind throws IllegalArgumentException")
    void blankKindRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin("   ", "orders"));
    }

    @Test
    @DisplayName("null reference throws NullPointerException")
    void nullReferenceRejected() {
        assertThrows(NullPointerException.class, () -> new DeferredExecutionOrigin("delayed-job", null));
    }

    @Test
    @DisplayName("blank reference throws IllegalArgumentException")
    void blankReferenceRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin("delayed-job", "   "));
    }

    @Test
    @DisplayName("reference over max length throws IllegalArgumentException")
    void referenceOverMaxLengthRejected() {
        String tooLong = "a".repeat(257);
        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin("delayed-job", tooLong));
    }

    @Test
    @DisplayName("reference with control character throws IllegalArgumentException")
    void referenceWithControlCharRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin("delayed-job", "a\nb"));
    }

    @Test
    @DisplayName("valid construction preserves both accessors")
    void validConstructionPreservesAccessors() {
        DeferredExecutionOrigin origin = new DeferredExecutionOrigin("delayed-job", "orders");
        assertEquals("delayed-job", origin.kind());
        assertEquals("orders", origin.reference());
    }

    @Test
    @DisplayName("of() strips control characters from reference")
    void ofStripsControlChars() {
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("delayed-job", "a\nb");
        assertEquals("ab", origin.reference());
    }

    @Test
    @DisplayName("of() truncates reference to max length")
    void ofTruncatesToMaxLength() {
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("delayed-job", "a".repeat(300));
        assertEquals(256, origin.reference().length());
    }

    @Test
    @DisplayName("of() falls back to kind when reference is blank")
    void ofBlankReferenceFallsBackToKind() {
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("delayed-job", "   ");
        assertEquals("delayed-job", origin.reference());
    }

    @Test
    @DisplayName("of() falls back to kind when reference is null")
    void ofNullReferenceFallsBackToKind() {
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("delayed-job", null);
        assertEquals("delayed-job", origin.reference());
    }

    @Test
    @DisplayName("of() preserves a clean reference unchanged")
    void ofPreservesCleanReference() {
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("delayed-job", "order.placed");
        assertEquals("order.placed", origin.reference());
    }

    @Test
    @DisplayName("of() never throws for an oversized or control-character-bearing kind")
    void ofNeverThrowsForOversizedOrControlKind() {
        DeferredExecutionOrigin oversized = DeferredExecutionOrigin.of("x".repeat(300), null);
        assertFalse(oversized.reference().isBlank());
        assertTrue(oversized.reference().length() <= 256);
        assertTrue(oversized.reference().codePoints().noneMatch(DeferredExecutionOriginTest::isControlOrSeparator));

        DeferredExecutionOrigin trailingSpaceKind = DeferredExecutionOrigin.of("kind ", "");
        assertFalse(trailingSpaceKind.reference().isBlank());
        assertTrue(trailingSpaceKind.reference().length() <= 256);
        assertTrue(trailingSpaceKind
                .reference()
                .codePoints()
                .noneMatch(DeferredExecutionOriginTest::isControlOrSeparator));
    }

    @Test
    @DisplayName("strict constructor rejects an oversized or control-character-bearing kind")
    void strictCtorRejectsOversizedOrControlKind() {
        String tooLong = "x".repeat(300);
        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin(tooLong, "ref"));
        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin("kind\n", "ref"));
    }

    @Test
    @DisplayName("sanitizer strips C1 controls and Unicode separators")
    void sanitizerStripsC1AndSeparators() {
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("outbox-relay", "a\u0085\u2028b");
        assertEquals("ab", origin.reference());

        assertThrows(IllegalArgumentException.class, () -> new DeferredExecutionOrigin("cron", "a\u0085b"));
    }

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    void truncationNeverSplitsSurrogatePair() {
        String raw = "x".repeat(255) + "😀"; // trailing surrogate pair (U+1F600)
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("cron", raw);
        String ref = origin.reference();

        assertTrue(ref.length() <= 256);
        long highSurrogates =
                ref.chars().filter(c -> Character.isHighSurrogate((char) c)).count();
        long lowSurrogates =
                ref.chars().filter(c -> Character.isLowSurrogate((char) c)).count();
        assertEquals(highSurrogates, lowSurrogates);

        byte[] encoded = ref.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String roundTripped = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(ref, roundTripped);
    }

    private static boolean isControlOrSeparator(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
