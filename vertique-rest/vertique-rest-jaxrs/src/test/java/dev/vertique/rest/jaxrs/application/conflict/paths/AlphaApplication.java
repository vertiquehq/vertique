// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import jakarta.ws.rs.core.Application;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-003 conflict fixture (T004): registered by this unit's {@code GeneratedJaxRsResourcesModule}
 * at path {@link #PATH}, the same normalized path as {@link BetaApplication} (case 1: two
 * application classes with the same normalized path). Has a public no-arg constructor (the
 * {@code A::new} C-GEN factory shape) and counts its own constructions in {@link #CONSTRUCTIONS},
 * so a step 1b violation's "no application is constructed" claim is provable.
 */
public class AlphaApplication extends Application {

    /** This application's registration path, matching {@link BetaApplication#PATH}. */
    public static final String PATH = "/api/dup";

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public AlphaApplication() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Returns {@link AlphaResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link AlphaResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(AlphaResource.class);
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
