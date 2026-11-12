// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link HealthReport}: the derived aggregate status, the rejection of a status that
 * contradicts the entries, and the defensive, unmodifiable entry list.
 */
class HealthReportTest {

    private static HealthReport.Entry up(String name) {
        return new HealthReport.Entry(name, HealthCheckResult.up());
    }

    private static HealthReport.Entry down(String name) {
        return new HealthReport.Entry(name, HealthCheckResult.down("broken"));
    }

    @Nested
    class AggregateStatus {

        @Test
        @DisplayName("of() with no entries is UP")
        void emptyIsUp() {
            HealthReport report = HealthReport.of(List.of());
            assertEquals(HealthStatus.UP, report.status());
            assertTrue(report.isUp());
            assertTrue(report.checks().isEmpty());
        }

        @Test
        @DisplayName("of() is UP when every entry is UP")
        void allUp() {
            HealthReport report = HealthReport.of(List.of(up("a"), up("b")));
            assertEquals(HealthStatus.UP, report.status());
        }

        @Test
        @DisplayName("of() is DOWN when any entry is DOWN")
        void anyDown() {
            HealthReport report = HealthReport.of(List.of(up("a"), down("b"), up("c")));
            assertEquals(HealthStatus.DOWN, report.status());
            assertFalse(report.isUp());
        }

        @Test
        @DisplayName("the canonical constructor accepts a status that matches the entries")
        void matchingStatusAccepted() {
            assertEquals(HealthStatus.DOWN, new HealthReport(HealthStatus.DOWN, List.of(down("a"))).status());
            assertEquals(HealthStatus.UP, new HealthReport(HealthStatus.UP, List.of(up("a"))).status());
        }

        @Test
        @DisplayName("the canonical constructor rejects a status that contradicts the entries")
        void contradictingStatusRejected() {
            assertThrows(IllegalArgumentException.class, () -> new HealthReport(HealthStatus.UP, List.of(down("a"))));
            assertThrows(IllegalArgumentException.class, () -> new HealthReport(HealthStatus.DOWN, List.of(up("a"))));
            assertThrows(IllegalArgumentException.class, () -> new HealthReport(HealthStatus.DOWN, List.of()));
        }
    }

    @Nested
    class Entries {

        @Test
        @DisplayName("entries keep the supplied order")
        void keepsOrder() {
            HealthReport report = HealthReport.of(List.of(up("z"), up("y"), down("x")));
            assertEquals(
                    List.of("z", "y", "x"),
                    report.checks().stream().map(HealthReport.Entry::name).toList());
        }

        @Test
        @DisplayName("the entry list is a defensive, unmodifiable copy")
        void defensiveUnmodifiableCopy() {
            List<HealthReport.Entry> entries = new ArrayList<>(List.of(up("a")));
            HealthReport report = HealthReport.of(entries);
            entries.add(down("b"));
            assertEquals(1, report.checks().size(), "a later change to the supplied list is not observed");
            assertThrows(
                    UnsupportedOperationException.class, () -> report.checks().add(down("c")));
        }

        @Test
        @DisplayName("an entry exposes its result's status")
        void entryStatus() {
            assertEquals(HealthStatus.UP, up("a").status());
            assertEquals(HealthStatus.DOWN, down("a").status());
        }

        @Test
        @DisplayName("nulls are rejected everywhere")
        void nullsRejected() {
            assertThrows(NullPointerException.class, () -> HealthReport.of(null));
            assertThrows(NullPointerException.class, () -> new HealthReport(null, List.of()));
            assertThrows(NullPointerException.class, () -> new HealthReport(HealthStatus.UP, null));
            List<HealthReport.Entry> withNull = new ArrayList<>();
            withNull.add(null);
            assertThrows(NullPointerException.class, () -> HealthReport.of(withNull));
            assertThrows(NullPointerException.class, () -> new HealthReport.Entry(null, HealthCheckResult.up()));
            assertThrows(NullPointerException.class, () -> new HealthReport.Entry("a", null));
        }
    }
}
