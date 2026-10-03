// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.conflict.opid;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Hand-written registration module for the {@code conflict.opid} compilation unit's cases (a), (b),
 * and (d), each application and its own activation flag gating it exactly as
 * {@link OpidApis} declares it.
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

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidAlphaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidAlphaApi.class,
                "opid-alpha",
                "/opid/alpha",
                List.of(OpidAlphaListResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, ALPHA_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidBetaApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidBetaApi.class,
                "opid-beta",
                "/opid/beta",
                List.of(OpidBetaListResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, BETA_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidShareOneApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidShareOneApi.class,
                "opid-share-one",
                "/opid/share-one",
                List.of(OpidSharedListResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SHARE_ONE_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidShareTwoApplicationRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidShareTwoApi.class,
                "opid-share-two",
                "/opid/share-two",
                List.of(OpidSharedListResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SHARE_TWO_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidInheritedFirstApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidInheritedFirstApi.class,
                "opid-inherited-first",
                "/opid/inherited-first",
                List.of(OpidInheritedFirstResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, INHERITED_FIRST_CONDITIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration opidInheritedSecondApplicationRegistration(
            @VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                OpidApis.OpidInheritedSecondApi.class,
                "opid-inherited-second",
                "/opid/inherited-second",
                List.of(OpidInheritedSecondResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, INHERITED_SECOND_CONDITIONS));
    }
}
