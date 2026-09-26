// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsApplicationRegistration;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsResourceEntry;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Provider;

/**
 * Hand-written module in the exact C-GEN shape for TP-003's (T004) {@code conflict.paths}
 * compilation unit: every conflict fixture is registered here, each application beside its one
 * counting resource's catalog entry. Every registration and every catalog entry carries its own
 * {@code @ConditionalOnProperty}-equivalent activation gate (see each registration method's
 * condition constant), mirrored the same way T002's {@code unitb} module gates
 * {@code PublicApplication} and {@code ManagementApplication}, since {@code vertique-codegen-core}
 * is not a test dependency of this module. {@link JaxRsApplicationMountConflictTest}'s
 * {@code @MethodSource} rows activate exactly the two applications each case names, by setting
 * that pair's two boolean flags {@code true}; every other application, and its resource's catalog
 * entry, stays cataloged but inactive/unselected. Catalog entries are always enabled: only
 * membership selection (an active application's {@code getClasses()}) determines whether a
 * resource is ever resolved.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] ALPHA_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.alpha.active", "true", false)};

    private static final PropertyCondition[] BETA_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.beta.active", "true", false)};

    private static final PropertyCondition[] GAMMA_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.gamma.active", "true", false)};

    private static final PropertyCondition[] DELTA_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.delta.active", "true", false)};

    private static final PropertyCondition[] ROOT_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.root.active", "true", false)};

    private static final PropertyCondition[] PUBLIC_PROBE_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.publicProbe.active", "true", false)};

    private static final PropertyCondition[] PUBLICITY_PROBE_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.paths.publicityProbe.active", "true", false)};

    /**
     * Registers {@link AlphaApplication}, active only when {@code conflict.paths.alpha.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #ALPHA_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration alphaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                AlphaApplication.class,
                AlphaApplication.PATH,
                PropertyCondition.matchesAll(config, ALPHA_CONDITIONS),
                AlphaApplication::new);
    }

    /**
     * Catalogs {@link AlphaResource} for {@link AlphaApplication}'s explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link AlphaResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry alphaResourceEntry(
            @VertxConfig JsonObject config, Provider<AlphaResource> provider) {
        return GeneratedJaxRsResourceEntry.of(AlphaResource.class, true, provider);
    }

    /**
     * Registers {@link BetaApplication}, active only when {@code conflict.paths.beta.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #BETA_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration betaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                BetaApplication.class,
                BetaApplication.PATH,
                PropertyCondition.matchesAll(config, BETA_CONDITIONS),
                BetaApplication::new);
    }

    /**
     * Catalogs {@link BetaResource} for {@link BetaApplication}'s explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link BetaResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry betaResourceEntry(
            @VertxConfig JsonObject config, Provider<BetaResource> provider) {
        return GeneratedJaxRsResourceEntry.of(BetaResource.class, true, provider);
    }

    /**
     * Registers {@link GammaApplication}, active only when {@code conflict.paths.gamma.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #GAMMA_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration gammaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                GammaApplication.class,
                GammaApplication.PATH,
                PropertyCondition.matchesAll(config, GAMMA_CONDITIONS),
                GammaApplication::new);
    }

    /**
     * Catalogs {@link GammaResource} for {@link GammaApplication}'s explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link GammaResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry gammaResourceEntry(
            @VertxConfig JsonObject config, Provider<GammaResource> provider) {
        return GeneratedJaxRsResourceEntry.of(GammaResource.class, true, provider);
    }

    /**
     * Registers {@link DeltaApplication}, active only when {@code conflict.paths.delta.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #DELTA_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration deltaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                DeltaApplication.class,
                DeltaApplication.PATH,
                PropertyCondition.matchesAll(config, DELTA_CONDITIONS),
                DeltaApplication::new);
    }

    /**
     * Catalogs {@link DeltaResource} for {@link DeltaApplication}'s explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link DeltaResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry deltaResourceEntry(
            @VertxConfig JsonObject config, Provider<DeltaResource> provider) {
        return GeneratedJaxRsResourceEntry.of(DeltaResource.class, true, provider);
    }

    /**
     * Registers {@link RootApplication}, active only when {@code conflict.paths.root.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #ROOT_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration rootApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                RootApplication.class,
                RootApplication.PATH,
                PropertyCondition.matchesAll(config, ROOT_CONDITIONS),
                RootApplication::new);
    }

    /**
     * Catalogs {@link RootResource} for {@link RootApplication}'s explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link RootResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry rootResourceEntry(
            @VertxConfig JsonObject config, Provider<RootResource> provider) {
        return GeneratedJaxRsResourceEntry.of(RootResource.class, true, provider);
    }

    /**
     * Registers {@link PublicProbeApplication}, active only when
     * {@code conflict.paths.publicProbe.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #PUBLIC_PROBE_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration publicProbeApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                PublicProbeApplication.class,
                PublicProbeApplication.PATH,
                PropertyCondition.matchesAll(config, PUBLIC_PROBE_CONDITIONS),
                PublicProbeApplication::new);
    }

    /**
     * Catalogs {@link PublicProbeResource} for {@link PublicProbeApplication}'s explicit-mode
     * selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link PublicProbeResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry publicProbeResourceEntry(
            @VertxConfig JsonObject config, Provider<PublicProbeResource> provider) {
        return GeneratedJaxRsResourceEntry.of(PublicProbeResource.class, true, provider);
    }

    /**
     * Registers {@link PublicityProbeApplication}, active only when
     * {@code conflict.paths.publicityProbe.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #PUBLICITY_PROBE_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration publicityProbeApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                PublicityProbeApplication.class,
                PublicityProbeApplication.PATH,
                PropertyCondition.matchesAll(config, PUBLICITY_PROBE_CONDITIONS),
                PublicityProbeApplication::new);
    }

    /**
     * Catalogs {@link PublicityProbeResource} for {@link PublicityProbeApplication}'s
     * explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link PublicityProbeResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry publicityProbeResourceEntry(
            @VertxConfig JsonObject config, Provider<PublicityProbeResource> provider) {
        return GeneratedJaxRsResourceEntry.of(PublicityProbeResource.class, true, provider);
    }
}
