// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.PropertyCondition;
import dev.vertique.rest.jaxrs.application.manual.MembershipBaseResource;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.membership.AmbiguousResource;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * Hand-written module registering every {@link MembershipCaseApis} interface, each gated on its
 * own property so exactly one row (or, for the two-applications row, exactly two rows) is active
 * per {@code MembershipComponents.StandardViolationComponent} invocation. Mirrors what the
 * annotation processor would emit for each declaration: {@code active} is the only
 * configuration-evaluated field (E17); {@code name}, {@code path}, and {@code resources} match the
 * declaring interface's own {@code @RestApplication} exactly.
 */
@Module
public final class MembershipViolationRegistrations {

    private MembershipViolationRegistrations() {}

    private static PropertyCondition[] conditions(String key) {
        return new PropertyCondition[] {new PropertyCondition(key, "true", false)};
    }

    private static final PropertyCondition[] NO_BINDING = conditions("membership.noBinding.active");
    private static final PropertyCondition[] PROVIDER = conditions("membership.provider.active");
    private static final PropertyCondition[] FEATURE = conditions("membership.feature.active");
    private static final PropertyCondition[] DYNAMIC_FEATURE = conditions("membership.dynamicFeature.active");
    private static final PropertyCondition[] INTERFACE_KIND = conditions("membership.interfaceKind.active");
    private static final PropertyCondition[] ABSTRACT_KIND = conditions("membership.abstractKind.active");
    private static final PropertyCondition[] NO_PATH = conditions("membership.noPath.active");
    private static final PropertyCondition[] AMBIGUOUS = conditions("membership.ambiguous.active");
    private static final PropertyCondition[] SURFACE_OWN_ANNOTATION =
            conditions("membership.surfaceOwnAnnotation.active");
    private static final PropertyCondition[] SURFACE_OWN_METHOD = conditions("membership.surfaceOwnMethod.active");
    private static final PropertyCondition[] SURFACE_PARAM_ANNOTATION =
            conditions("membership.surfaceParamAnnotation.active");
    private static final PropertyCondition[] SURFACE_NEW_INTERFACE =
            conditions("membership.surfaceNewInterface.active");
    private static final PropertyCondition[] SURFACE_GRANDCHILD = conditions("membership.surfaceGrandchild.active");
    private static final PropertyCondition[] SURFACE_CLASS_PERMIT_ALL =
            conditions("membership.surfaceClassLevelPermitAll.active");
    private static final PropertyCondition[] SURFACE_ROLES_ALLOWED =
            conditions("membership.surfaceRolesAllowed.active");
    private static final PropertyCondition[] TWO_VIOLATIONS = conditions("membership.twoViolations.active");
    private static final PropertyCondition[] AOP_ACCEPTED = conditions("membership.aopAccepted.active");
    private static final PropertyCondition[] DISABLED_ACCEPTED = conditions("membership.disabledAccepted.active");
    private static final PropertyCondition[] DUPLICATE_MANUAL = conditions("membership.duplicateManual.active");
    private static final PropertyCondition[] DIAGNOSTICS_UNSELECTED =
            conditions("membership.diagnosticsUnselected.active");

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration noBindingCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.NoBindingCaseApi.class,
                "membership-no-binding",
                "/membership/no-binding",
                List.of(NoBindingPathResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, NO_BINDING));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration providerCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.ProviderCaseApi.class,
                "membership-provider",
                "/membership/provider",
                List.of(SampleProviderType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, PROVIDER));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration featureCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.FeatureCaseApi.class,
                "membership-feature",
                "/membership/feature",
                List.of(SampleFeatureType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, FEATURE));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration dynamicFeatureCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.DynamicFeatureCaseApi.class,
                "membership-dynamic-feature",
                "/membership/dynamic-feature",
                List.of(SampleDynamicFeatureType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, DYNAMIC_FEATURE));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration interfaceCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.InterfaceCaseApi.class,
                "membership-interface",
                "/membership/interface",
                List.of(SampleInterfaceType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, INTERFACE_KIND));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration abstractCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.AbstractCaseApi.class,
                "membership-abstract",
                "/membership/abstract",
                List.of(SampleAbstractType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, ABSTRACT_KIND));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration noPathCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.NoPathCaseApi.class,
                "membership-no-path",
                "/membership/no-path",
                List.of(SampleNoPathResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, NO_PATH));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration ambiguousCaseRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.AmbiguousCaseApi.class,
                "membership-ambiguous",
                "/membership/ambiguous",
                List.of(AmbiguousResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, AMBIGUOUS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration ownAnnotationSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.OwnAnnotationSurfaceApi.class,
                "membership-surface-own-annotation",
                "/membership/surface-own-annotation",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_OWN_ANNOTATION));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration ownMethodSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.OwnMethodSurfaceApi.class,
                "membership-surface-own-method",
                "/membership/surface-own-method",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_OWN_METHOD));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration paramAnnotationSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.ParamAnnotationSurfaceApi.class,
                "membership-surface-param-annotation",
                "/membership/surface-param-annotation",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_PARAM_ANNOTATION));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration newInterfaceSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.NewInterfaceSurfaceApi.class,
                "membership-surface-new-interface",
                "/membership/surface-new-interface",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_NEW_INTERFACE));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration grandchildSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.GrandchildSurfaceApi.class,
                "membership-surface-grandchild",
                "/membership/surface-grandchild",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_GRANDCHILD));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration classLevelPermitAllSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.ClassLevelPermitAllSurfaceApi.class,
                "membership-surface-class-permit-all",
                "/membership/surface-class-permit-all",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_CLASS_PERMIT_ALL));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration rolesAllowedSurfaceRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.RolesAllowedSurfaceApi.class,
                "membership-surface-roles-allowed",
                "/membership/surface-roles-allowed",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, SURFACE_ROLES_ALLOWED));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration twoViolationsFirstRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.TwoViolationsFirstApi.class,
                "membership-two-violations-first",
                "/membership/two-violations-first",
                List.of(SampleProviderType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, TWO_VIOLATIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration twoViolationsSecondRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.TwoViolationsSecondApi.class,
                "membership-two-violations-second",
                "/membership/two-violations-second",
                List.of(SampleInterfaceType.class),
                false,
                "",
                PropertyCondition.matchesAll(config, TWO_VIOLATIONS));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration aopAcceptedRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.AopAcceptedApi.class,
                "membership-aop-accepted",
                "/membership/aop-accepted",
                List.of(MembershipBaseResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, AOP_ACCEPTED));
    }

    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration disabledAcceptedRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.DisabledAcceptedApi.class,
                "membership-disabled-accepted",
                "/membership/disabled-accepted",
                List.of(DisabledResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, DISABLED_ACCEPTED));
    }

    /**
     * T023 L22 restoration (TP-003 case 13): {@link DuplicateManualResource} is contributed manually
     * twice ({@code DuplicateManualResourceModuleA} and {@code DuplicateManualResourceModuleB}, both
     * added to {@code MembershipComponents.StandardViolationComponent}), so when listed it has two
     * manual matches, tripping the ambiguity check. No catalog entry exists for this class.
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration duplicateManualRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.DuplicateManualApi.class,
                "membership-duplicate-manual",
                "/membership/duplicate-manual",
                List.of(DuplicateManualResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, DUPLICATE_MANUAL));
    }

    /**
     * T023 L22 restoration (G-08 (c)): lists only {@link CatalogResource}, leaving {@code unita}'s
     * enabled {@code ExtraResource} catalog entry unselected when this is the sole active row, so
     * the composer's unselected-resource report has something to name by fully qualified name.
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration diagnosticsUnselectedRegistration(@VertxConfig JsonObject config) {
        return GeneratedRestApplicationRegistration.of(
                MembershipCaseApis.DiagnosticsUnselectedApi.class,
                "membership-diagnostics-unselected",
                "/membership/diagnostics-unselected",
                List.of(CatalogResource.class),
                false,
                "",
                PropertyCondition.matchesAll(config, DIAGNOSTICS_UNSELECTED));
    }
}
