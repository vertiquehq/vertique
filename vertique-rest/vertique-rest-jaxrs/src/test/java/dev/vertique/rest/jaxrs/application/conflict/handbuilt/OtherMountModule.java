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
 * {@link #MOUNT_PATH}. Used by case (f), beside the reused
 * {@link dev.vertique.rest.jaxrs.application.conflict.paths.RootApplication RootApplication} at
 * the root path ({@code /*}), which conflicts with every other mount under C-CONFLICT.
 */
@Module
public final class OtherMountModule {

    private OtherMountModule() {}

    /** This mount's path; conflicts with the root application's {@code /*} mount. */
    public static final String MOUNT_PATH = "/other/*";

    /**
     * Builds the hand-built mount from {@link OtherResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount otherMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new OtherResource()));
    }
}
