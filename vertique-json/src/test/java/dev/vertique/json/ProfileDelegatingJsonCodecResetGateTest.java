// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import io.vertx.core.json.jackson.VertxModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VertiqueJson#resetForTests()} is reachable only when the JVM was
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
class ProfileDelegatingJsonCodecResetGateTest {

    private static final String ALLOW_RESET = "vertique.json.codec.allowReset";

    @Test
    @DisplayName("resetForTests() is refused when the flag was absent at JVM start")
    void resetIsRefusedWhenTheFlagWasAbsentAtStartup() {
        assumeFalse(
                Boolean.getBoolean(ALLOW_RESET), "this proof only means anything in the flagless surefire execution");

        // Force class initialization while the flag is absent: the gate is captured here.
        assertTrue(VertiqueJson.profile().isEmpty(), "no profile is installed in this fork");

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

            // With a profile installed, a refused reset must leave the installation in place.
            JsonProfileId id = JsonProfileId.of("reset-gate-test");
            VertiqueJson.install(Jackson2JsonProfiles.of(id, new ObjectMapper().registerModule(new VertxModule())));
            IllegalStateException refusedWithProfile =
                    assertThrows(IllegalStateException.class, VertiqueJson::resetForTests);
            assertTrue(
                    String.valueOf(refusedWithProfile.getMessage()).contains(ALLOW_RESET),
                    "the refusal must name the property; got: " + refusedWithProfile.getMessage());
            assertEquals(id, VertiqueJson.profile().orElse(null), "a refused reset must leave the profile installed");
        } finally {
            System.clearProperty(ALLOW_RESET);
        }
    }
}
