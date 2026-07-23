// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RelayCapabilities} — defensive copy of the input map, immutability of
 * the returned map, null rejection, and equality/hashCode.
 */
@DisplayName("RelayCapabilities")
class RelayCapabilitiesTest {

    // --- Helpers ---

    /** Returns a {@link ClaimScope.Destinations} wrapping a fixed singleton set. */
    private static ClaimScope destinationsOf(String target) {
        return ClaimScope.destinations(() -> Set.of(target));
    }

    // --- Test groups ---

    @Nested
    @DisplayName("defensive copy of byType map")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the source map after construction does not affect caps.byType()")
        void mutatingSourceMapDoesNotAffectRecord() {
            Map<DestinationType, ClaimScope> mutable = new HashMap<>();
            mutable.put(DestinationType.KAFKA, ClaimScope.all());
            RelayCapabilities caps = new RelayCapabilities(mutable);

            // Mutation after construction — add a new entry
            mutable.put(DestinationType.SERVICE, destinationsOf("svc-a"));

            assertEquals(1, caps.byType().size(), "byType() must reflect only the entries present at construction");
            assertEquals(ClaimScope.all(), caps.byType().get(DestinationType.KAFKA));
        }

        @Test
        @DisplayName("caps.byType() is a different map instance from the source")
        void byTypeIsNotSameReference() {
            Map<DestinationType, ClaimScope> mutable = new HashMap<>();
            mutable.put(DestinationType.SERVICE, destinationsOf("svc-b"));
            RelayCapabilities caps = new RelayCapabilities(mutable);

            assertNotSame(mutable, caps.byType(), "byType() must be a defensive copy, not the original map");
        }
    }

    @Nested
    @DisplayName("immutability of byType()")
    class Immutability {

        @Test
        @DisplayName("byType() is unmodifiable — put throws UnsupportedOperationException")
        void byTypeIsUnmodifiable() {
            RelayCapabilities caps = new RelayCapabilities(Map.of(DestinationType.KAFKA, ClaimScope.all()));

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> caps.byType().put(DestinationType.SERVICE, destinationsOf("x")),
                    "byType() must be unmodifiable");
        }
    }

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("new RelayCapabilities(null) throws NullPointerException")
        void nullMapThrowsNpe() {
            assertThrows(NullPointerException.class, () -> new RelayCapabilities(null));
        }
    }

    @Nested
    @DisplayName("equality and hashCode")
    class EqualityAndHashCode {

        @Test
        @DisplayName("two records built from equal maps are equal")
        void equalMapsProduceEqualRecords() {
            RelayCapabilities a = new RelayCapabilities(Map.of(DestinationType.KAFKA, ClaimScope.all()));
            RelayCapabilities b = new RelayCapabilities(Map.of(DestinationType.KAFKA, ClaimScope.all()));

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("empty-map record equals another empty-map record")
        void emptyMapsAreEqual() {
            assertEquals(new RelayCapabilities(Map.of()), new RelayCapabilities(Map.of()));
        }
    }
}
