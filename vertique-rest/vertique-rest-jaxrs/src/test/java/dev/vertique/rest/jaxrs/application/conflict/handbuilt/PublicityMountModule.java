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
 * {@link #MOUNT_PATH}. Used by case (b), the control: {@code PublicApplication} at
 * {@code /api/public} beside this hand-built {@code /api/publicity/*} mount, which does not
 * conflict, since neither mount path's prefix contains the other's (C-CONFLICT: {@code /api/public}
 * and {@code /api/publicity} do not conflict).
 */
@Module
public final class PublicityMountModule {

    private PublicityMountModule() {}

    /** This mount's path; does not conflict with {@code PublicApplication}'s {@code /api/public/*}. */
    public static final String MOUNT_PATH = "/api/publicity/*";

    /**
     * Builds the hand-built mount from {@link PublicityResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount publicityMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new PublicityResource()));
    }
}
