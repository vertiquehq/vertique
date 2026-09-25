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
 * {@link #MOUNT_PATH}, built directly through {@link JaxRsRouterMount.Factory#create}, the way an
 * application never built through the declared-application composer would (C-CONFLICT). Used by
 * case (a): {@code ManagementApplication} at {@code /api/mgmt} beside this hand-built
 * {@code /api/*} mount, a conflicting pair because {@code /api/} is a prefix of {@code /api/mgmt/}.
 */
@Module
public final class ApiPrefixMountModule {

    private ApiPrefixMountModule() {}

    /** This mount's path, deliberately a prefix of {@code ManagementApplication}'s {@code /api/mgmt/*}. */
    public static final String MOUNT_PATH = "/api/*";

    /**
     * Builds the hand-built mount from {@link ApiPrefixResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount apiPrefixMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new ApiPrefixResource()));
    }
}
