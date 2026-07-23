// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Verticle;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Construction-time validation tests for {@link VerticleDeployment} (FR-APP-012).
 *
 * <p>Verifies that a {@link VerticleDeployment} can only be constructed with a
 * {@link LifecyclePhase#isVerticlePhase() verticle phase}: the four verticle-subset phases
 * ({@code BOOTSTRAP/INFRA/SERVICES/EDGE}) succeed and round-trip through {@link
 * VerticleDeployment#phase()}, while each non-verticle phase ({@code CONFIGURE/VALIDATE/MIGRATE/
 * AFTER_START}) is rejected with an {@link IllegalArgumentException} naming the deployment and the
 * legal phases.
 */
class VerticleDeploymentConstructionTest {

    /** A no-op verticle used only as a supplier target; never deployed in these tests. */
    static final class NoOpVerticle extends AbstractVerticle {}

    private static final Supplier<Verticle> SUPPLIER = NoOpVerticle::new;

    // --- non-verticle phases rejected ---

    @ParameterizedTest
    @EnumSource(
            value = LifecyclePhase.class,
            names = {"CONFIGURE", "VALIDATE", "MIGRATE", "AFTER_START"})
    @DisplayName("of(name, supplier, phase) rejects a non-verticle phase, naming deployment + legal phases")
    void of_nonVerticlePhase_throwsNamingDeploymentAndLegalPhases(LifecyclePhase phase) {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> VerticleDeployment.of("x", SUPPLIER, phase));
        assertTrue(ex.getMessage().contains("'x'"), "message should name the deployment");
        assertTrue(ex.getMessage().contains(phase.name()), "message should name the offending phase");
        assertTrue(
                ex.getMessage().contains("BOOTSTRAP, INFRA, SERVICES, EDGE"),
                "message should list the legal verticle phases");
    }

    @ParameterizedTest
    @EnumSource(
            value = LifecyclePhase.class,
            names = {"CONFIGURE", "VALIDATE", "MIGRATE", "AFTER_START"})
    @DisplayName("of(name, supplier, phase, priority) rejects a non-verticle phase")
    void ofWithPriority_nonVerticlePhase_throws(LifecyclePhase phase) {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> VerticleDeployment.of("y", SUPPLIER, phase, 5));
        assertTrue(ex.getMessage().contains("'y'"), "message should name the deployment");
        assertTrue(
                ex.getMessage().contains("BOOTSTRAP, INFRA, SERVICES, EDGE"),
                "message should list the legal verticle phases");
    }

    // --- verticle phases accepted ---

    @ParameterizedTest
    @EnumSource(
            value = LifecyclePhase.class,
            names = {"BOOTSTRAP", "INFRA", "SERVICES", "EDGE"})
    @DisplayName("of(name, supplier, phase) accepts every verticle phase and round-trips it")
    void of_verticlePhase_succeedsAndRoundTrips(LifecyclePhase phase) {
        VerticleDeployment deployment = VerticleDeployment.of("ok", SUPPLIER, phase);
        assertSame(phase, deployment.phase());
        assertEquals(0, deployment.priority());
    }

    @Test
    @DisplayName("of(name, supplier, phase, priority) accepts a verticle phase with explicit priority")
    void ofWithPriority_verticlePhase_succeeds() {
        VerticleDeployment deployment = VerticleDeployment.of("ok", SUPPLIER, LifecyclePhase.SERVICES, 7);
        assertSame(LifecyclePhase.SERVICES, deployment.phase());
        assertEquals(7, deployment.priority());
    }
}
