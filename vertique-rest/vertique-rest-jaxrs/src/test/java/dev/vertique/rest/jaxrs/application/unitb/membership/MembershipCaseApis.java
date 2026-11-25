// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.jaxrs.application.manual.MembershipBaseResource;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unita.membership.AmbiguousResource;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogResource;
import dev.vertique.rest.jaxrs.application.unita.membership.HandWrittenEntryResource;
import dev.vertique.rest.jaxrs.application.unita.membership.SubstitutedBindingResource;

/**
 * Declaring interfaces, one per row of
 * {@code JaxRsApplicationCompositionTest.membershipViolationsFailNamingApplicationAndClass} and its
 * two accepted-row supporting tests, plus the duplicate-catalog and catalog-instance rows (
 * {@link DuplicateManualApi}, {@link DuplicateCatalogApi}, {@link SubstitutedSubclassBindingApi},
 * {@link UnrelatedCatalogInstanceApi}, {@link NullCatalogEntryApi}). Every interface is registered
 * by exactly one method of {@link MembershipViolationRegistrations} or, for the
 * dedicated-component rows, its own single-purpose registration module named after it. Every property-gated row shares
 * {@code MembershipComponents.StandardViolationComponent} so exactly one row's registration is
 * active per test invocation (inactive registrations skip membership evaluation entirely);
 * the always-active dedicated rows each get their own isolated component instead,
 * because their fixtures (a duplicate catalog entry, a substituted binding, an unrelated-type
 * provider) would otherwise affect every other row sharing the component. Every interface lists
 * exactly the one class its row names. None is ever implemented or constructed (native
 * composition never instantiates a declaring interface).
 */
final class MembershipCaseApis {

    private MembershipCaseApis() {}

    /** Row: no catalog entry or manual instance binds ({@code NoBindingPathResource}). */
    @RestApplication(
            name = "membership-no-binding",
            path = "/membership/no-binding",
            resources = NoBindingPathResource.class)
    interface NoBindingCaseApi {}

    /** Row: the listed class is a {@code @jakarta.ws.rs.ext.Provider}. */
    @RestApplication(name = "membership-provider", path = "/membership/provider", resources = SampleProviderType.class)
    interface ProviderCaseApi {}

    /** Row: the listed class implements {@code Feature}. */
    @RestApplication(name = "membership-feature", path = "/membership/feature", resources = SampleFeatureType.class)
    interface FeatureCaseApi {}

    /** Row: the listed class implements {@code DynamicFeature}. */
    @RestApplication(
            name = "membership-dynamic-feature",
            path = "/membership/dynamic-feature",
            resources = SampleDynamicFeatureType.class)
    interface DynamicFeatureCaseApi {}

    /** Row: the listed class is a plain interface. */
    @RestApplication(
            name = "membership-interface",
            path = "/membership/interface",
            resources = SampleInterfaceType.class)
    interface InterfaceCaseApi {}

    /** Row: the listed class is abstract. */
    @RestApplication(name = "membership-abstract", path = "/membership/abstract", resources = SampleAbstractType.class)
    interface AbstractCaseApi {}

    /** Row: the listed class has no effective {@code @Path}. */
    @RestApplication(name = "membership-no-path", path = "/membership/no-path", resources = SampleNoPathResource.class)
    interface NoPathCaseApi {}

    /** Row: the listed class matches both a catalog entry and a manual instance. */
    @RestApplication(name = "membership-ambiguous", path = "/membership/ambiguous", resources = AmbiguousResource.class)
    interface AmbiguousCaseApi {}

    /**
     * Row: {@code MembershipBaseResource} is listed, but the sole manual candidate
     * ({@code MembershipClassPathResource}) differs by a class-level {@code @Path} of its own.
     */
    @RestApplication(
            name = "membership-surface-own-annotation",
            path = "/membership/surface-own-annotation",
            resources = MembershipBaseResource.class)
    interface OwnAnnotationSurfaceApi {}

    /** Row: the sole manual candidate adds a new annotated resource method. */
    @RestApplication(
            name = "membership-surface-own-method",
            path = "/membership/surface-own-method",
            resources = MembershipBaseResource.class)
    interface OwnMethodSurfaceApi {}

    /** Row: the sole manual candidate's override carries an annotation only on a parameter. */
    @RestApplication(
            name = "membership-surface-param-annotation",
            path = "/membership/surface-param-annotation",
            resources = MembershipBaseResource.class)
    interface ParamAnnotationSurfaceApi {}

    /** Row: the sole manual candidate implements a new {@code @GET}-declaring interface. */
    @RestApplication(
            name = "membership-surface-new-interface",
            path = "/membership/surface-new-interface",
            resources = MembershipBaseResource.class)
    interface NewInterfaceSurfaceApi {}

    /** Row: the sole manual candidate is a grandchild, not a direct subclass. */
    @RestApplication(
            name = "membership-surface-grandchild",
            path = "/membership/surface-grandchild",
            resources = MembershipBaseResource.class)
    interface GrandchildSurfaceApi {}

