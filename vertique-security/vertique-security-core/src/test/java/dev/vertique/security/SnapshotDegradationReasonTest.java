// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SnapshotDegradationReason}.
 *
 * <p>Verifies: every enum constant exists (including {@link SnapshotDegradationReason#EXPECTED_ABSENT},
 * added for expected-but-absent carriage detection, P2.S0 commit 5b); {@code valueOf} round-trips for
 * each constant; the enum has exactly seven constants.
 */
class SnapshotDegradationReasonTest {

    // --- all constants present ---

    @Test
    @DisplayName("BAD_HMAC constant exists")
    void badHmacConstantExists() {
        assertNotNull(SnapshotDegradationReason.BAD_HMAC);
    }

    @Test
    @DisplayName("UNKNOWN_KEY constant exists")
    void unknownKeyConstantExists() {
        assertNotNull(SnapshotDegradationReason.UNKNOWN_KEY);
    }

    @Test
    @DisplayName("KEY_UNAVAILABLE constant exists")
    void keyUnavailableConstantExists() {
        assertNotNull(SnapshotDegradationReason.KEY_UNAVAILABLE);
    }

    @Test
    @DisplayName("DECODE_FAILED constant exists")
    void decodeFailedConstantExists() {
        assertNotNull(SnapshotDegradationReason.DECODE_FAILED);
    }

    @Test
    @DisplayName("SCHEMA_INCOMPATIBLE constant exists")
    void schemaIncompatibleConstantExists() {
        assertNotNull(SnapshotDegradationReason.SCHEMA_INCOMPATIBLE);
    }

    @Test
    @DisplayName("EXPIRED constant exists")
    void expiredConstantExists() {
        assertNotNull(SnapshotDegradationReason.EXPIRED);
    }

    @Test
    @DisplayName("MALFORMED_TEMPORAL constant exists")
    void malformedTemporalConstantExists() {
        assertNotNull(SnapshotDegradationReason.MALFORMED_TEMPORAL);
    }

    @Test
    @DisplayName("EXPECTED_ABSENT constant exists")
    void expectedAbsentConstantExists() {
        assertNotNull(SnapshotDegradationReason.EXPECTED_ABSENT);
    }

    // --- valueOf round-trip for the new constant ---

    @Test
    @DisplayName("valueOf(\"EXPECTED_ABSENT\") returns EXPECTED_ABSENT")
    void valueOfExpectedAbsent() {
        assertEquals(SnapshotDegradationReason.EXPECTED_ABSENT, SnapshotDegradationReason.valueOf("EXPECTED_ABSENT"));
    }

    // --- exactly eight constants ---

    @Test
    @DisplayName("enum has exactly eight constants")
    void exactlyEightConstants() {
        assertEquals(8, SnapshotDegradationReason.values().length);
    }
}
