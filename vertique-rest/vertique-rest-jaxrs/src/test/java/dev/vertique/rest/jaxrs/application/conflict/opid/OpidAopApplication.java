// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (f) fixture: an explicit-mode application at {@link #PATH}, registered by
 * {@link OpidAopApplicationRegistrationModule}, listing {@link OpidAopBaseResource}. Its only
 * bound instance of that class is {@link OpidAopProxyResource}, contributed manually by
 * {@link OpidAopProxyResourceModule}; no catalog entry for {@link OpidAopBaseResource} exists, so
 * the listing is unambiguous.
 */
@ApplicationPath("/opid/aop")
public class OpidAopApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/opid/aop";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidAopApplication() {}

    /**
     * Returns {@link OpidAopBaseResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link OpidAopBaseResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OpidAopBaseResource.class);
    }
}
