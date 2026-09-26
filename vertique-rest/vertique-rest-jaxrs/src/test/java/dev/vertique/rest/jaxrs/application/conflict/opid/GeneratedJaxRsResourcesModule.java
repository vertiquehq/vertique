// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

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
 * Hand-written module in the exact C-GEN shape for TP-005's (T004) {@code conflict.opid}
 * compilation unit: cases (a), (b), and (d) share this one module, each application and its
 * catalog entry gated by its own {@code @ConditionalOnProperty}-equivalent activation flag,
 * mirroring {@code conflict.paths.GeneratedJaxRsResourcesModule} (TP-003). Case (a)'s
 * {@link OpidAlphaApplication} and {@link OpidBetaApplication} list two unrelated resource
 * classes that each declare {@code list()}; case (b)'s {@link OpidShareOneApplication} and
 * {@link OpidShareTwoApplication} both list the same {@link OpidSharedListResource}; case (d)'s
 * {@link OpidInheritedFirstApplication} and {@link OpidInheritedSecondApplication} each list a
 * distinct concrete subclass of {@link OpidInheritedBaseResource} that inherits {@code list()}
 * without overriding it. Cases (c), (e), and (f) use their own dedicated modules instead, so this
 * module's registrations never appear in those cases' {@code Set<GeneratedJaxRsApplicationRegistration>}.
 */
@Module
public final class GeneratedJaxRsResourcesModule {

    private static final PropertyCondition[] ALPHA_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.opid.alpha.active", "true", false)};

    private static final PropertyCondition[] BETA_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.opid.beta.active", "true", false)};

    private static final PropertyCondition[] SHARE_ONE_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.opid.shareOne.active", "true", false)};

    private static final PropertyCondition[] SHARE_TWO_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.opid.shareTwo.active", "true", false)};

    private static final PropertyCondition[] INHERITED_FIRST_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.opid.inheritedFirst.active", "true", false)};

    private static final PropertyCondition[] INHERITED_SECOND_CONDITIONS =
            new PropertyCondition[] {new PropertyCondition("conflict.opid.inheritedSecond.active", "true", false)};

    /**
     * Registers {@link OpidAlphaApplication}, active only when
     * {@code conflict.opid.alpha.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #ALPHA_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidAlphaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidAlphaApplication.class,
                OpidAlphaApplication.PATH,
                PropertyCondition.matchesAll(config, ALPHA_CONDITIONS),
                OpidAlphaApplication::new);
    }

    /**
     * Catalogs {@link OpidAlphaListResource} for {@link OpidAlphaApplication}'s explicit-mode
     * selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link OpidAlphaListResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry opidAlphaListResourceEntry(
            @VertxConfig JsonObject config, Provider<OpidAlphaListResource> provider) {
        return GeneratedJaxRsResourceEntry.of(OpidAlphaListResource.class, true, provider);
    }

    /**
     * Registers {@link OpidBetaApplication}, active only when
     * {@code conflict.opid.beta.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #BETA_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidBetaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidBetaApplication.class,
                OpidBetaApplication.PATH,
                PropertyCondition.matchesAll(config, BETA_CONDITIONS),
                OpidBetaApplication::new);
    }

    /**
     * Catalogs {@link OpidBetaListResource} for {@link OpidBetaApplication}'s explicit-mode
     * selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link OpidBetaListResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry opidBetaListResourceEntry(
            @VertxConfig JsonObject config, Provider<OpidBetaListResource> provider) {
        return GeneratedJaxRsResourceEntry.of(OpidBetaListResource.class, true, provider);
    }

    /**
     * Registers {@link OpidShareOneApplication}, active only when
     * {@code conflict.opid.shareOne.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #SHARE_ONE_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidShareOneApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidShareOneApplication.class,
                OpidShareOneApplication.PATH,
                PropertyCondition.matchesAll(config, SHARE_ONE_CONDITIONS),
                OpidShareOneApplication::new);
    }

    /**
     * Registers {@link OpidShareTwoApplication}, active only when
     * {@code conflict.opid.shareTwo.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #SHARE_TWO_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidShareTwoApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidShareTwoApplication.class,
                OpidShareTwoApplication.PATH,
                PropertyCondition.matchesAll(config, SHARE_TWO_CONDITIONS),
                OpidShareTwoApplication::new);
    }

    /**
     * Catalogs {@link OpidSharedListResource}, selectable by both {@link OpidShareOneApplication}
     * and {@link OpidShareTwoApplication}.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link OpidSharedListResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry opidSharedListResourceEntry(
            @VertxConfig JsonObject config, Provider<OpidSharedListResource> provider) {
        return GeneratedJaxRsResourceEntry.of(OpidSharedListResource.class, true, provider);
    }

    /**
     * Registers {@link OpidInheritedFirstApplication}, active only when
     * {@code conflict.opid.inheritedFirst.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #INHERITED_FIRST_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidInheritedFirstApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidInheritedFirstApplication.class,
                OpidInheritedFirstApplication.PATH,
                PropertyCondition.matchesAll(config, INHERITED_FIRST_CONDITIONS),
                OpidInheritedFirstApplication::new);
    }

    /**
     * Catalogs {@link OpidInheritedFirstResource} for {@link OpidInheritedFirstApplication}'s
     * explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link OpidInheritedFirstResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry opidInheritedFirstResourceEntry(
            @VertxConfig JsonObject config, Provider<OpidInheritedFirstResource> provider) {
        return GeneratedJaxRsResourceEntry.of(OpidInheritedFirstResource.class, true, provider);
    }

    /**
     * Registers {@link OpidInheritedSecondApplication}, active only when
     * {@code conflict.opid.inheritedSecond.active=true}.
     *
     * @param config the application configuration the condition is evaluated against
     * @return the registration, active per {@link #INHERITED_SECOND_CONDITIONS}
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsApplicationRegistration opidInheritedSecondApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedJaxRsApplicationRegistration.of(
                OpidInheritedSecondApplication.class,
                OpidInheritedSecondApplication.PATH,
                PropertyCondition.matchesAll(config, INHERITED_SECOND_CONDITIONS),
                OpidInheritedSecondApplication::new);
    }

    /**
     * Catalogs {@link OpidInheritedSecondResource} for {@link OpidInheritedSecondApplication}'s
     * explicit-mode selection.
     *
     * @param config   the application configuration (unused; unconditionally enabled)
     * @param provider lazily constructs {@link OpidInheritedSecondResource}
     * @return the catalog entry, always enabled
     */
    @Provides
    @IntoSet
    static GeneratedJaxRsResourceEntry opidInheritedSecondResourceEntry(
            @VertxConfig JsonObject config, Provider<OpidInheritedSecondResource> provider) {
        return GeneratedJaxRsResourceEntry.of(OpidInheritedSecondResource.class, true, provider);
    }
}
