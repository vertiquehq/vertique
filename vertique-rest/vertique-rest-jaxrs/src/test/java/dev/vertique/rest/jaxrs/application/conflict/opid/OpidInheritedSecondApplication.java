// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * TP-005 (T004) case (d) fixture: an explicit-mode application at {@link #PATH}, registered by
 * {@link GeneratedJaxRsResourcesModule#opidInheritedSecondApplicationRegistration}, listing
 * {@link OpidInheritedSecondResource}. Its mount does not conflict with
 * {@link OpidInheritedFirstApplication}'s.
 */
@ApplicationPath("/opid/inherited-second")
public class OpidInheritedSecondApplication extends Application {

    /** This application's registration path. */
    public static final String PATH = "/opid/inherited-second";

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public OpidInheritedSecondApplication() {}

    /**
     * Returns {@link OpidInheritedSecondResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link OpidInheritedSecondResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(OpidInheritedSecondResource.class);
    }
}
