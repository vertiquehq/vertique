// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Second half of TP-001: {@link VertiqueJson#resetForTests()} is reachable only when the JVM was
 * <em>started</em> with {@code -Dvertique.json.codec.allowReset=true}.
 *
 * <p>This class runs in its own surefire execution ({@code reset-gate} in {@code vertique-core/pom.xml})
 * whose {@code argLine} deliberately omits the flag; the module's default execution excludes it. The
 * {@code assumeFalse} guard makes a run in the flagged fork (e.g. a hand-typed {@code -Dtest=} that
 * bypasses the execution's includes/excludes) a skip rather than a spurious red.
 *
 * <p>The gate must be captured <em>once at class initialization</em>: the test therefore touches
 * {@link VertiqueJson} first (initializing it with the flag absent), then sets the property at
 * runtime, and proves the later {@code System.setProperty} has no effect. That ordering is the whole
 * point — a gate re-read per call would be defeatable by any code on the classpath.
 */
class VertiqueJsonCodecResetGateTest {

    private static final String ALLOW_RESET = "vertique.json.codec.allowReset";

    @Test
    @DisplayName("TP-001: resetForTests() is refused when the flag was absent at JVM start")
    void resetIsRefusedWhenTheFlagWasAbsentAtStartup() {
        assumeFalse(
                Boolean.getBoolean(ALLOW_RESET), "this proof only means anything in the flagless surefire execution");

        // Force class initialization while the flag is absent: the gate is captured here.
        assertTrue(VertiqueJson.installedProfile().isEmpty(), "no profile is installed in this fork");

        System.setProperty(ALLOW_RESET, "true");
        try {
            IllegalStateException refused = assertThrows(
                    IllegalStateException.class,
                    VertiqueJson::resetForTests,
                    "resetForTests() must stay refused: the flag is read once at class initialization, so a later"
                            + " System.setProperty cannot open the seam");
            assertTrue(
                    String.valueOf(refused.getMessage()).contains(ALLOW_RESET),
                    "the refusal must name the flag; got: " + refused.getMessage());
        } finally {
            System.clearProperty(ALLOW_RESET);
        }
    }
}
