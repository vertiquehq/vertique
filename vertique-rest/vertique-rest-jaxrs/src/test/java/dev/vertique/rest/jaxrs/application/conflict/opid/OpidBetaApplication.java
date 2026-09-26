// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (a) fixture: an explicit-mode application at {@link #PATH}, registered by
 * {@link GeneratedJaxRsResourcesModule#opidBetaApplicationRegistration}, listing only
 * {@link OpidBetaListResource}. Its mount does not conflict with {@link OpidAlphaApplication}'s.
 */
@ApplicationPath("/opid/beta")
public class OpidBetaApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/opid/beta";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidBetaApplication() {}

    /**
     * Returns {@link OpidBetaListResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link OpidBetaListResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OpidBetaListResource.class);
    }
}
