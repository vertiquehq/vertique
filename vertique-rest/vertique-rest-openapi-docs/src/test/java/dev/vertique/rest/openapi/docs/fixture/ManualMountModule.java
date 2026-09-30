// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.Set;

/**
 * Contributes a hand-built manual JAX-RS mount at {@value #MOUNT_PATH} holding one
 * {@link ManualResource}, with no OpenAPI contract location. The mount belongs to no application,
 * so its publication names none.
 */
@Module
public final class ManualMountModule {

    /** The manual mount's path; it overlaps no application mount path. */
    public static final String MOUNT_PATH = "/api/manual/*";

    /** The name a documentation URL for the manual mount would use if it had one. */
    public static final String NAME = "manual";

    private ManualMountModule() {}

    /**
     * Builds the manual mount.
     *
     * @param factory the JAX-RS mount factory
     * @return the manual mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount manualMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, null, Set.of(new ManualResource()));
    }
}
