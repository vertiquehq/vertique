// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CronTargetReference} — parse, toCanonical, and round-trip correctness.
 */
@DisplayName("CronTargetReference")
class CronTargetReferenceTest {

    // --- parse ---

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("service: prefix returns ServiceTarget with correct stableTargetId")
        void parseServiceTarget() {
            CronTargetReference ref = CronTargetReference.parse("service:foo.bar.op");

            CronTargetReference.ServiceTarget st = assertInstanceOf(CronTargetReference.ServiceTarget.class, ref);
            assertEquals("foo.bar.op", st.stableTargetId());
        }

        @Test
        @DisplayName("eventbus: prefix returns EventBusTarget with correct address")
        void parseEventBusTarget() {
            CronTargetReference ref = CronTargetReference.parse("eventbus:some/address");

            CronTargetReference.EventBusTarget et = assertInstanceOf(CronTargetReference.EventBusTarget.class, ref);
            assertEquals("some/address", et.address());
        }

        @Test
        @DisplayName("service: with dot-delimited multi-segment id parses correctly")
        void parseServiceTargetMultiSegment() {
            CronTargetReference ref = CronTargetReference.parse("service:integration.user-service.get-user");

            CronTargetReference.ServiceTarget st = assertInstanceOf(CronTargetReference.ServiceTarget.class, ref);
            assertEquals("integration.user-service.get-user", st.stableTargetId());
        }

        @Test
        @DisplayName("eventbus: with slash-delimited address parses correctly")
        void parseEventBusTargetWithSlashes() {
            CronTargetReference ref = CronTargetReference.parse("eventbus:services/reporting/generate");

            CronTargetReference.EventBusTarget et = assertInstanceOf(CronTargetReference.EventBusTarget.class, ref);
            assertEquals("services/reporting/generate", et.address());
        }

        @Test
        @DisplayName("unknown scheme throws IllegalArgumentException")
        void parseUnknownSchemeThrows() {
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> CronTargetReference.parse("handler:foo.bar"));
            assertEquals(
                    "Unknown target reference scheme in 'handler:foo.bar' — expected 'service:' or 'eventbus:'",
                    ex.getMessage());
        }

        @Test
        @DisplayName("bare string with no scheme throws IllegalArgumentException")
        void parseBareStringThrows() {
            assertThrows(IllegalArgumentException.class, () -> CronTargetReference.parse("someaddress"));
        }

        @Test
        @DisplayName("blank string throws IllegalArgumentException")
        void parseBlankThrows() {
            assertThrows(IllegalArgumentException.class, () -> CronTargetReference.parse("  "));
        }

        @Test
        @DisplayName("null throws IllegalArgumentException")
        void parseNullThrows() {
            assertThrows(IllegalArgumentException.class, () -> CronTargetReference.parse(null));
        }

        @Test
        @DisplayName("service: with blank id throws IllegalArgumentException")
        void parseServiceBlankIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> CronTargetReference.parse("service:"));
        }

        @Test
        @DisplayName("eventbus: with blank address throws IllegalArgumentException")
        void parseEventBusBlankAddressThrows() {
            assertThrows(IllegalArgumentException.class, () -> CronTargetReference.parse("eventbus:"));
        }
    }

    // --- toCanonical ---

    @Nested
    @DisplayName("toCanonical")
    class ToCanonical {

        @Test
        @DisplayName("ServiceTarget toCanonical returns service:<id>")
        void serviceTargetCanonical() {
            CronTargetReference.ServiceTarget st = new CronTargetReference.ServiceTarget("foo.bar.op");
            assertEquals("service:foo.bar.op", st.toCanonical());
        }

        @Test
        @DisplayName("EventBusTarget toCanonical returns eventbus:<address>")
        void eventBusTargetCanonical() {
            CronTargetReference.EventBusTarget et = new CronTargetReference.EventBusTarget("some/address");
            assertEquals("eventbus:some/address", et.toCanonical());
        }
    }

    // --- round-trip ---

    @Nested
    @DisplayName("round-trip")
    class RoundTrip {

        @Test
        @DisplayName("ServiceTarget canonical form round-trips through parse")
        void serviceTargetRoundTrip() {
            CronTargetReference original = new CronTargetReference.ServiceTarget("reporting.report-service.generate");
            CronTargetReference parsed = CronTargetReference.parse(original.toCanonical());

            CronTargetReference.ServiceTarget st = assertInstanceOf(CronTargetReference.ServiceTarget.class, parsed);
            assertEquals("reporting.report-service.generate", st.stableTargetId());
        }

        @Test
        @DisplayName("EventBusTarget canonical form round-trips through parse")
        void eventBusTargetRoundTrip() {
            CronTargetReference original = new CronTargetReference.EventBusTarget("myapp/reporting/generateReport");
            CronTargetReference parsed = CronTargetReference.parse(original.toCanonical());

            CronTargetReference.EventBusTarget et = assertInstanceOf(CronTargetReference.EventBusTarget.class, parsed);
            assertEquals("myapp/reporting/generateReport", et.address());
        }
    }
}
