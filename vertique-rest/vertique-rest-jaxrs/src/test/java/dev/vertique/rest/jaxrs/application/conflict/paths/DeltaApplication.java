// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import jakarta.ws.rs.core.Application;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-003 conflict fixture (T004): registered by this unit's {@code GeneratedJaxRsResourcesModule}
 * at path {@link #PATH}. Its mount path {@code /api/mgmt/*} conflicts with both
 * {@link GammaApplication}'s {@code /api/*} (case 2) and {@link RootApplication}'s {@code /*}
 * (case 3), so this fixture is reused, activated by a different partner, across both cases. Has a
 * public no-arg constructor (the {@code A::new} C-GEN factory shape) and counts its own
 * constructions in {@link #CONSTRUCTIONS}, so a step 1b violation's "no application is
 * constructed" claim is provable.
 */
public class DeltaApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/api/mgmt";

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public DeltaApplication() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Returns {@link DeltaResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link DeltaResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(DeltaResource.class);
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
