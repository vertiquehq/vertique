// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.Set;

/**
 * Single-proof (TP-016) third overriding JAX-RS application fixture, registered by
 * {@link ThirdOverridingRegistrationModule} with a condition that never matches in
 * {@code JaxRsApplicationCompositionTest}: it exists only so the registration INFO line (C-COMPOSE
 * step 1) has three registrations to list, alongside {@link PublicApplication} and
 * {@link ManagementApplication}. It overrides {@link #getClasses()} so C-COMPOSE step 1 classifies
 * it as overriding, like the other two; it is never constructed because it is always inactive.
 */
@ApplicationPath("/api/third")
public class ThirdOverridingApplication extends Application {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public ThirdOverridingApplication() {}

    /**
     * Returns an empty membership set. Never invoked: this application's registration condition
     * never matches, so it is never constructed.
     *
     * @return an empty set
     */
    @Override
    public Set<Class<?>> getClasses() {
        return Set.of();
    }
}
