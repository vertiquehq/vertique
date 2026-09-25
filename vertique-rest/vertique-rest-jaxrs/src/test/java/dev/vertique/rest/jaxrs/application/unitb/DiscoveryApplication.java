// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Single-proof (TP-006) discovery-mode JAX-RS application fixture, registered by
 * {@link DiscoveryRegistrationModule}. It overrides neither {@link Application#getClasses()} nor
 * {@link Application#getSingletons()}, so C-COMPOSE step 1's overriding check
 * ({@code getDeclaredMethod} on the hierarchy from {@code registration.type()} up to, but
 * excluding, {@code Application}) never finds a declared no-parameter {@code getClasses} or
 * {@code getSingletons}: the registration classifies as discovery, and the sole active discovery
 * application selects every enabled catalog entry and every {@code @JaxRsResources} instance
 * (C-COMPOSE step 5).
 */
@ApplicationPath("/api")
public class DiscoveryApplication extends Application {

    /** Public no-arg constructor, matching the C-GEN {@code A::new} factory shape. */
    public DiscoveryApplication() {}
}
