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
 * {@link #MOUNT_PATH}. Used by case (c), beside {@link LegacyReportsMountModule}'s
 * {@code /api/legacy/reports/*} mount, with no application declared: this pair only exercises
 * {@code HttpVerticle}'s existing containment-overlap warning, unchanged.
 */
@Module
public final class LegacyOuterMountModule {

    private LegacyOuterMountModule() {}

    /** This mount's path; a prefix of {@link LegacyReportsMountModule#MOUNT_PATH}. */
    public static final String MOUNT_PATH = "/api/legacy/*";

    /**
     * Builds the hand-built mount from {@link LegacyOuterResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount legacyOuterMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new LegacyOuterResource()));
    }
}
