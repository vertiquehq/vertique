// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins the {@link ClaimScope} contract: the two permitted variants, their factories, and null rejection. */
@DisplayName("ClaimScope")
class ClaimScopeTest {

    @Test
    @DisplayName("all() returns the shared All instance")
    void allIsShared() {
        ClaimScope first = ClaimScope.all();
        ClaimScope second = ClaimScope.all();

        assertInstanceOf(ClaimScope.All.class, first);
        assertSame(first, second);
    }

    @Test
    @DisplayName("destinations(supplier) keeps the supplier and evaluates it lazily on each call")
    void destinationsKeepsSupplier() {
        int[] calls = {0};
        Supplier<Set<String>> supplier = () -> {
            calls[0]++;
            return Set.of("orders", "payments");
        };

        ClaimScope scope = ClaimScope.destinations(supplier);

        ClaimScope.Destinations destinations = assertInstanceOf(ClaimScope.Destinations.class, scope);
        assertSame(supplier, destinations.claimableTargets());
        assertEquals(0, calls[0], "construction must not evaluate the supplier");
        assertEquals(
                Set.of("orders", "payments"), destinations.claimableTargets().get());
        assertEquals(1, calls[0]);
    }

    @Test
    @DisplayName("destinations(null) is rejected")
    void destinationsRejectsNull() {
        assertThrows(NullPointerException.class, () -> ClaimScope.destinations(null));
        assertThrows(NullPointerException.class, () -> new ClaimScope.Destinations(null));
    }

    @Test
    @DisplayName("the sealed hierarchy is exhaustive over All and Destinations")
    void sealedHierarchyIsExhaustive() {
        assertEquals("all", describe(ClaimScope.all()));
        assertEquals("destinations", describe(ClaimScope.destinations(Set::of)));
    }

    private static String describe(ClaimScope scope) {
        return switch (scope) {
            case ClaimScope.All all -> "all";
            case ClaimScope.Destinations destinations -> "destinations";
        };
    }
}
