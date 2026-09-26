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
 * {@link #MOUNT_PATH}. Used by case (e): {@code PublicApplication} at {@code /api/public} beside
 * this hand-built mount, which {@code PublicApplication}'s {@code /api/public/*} contains — the
 * direction a one-direction-only bug (checking only whether the hand-built prefix contains the
 * application's) would miss.
 */
@Module
public final class PublicAdminMountModule {

    private PublicAdminMountModule() {}

    /** This mount's path; contained by {@code PublicApplication}'s {@code /api/public/*}. */
    public static final String MOUNT_PATH = "/api/public/admin/*";

    /**
     * Builds the hand-built mount from {@link PublicAdminResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount publicAdminMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new PublicAdminResource()));
    }
}
