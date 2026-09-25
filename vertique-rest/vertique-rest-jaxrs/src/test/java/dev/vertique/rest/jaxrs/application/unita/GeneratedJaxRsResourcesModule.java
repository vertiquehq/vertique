// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unita;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;
import java.util.Set;

/**
 * Hand-written module in the exact C-GEN shape for compilation unit {@code unita}: a
 * resources-only unit contributing {@link CatalogResource}, {@link ExtraResource}, and
 * {@link DisabledResource}. Every resource binding first checks {@code applications.isEmpty()}
 * (D001: with any application registration present, {@code @JaxRsResources} contributes nothing —
 * explicit mode routes only through selected applications), and every resource also gets a
 * {@link GeneratedJaxRsResourceEntry} so {@code JaxRsApplicationComposer} can select it from an
 * application's {@code getClasses()}.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] DISABLED_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("unita.disabledResource.enabled", "true", false)};

    /**
     * Unconditionally contributes {@link CatalogResource} to {@code @JaxRsResources} in
     * zero-declaration mode only.
     *
     * @param config       the application configuration (unused; present for parameter-shape
     *                     parity with the conditional binding below)
     * @param applications the generated application registration set; a non-empty set means
     *                     explicit mode, so this binding contributes nothing
     * @param provider     lazily constructs {@link CatalogResource}
     * @return a singleton set holding the constructed resource in zero-declaration mode, otherwise
     *     an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> catalogResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<CatalogResource> provider) {
        return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
    }

    /**
     * Catalogs {@link CatalogResource} for explicit-mode selection.
     *
     * @param config   the application configuration (unused; {@link CatalogResource} is
     *                 unconditional)
     * @param provider lazily constructs {@link CatalogResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry catalogResourceEntry(
            @VertxConfig JsonObject config, Provider<CatalogResource> provider) {
        return GeneratedJaxRsResourceEntry.of(CatalogResource.class, true, provider);
    }

    /**
     * Unconditionally contributes {@link ExtraResource} to {@code @JaxRsResources} in
     * zero-declaration mode only.
     *
     * @param config       the application configuration (unused)
     * @param applications the generated application registration set
     * @param provider     lazily constructs {@link ExtraResource}
     * @return a singleton set holding the constructed resource in zero-declaration mode, otherwise
     *     an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> extraResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<ExtraResource> provider) {
        return applications.isEmpty() ? Set.of(provider.get()) : Set.of();
    }

    /**
     * Catalogs {@link ExtraResource} for explicit-mode selection.
     *
     * @param config   the application configuration (unused; {@link ExtraResource} is
     *                 unconditional)
     * @param provider lazily constructs {@link ExtraResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry extraResourceEntry(
            @VertxConfig JsonObject config, Provider<ExtraResource> provider) {
        return GeneratedJaxRsResourceEntry.of(ExtraResource.class, true, provider);
    }

    /**
     * Conditionally contributes {@link DisabledResource} to {@code @JaxRsResources} in
     * zero-declaration mode only, mirroring the runtime evaluation of
     * {@code @ConditionalOnProperty(name = "unita.disabledResource.enabled")}.
     *
     * @param config       the application configuration the condition is evaluated against
     * @param applications the generated application registration set
     * @param provider     lazily constructs {@link DisabledResource}
     * @return a singleton set holding the constructed resource when in zero-declaration mode and
     *     the condition matches, otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> disabledResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<DisabledResource> provider) {
        return applications.isEmpty() && PropertyCondition.matchesAll(config, DISABLED_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }

    /**
     * Catalogs {@link DisabledResource} for explicit-mode selection, {@code enabled} reflecting
     * the same condition the zero-declaration binding above evaluates.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link DisabledResource}
     * @return the catalog entry, enabled only when the condition matches
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry disabledResourceEntry(
            @VertxConfig JsonObject config, Provider<DisabledResource> provider) {
        return GeneratedJaxRsResourceEntry.of(
                DisabledResource.class,
                PropertyCondition.matchesAll(config, DISABLED_RESOURCE_BINDING_CONDITIONS),
                provider);
    }
}
