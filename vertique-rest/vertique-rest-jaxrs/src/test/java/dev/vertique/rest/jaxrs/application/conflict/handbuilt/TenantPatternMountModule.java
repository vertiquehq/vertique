// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.handbuilt;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.Set;

/**
 * TP-004 (T004) fixture: hand-built module contributing a {@code JaxRsRouterMount} at
 * {@link #MOUNT_PATH}, a valid mount path per {@code HttpVerticle#validateMountPaths} (which
 * accepts a leading path variable segment). Used by case (d): {@code PublicApplication} at
 * {@code /api/public} beside this pattern-path mount, which conflicts with every application
 * mount under C-CONFLICT's pattern-path rule.
 */
@Module
public final class TenantPatternMountModule {

    private TenantPatternMountModule() {}

    /** This mount's path; its prefix contains {@code :}, so it conflicts with every application. */
    public static final String MOUNT_PATH = "/:tenant/*";

    /**
     * Builds the hand-built mount from {@link TenantPatternResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount tenantPatternMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new TenantPatternResource()));
    }
}
