// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import jakarta.ws.rs.core.Application;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-003 conflict fixture (T004): registered by this unit's {@code GeneratedJaxRsResourcesModule}
 * at the root registration path {@link #PATH} (mount path {@code /*}), which conflicts with every
 * other application, including {@link DeltaApplication}'s {@code /api/mgmt/*} (case 3: root
 * {@code /} beside {@code /api/mgmt}). Has a public no-arg constructor (the {@code A::new} C-GEN
 * factory shape) and counts its own constructions in {@link #CONSTRUCTIONS}, so a step 1b
 * violation's "no application is constructed" claim is provable.
 */
public class RootApplication extends Application {

    /** This application's registration path: the root path. */
    public static final String PATH = "/";

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public RootApplication() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Returns {@link RootResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link RootResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(RootResource.class);
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
