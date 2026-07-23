// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for {@link SchedulerMdcScope}. Verifies that the snapshot/restore semantics correctly
 * preserve prior MDC values (or absence) per key, rather than blindly removing the keys on cleanup
 * — which is the regression the holder-side {@code MDCContexts.bindAll} pattern was already
 * designed to avoid.
 */
class SchedulerMdcScopeTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("absent keys are removed on close")
    void absentKeysRemovedOnClose() {
        assertNull(MDC.get("k1"));
        try (SchedulerMdcScope scope = SchedulerMdcScope.install(Map.of("k1", "v1"))) {
            assertEquals("v1", MDC.get("k1"));
        }
        assertNull(MDC.get("k1"));
    }

    @Test
    @DisplayName("prior values are restored on close (not removed)")
    void priorValuesRestored() {
        MDC.put("k1", "prior");
        try (SchedulerMdcScope scope = SchedulerMdcScope.install(Map.of("k1", "enriched"))) {
            assertEquals("enriched", MDC.get("k1"));
        }
        assertEquals("prior", MDC.get("k1"), "prior MDC value must be restored on scope close, not removed");
    }

    @Test
    @DisplayName("mixed prior/absent keys restore correctly")
    void mixedKeysRestoreCorrectly() {
        MDC.put("withPrior", "prior");
        try (SchedulerMdcScope scope = SchedulerMdcScope.install(Map.of(
                "withPrior", "enrichedA",
                "absent", "enrichedB"))) {
            assertEquals("enrichedA", MDC.get("withPrior"));
            assertEquals("enrichedB", MDC.get("absent"));
        }
        assertEquals("prior", MDC.get("withPrior"));
        assertNull(MDC.get("absent"));
    }

    @Test
    @DisplayName("close is idempotent")
    void closeIsIdempotent() {
        MDC.put("k1", "prior");
        SchedulerMdcScope scope = SchedulerMdcScope.install(Map.of("k1", "enriched"));
        scope.close();
        // Second close is a no-op even though we manually mutate in between.
        MDC.put("k1", "manual");
        scope.close();
        assertEquals("manual", MDC.get("k1"), "second close must not re-restore prior value");
    }

    @Test
    @DisplayName("empty enrichment is a no-op")
    void emptyEnrichmentNoOp() {
        MDC.put("k1", "untouched");
        try (SchedulerMdcScope scope = SchedulerMdcScope.install(Map.of())) {
            assertEquals("untouched", MDC.get("k1"));
        }
        assertEquals("untouched", MDC.get("k1"));
    }
}
