// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.cache.spi.CacheCleanupObservation;
import org.junit.jupiter.api.Test;

class CacheCleanupObservationTest {

    @Test
    void truncatesDimensionLabelsToTheContractBound() {
        String longLabel = "x".repeat(65);

        CacheCleanupObservation observation =
                new CacheCleanupObservation(longLabel, longLabel, longLabel, 1, 2, 3, false);

        assertEquals(64, observation.profile().length());
        assertEquals(64, observation.namespace().length());
        assertEquals(64, observation.outcome().length());
    }

    @Test
    void rejectsMissingBlankAndNegativeObservationValues() {
        assertThrows(
                NullPointerException.class,
                () -> new CacheCleanupObservation(null, "namespace", "success", 0, 0, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheCleanupObservation("profile", " ", "success", 0, 0, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheCleanupObservation("profile", "namespace", "success", -1, 0, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheCleanupObservation("profile", "namespace", "success", 0, -1, 0, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CacheCleanupObservation("profile", "namespace", "success", 0, 0, -1, false));
    }
}
