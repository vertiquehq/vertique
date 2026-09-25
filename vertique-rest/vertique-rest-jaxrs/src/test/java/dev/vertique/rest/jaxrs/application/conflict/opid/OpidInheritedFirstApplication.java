// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (d) fixture: an explicit-mode application at {@link #PATH}, registered by
 * {@link GeneratedJaxRsResourcesModule#opidInheritedFirstApplicationRegistration}, listing
 * {@link OpidInheritedFirstResource}. Its mount does not conflict with
 * {@link OpidInheritedSecondApplication}'s.
 */
@ApplicationPath("/opid/inherited-first")
public class OpidInheritedFirstApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/opid/inherited-first";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidInheritedFirstApplication() {}

    /**
     * Returns {@link OpidInheritedFirstResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link OpidInheritedFirstResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OpidInheritedFirstResource.class);
    }
}
