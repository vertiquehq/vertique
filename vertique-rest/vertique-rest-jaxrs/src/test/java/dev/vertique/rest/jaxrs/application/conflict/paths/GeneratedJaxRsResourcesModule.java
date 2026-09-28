// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.paths;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Hand-written registration module for TP-016's {@code conflict.paths} compilation unit: every
 * conflict fixture, each gated on its own activation flag exactly as {@link PathConflictApis}
 * declares it. {@link JaxRsApplicationMountConflictTest}'s rows activate exactly the pair each case
 * names. Includes {@link PathConflictResourcesModule} (T023 L28), so every component that wires
 * this registration module also gets a manual match for every listed resource class, without this
 * package's own Dagger components ({@code ConflictCompositionComponents.ConflictPathsComponent},
 * {@code ConflictDeploymentComponents.RootApplicationConflictComponent}) needing their module list
 * changed.
 */
@Module(includes = PathConflictResourcesModule.class)
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

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration alphaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.AlphaApi.class,
                "paths-alpha",
                "/api/dup",
                List.of(AlphaResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, ALPHA_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration betaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.BetaApi.class,
                "paths-beta",
                "/api/dup",
                List.of(BetaResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, BETA_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration gammaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.GammaApi.class,
                "paths-gamma",
                "/api",
                List.of(GammaResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, GAMMA_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration deltaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.DeltaApi.class,
                "paths-delta",
                "/api/mgmt",
                List.of(DeltaResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, DELTA_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration rootApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.RootApi.class,
                "paths-root",
                "/",
                List.of(RootResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, ROOT_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicProbeApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.PublicProbeApi.class,
                "paths-public-probe",
                "/api/public",
                List.of(PublicProbeResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, PUBLIC_PROBE_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration publicityProbeApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                PathConflictApis.PublicityProbeApi.class,
                "paths-publicity-probe",
                "/api/publicity",
                List.of(PublicityProbeResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, PUBLICITY_PROBE_CONDITIONS));
    }
}
