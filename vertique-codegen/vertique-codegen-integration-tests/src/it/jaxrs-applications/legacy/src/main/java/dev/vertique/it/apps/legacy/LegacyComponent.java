// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.legacy;

import dagger.BindsInstance;
import dagger.Component;
import dev.vertique.core.VertxConfig;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.RestModule;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * T003 TP-006 component: lists only {@link RestModule}, the {@code resources} unit's real, processor-
 * generated {@code dev.vertique.it.apps.resources.GeneratedJaxRsResourcesModule}, and
 * {@link LegacySupportModule} — no application registration, no handwritten mount module. With the
 * generated application registration set empty (C-GEN's {@code applications.isEmpty()} gate), the
 * default {@code @JaxRsResources}-driven mount must serve every enabled generated resource, exactly
 * as it did before T003 (I-1).
 */
@Singleton
@Component(
        modules = {
            RestModule.class,
            LegacySupportModule.class,
            dev.vertique.it.apps.resources.GeneratedJaxRsResourcesModule.class
        })
public interface LegacyComponent {

    /**
     * Resolves the {@code @JaxRsResources Set<Object>} multibinding.
     *
     * @return the resolved resource set
     */
    @JaxRsResources
    Set<Object> jaxRsResources();

    /**
     * Resolves the {@code Set<RouterMount>} multibinding, including {@code RestModule}'s default
     * JAX-RS mount provider.
     *
     * @return the resolved mount set
     */
    Set<RouterMount> routerMounts();

    /** Factory taking the application configuration. */
    @Component.Factory
    interface Factory {

        /**
         * Creates the component bound to the given configuration.
         *
         * @param config the application configuration
         * @return the constructed component
         */
        LegacyComponent create(@BindsInstance @VertxConfig JsonObject config);
    }
}