    /** Row: the sole manual candidate carries a class-level {@code @PermitAll} and nothing else. */
    @RestApplication(
            name = "membership-surface-class-permit-all",
            path = "/membership/surface-class-permit-all",
            resources = MembershipBaseResource.class)
    interface ClassLevelPermitAllSurfaceApi {}

    /** Row: the sole manual candidate's override carries {@code @RolesAllowed}. */
    @RestApplication(
            name = "membership-surface-roles-allowed",
            path = "/membership/surface-roles-allowed",
            resources = MembershipBaseResource.class)
    interface RolesAllowedSurfaceApi {}

    /** Row: two applications, each with its own single violation, fail together. */
    @RestApplication(
            name = "membership-two-violations-first",
            path = "/membership/two-violations-first",
            resources = SampleProviderType.class)
    interface TwoViolationsFirstApi {}

    /** Paired with {@link TwoViolationsFirstApi}: the second application's own violation. */
    @RestApplication(
            name = "membership-two-violations-second",
            path = "/membership/two-violations-second",
            resources = SampleInterfaceType.class)
    interface TwoViolationsSecondApi {}

    /** Accepted row: an AOP-proxy-shaped manual subclass matches its listed base class. */
    @RestApplication(
            name = "membership-aop-accepted",
            path = "/membership/aop-accepted",
            resources = MembershipBaseResource.class)
    interface AopAcceptedApi {}

    /** Accepted row: the listed class's only catalog entry is disabled. */
    @RestApplication(
            name = "membership-disabled-accepted",
            path = "/membership/disabled-accepted",
            resources = DisabledResource.class)
    interface DisabledAcceptedApi {}

    /**
     * Row: the listed class ({@link DuplicateManualResource}) is contributed
     * manually twice, by two separate modules, so it has two manual matches when listed (no catalog
     * entry exists for this class). Shares {@code MembershipComponents.StandardViolationComponent}
     * with every other row above; harmless when inactive, since membership is only evaluated for a
     * listed class of an active registration.
     */
    @RestApplication(
            name = "membership-duplicate-manual",
            path = "/membership/duplicate-manual",
            resources = DuplicateManualResource.class)
    interface DuplicateManualApi {}

    /**
     * Row: the listed class ({@link DuplicateCatalogResource}) is cataloged
     * twice, by two separate modules, tripping the composer's duplicate-catalog-entry check
     * before any manual contribution or catalog entry resolves. Never shares a component with any
     * other row: a duplicate catalog entry is a whole-composition violation, so it would
     * fail every other row sharing the same component too.
     */
    @RestApplication(
            name = "membership-duplicate-catalog",
            path = "/membership/duplicate-catalog",
            resources = DuplicateCatalogResource.class)
    interface DuplicateCatalogApi {}

    /**
     * Row: the listed class ({@link SubstitutedBindingResource}) is cataloged in
     * the generated-module shape, but its Dagger binding is substituted with a
     * {@code SubstitutedBindingSubclassResource} instance that adds a resource method, tripping the catalog
     * instance's {@code sameSurface} check. Its own dedicated component: the substituted binding
     * would affect every other row's use of {@link SubstitutedBindingResource}, so none exists.
     */
    @RestApplication(
            name = "membership-substituted-subclass-binding",
            path = "/membership/substituted-subclass-binding",
            resources = SubstitutedBindingResource.class)
    interface SubstitutedSubclassBindingApi {}

    /**
     * Row: the listed class ({@link HandWrittenEntryResource}) has a
     * hand-written (not generated-module-shaped) catalog entry whose provider returns an instance of an
     * unrelated type, tripping the catalog instance's {@code sameSurface} check. Its own dedicated
     * component, isolated from {@link NullCatalogEntryApi}'s reuse of the same resource type.
     */
    @RestApplication(
            name = "membership-unrelated-catalog-instance",
            path = "/membership/unrelated-catalog-instance",
            resources = HandWrittenEntryResource.class)
    interface UnrelatedCatalogInstanceApi {}

    /**
     * Row: reuses {@link HandWrittenEntryResource} purely for its type and
     * path; its dedicated component's hand-written catalog entry provider always returns
     * {@code null}. Never shares a component with {@link UnrelatedCatalogInstanceApi}: two catalog
     * entries for the same type would trip the duplicate-catalog-entry check.
     */
    @RestApplication(
            name = "membership-null-catalog-entry",
            path = "/membership/null-catalog-entry",
            resources = HandWrittenEntryResource.class)
    interface NullCatalogEntryApi {}

    /**
     * Row: lists only {@link CatalogResource}, leaving
     * {@code unita}'s enabled {@link ExtraResource} catalog entry unselected by any active
     * application, so composing this row alone triggers the unselected-resource report, which names
     * {@link ExtraResource} by fully qualified name.
     */
    @RestApplication(
            name = "membership-diagnostics-unselected",
            path = "/membership/diagnostics-unselected",
            resources = CatalogResource.class)
    interface DiagnosticsUnselectedApi {}
}
