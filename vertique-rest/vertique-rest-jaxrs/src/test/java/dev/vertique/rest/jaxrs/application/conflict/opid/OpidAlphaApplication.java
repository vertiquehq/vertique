// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (a) fixture: an explicit-mode application at {@link #PATH}, registered by
 * {@link GeneratedJaxRsResourcesModule#opidAlphaApplicationRegistration}, listing only
 * {@link OpidAlphaListResource}. Its mount does not conflict with {@link OpidBetaApplication}'s,
 * but both list a resource whose sole method's default operationId is {@code "list"}.
 */
@ApplicationPath("/opid/alpha")
public class OpidAlphaApplication extends Application {

    /** This application's registration path, matching {@link #PATH}'s mount path. */
    public static final String PATH = "/opid/alpha";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidAlphaApplication() {}

    /**
     * Returns {@link OpidAlphaListResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link OpidAlphaListResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OpidAlphaListResource.class);
    }
}
