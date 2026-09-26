// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (b) fixture: an explicit-mode application at {@link #PATH}, registered by
 * {@link GeneratedJaxRsResourcesModule#opidShareOneApplicationRegistration}, listing
 * {@link OpidSharedListResource} — the same resource class {@link OpidShareTwoApplication} lists.
 */
@ApplicationPath("/opid/share-one")
public class OpidShareOneApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/opid/share-one";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidShareOneApplication() {}

    /**
     * Returns {@link OpidSharedListResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link OpidSharedListResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OpidSharedListResource.class);
    }
}
