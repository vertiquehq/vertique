// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import java.util.Set;

/**
 * TP-005 (T004) case (c) fixture module: contributes zero-declaration mode's two resources —
 * {@link OpidZeroDeclarationManualResource} into the {@code @JaxRsResources} multibinding (so it
 * becomes the default mount's sole resource), and {@link OpidZeroDeclarationOtherResource} as a
 * hand-built {@code /other/*} mount ({@link #MOUNT_PATH}), built directly through
 * {@link JaxRsRouterMount.Factory#create}, never through {@code @JaxRsResources}. This case's
 * component includes no {@code GeneratedJaxRsApplicationRegistration} module at all, so
 * {@code Set<GeneratedJaxRsApplicationRegistration>} is empty and only the existing per-mount
 * operationId rule applies, unchanged.
 */
@Module
public final class OpidZeroDeclarationResourcesModule {

    private OpidZeroDeclarationResourcesModule() {}

    /** The hand-built mount's path, non-conflicting with the default mount at {@code /api/*}. */
    public static final String MOUNT_PATH = "/other/*";

    /**
     * Contributes {@link OpidZeroDeclarationManualResource} into the {@code @JaxRsResources}
     * multibinding, so the zero-declaration default mount at {@code jaxrs.basePath} serves it.
     *
     * @param r the injected resource instance
     * @return {@code r}, contributed into the set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object opidZeroDeclarationManualResource(OpidZeroDeclarationManualResource r) {
        return r;
    }

    /**
     * Builds the hand-built {@code /other/*} mount from {@link OpidZeroDeclarationOtherResource}.
     *
     * @param factory the shared JAX-RS mount factory
     * @return the hand-built mount, contributed into {@code Set<RouterMount>}
     */
    @Provides
    @IntoSet
    static RouterMount opidZeroDeclarationOtherMount(JaxRsRouterMount.Factory factory) {
        return factory.create(MOUNT_PATH, "openapi.json", Set.of(new OpidZeroDeclarationOtherResource()));
    }
}
