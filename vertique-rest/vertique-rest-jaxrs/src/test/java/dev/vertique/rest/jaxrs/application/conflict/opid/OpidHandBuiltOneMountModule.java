// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.Set;

/**
 * TP-005 (T004) case (e) fixture: hand-built module contributing a {@code JaxRsRouterMount} at
 * {@link #MOUNT_PATH}, built directly through {@link JaxRsRouterMount.Factory#create}. Non-
 * conflicting with {@link OpidHandBuiltTwoMountModule}'s mount.
 */
@Module
public final class OpidHandBuiltOneMountModule {

    private OpidHandBuiltOneMountModule() {}

    /** This mount's path, non-conflicting with {@link OpidHandBuiltTwoMountModule#MOUNT_PATH}. */
    public static final String MOUNT_PATH = "/opid-handbuilt-one/*";

    /**
     * Builds the hand-built mount from {@link OpidHandBuiltOneResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount opidHandBuiltOneMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new OpidHandBuiltOneResource()));
    }
}
