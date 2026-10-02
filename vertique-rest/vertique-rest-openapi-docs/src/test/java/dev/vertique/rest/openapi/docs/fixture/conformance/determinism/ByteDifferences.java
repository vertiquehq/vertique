// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.determinism;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Describes where two byte sequences first differ, for assertion messages. */
public final class ByteDifferences {

    /** How many bytes an excerpt shows on each side of the first differing offset. */
    private static final int EXCERPT_RADIUS = 40;

    private ByteDifferences() {}

    /**
     * Describes the first difference between two byte sequences.
     *
     * @param expected the expected bytes
     * @param actual the actual bytes
     * @return empty when the sequences are equal; otherwise the first differing byte offset, both
     *     lengths, and a short excerpt of each sequence around that offset
     */
    public static Optional<String> first(byte[] expected, byte[] actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        int offset = Arrays.mismatch(expected, actual);
        if (offset < 0) {
            return Optional.empty();
        }
        return Optional.of("first differing byte at offset " + offset + " (expected length " + expected.length
                + ", actual length " + actual.length + "); expected around it: [" + excerpt(expected, offset)
                + "]; actual around it: [" + excerpt(actual, offset) + "]");
    }

    private static String excerpt(byte[] bytes, int offset) {
        int from = Math.max(0, offset - EXCERPT_RADIUS);
        int to = Math.min(bytes.length, offset + EXCERPT_RADIUS);
        if (from >= to) {
            return "<end of input>";
        }
        String text = new String(bytes, from, to - from, StandardCharsets.UTF_8);
        return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
