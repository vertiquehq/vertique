// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * Explicit-mode JAX-RS application fixture, registered by {@code unitb}'s
 * {@link GeneratedJaxRsResourcesModule#managementApplicationRegistration}. It has a public no-arg
 * constructor (the {@code A::new} C-GEN factory shape for an application without an
 * {@code @Inject} constructor) and always selects {@link ExtraResource}.
 */
@ApplicationPath("/api/mgmt")
public class ManagementApplication extends Application {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public ManagementApplication() {}

    /**
     * Returns {@link ExtraResource} as this application's sole membership.
     *
     * @return a singleton set containing {@link ExtraResource}
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(ExtraResource.class);
    }
}
