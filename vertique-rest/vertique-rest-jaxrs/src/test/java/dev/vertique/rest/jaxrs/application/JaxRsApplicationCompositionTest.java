// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.application.CompositionComponents.DiscoveryComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.DiscoverySoloComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.NestedCompositionZeroDeclarationComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ReentrantResourceExplicitComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ReentrantResourceZeroDeclarationComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.StandardComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ZeroDeclarationComponent;
import dev.vertique.rest.jaxrs.application.DuplicateNameComponents.DuplicateNameComponent;
import dev.vertique.rest.jaxrs.application.DuplicateNameComponents.RenamedControlComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.AopAcceptedComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.DuplicateCatalogEntryComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.HandWrittenEntryComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.NullCatalogEntryComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.StandardViolationComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubstitutedBindingComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceClassPermitAllComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceGrandchildComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceNewInterfaceComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceOwnAnnotationComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceOwnMethodComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceParamAnnotationComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SurfaceRolesAllowedComponent;
import dev.vertique.rest.jaxrs.application.dupname.UnitOneResource;
import dev.vertique.rest.jaxrs.application.dupname.UnitTwoResource;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipAopProxyResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipBaseResource;
import dev.vertique.rest.jaxrs.application.manual.membership.DuplicateManualResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unita.membership.AmbiguousResource;
import dev.vertique.rest.jaxrs.application.unita.membership.Case21Resource;
import dev.vertique.rest.jaxrs.application.unita.membership.Case21SubclassResource;
import dev.vertique.rest.jaxrs.application.unita.membership.Case22Resource;
import dev.vertique.rest.jaxrs.application.unita.membership.Case22UnrelatedResource;
import dev.vertique.rest.jaxrs.application.unita.membership.DuplicateCatalogResource;
import dev.vertique.rest.jaxrs.application.unita.scoped.ScopedResource;
import dev.vertique.rest.jaxrs.application.unitb.DiscoveryApi;
import dev.vertique.rest.jaxrs.application.unitb.ManagementApi;
import dev.vertique.rest.jaxrs.application.unitb.PublicApi;
import dev.vertique.rest.jaxrs.application.unitb.membership.NoBindingPathResource;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleAbstractType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleDynamicFeatureType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleFeatureType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleInterfaceType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleNoPathResource;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleProviderType;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Proves {@code JaxRsApplicationComposer}'s native-composition behavior: TP-001 to TP-005, TP-008,
 * and TP-015. The composer's logger is captured by its fully qualified name,
 * {@link #COMPOSER_LOGGER_NAME}, because {@code JaxRsApplicationComposer} is package-private: this
 * test class, in the {@code application} subpackage, cannot reference it directly.
 */
class JaxRsApplicationCompositionTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(JaxRsApplicationCompositionTest.class);

    /** The composer's fully qualified logger name, captured by name because the class is package-private. */
    private static final String COMPOSER_LOGGER_NAME = "dev.vertique.rest.jaxrs.JaxRsApplicationComposer";

    /**
     * TP-003's kind-check rows' declaring interfaces' package-qualified prefix, captured by name
     * because {@code MembershipCaseApis} and its nested interfaces are package-private in a
     * different package ({@code application.unitb.membership}): this test class cannot reference
     * them directly (R-001).
     */
    private static final String MEMBERSHIP_CASE_APIS_PREFIX =
            "dev.vertique.rest.jaxrs.application.unitb.membership.MembershipCaseApis$";

    /** R-001: the failure header every composer violation exception carries. */
    private static final String INVALID_COMPOSITION_HEADER = "Invalid JAX-RS application composition:";

    /** R-001: the reason fragment for the provider, feature, and dynamic-feature kind-check rows. */
    private static final String PROVIDER_OR_FEATURE_REASON_FRAGMENT = "provider or feature type";

    /** R-001: the reason fragment for the interface, abstract, and no-path kind-check rows. */
    private static final String NOT_CONCRETE_ROOT_RESOURCE_REASON_FRAGMENT = "not a concrete JAX-RS root resource";

    private Logger composerLogger;
    private Level previousComposerLevel;
    private ListAppender<ILoggingEvent> composerAppender;

    @BeforeEach
    void resetCounters() {
        CatalogResource.reset();
        ExtraResource.reset();
        DisabledResource.reset();
        BlobLikeResource.reset();
        ScopedResource.reset();
        AmbiguousResource.reset();
        UnitOneResource.reset();
        UnitTwoResource.reset();
    }

    @BeforeEach
    void captureComposerLogs() {
        composerLogger = (Logger) LoggerFactory.getLogger(COMPOSER_LOGGER_NAME);
        previousComposerLevel = composerLogger.getLevel();
        composerLogger.setLevel(Level.INFO);
        composerAppender = new ListAppender<>();
        composerAppender.start();
        composerLogger.addAppender(composerAppender);
    }

    @AfterEach
    void releaseComposerLogs() {
        composerLogger.detachAppender(composerAppender);
        composerAppender.stop();
        composerLogger.setLevel(previousComposerLevel);
    }

    /**
     * TP-001 — Explicit applications mount only their listed resources, named by their declaration.
     */
    @Test
    @DisplayName(
            "Explicit applications mount only their listed resources, named by their declaration, at priority 1000")
    void explicitApplicationsMountTheirListedResources() {
        JsonObject config = config("unitb.publicApplication.active", true, "unitb.managementApplication.active", true);
        StandardComponent component = standardComponent(config);

        Set<RouterMount> firstMounts = component.routerMounts();
        Set<Object> firstResources = component.jaxRsResources();
        Set<RouterMount> secondMounts = component.routerMounts();

        Map<String, JaxRsRouterMount> mounts = mountsByPath(firstMounts);
        LOG.info("TP-001 mount paths: {}", mounts.keySet());
        assertEquals(Set.of("/api/public/*", "/api/mgmt/*"), mounts.keySet(), "exactly the two application mounts");

        assertEquals(
                List.of(BlobLikeResource.class, CatalogResource.class, ScopedResource.class),
                orderedResourceClassesAt(firstMounts, "/api/public/*"),
                "public's resources, ordered by fully qualified class name");
        assertEquals(
                Set.of(ExtraResource.class), resourceTypesByPath(firstMounts).get("/api/mgmt/*"));

        assertEquals("public", ApplicationMountTestAccess.applicationName(mounts.get("/api/public/*")));
        assertEquals(
                dev.vertique.rest.jaxrs.application.unitb.PublicApi.class,
                ApplicationMountTestAccess.declaringType(mounts.get("/api/public/*")));
        assertEquals(1000, mounts.get("/api/public/*").priority());
        assertEquals("mgmt", ApplicationMountTestAccess.applicationName(mounts.get("/api/mgmt/*")));
        assertEquals(
                dev.vertique.rest.jaxrs.application.unitb.ManagementApi.class,
                ApplicationMountTestAccess.declaringType(mounts.get("/api/mgmt/*")));
        assertEquals(1000, mounts.get("/api/mgmt/*").priority());

        assertEquals(1, firstResources.size(), "@JaxRsResources holds only the manual contribution");
        assertEquals(BlobLikeResource.class, firstResources.iterator().next().getClass());

        List<String> info = composerMessagesAt(Level.INFO);
        assertTrue(
                info.stream().anyMatch(m -> m.contains("public") && m.contains("mgmt")),
                () -> "one INFO line lists both registrations: " + info);
        assertTrue(
                info.stream()
                        .anyMatch(m -> m.contains("/api/public/*")
                                && m.contains(CatalogResource.class.getSimpleName())
                                && m.contains(BlobLikeResource.class.getSimpleName())),
                () -> "one INFO line per mount names the application and its resources: " + info);

        assertEquals(0, DisabledResource.CONSTRUCTIONS.get(), "DisabledResource is never constructed");
        assertEquals(
                1,
                ScopedResource.CONSTRUCTIONS.get(),
                "the @Singleton resource is constructed once across both compositions");

        assertEquals(
                mounts.keySet(), mountsByPath(secondMounts).keySet(), "the second resolution composes the same mounts");
    }

    /**
     * TP-002 — Discovery selects everything enabled and must be sole (AC-024.5, AC-024.3).
     */
    @Test
    @DisplayName(
            "A sole active discovery application selects everything enabled; it never tolerates company, active or not")
    void soleDiscoveryApplicationSelectsEverythingAndRejectsCompany() {
        String discoveryPath = "/api/*";

        JsonObject soloConfig = config("unitb.discoveryApplication.active", true);
        Map<String, JaxRsRouterMount> soloMounts =
                mountsByPath(discoverySoloComponent(soloConfig).routerMounts());
        LOG.info("TP-002 (a) mount paths: {}", soloMounts.keySet());
        assertEquals(Set.of(discoveryPath), soloMounts.keySet(), "exactly the discovery mount");
        assertEquals(
                Set.of(CatalogResource.class, ExtraResource.class, BlobLikeResource.class),
                soloMounts.get(discoveryPath).meta().resourceTypes(),
                "every enabled catalog entry and the manual contribution, without the disabled entry");

        JsonObject besideActiveConfig =
                config("unitb.discoveryApplication.active", true, "unitb.managementApplication.active", true);
        assertDiscoveryCompanyViolation(
                besideActiveConfig, "(b) a discovery application beside an active application must fail");

        JsonObject besideInactiveConfig = config("unitb.discoveryApplication.active", true);
        assertDiscoveryCompanyViolation(
                besideInactiveConfig, "(c) inactive registrations still count toward the sole-discovery check");

        JsonObject inactiveDiscoveryConfig = config("unitb.managementApplication.active", true);
        assertDiscoveryCompanyViolation(
                inactiveDiscoveryConfig, "(d) the discovery check runs for every registration, active or inactive");
    }

    /**
     * R-007, PIT G2: TP-002 rows (b)-(d)'s shared assertion. Resets the construction counters, runs
     * the given (failing) configuration, and asserts the failure names {@code DiscoveryApi}'s
     * application name and declaring interface, every other declared registration
     * ({@code ManagementApi}, {@code PublicApi}), and the "sole" statement, raised before any catalog
     * entry or manual contribution is constructed.
     *
     * @param config the row's configuration, always producing company for the discovery application
     * @param label  the row's label, prefixed to every assertion message
     */
    private void assertDiscoveryCompanyViolation(JsonObject config, String label) {
        CatalogResource.reset();
        ExtraResource.reset();
        BlobLikeResource.reset();

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> discoveryComponent(config).routerMounts(),
                label);
        LOG.info("TP-002 {} failure: {}", label, ex.getMessage());
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains("'api'"),
                () -> label + ": expected the failure to name 'api': " + ex.getMessage());
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains(DiscoveryApi.class.getName()),
                () -> label + ": expected the failure to name DiscoveryApi: " + ex.getMessage());
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains(ManagementApi.class.getName()),
                () -> label + ": expected the failure to name every declared registration, including "
                        + "ManagementApi: " + ex.getMessage());
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains(PublicApi.class.getName()),
                () -> label + ": expected the failure to name every declared registration, including PublicApi: "
                        + ex.getMessage());
        assertTrue(
                ex.getMessage() != null && ex.getMessage().contains("sole"),
                () -> label + ": expected the failure to state the sole-registration requirement: " + ex.getMessage());
        assertEquals(0, CatalogResource.CONSTRUCTIONS.get(), label + ": no resource constructed before the failure");
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), label + ": no resource constructed before the failure");
        assertEquals(0, BlobLikeResource.CONSTRUCTIONS.get(), label + ": no resource constructed before the failure");
    }

    /**
     * TP-003 — An unbound listed class fails, naming the application and the class (AC-024.2).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("membershipViolationCases")
    @DisplayName("Each membership violation fails, naming the application and the offending class")
    void membershipViolationsFailNamingApplicationAndClass(MembershipCase testCase) {
        RestConfigurationException ex =
                assertThrows(RestConfigurationException.class, testCase.action()::get, testCase.name() + " must fail");
        LOG.info("TP-003 {} failure: {}", testCase.name(), ex.getMessage());
        for (String fragment : testCase.expectedMessageFragments()) {
            assertTrue(
                    ex.getMessage() != null && ex.getMessage().contains(fragment),
                    () -> testCase.name() + ": expected the failure to name '" + fragment + "': " + ex.getMessage());
        }
    }

    private static Stream<MembershipCase> membershipViolationCases() {
        return Stream.of(
                membershipCase(
                        "no catalog or manual binding",
                        "membership.noBinding.active",
                        NoBindingPathResource.class.getSimpleName()),
                kindCheckMembershipCase(
                        "provider kind",
                        "membership.provider.active",
                        "membership-provider",
                        "ProviderCaseApi",
                        SampleProviderType.class.getSimpleName(),
                        PROVIDER_OR_FEATURE_REASON_FRAGMENT),
                kindCheckMembershipCase(
                        "feature kind",
                        "membership.feature.active",
                        "membership-feature",
                        "FeatureCaseApi",
                        SampleFeatureType.class.getSimpleName(),
                        PROVIDER_OR_FEATURE_REASON_FRAGMENT),
                kindCheckMembershipCase(
                        "dynamic feature kind",
                        "membership.dynamicFeature.active",
                        "membership-dynamic-feature",
                        "DynamicFeatureCaseApi",
                        SampleDynamicFeatureType.class.getSimpleName(),
                        PROVIDER_OR_FEATURE_REASON_FRAGMENT),
                kindCheckMembershipCase(
                        "interface kind",
                        "membership.interfaceKind.active",
                        "membership-interface",
                        "InterfaceCaseApi",
                        SampleInterfaceType.class.getSimpleName(),
                        NOT_CONCRETE_ROOT_RESOURCE_REASON_FRAGMENT),
                kindCheckMembershipCase(
                        "abstract kind",
                        "membership.abstractKind.active",
                        "membership-abstract",
                        "AbstractCaseApi",
                        SampleAbstractType.class.getSimpleName(),
                        NOT_CONCRETE_ROOT_RESOURCE_REASON_FRAGMENT),
                kindCheckMembershipCase(
                        "no path",
                        "membership.noPath.active",
                        "membership-no-path",
                        "NoPathCaseApi",
                        SampleNoPathResource.class.getSimpleName(),
                        NOT_CONCRETE_ROOT_RESOURCE_REASON_FRAGMENT),
                membershipCase(
                        "ambiguous match", "membership.ambiguous.active", AmbiguousResource.class.getSimpleName()),
                new MembershipCase(
                        "surface: own annotation",
                        () -> surfaceOwnAnnotationComponent(config("membership.surfaceOwnAnnotation.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "surface: own method",
                        () -> surfaceOwnMethodComponent(config("membership.surfaceOwnMethod.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "surface: parameter annotation only",
                        () -> surfaceParamAnnotationComponent(config("membership.surfaceParamAnnotation.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "surface: added interface",
                        () -> surfaceNewInterfaceComponent(config("membership.surfaceNewInterface.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "surface: grandchild",
                        () -> surfaceGrandchildComponent(config("membership.surfaceGrandchild.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "surface: class-level @PermitAll",
                        () -> surfaceClassPermitAllComponent(
                                        config("membership.surfaceClassLevelPermitAll.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "surface: @RolesAllowed",
                        () -> surfaceRolesAllowedComponent(config("membership.surfaceRolesAllowed.active", true))
                                .routerMounts(),
                        List.of(MembershipBaseResource.class.getSimpleName())),
                new MembershipCase(
                        "two applications, each with one violation",
                        () -> standardViolationComponent(config("membership.twoViolations.active", true))
                                .routerMounts(),
                        List.of(SampleProviderType.class.getSimpleName(), SampleInterfaceType.class.getSimpleName())),
                new MembershipCase(
                        "case 13: the listed class is matched by two separate manual instances",
                        () -> standardViolationComponent(config("membership.duplicateManual.active", true))
                                .routerMounts(),
                        List.of(DuplicateManualResource.class.getSimpleName())),
                new MembershipCase(
                        "case 14: two catalog entries of one resource class (composer step 1)",
                        () -> duplicateCatalogEntryComponent(config()).routerMounts(),
                        List.of(DuplicateCatalogResource.class.getSimpleName())),
                new MembershipCase(
                        "case 21: the catalog entry's Dagger binding is substituted with a subclass that adds a"
                                + " resource method",
                        () -> substitutedBindingComponent(config()).routerMounts(),
                        List.of(Case21Resource.class.getSimpleName(), Case21SubclassResource.class.getSimpleName())),
                new MembershipCase(
                        "case 22: a hand-written catalog entry's provider returns an instance of an unrelated type",
                        () -> handWrittenEntryComponent(config()).routerMounts(),
                        List.of(Case22Resource.class.getSimpleName(), Case22UnrelatedResource.class.getSimpleName())));
    }

    private static MembershipCase membershipCase(String label, String activationKey, String expectedFragment) {
        return new MembershipCase(
                label,
                () -> standardViolationComponent(config(activationKey, true)).routerMounts(),
                List.of(expectedFragment));
    }

    /**
     * R-001: builds one of TP-003's kind-check rows (provider, feature, dynamic-feature, interface,
     * abstract, or no-path), asserting not just the offending class's simple name but the failure
     * header, the application's name and declaring interface, and the kind-specific reason fragment
     * the composer emits.
     *
     * @param label                       the row's display label
     * @param activationKey               the row's activation property key
     * @param applicationName             the row's registration name
     * @param declaringInterfaceSimpleName the row's declaring interface's simple name, nested under
     *                                    {@code MembershipCaseApis}
     * @param classSimpleName             the offending listed class's simple name
     * @param reasonFragment              the kind-specific reason fragment the composer emits
     * @return the assembled row
     */
    private static MembershipCase kindCheckMembershipCase(
            String label,
            String activationKey,
            String applicationName,
            String declaringInterfaceSimpleName,
            String classSimpleName,
            String reasonFragment) {
        return new MembershipCase(
                label,
                () -> standardViolationComponent(config(activationKey, true)).routerMounts(),
                List.of(
                        INVALID_COMPOSITION_HEADER,
                        "Application '" + applicationName + "' (" + MEMBERSHIP_CASE_APIS_PREFIX
                                + declaringInterfaceSimpleName + ")",
                        classSimpleName,
                        reasonFragment));
    }

    private record MembershipCase(
            String name, Supplier<Set<RouterMount>> action, List<String> expectedMessageFragments) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** TP-003's accepted row: an AOP-proxy-shaped manual subclass matches its listed base class. */
    @Test
    @DisplayName(
            "An AOP-proxy-shaped manual subclass matches its listed base class; composition succeeds and mounts the subclass instance")
    void aopShapedManualSubclassMatchesListedClass() {
        AopAcceptedComponent component = aopAcceptedComponent(config("membership.aopAccepted.active", true));
        Set<RouterMount> mounts = assertDoesNotThrow(
                component::routerMounts, "an AOP-proxy-shaped manual subclass must satisfy sameSurface");

        Map<String, JaxRsRouterMount> byPath = mountsByPath(mounts);
        String path = "/membership/aop-accepted/*";
        LOG.info("TP-003 (accepted, AOP) mount paths: {}", byPath.keySet());
        assertTrue(byPath.containsKey(path), () -> "expected a mount at " + path + " but found " + byPath.keySet());
        assertTrue(
                byPath.get(path).meta().resourceTypes().contains(MembershipAopProxyResource.class),
                "the mount must hold the manual AOP-proxy-shaped subclass instance, not the base class");
    }

    /** TP-003's accepted row: a listed class whose only catalog entry is disabled mounts with no resources. */
    @Test
    @DisplayName("A listed class whose only catalog entry is disabled mounts with no resources and no fallback")
    void disabledCatalogEntryAcceptedWithNoFallback() {
        StandardViolationComponent component =
                standardViolationComponent(config("membership.disabledAccepted.active", true));
        Set<RouterMount> mounts =
                assertDoesNotThrow(component::routerMounts, "a disabled catalog entry must not fail composition");

        Map<String, JaxRsRouterMount> byPath = mountsByPath(mounts);
        String path = "/membership/disabled-accepted/*";
        assertTrue(byPath.containsKey(path), () -> "expected a mount at " + path + " but found " + byPath.keySet());
        assertTrue(
                byPath.get(path).meta().resourceTypes().isEmpty(),
                "no resources are routed when the only listed class is disabled");
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get());
    }

    /**
     * T023 L22 restoration (G-07 (b)): a hand-written catalog entry whose provider returns
     * {@code null} fails naming the entry type, not {@code NullPointerException}. Retained behavior:
     * the composer's catalog resolution (D002's lazy catalog) still guards a null-returning provider
     * regardless of the removed application-construction and application-factory null checks.
     */
    @Test
    @DisplayName(
            "A hand-written catalog entry whose provider returns null fails naming the entry type, not NullPointerException")
    void nullCatalogEntryInstanceFailsNamingEntryType() {
        NullCatalogEntryComponent component = nullCatalogEntryComponent(config());

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                component::routerMounts,
                "a null-returning catalog entry provider must fail named, not throw NullPointerException");
        LOG.info("TP-003 (null catalog entry) failure: {}", ex.getMessage());
        assertTrue(
                chainContains(ex, Case22Resource.class.getSimpleName()),
                () -> "expected the failure to name the entry type: " + ex.getMessage());
    }

    /**
     * T023 L22 restoration (G-08): binary (fully qualified) names in diagnostics are retained
     * behavior — the composer must keep naming applications and resources by fully qualified class
     * name, not only simple name, across the registration line, the per-mount line, the unselected-
     * resource report, and a membership-violation message.
     */
    @Test
    @DisplayName(
            "Composer diagnostics name applications and resources by fully qualified class name, not only simple name")
    void diagnosticsNameByFullyQualifiedName() {
        // (a) and (b): the registration INFO line and the per-mount INFO line.
        JsonObject activeConfig =
                config("unitb.publicApplication.active", true, "unitb.managementApplication.active", true);
        standardComponent(activeConfig).routerMounts();
        List<String> infoLines = composerMessagesAt(Level.INFO);
        LOG.info("G-08 (a)/(b) INFO lines: {}", infoLines);

        boolean registrationLineHasFullyQualifiedNames = infoLines.stream()
                .anyMatch(message -> message.contains(
                                dev.vertique.rest.jaxrs.application.unitb.PublicApi.class.getName())
                        && message.contains(dev.vertique.rest.jaxrs.application.unitb.ManagementApi.class.getName()));
        assertTrue(
                registrationLineHasFullyQualifiedNames,
                () -> "G-08 (a): one INFO line must name every declaring interface by fully qualified name: "
                        + infoLines);

        boolean mountLineHasFullyQualifiedNames = infoLines.stream()
                .anyMatch(message -> message.contains("/api/public/*")
                        && message.contains(CatalogResource.class.getName())
                        && message.contains(BlobLikeResource.class.getName())
                        && message.contains(dev.vertique.rest.jaxrs.application.unitb.PublicApi.class.getName()));
        assertTrue(
                mountLineHasFullyQualifiedNames,
                () -> "G-08 (b): the per-mount INFO line must name the application and its resources by fully"
                        + " qualified name: " + infoLines);

        composerAppender.list.clear();

        // (c): the unselected-resource report names the resource by fully qualified name.
        standardViolationComponent(config("membership.diagnosticsUnselected.active", true))
                .routerMounts();
        List<String> unselectedWarnings = composerMessagesAt(Level.WARN).stream()
                .filter(message -> message.contains("not selected by any Application"))
                .toList();
        LOG.info("G-08 (c) unselected warnings: {}", unselectedWarnings);
        assertEquals(1, unselectedWarnings.size(), "exactly one unselected-resource warning");
        assertTrue(
                unselectedWarnings.get(0).contains(ExtraResource.class.getName()),
                () -> "G-08 (c): the unselected-resource warning must name the resource by fully qualified name: "
                        + unselectedWarnings.get(0));

        // (d): a membership-violation message names the declaring interface and the offending type by
        // fully qualified name.
        RestConfigurationException ex = assertThrows(RestConfigurationException.class, () -> standardViolationComponent(
                        config("membership.provider.active", true))
                .routerMounts());
        LOG.info("G-08 (d) failure: {}", ex.getMessage());
        assertTrue(
                chainContains(ex, PROVIDER_CASE_API_FULLY_QUALIFIED_NAME),
                () -> "G-08 (d): the message must name the declaring interface by fully qualified name: "
                        + ex.getMessage());
        assertTrue(
                chainContains(ex, SampleProviderType.class.getName()),
                () -> "G-08 (d): the message must name the offending type by fully qualified name: " + ex.getMessage());
    }

    /**
     * {@code MembershipCaseApis.ProviderCaseApi}'s fully qualified (binary) name, hardcoded because
     * the interface is a package-private nested type of a package-private holder class in a
     * different package ({@code unitb.membership}), so this class cannot reference it directly; the
     * literal mirrors {@link Class#getName()}'s {@code $}-nested-class form.
     */
    private static final String PROVIDER_CASE_API_FULLY_QUALIFIED_NAME =
            "dev.vertique.rest.jaxrs.application.unitb.membership.MembershipCaseApis$ProviderCaseApi";

    /**
     * TP-004 — Inactive declarations mount, publish, and fail nothing (AC-025.3, AC-026.2).
     */
    @Test
    @DisplayName("Inactive declarations mount nothing, construct nothing, and fail nothing")
    void inactiveApplicationsMountNothingAndConstructNothing() {
        JsonObject firstConfig =
                config("unitb.publicApplication.active", true, "jaxrs.applications.mgmt.openapiPath", "mgmt.yaml");
        StandardComponent first = standardComponent(firstConfig);
        Set<RouterMount> firstMounts =
                assertDoesNotThrow(first::routerMounts, "an inactive mgmt entry must be accepted");
        Map<String, JaxRsRouterMount> firstByPath = mountsByPath(firstMounts);
        LOG.info("TP-004 (first) mount paths: {}", firstByPath.keySet());
        assertEquals(Set.of("/api/public/*"), firstByPath.keySet(), "only the active application mounts");
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), "mgmt's listed resource is never constructed");

        // The first scenario legitimately constructs public's resources and logs its own
        // unselected-resource warning (mgmt's ExtraResource, inactive); reset both before the second
        // scenario asserts that nothing is constructed and exactly one warning is logged.
        resetCounters();
        composerAppender.list.clear();

        JsonObject secondConfig = config();
        StandardComponent second = standardComponent(secondConfig);
        Set<RouterMount> secondMounts = second.routerMounts();
        assertTrue(secondMounts.isEmpty(), "no mount at all when every registration is inactive");
        assertEquals(0, CatalogResource.CONSTRUCTIONS.get());
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get());
        assertEquals(0, BlobLikeResource.CONSTRUCTIONS.get());

        List<String> warnings = composerMessagesAt(Level.WARN);
        assertEquals(1, warnings.size(), "exactly one warning naming the enabled catalog entries");
        assertTrue(warnings.get(0).contains("not resolved"));
    }

    /**
     * TP-005 — Zero registrations keep the legacy default mount and its content (AC-026.1); the
     * fourth existing (unchanged) assertion of the pre-port suite.
     */
    @Test
    @DisplayName(
            "Zero declarations under the native shape keep the legacy default mount content, and log no composer line")
    void zeroDeclarationsWithNewShapeKeepLegacyContent() {
        Set<Class<?>> expectedTypes = Set.of(CatalogResource.class, ExtraResource.class, BlobLikeResource.class);
        JsonObject config = config("jaxrs.basePath", "/api/*", "jaxrs.openapiPath", "custom.json");
        ZeroDeclarationComponent component = zeroDeclarationComponent(config);

        Set<Object> first = component.jaxRsResources();
        assertEquals(3, first.size(), "exactly one instance each of Catalog, Extra, and BlobLike");
        assertEquals(expectedTypes, first.stream().map(Object::getClass).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get());

        Set<RouterMount> mounts = component.routerMounts();
        assertEquals(1, mounts.size(), "exactly one default mount");
        JaxRsRouterMount mount =
                assertInstanceOf(JaxRsRouterMount.class, mounts.iterator().next());
        assertEquals("/api/*", mount.mountPath());
        assertEquals("custom.json", mount.meta().openapiPath());
        assertEquals(expectedTypes, mount.meta().resourceTypes());

        assertTrue(allComposerMessages().isEmpty(), "no composer line is logged in zero-declaration mode");
    }

    /**
     * TP-008 — A repeated name fails when the view is built, naming both declarations (AC-022.2).
     */
    @Test
    @DisplayName(
            "A duplicate application name across units fails naming both declarations, before any resource is constructed")
    void duplicateApplicationNamesFailNamingBothDeclarations() {
        JsonObject bothActive = config("dupname.unitOne.active", true, "dupname.unitTwo.active", true);
        assertDuplicateNameFails(bothActive, "both active");

        JsonObject secondInactive = config("dupname.unitOne.active", true);
        assertDuplicateNameFails(secondInactive, "second inactive");

        JsonObject bothInactive = config();
        assertDuplicateNameFails(bothInactive, "both inactive");

        JsonObject controlConfig = config("dupname.unitOne.active", true, "dupname.unitTwo.active", true);
        RenamedControlComponent control =
                DaggerDuplicateNameComponents_RenamedControlComponent.factory().create(controlConfig);
        Set<RouterMount> controlMounts =
                assertDoesNotThrow(control::routerMounts, "the control (renamed second unit) must compose");
        assertEquals(2, controlMounts.size(), "the control composes both mounts");
    }

    private void assertDuplicateNameFails(JsonObject config, String label) {
        UnitOneResource.reset();
        UnitTwoResource.reset();
        DuplicateNameComponent component =
                DaggerDuplicateNameComponents_DuplicateNameComponent.factory().create(config);
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class, component::routerMounts, label + ": duplicate name must fail");
        LOG.info("TP-008 {} failure: {}", label, ex.getMessage());
        assertTrue(chainContains(ex, "api"), () -> label + ": expected the failure to name 'api': " + ex.getMessage());
        assertTrue(
                chainContains(ex, dev.vertique.rest.jaxrs.application.dupname.UnitOneApi.class.getName()),
                () -> label + ": expected the failure to name UnitOneApi: " + ex.getMessage());
        assertTrue(
                chainContains(ex, dev.vertique.rest.jaxrs.application.dupname.UnitTwoApi.class.getName()),
                () -> label + ": expected the failure to name UnitTwoApi: " + ex.getMessage());
        assertEquals(0, UnitOneResource.CONSTRUCTIONS.get(), label + ": no resource constructed before the failure");
        assertEquals(0, UnitTwoResource.CONSTRUCTIONS.get(), label + ": no resource constructed before the failure");
    }

    // --- TP-015: the Set<RouterMount> re-entry guard still names a re-entering resource (existing, ported) ---

    @Test
    @DisplayName(
            "G-06 (a): a manual resource depending on Set<RouterMount> in zero-declaration mode fails naming the re-entrant composition, never StackOverflowError, and a later composition on the same thread still succeeds")
    void zeroDeclarationResourceReentryFailsNamed() {
        ReentrantResourceZeroDeclarationComponent component = reentrantResourceZeroDeclarationComponent(config());

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                component::routerMounts,
                "a manual resource depending on Set<RouterMount> in zero-declaration mode must fail named, not"
                        + " overflow the stack");
        LOG.info("TP-015 (a) failure: {}", ex.getMessage());
        assertTrue(
                chainContains(ex, "re-entered") || chainContains(ex, "Set<RouterMount>"),
                () -> "expected the failure to name the re-entrant composition: " + ex.getMessage());

        JsonObject validConfig =
                config("unitb.publicApplication.active", true, "unitb.managementApplication.active", true);
        StandardComponent valid = standardComponent(validConfig);
        assertDoesNotThrow(valid::routerMounts, "a valid composition must still succeed on the same thread afterward");
    }

    @Test
    @DisplayName(
            "G-06 (b): the same manual resource, listed by an active application, fails naming the re-entrant composition in explicit mode too, never StackOverflowError")
    void explicitModeResourceReentryFailsNamed() {
        JsonObject reentrantConfig = config();
        ReentrantResourceExplicitComponent component = reentrantResourceExplicitComponent(reentrantConfig);

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                component::routerMounts,
                "an explicit-mode manual resource depending on Set<RouterMount> must fail named, not overflow the"
                        + " stack");
        LOG.info("TP-015 (b) failure: {}", ex.getMessage());
        assertTrue(
                chainContains(ex, "re-entered") || chainContains(ex, "Set<RouterMount>"),
                () -> "expected the failure to name the re-entrant composition: " + ex.getMessage());

        JsonObject validConfig =
                config("unitb.publicApplication.active", true, "unitb.managementApplication.active", true);
        StandardComponent valid = standardComponent(validConfig);
        assertDoesNotThrow(valid::routerMounts, "a valid composition must still succeed on the same thread afterward");
    }

    @Test
    @DisplayName("W-1: a manual resource that builds and resolves a second, independent Dagger component's"
            + " Set<RouterMount> inside its own construction must succeed, because a nested composition of a"
            + " different component is legitimate, unlike same-component re-entry (G-06)")
    void nestedCompositionOfDifferentComponentSucceeds() {
        NestedCompositionZeroDeclarationComponent component = nestedCompositionZeroDeclarationComponent(config());

        assertDoesNotThrow(
                component::routerMounts,
                "resolving a second, independent component's Set<RouterMount> from within a manually contributed"
                        + " resource's own construction must succeed");
    }

    // --- Component-building helpers ---

    private static StandardComponent standardComponent(JsonObject config) {
        return DaggerCompositionComponents_StandardComponent.factory().create(config);
    }

    private static ZeroDeclarationComponent zeroDeclarationComponent(JsonObject config) {
        return DaggerCompositionComponents_ZeroDeclarationComponent.factory().create(config);
    }

    private static DiscoverySoloComponent discoverySoloComponent(JsonObject config) {
        return DaggerCompositionComponents_DiscoverySoloComponent.factory().create(config);
    }

    private static DiscoveryComponent discoveryComponent(JsonObject config) {
        return DaggerCompositionComponents_DiscoveryComponent.factory().create(config);
    }

    private static StandardViolationComponent standardViolationComponent(JsonObject config) {
        return DaggerMembershipComponents_StandardViolationComponent.factory().create(config);
    }

    private static SurfaceOwnAnnotationComponent surfaceOwnAnnotationComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceOwnAnnotationComponent.factory()
                .create(config);
    }

    private static SurfaceOwnMethodComponent surfaceOwnMethodComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceOwnMethodComponent.factory().create(config);
    }

    private static SurfaceParamAnnotationComponent surfaceParamAnnotationComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceParamAnnotationComponent.factory()
                .create(config);
    }

    private static SurfaceNewInterfaceComponent surfaceNewInterfaceComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceNewInterfaceComponent.factory().create(config);
    }

    private static SurfaceGrandchildComponent surfaceGrandchildComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceGrandchildComponent.factory().create(config);
    }

    private static SurfaceClassPermitAllComponent surfaceClassPermitAllComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceClassPermitAllComponent.factory()
                .create(config);
    }

    private static SurfaceRolesAllowedComponent surfaceRolesAllowedComponent(JsonObject config) {
        return DaggerMembershipComponents_SurfaceRolesAllowedComponent.factory().create(config);
    }

    private static AopAcceptedComponent aopAcceptedComponent(JsonObject config) {
        return DaggerMembershipComponents_AopAcceptedComponent.factory().create(config);
    }

    private static DuplicateCatalogEntryComponent duplicateCatalogEntryComponent(JsonObject config) {
        return DaggerMembershipComponents_DuplicateCatalogEntryComponent.factory()
                .create(config);
    }

    private static SubstitutedBindingComponent substitutedBindingComponent(JsonObject config) {
        return DaggerMembershipComponents_SubstitutedBindingComponent.factory().create(config);
    }

    private static HandWrittenEntryComponent handWrittenEntryComponent(JsonObject config) {
        return DaggerMembershipComponents_HandWrittenEntryComponent.factory().create(config);
    }

    private static NullCatalogEntryComponent nullCatalogEntryComponent(JsonObject config) {
        return DaggerMembershipComponents_NullCatalogEntryComponent.factory().create(config);
    }

    private static ReentrantResourceZeroDeclarationComponent reentrantResourceZeroDeclarationComponent(
            JsonObject config) {
        return DaggerCompositionComponents_ReentrantResourceZeroDeclarationComponent.factory()
                .create(config);
    }

    private static ReentrantResourceExplicitComponent reentrantResourceExplicitComponent(JsonObject config) {
        return DaggerCompositionComponents_ReentrantResourceExplicitComponent.factory()
                .create(config);
    }

    private static NestedCompositionZeroDeclarationComponent nestedCompositionZeroDeclarationComponent(
            JsonObject config) {
        return DaggerCompositionComponents_NestedCompositionZeroDeclarationComponent.factory()
                .create(config);
    }

    // --- Configuration-literal helper ---

    /**
     * Builds a configuration {@link JsonObject} from dotted-path key/value pairs, so each test's
     * configuration reads as a flat table instead of nested {@code JsonObject} construction.
     *
     * @param dottedKeyValuePairs alternating dotted-path key ({@link String}) and value arguments
     * @return the assembled, possibly nested, configuration object
     */
    private static JsonObject config(Object... dottedKeyValuePairs) {
        JsonObject root = new JsonObject();
        for (int i = 0; i < dottedKeyValuePairs.length; i += 2) {
            putDotted(root, (String) dottedKeyValuePairs[i], dottedKeyValuePairs[i + 1]);
        }
        return root;
    }

    private static void putDotted(JsonObject root, String dottedKey, Object value) {
        String[] segments = dottedKey.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonObject next = current.getJsonObject(segments[i]);
            if (next == null) {
                next = new JsonObject();
                current.put(segments[i], next);
            }
            current = next;
        }
        current.put(segments[segments.length - 1], value);
    }

    // --- Mount-inspection helpers ---

    private static Map<String, JaxRsRouterMount> mountsByPath(Set<RouterMount> mounts) {
        Map<String, JaxRsRouterMount> byPath = new LinkedHashMap<>();
        for (RouterMount mount : mounts) {
            JaxRsRouterMount jaxRsMount =
                    assertInstanceOf(JaxRsRouterMount.class, mount, "every mount in this suite is a JaxRsRouterMount");
            byPath.put(jaxRsMount.mountPath(), jaxRsMount);
        }
        return byPath;
    }

    private static Map<String, Set<Class<?>>> resourceTypesByPath(Set<RouterMount> mounts) {
        Map<String, Set<Class<?>>> byPath = new LinkedHashMap<>();
        mountsByPath(mounts)
                .forEach((path, mount) -> byPath.put(path, mount.meta().resourceTypes()));
        return byPath;
    }

    private static List<Class<?>> orderedResourceClassesAt(Set<RouterMount> mounts, String path) {
        Map<String, JaxRsRouterMount> byPath = mountsByPath(mounts);
        assertTrue(byPath.containsKey(path), () -> "expected a mount at " + path + " but found " + byPath.keySet());
        return ApplicationMountTestAccess.orderedResources(byPath.get(path)).stream()
                .map(Object::getClass)
                .toList();
    }

    // --- Log-capture helpers ---

    private List<String> composerMessagesAt(Level level) {
        return composerAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> allComposerMessages() {
        return composerAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    // --- Exception-chain helper ---

    private static boolean chainContains(Throwable throwable, String text) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }
}
