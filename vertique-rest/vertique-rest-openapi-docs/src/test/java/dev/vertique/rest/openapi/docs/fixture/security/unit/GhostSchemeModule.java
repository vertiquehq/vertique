// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.unit;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.openapi.docs.fixture.StubSchemeHandler;

/**
 * Binds a {@link StubSchemeHandler} for {@value GhostResource#SCHEME} and the {@link
 * AuthEnforcementCapability} marker, so the mount of {@link GhostApi} builds: the route registrar
 * refuses an operation whose scheme has no handler, and a scopeless requirement is a restrictive
 * policy that needs the enforcement marker.
 */
@Module
public final class GhostSchemeModule {

    private GhostSchemeModule() {}

    /**
     * Contributes the {@value GhostResource#SCHEME} stub handler, which describes nothing.
     *
     * @return the stub handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler ghostAuthHandler() {
        return new StubSchemeHandler(GhostResource.SCHEME);
    }

    /**
     * Provides the auth enforcement marker.
     *
     * @return the marker instance
     */
    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }
}
