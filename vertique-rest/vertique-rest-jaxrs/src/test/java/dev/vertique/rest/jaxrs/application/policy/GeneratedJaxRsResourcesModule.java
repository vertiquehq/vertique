// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

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
 * Hand-written module in the exact C-GEN shape for compilation unit {@code policy} (T005): a
 * resources-only unit contributing {@link UnannotatedResource}, {@link PermitAllResource},
 * {@link ScopelessRequirementResource}, {@link RequiresActionResource}, and
 * {@link PermitAllScopedResource} — TP-002's cases (a) to (e) and TP-004's zero-declaration
 * fixture. Mirrors {@code application.unita.GeneratedJaxRsResourcesModule} (T002) exactly: every
 * resource binding first checks {@code applications.isEmpty()} (D001 — with any application
 * registration present, {@code @JaxRsResources} contributes nothing), gated by its own
 * {@code policy.<variant>.enabled} condition, and every resource also gets a
 * {@link GeneratedJaxRsResourceEntry} so {@code JaxRsApplicationComposer} can select it from
 * {@code policy.app}'s {@code ManagementApplication#getClasses()}.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] UNANNOTATED_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("policy.unannotated.enabled", "true", false)};

    private static final PropertyCondition[] PERMIT_ALL_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("policy.permitAll.enabled", "true", false)};

    private static final PropertyCondition[] SCOPELESS_REQUIREMENT_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("policy.scopeless.enabled", "true", false)};

    private static final PropertyCondition[] REQUIRES_ACTION_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("policy.requiresAction.enabled", "true", false)};

    private static final PropertyCondition[] PERMIT_ALL_SCOPED_RESOURCE_BINDING_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("policy.permitAllScoped.enabled", "true", false)};

    // --- (a) UnannotatedResource ---

    /**
     * Conditionally contributes {@link UnannotatedResource} to {@code @JaxRsResources} in
     * zero-declaration mode only (TP-004), mirroring
     * {@code @ConditionalOnProperty(name = "policy.unannotated.enabled")}.
     *
     * @param config       the application configuration the condition is evaluated against
     * @param applications the generated application registration set; a non-empty set means
     *                     explicit mode, so this binding contributes nothing
     * @param provider     lazily constructs {@link UnannotatedResource}
     * @return a singleton set holding the constructed resource when in zero-declaration mode and
     *     the condition matches, otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> unannotatedResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<UnannotatedResource> provider) {
        return applications.isEmpty() && PropertyCondition.matchesAll(config, UNANNOTATED_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }

    /**
     * Catalogs {@link UnannotatedResource} for explicit-mode selection (TP-002 (a)), {@code enabled}
     * reflecting {@code policy.unannotated.enabled}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link UnannotatedResource}
     * @return the catalog entry
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry unannotatedResourceEntry(
            @VertxConfig JsonObject config, Provider<UnannotatedResource> provider) {
        return GeneratedJaxRsResourceEntry.of(
                UnannotatedResource.class,
                PropertyCondition.matchesAll(config, UNANNOTATED_RESOURCE_BINDING_CONDITIONS),
                provider);
    }

    // --- (b) PermitAllResource ---

    /**
     * Conditionally contributes {@link PermitAllResource} to {@code @JaxRsResources} in
     * zero-declaration mode only, mirroring
     * {@code @ConditionalOnProperty(name = "policy.permitAll.enabled")}.
     *
     * @param config       the application configuration the condition is evaluated against
     * @param applications the generated application registration set
     * @param provider     lazily constructs {@link PermitAllResource}
     * @return a singleton set holding the constructed resource when in zero-declaration mode and
     *     the condition matches, otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> permitAllResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<PermitAllResource> provider) {
        return applications.isEmpty() && PropertyCondition.matchesAll(config, PERMIT_ALL_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }

    /**
     * Catalogs {@link PermitAllResource} for explicit-mode selection (TP-002 (b)), {@code enabled}
     * reflecting {@code policy.permitAll.enabled}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link PermitAllResource}
     * @return the catalog entry
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry permitAllResourceEntry(
            @VertxConfig JsonObject config, Provider<PermitAllResource> provider) {
        return GeneratedJaxRsResourceEntry.of(
                PermitAllResource.class,
                PropertyCondition.matchesAll(config, PERMIT_ALL_RESOURCE_BINDING_CONDITIONS),
                provider);
    }

    // --- (c) ScopelessRequirementResource ---

    /**
     * Conditionally contributes {@link ScopelessRequirementResource} to {@code @JaxRsResources} in
     * zero-declaration mode only, mirroring
     * {@code @ConditionalOnProperty(name = "policy.scopeless.enabled")}.
     *
     * @param config       the application configuration the condition is evaluated against
     * @param applications the generated application registration set
     * @param provider     lazily constructs {@link ScopelessRequirementResource}
     * @return a singleton set holding the constructed resource when in zero-declaration mode and
     *     the condition matches, otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> scopelessRequirementResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<ScopelessRequirementResource> provider) {
        return applications.isEmpty()
                        && PropertyCondition.matchesAll(config, SCOPELESS_REQUIREMENT_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }

    /**
     * Catalogs {@link ScopelessRequirementResource} for explicit-mode selection (TP-002 (c)),
     * {@code enabled} reflecting {@code policy.scopeless.enabled}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link ScopelessRequirementResource}
     * @return the catalog entry
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry scopelessRequirementResourceEntry(
            @VertxConfig JsonObject config, Provider<ScopelessRequirementResource> provider) {
        return GeneratedJaxRsResourceEntry.of(
                ScopelessRequirementResource.class,
                PropertyCondition.matchesAll(config, SCOPELESS_REQUIREMENT_RESOURCE_BINDING_CONDITIONS),
                provider);
    }

    // --- (d) RequiresActionResource ---

    /**
     * Conditionally contributes {@link RequiresActionResource} to {@code @JaxRsResources} in
     * zero-declaration mode only, mirroring
     * {@code @ConditionalOnProperty(name = "policy.requiresAction.enabled")}.
     *
     * @param config       the application configuration the condition is evaluated against
     * @param applications the generated application registration set
     * @param provider     lazily constructs {@link RequiresActionResource}
     * @return a singleton set holding the constructed resource when in zero-declaration mode and
     *     the condition matches, otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> requiresActionResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<RequiresActionResource> provider) {
        return applications.isEmpty()
                        && PropertyCondition.matchesAll(config, REQUIRES_ACTION_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }

    /**
     * Catalogs {@link RequiresActionResource} for explicit-mode selection (TP-002 (d)),
     * {@code enabled} reflecting {@code policy.requiresAction.enabled}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link RequiresActionResource}
     * @return the catalog entry
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry requiresActionResourceEntry(
            @VertxConfig JsonObject config, Provider<RequiresActionResource> provider) {
        return GeneratedJaxRsResourceEntry.of(
                RequiresActionResource.class,
                PropertyCondition.matchesAll(config, REQUIRES_ACTION_RESOURCE_BINDING_CONDITIONS),
                provider);
    }

    // --- (e) PermitAllScopedResource ---

    /**
     * Conditionally contributes {@link PermitAllScopedResource} to {@code @JaxRsResources} in
     * zero-declaration mode only, mirroring
     * {@code @ConditionalOnProperty(name = "policy.permitAllScoped.enabled")}.
     *
     * @param config       the application configuration the condition is evaluated against
     * @param applications the generated application registration set
     * @param provider     lazily constructs {@link PermitAllScopedResource}
     * @return a singleton set holding the constructed resource when in zero-declaration mode and
     *     the condition matches, otherwise an empty set
     */
    @Provides
    @ElementsIntoSet
    @JaxRsResources
    static Set<Object> permitAllScopedResourceBinding(
            @VertxConfig JsonObject config,
            Set<GeneratedJaxRsApplicationRegistration> applications,
            Provider<PermitAllScopedResource> provider) {
        return applications.isEmpty()
                        && PropertyCondition.matchesAll(config, PERMIT_ALL_SCOPED_RESOURCE_BINDING_CONDITIONS)
                ? Set.of(provider.get())
                : Set.of();
    }

    /**
     * Catalogs {@link PermitAllScopedResource} for explicit-mode selection (TP-002 (e)),
     * {@code enabled} reflecting {@code policy.permitAllScoped.enabled}.
     *
     * @param config   the application configuration the condition is evaluated against
     * @param provider lazily constructs {@link PermitAllScopedResource}
     * @return the catalog entry
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry permitAllScopedResourceEntry(
            @VertxConfig JsonObject config, Provider<PermitAllScopedResource> provider) {
        return GeneratedJaxRsResourceEntry.of(
                PermitAllScopedResource.class,
                PropertyCondition.matchesAll(config, PERMIT_ALL_SCOPED_RESOURCE_BINDING_CONDITIONS),
                provider);
    }
}
