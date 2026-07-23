// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.resource;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;

/**
 * Dagger module that contributes JAX-RS resources to the framework's resource multibinding.
 */
@Module
public abstract class ResourceModule {

    /**
     * Contributes the {@link PingResource} to the JAX-RS resource multibinding.
     *
     * @param resource the ping resource instance
     * @return the resource wrapped as {@code Object} for the multibinding
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object pingResource(PingResource resource) {
        return resource;
    }
}
