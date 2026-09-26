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
 * {@link #MOUNT_PATH}. Used by case (c), nested inside {@link LegacyOuterMountModule}'s
 * {@code /api/legacy/*} mount, with no application declared: this pair only exercises
 * {@code HttpVerticle}'s existing containment-overlap warning, unchanged.
 */
@Module
public final class LegacyReportsMountModule {

    private LegacyReportsMountModule() {}

    /** This mount's path; nested inside {@link LegacyOuterMountModule#MOUNT_PATH}'s prefix. */
    public static final String MOUNT_PATH = "/api/legacy/reports/*";

    /**
     * Builds the hand-built mount from {@link LegacyReportsResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount legacyReportsMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new LegacyReportsResource()));
    }
}
