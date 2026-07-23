// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.vertx.core.Deployable;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VertiqueApplication#verticleSupplier()} (FR-APP-030) — the standalone-entry
 * supplier and its {@code vertique.bootstrap.verticle=false} opt-out.
 *
 * <p>By default the supplier is non-null and yields a {@link VertiqueBootstrapVerticle} (which is a
 * {@link Deployable}), so the upstream launcher bypasses {@code Main-Verticle} resolution. With the
 * opt-out property set, the supplier is {@code null} so the launcher falls back to standard
 * resolution. The property is set/cleared per test and restored in a {@code finally} block to avoid
 * cross-test leakage.
 */
class VertiqueApplicationVerticleSupplierTest {

    @Test
    @DisplayName("verticleSupplier() returns a non-null supplier of a VertiqueBootstrapVerticle by default")
    void verticleSupplier_default_suppliesBootstrapVerticle() {
        String previous = System.getProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);
        try {
            System.clearProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);

            VertiqueApplication app = new VertiqueApplication(new String[0]);
            Supplier<? extends Deployable> supplier = app.verticleSupplier();

            assertNotNull(supplier, "default supplier is non-null so Main-Verticle resolution is bypassed");
            Deployable deployable = supplier.get();
            assertInstanceOf(
                    VertiqueBootstrapVerticle.class,
                    deployable,
                    "the supplied deployable is the framework bootstrap verticle (and a Deployable)");
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("verticleSupplier() returns null when vertique.bootstrap.verticle=false (opt-out)")
    void verticleSupplier_optOut_returnsNull() {
        String previous = System.getProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);
        try {
            System.setProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY, "false");

            VertiqueApplication app = new VertiqueApplication(new String[0]);

            assertNull(app.verticleSupplier(), "opt-out defers to the launcher's Main-Verticle/CLI resolution");
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("verticleSupplier() opt-out matches the property case-insensitively")
    void verticleSupplier_optOut_caseInsensitive() {
        String previous = System.getProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);
        try {
            System.setProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY, "FALSE");

            VertiqueApplication app = new VertiqueApplication(new String[0]);

            assertNull(app.verticleSupplier(), "the opt-out comparison is case-insensitive");
        } finally {
            restore(previous);
        }
    }

    @Test
    @DisplayName("verticleSupplier() supplies a fresh verticle instance per get() call")
    void verticleSupplier_default_freshInstancePerGet() {
        String previous = System.getProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);
        try {
            System.clearProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);

            Supplier<? extends Deployable> supplier = new VertiqueApplication(new String[0]).verticleSupplier();
            assertNotNull(supplier);

            Deployable first = supplier.get();
            Deployable second = supplier.get();
            assertInstanceOf(VertiqueBootstrapVerticle.class, first);
            assertInstanceOf(VertiqueBootstrapVerticle.class, second);
            org.junit.jupiter.api.Assertions.assertNotSame(
                    first,
                    second,
                    "each get() yields a fresh verticle (Vert.x deploys one instance per supplier call)");
        } finally {
            restore(previous);
        }
    }

    /**
     * Restores the opt-out system property to a prior value, clearing it when the prior value was
     * absent.
     *
     * @param previous the value captured before the test mutated the property, or {@code null} if it
     *     was unset
     */
    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY);
        } else {
            System.setProperty(VertiqueApplication.BOOTSTRAP_VERTICLE_PROPERTY, previous);
        }
    }
}
