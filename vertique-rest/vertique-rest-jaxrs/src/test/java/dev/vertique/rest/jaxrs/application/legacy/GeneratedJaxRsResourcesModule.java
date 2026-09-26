// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.legacy;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.core.dagger.JaxRsResources;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import java.util.Set;

/**
 * Hand-written module in exactly the shape {@code GeneratedJaxRsResourcesModuleEmitter} emits at
 * the rest-024 branch-start commit: one {@code @Provides @ElementsIntoSet @JaxRsResources} binding
 * per DI-eligible resource, no registration-set parameter, and no
 * {@code GeneratedJaxRsResourceEntry} contribution — neither of those C-GEN types exists before
 * T002. This fixture represents a module produced by an older processor (D002), which in explicit
 * mode behaves as manual contributions; later rest-024 tasks never migrate it to the new shape.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] DISABLED_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("legacy.disabledResource.enabled", "true", false)};

    /**
     * Unconditionally contributes {@link CatalogResource}, mirroring the emitter's unconditional
     * binding shape.
     *
     * @param config   the application configuration (unused for an unconditional binding; present
     *                 for parameter-shape parity with the conditional binding below)
     * @param provider lazily constructs {@link CatalogResource}
     * @return a singleton set holding the constructed resource
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> catalogResourceBinding(@VertxConfig JsonObject config, Provider<CatalogResource> provider) {
        return Set.of(provider.get());
    }

    /**
     * Unconditionally contributes {@link ExtraResource}, mirroring the emitter's unconditional
     * binding shape.
     *
     * @param config   the application configuration (unused for an unconditional binding)
     * @param provider lazily constructs {@link ExtraResource}
     * @return a singleton set holding the constructed resource
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> extraResourceBinding(@VertxConfig JsonObject config, Provider<ExtraResource> provider) {
        return Set.of(provider.get());
    }

    /**
     * Conditionally contributes {@link DisabledResource}, mirroring the emitter's conditional
     * binding shape for a {@code @ConditionalOnProperty}-annotated resource. No characterization
     * test configuration sets {@code legacy.disabledResource.enabled}, so this always evaluates to
     * an empty set and {@link DisabledResource} is never constructed.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link DisabledResource}
     * @return a singleton set holding the constructed resource when the condition matches,
     *     otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> disabledResourceBinding(@VertxConfig JsonObject config, Provider<DisabledResource> provider) {
        return PropertyCondition.matchesAll(config, DISABLED_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }
}
