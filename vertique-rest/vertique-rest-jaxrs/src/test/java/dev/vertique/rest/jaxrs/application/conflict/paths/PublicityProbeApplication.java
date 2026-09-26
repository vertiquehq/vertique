// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import jakarta.ws.rs.core.Application;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TP-003 control fixture (T004): registered by this unit's {@code GeneratedJaxRsResourcesModule}
 * at path {@link #PATH}, whose mount path {@code /api/publicity/*} does NOT conflict with
 * {@link PublicProbeApplication}'s {@code /api/public/*} (case 4, the control). Has a public
 * no-arg constructor (the {@code A::new} C-GEN factory shape) and counts its own constructions in
 * {@link #CONSTRUCTIONS}, so the control's real composition is provable.
 */
public class PublicityProbeApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/api/publicity";

    /** Construction count; reset before every test via {@link #reset()}. */
    public static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public PublicityProbeApplication() {
        CONSTRUCTIONS.incrementAndGet();
    }

    /**
     * Returns {@link PublicityProbeResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link PublicityProbeResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(PublicityProbeResource.class);
    }

    /** Resets {@link #CONSTRUCTIONS} to {@code 0}. */
    public static void reset() {
        CONSTRUCTIONS.set(0);
    }
}
