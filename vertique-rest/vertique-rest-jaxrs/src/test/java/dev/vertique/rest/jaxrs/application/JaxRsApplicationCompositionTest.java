// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.vertique.rest.jaxrs.application.CompositionComponents.LazinessComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ReentrantComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.StandardComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ThreeRegistrationsComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ZeroDeclarationComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.AopProxyMatchComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.DuplicateCatalogEntryComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.DuplicateRegistrationComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.HandWrittenEntryComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.MismatchedFactoryComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.StandardViolationComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubclassClassPathComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubclassGrandchildComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubclassNewInterfaceComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubclassOwnMethodComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubclassRolesAllowedComponent;
import dev.vertique.rest.jaxrs.application.MembershipComponents.SubstitutedBindingComponent;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipAopProxyResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipBaseResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipClassPathResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipGrandchildResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipNewInterfaceResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipOwnMethodResource;
import dev.vertique.rest.jaxrs.application.manual.MembershipRolesAllowedResource;
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
import dev.vertique.rest.jaxrs.application.unitb.ManagementApplication;
import dev.vertique.rest.jaxrs.application.unitb.PublicApplication;
import dev.vertique.rest.jaxrs.application.unitb.ReentrantApplication;
import dev.vertique.rest.jaxrs.application.unitb.ThirdOverridingApplication;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipCaseApplication;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipDeclaredApplication;
import dev.vertique.rest.jaxrs.application.unitb.membership.MembershipWrongTypeApplication;
import dev.vertique.rest.jaxrs.application.unitb.membership.NoBindingPathResource;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleAbstractType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleDynamicFeatureType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleFeatureType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleInterfaceType;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleNoPathResource;
import dev.vertique.rest.jaxrs.application.unitb.membership.SampleProviderType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Proves {@code JaxRsApplicationComposer}'s C-COMPOSE behavior (steps 1 to 11, without the step 1a
 * and step 1b slots T007 and T004 fill) through {@code RestModule.jaxRsRouterMount}'s
 * {@code Set<RouterMount>} and {@code @JaxRsResources Set<Object>} multibindings. TP-005 (22
 * membership-violation cases) and TP-018 (the AOP-proxy-shaped manual match) are driven by
 * {@link MembershipComponents}' self-contained fixtures, never L01's or L02's shared {@code unita},
 * {@code unitb}, or {@code manual} modules.
 *
 * <p>The composer's logger is captured by its fully qualified name, {@link #COMPOSER_LOGGER_NAME},
 * because {@code JaxRsApplicationComposer} is package-private: this test class, in the
 * {@code application} subpackage, cannot reference it directly. Observed texts (warnings, INFO
 * lines, failure messages) are logged through this class's own logger so they land in the Surefire
 * {@code *-output.txt} for evidence.
 */
class JaxRsApplicationCompositionTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(JaxRsApplicationCompositionTest.class);

    /** The composer's fully qualified logger name, captured by name because the class is package-private. */
    private static final String COMPOSER_LOGGER_NAME = "dev.vertique.rest.jaxrs.JaxRsApplicationComposer";

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
        PublicApplication.reset();
        MembershipCaseApplication.reset();
        MembershipDeclaredApplication.reset();
        AmbiguousResource.reset();
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

    @Test
    @DisplayName(
            "Explicit applications route only their selected resources; @JaxRsResources holds only the manual contribution")
    void explicitApplicationsGetOnlyTheirSelectedResources() {
        JsonObject config = config(
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName(), BlobLikeResource.class.getName())));
        StandardComponent component = standardComponent(config);

        Map<String, Set<Class<?>>> resourceTypesByPath = resourceTypesByPath(component.routerMounts());
        LOG.info("TP-001 mounts: {}", resourceTypesByPath);

        assertEquals(
                Set.of("/api/mgmt/*", "/api/public/*"),
                resourceTypesByPath.keySet(),
                "exactly the two application mounts, and no mount at jaxrs.basePath");
        assertEquals(Set.of(ExtraResource.class), resourceTypesByPath.get("/api/mgmt/*"));
        assertEquals(Set.of(CatalogResource.class, BlobLikeResource.class), resourceTypesByPath.get("/api/public/*"));

        Set<Object> resources = component.jaxRsResources();
        assertEquals(1, resources.size(), "@JaxRsResources holds only the manual contribution in explicit mode");
        assertEquals(BlobLikeResource.class, resources.iterator().next().getClass());
    }

    @Test
    @DisplayName("Every registration inactive suppresses the default mount without constructing any resource")
    void inactiveApplicationsSuppressDefaultMountWithoutConstruction() {
        StandardComponent component = standardComponent(config());

        Set<RouterMount> mounts = component.routerMounts();
        LOG.info("TP-002 mount paths: {}", mountsByPath(mounts).keySet());

        assertTrue(mounts.isEmpty(), "no mount when every registration is inactive");
        assertEquals(0, CatalogResource.CONSTRUCTIONS.get());
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get());
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get());
        assertEquals(0, BlobLikeResource.CONSTRUCTIONS.get());

        List<String> warnings = composerMessagesAt(Level.WARN);
        LOG.info("TP-002 warnings: {}", warnings);
        assertEquals(1, warnings.size(), "exactly one step-3 warning");
        String warning = warnings.get(0);
        assertTrue(warning.contains(CatalogResource.class.getSimpleName()));
        assertTrue(warning.contains(ExtraResource.class.getSimpleName()));
        assertTrue(
                warning.contains("not resolved"), "states that manual @JaxRsResources contributions were not resolved");
    }

    @Test
    @DisplayName(
            "An unselected or disabled provider is never called, and a mount orders its resources by class name, not getClasses() order")
    void unselectedAndDisabledProvidersAreNeverCalled() {
        ExtraResource.setFailIfConstructed(true);
        DisabledResource.setFailIfConstructed(true);

        JsonObject config = config(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(
                        ScopedResource.class.getName(),
                        CatalogResource.class.getName(),
                        DisabledResource.class.getName())));
        LazinessComponent component = lazinessComponent(config);

        Set<RouterMount> firstMounts = assertDoesNotThrow(component::routerMounts, "first composition must succeed");
        assertEquals(TP003_EXPECTED_ORDER, orderedResourceClassesAt(firstMounts, "/api/public/*"));

        Set<RouterMount> secondMounts = assertDoesNotThrow(component::routerMounts, "second composition must succeed");
        assertEquals(TP003_EXPECTED_ORDER, orderedResourceClassesAt(secondMounts, "/api/public/*"));

        assertEquals(
                1,
                ScopedResource.CONSTRUCTIONS.get(),
                "the @Singleton resource is constructed once across both compositions");
        assertEquals(
                2, CatalogResource.CONSTRUCTIONS.get(), "the unscoped resource is constructed once per composition");
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), "the unselected provider is never called");
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get(), "the disabled provider is never called");
    }

    private static final List<Class<?>> TP003_EXPECTED_ORDER = List.of(CatalogResource.class, ScopedResource.class);

    @Test
    @DisplayName("A manual contribution is selectable by an application, and reported when no application selects it")
    void manualContributionIsSelectableAndReportedWhenUnselected() {
        String publicPath = "/api/public/*";

        JsonObject selectedConfig = config(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(BlobLikeResource.class.getName())));
        Map<String, JaxRsRouterMount> selectedMounts =
                mountsByPath(standardComponent(selectedConfig).routerMounts());
        LOG.info("TP-004 (a) mount paths: {}", selectedMounts.keySet());
        assertTrue(
                selectedMounts.containsKey(publicPath),
                () -> "expected a mount at " + publicPath + " but found " + selectedMounts.keySet());
        assertTrue(
                selectedMounts.get(publicPath).meta().resourceTypes().contains(BlobLikeResource.class),
                "BlobLikeResource must be routed when PublicApplication selects it");

        composerAppender.list.clear();

        JsonObject unselectedConfig = config(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        Map<String, JaxRsRouterMount> unselectedMounts =
                mountsByPath(standardComponent(unselectedConfig).routerMounts());
        boolean blobLikeMounted = unselectedMounts.values().stream()
                .anyMatch(mount -> mount.meta().resourceTypes().contains(BlobLikeResource.class));
        assertFalse(blobLikeMounted, "BlobLikeResource must not be routed by any mount when no application selects it");

        List<String> unselectedWarnings = composerMessagesAt(Level.WARN);
        LOG.info("TP-004 (b) unselected warnings: {}", unselectedWarnings);
        assertEquals(1, unselectedWarnings.size(), "exactly one step-10 warning");
        assertTrue(unselectedWarnings.get(0).contains(BlobLikeResource.class.getSimpleName()));
        assertTrue(unselectedWarnings.get(0).contains("not selected by any Application"));
    }

    @Test
    @DisplayName(
            "A sole active discovery application selects everything enabled; it never tolerates company, active or not")
    void soleDiscoveryApplicationSelectsEverythingAndRejectsCompany() {
        String discoveryPath = "/api/*";

        // (a) DiscoveryApplication as the only registration, active.
        JsonObject soloConfig = config("unitb.discoveryApplication.active", true);
        Map<String, JaxRsRouterMount> soloMounts =
                mountsByPath(discoverySoloComponent(soloConfig).routerMounts());
        LOG.info("TP-006 (a) mount paths: {}", soloMounts.keySet());
        assertEquals(Set.of(discoveryPath), soloMounts.keySet(), "exactly the discovery mount");
        assertEquals(
                Set.of(CatalogResource.class, ExtraResource.class, BlobLikeResource.class),
                soloMounts.get(discoveryPath).meta().resourceTypes(),
                "every enabled catalog entry and the manual contribution, without the disabled entry");
        List<String> soloUnselectedWarnings = composerMessagesAt(Level.WARN).stream()
                .filter(message -> message.contains("not selected by any Application"))
                .toList();
        assertTrue(
                soloUnselectedWarnings.isEmpty(), "the sole discovery application selects everything enabled (SP-003)");

        // (b) DiscoveryApplication beside an active ManagementApplication.
        JsonObject besideActiveConfig =
                config("unitb.discoveryApplication.active", true, "unitb.managementApplication.active", true);
        RestConfigurationException besideActive = assertThrows(
                RestConfigurationException.class,
                () -> discoveryComponent(besideActiveConfig).routerMounts(),
                "a discovery application beside an active application must fail");
        LOG.info("TP-006 (b) failure: {}", besideActive.getMessage());

        // (c) DiscoveryApplication beside an inactive registration of another application.
        JsonObject besideInactiveConfig = config("unitb.discoveryApplication.active", true);
        RestConfigurationException besideInactive = assertThrows(
                RestConfigurationException.class,
                () -> discoveryComponent(besideInactiveConfig).routerMounts(),
                "inactive registrations still count toward the sole-discovery check");
        LOG.info("TP-006 (c) failure: {}", besideInactive.getMessage());

        // (d) An inactive DiscoveryApplication registration beside an active ManagementApplication.
        JsonObject inactiveDiscoveryConfig = config("unitb.managementApplication.active", true);
        RestConfigurationException inactiveDiscovery = assertThrows(
                RestConfigurationException.class,
                () -> discoveryComponent(inactiveDiscoveryConfig).routerMounts(),
                "the discovery check runs at step 1 for every registration, active or inactive");
        LOG.info("TP-006 (d) failure: {}", inactiveDiscovery.getMessage());
    }

    @Test
    @DisplayName(
            "A non-default jaxrs.basePath warns that it is not applied to application mounts, and never echoes the value")
    void nonDefaultBasePathWarnsUnused() {
        String nonDefaultBasePath = "/legacy/*";
        JsonObject config = config("jaxrs.basePath", nonDefaultBasePath, "unitb.managementApplication.active", true);
        StandardComponent component = standardComponent(config);

        Map<String, JaxRsRouterMount> mounts = mountsByPath(component.routerMounts());
        LOG.info("TP-007 mount paths: {}", mounts.keySet());
        List<String> warnings = composerMessagesAt(Level.WARN);
        LOG.info("TP-007 warnings: {}", warnings);

        List<String> basePathWarnings = warnings.stream()
                .filter(message -> message.contains("jaxrs.basePath"))
                .toList();
        assertEquals(1, basePathWarnings.size(), "exactly one step-2 warning names jaxrs.basePath");
        assertTrue(basePathWarnings.get(0).contains("not applied to application mounts"));

        assertTrue(
                allComposerMessages().stream().noneMatch(message -> message.contains(nonDefaultBasePath)),
                "no composer message echoes the configured base path value");
        assertTrue(
                mounts.keySet().stream().noneMatch(path -> path.startsWith("/legacy")),
                "no mount path derives from jaxrs.basePath in explicit mode (FR-004)");
    }

    @Test
    @DisplayName("Every application mount uses the configured global OpenAPI contract location")
    void applicationMountsUseGlobalContractLocation() {
        Set<String> applicationMountPaths = Set.of("/api/public/*", "/api/mgmt/*");
        JsonObject config = config(
                "jaxrs.openapiPath",
                "contract.json",
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        StandardComponent component = standardComponent(config);

        Map<String, JaxRsRouterMount> mounts = mountsByPath(component.routerMounts());
        LOG.info("TP-008 mount paths: {}", mounts.keySet());
        assertEquals(applicationMountPaths, mounts.keySet(), "exactly the two application mounts");

        for (String path : applicationMountPaths) {
            assertEquals("contract.json", mounts.get(path).meta().openapiPath(), "mount at " + path);
        }
    }

    @Test
    @DisplayName("Zero declarations under the new C-GEN shape keep T001's legacy content, and log no composer line")
    void zeroDeclarationsWithNewShapeKeepLegacyContent() {
        Set<Class<?>> expectedTypes = Set.of(CatalogResource.class, ExtraResource.class, BlobLikeResource.class);
        JsonObject config = config("jaxrs.basePath", "/api/*", "jaxrs.openapiPath", "custom.json");
        ZeroDeclarationComponent component = zeroDeclarationComponent(config);

        Set<Object> first = component.jaxRsResources();
        assertEquals(3, first.size(), "exactly one instance each of Catalog, Extra, and BlobLike");
        assertEquals(expectedTypes, first.stream().map(Object::getClass).collect(Collectors.toSet()));
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get(), "the non-matching condition must never construct");
        assertEquals(1, CatalogResource.CONSTRUCTIONS.get(), "one construction per resolution");
        assertEquals(1, ExtraResource.CONSTRUCTIONS.get(), "one construction per resolution");

        Set<Object> second = component.jaxRsResources();
        assertEquals(3, second.size(), "exactly one instance each of Catalog, Extra, and BlobLike");
        assertEquals(expectedTypes, second.stream().map(Object::getClass).collect(Collectors.toSet()));
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get(), "the non-matching condition must never construct");
        assertEquals(2, CatalogResource.CONSTRUCTIONS.get(), "the counter rises by exactly 1 per resolution");
        assertEquals(2, ExtraResource.CONSTRUCTIONS.get(), "the counter rises by exactly 1 per resolution");

        Set<RouterMount> mounts = component.routerMounts();
        assertEquals(1, mounts.size(), "exactly one default mount");
        JaxRsRouterMount mount =
                assertInstanceOf(JaxRsRouterMount.class, mounts.iterator().next());
        assertEquals("/api/*", mount.mountPath());
        assertEquals("custom.json", mount.meta().openapiPath());
        assertEquals(expectedTypes, mount.meta().resourceTypes());

        List<String> composerRecords = allComposerMessages();
        LOG.info("TP-009 composer records: {}", composerRecords);
        assertTrue(composerRecords.isEmpty(), "no composer line is logged in zero-declaration mode (I-1)");
    }

    @Test
    @DisplayName("An all-disabled selection mounts with no routes, and there is no fallback to a default mount")
    void allDisabledSelectionMountsNoRoutes() {
        String publicPath = "/api/public/*";
        JsonObject config = config(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(DisabledResource.class.getName())));
        StandardComponent component = standardComponent(config);

        Map<String, JaxRsRouterMount> mounts = mountsByPath(component.routerMounts());
        LOG.info("TP-014 mount paths: {}", mounts.keySet());
        assertEquals(
                Set.of(publicPath), mounts.keySet(), "exactly the application mount, with no fallback default mount");
        assertTrue(
                mounts.get(publicPath).meta().resourceTypes().isEmpty(),
                "no resources are routed when every listed class is disabled (S-004)");
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get());
    }

    @Test
    @DisplayName(
            "Re-entrant composition on the same thread fails naming the re-entering application, and clears the flag for the next composition")
    void reentrantCompositionFailsNamingApplication() {
        JsonObject reentrantConfig = config("unitb.reentrantApplication.active", true);
        ReentrantComponent reentrant = reentrantComponent(reentrantConfig);

        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                reentrant::routerMounts,
                "a re-entrant Application constructor must fail naming itself, not overflow the stack");
        LOG.info("TP-015 failure: {}", ex.getMessage());
        assertTrue(
                chainContains(ex, ReentrantApplication.class.getSimpleName()),
                () -> "expected the failure to name " + ReentrantApplication.class.getSimpleName() + ": "
                        + ex.getMessage());

        JsonObject validConfig = config(
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName(), BlobLikeResource.class.getName())));
        StandardComponent valid = standardComponent(validConfig);
        assertDoesNotThrow(
                valid::routerMounts, "the re-entry flag must be cleared for the next composition on this thread");
    }

    @Test
    @DisplayName(
            "Explicit mode logs one registration line and one line per application mount, even when every registration is inactive")
    void explicitModeLogsRegistrationAndMountLines() {
        String publicMountPath = "/api/public/*";
        String mgmtMountPath = "/api/mgmt/*";

        // (a) Public and Management active, Third inactive: three registrations, two mounts.
        JsonObject activeConfig = config(
                "unitb.publicApplication.active",
                true,
                "unitb.managementApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName(), BlobLikeResource.class.getName())));
        threeRegistrationsComponent(activeConfig).routerMounts();
        List<String> activeInfo = composerMessagesAt(Level.INFO);
        LOG.info("TP-016 (a) INFO lines: {}", activeInfo);

        long registrationLinesA = activeInfo.stream()
                .filter(message -> message.contains(PublicApplication.class.getSimpleName())
                        && message.contains(ManagementApplication.class.getSimpleName())
                        && message.contains(ThirdOverridingApplication.class.getSimpleName()))
                .count();
        assertEquals(1, registrationLinesA, "exactly one INFO line lists all three registrations");

        long publicMountLinesA = activeInfo.stream()
                .filter(message -> message.contains(publicMountPath)
                        && message.contains(CatalogResource.class.getSimpleName())
                        && message.contains(BlobLikeResource.class.getSimpleName()))
                .count();
        assertEquals(1, publicMountLinesA, "exactly one INFO line names the public mount and its resources");

        long mgmtMountLinesA = activeInfo.stream()
                .filter(message ->
                        message.contains(mgmtMountPath) && message.contains(ExtraResource.class.getSimpleName()))
                .count();
        assertEquals(1, mgmtMountLinesA, "exactly one INFO line names the management mount and its resources");

        composerAppender.list.clear();

        // (b) every registration inactive: still one registration line, no per-mount line.
        threeRegistrationsComponent(config()).routerMounts();
        List<String> inactiveInfo = composerMessagesAt(Level.INFO);
        LOG.info("TP-016 (b) INFO lines: {}", inactiveInfo);

        long registrationLinesB = inactiveInfo.stream()
                .filter(message -> message.contains(PublicApplication.class.getSimpleName())
                        && message.contains(ManagementApplication.class.getSimpleName())
                        && message.contains(ThirdOverridingApplication.class.getSimpleName()))
                .count();
        assertEquals(1, registrationLinesB, "the registration line is logged before the no-active-application return");

        long mountLinesB = inactiveInfo.stream()
                .filter(message -> message.contains(publicMountPath) || message.contains(mgmtMountPath))
                .count();
        assertEquals(0, mountLinesB, "no per-mount line is logged when no application is active");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("membershipViolationCases")
    @DisplayName(
            "Each membership violation fails, naming the offending type and (except cases 21 and 22) the application's class and path")
    void membershipViolationsFailNamingApplicationAndType(MembershipCase testCase) {
        RestConfigurationException ex =
                assertThrows(RestConfigurationException.class, testCase.action()::get, testCase.name() + " must fail");
        LOG.info("TP-005 {} failure: {}", testCase.name(), ex.getMessage());

        for (String fragment : testCase.expectedMessageFragments()) {
            assertTrue(
                    chainContains(ex, fragment),
                    () -> testCase.name() + ": expected the failure to name '" + fragment + "': " + ex.getMessage());
        }

        assertEquals(
                0,
                MembershipCaseApplication.GET_PROPERTIES_CALLS.get()
                        + MembershipDeclaredApplication.GET_PROPERTIES_CALLS.get(),
                testCase.name() + ": getProperties() must never be called (C-COMPOSE step 7)");

        if (testCase.expectZeroApplicationConstructions()) {
            assertEquals(
                    0,
                    MembershipCaseApplication.CONSTRUCTIONS.get(),
                    testCase.name() + ": no application is constructed before a step 1 violation fails");
        }
    }

    /** TP-005's registration path (unnormalized, as stored on the registration). */
    private static final String MEMBERSHIP_PATH = MembershipCaseApplication.PATH;

    /** The membership application's mount path: its normalized path plus {@code /*}. */
    private static final String MEMBERSHIP_MOUNT_PATH = MEMBERSHIP_PATH + "/*";

    /** TP-005 case 16's declared-application registration path. */
    private static final String MISMATCH_PATH = MembershipDeclaredApplication.PATH;

    /**
     * Data for {@link #membershipViolationsFailNamingApplicationAndType}: TP-005's 22 cases, each
     * pairing a case name with the composition action to run, the message fragments the thrown
     * {@link RestConfigurationException} must contain (asserted by
     * {@code chainContains(exception, fragment)} — every fragment assertion in this suite uses
     * {@link Class#getSimpleName()} substrings, chosen consistently over fully qualified names), and
     * whether {@link MembershipCaseApplication#CONSTRUCTIONS} must stay {@code 0} (the step 1 cases,
     * 8 and 14).
     *
     * @return the 22 TP-005 cases, in contract order
     */
    private static Stream<MembershipCase> membershipViolationCases() {
        return Stream.of(
                new MembershipCase(
                        "case 1: non-empty getSingletons()",
                        () -> {
                            MembershipCaseApplication.classesSupplier = Set::of;
                            MembershipCaseApplication.singletonsSupplier = () -> Set.of(new Object());
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(MembershipCaseApplication.class.getSimpleName(), MEMBERSHIP_PATH),
                        false),
                new MembershipCase(
                        "case 2: getClasses() lists a @Provider class",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(SampleProviderType.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                SampleProviderType.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 3: getClasses() lists a Feature implementation",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(SampleFeatureType.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                SampleFeatureType.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 4: getClasses() lists a @Path class with no catalog entry and no manual instance",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(NoBindingPathResource.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                NoBindingPathResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 5: getClasses() lists a null member",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> {
                                Set<Class<?>> classes = new LinkedHashSet<>();
                                classes.add(null);
                                return classes;
                            };
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(MembershipCaseApplication.class.getSimpleName(), MEMBERSHIP_PATH),
                        false),
                new MembershipCase(
                        "case 6: getClasses() lists a class with both a catalog entry and a manual instance",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(AmbiguousResource.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                AmbiguousResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 7: empty getClasses()",
                        () -> {
                            MembershipCaseApplication.classesSupplier = Set::of;
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(MembershipCaseApplication.class.getSimpleName(), MEMBERSHIP_PATH),
                        false),
                new MembershipCase(
                        "case 8: two registrations of the same application class",
                        () -> duplicateRegistrationComponent(config()).routerMounts(),
                        List.of(MembershipCaseApplication.class.getSimpleName()),
                        true),
                new MembershipCase(
                        "case 9: getClasses() lists a DynamicFeature implementation",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(SampleDynamicFeatureType.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                SampleDynamicFeatureType.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 10: getClasses() lists an interface",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(SampleInterfaceType.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                SampleInterfaceType.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 11: getClasses() lists an abstract class",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(SampleAbstractType.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                SampleAbstractType.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 12: getClasses() lists a concrete class without an effective @Path",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(SampleNoPathResource.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                SampleNoPathResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 13: getClasses() lists a class matched by two manual instances",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(DuplicateManualResource.class);
                            return standardViolationComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                DuplicateManualResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 14: two catalog entries of one resource class",
                        () -> duplicateCatalogEntryComponent(config()).routerMounts(),
                        List.of(
                                DuplicateCatalogResource.class.getSimpleName(),
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH),
                        true),
                new MembershipCase(
                        "case 15: the only manual instance declares its own resource method",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(MembershipBaseResource.class);
                            return subclassOwnMethodComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                MembershipBaseResource.class.getSimpleName(),
                                MembershipOwnMethodResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 16: the registration's factory returns an instance of a different Application type",
                        () -> mismatchedFactoryComponent(config()).routerMounts(),
                        List.of(
                                MembershipDeclaredApplication.class.getSimpleName(),
                                MISMATCH_PATH,
                                MembershipWrongTypeApplication.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 17: the only manual instance carries a class-level @Path",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(MembershipBaseResource.class);
                            return subclassClassPathComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                MembershipBaseResource.class.getSimpleName(),
                                MembershipClassPathResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 18: the only manual instance implements a new @GET-declaring interface",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(MembershipBaseResource.class);
                            return subclassNewInterfaceComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                MembershipBaseResource.class.getSimpleName(),
                                MembershipNewInterfaceResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 19: the only manual instance is a grandchild, not a direct subclass",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(MembershipBaseResource.class);
                            return subclassGrandchildComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                MembershipBaseResource.class.getSimpleName(),
                                MembershipGrandchildResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 20: the only manual instance's override carries @RolesAllowed",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(MembershipBaseResource.class);
                            return subclassRolesAllowedComponent(config()).routerMounts();
                        },
                        List.of(
                                MembershipCaseApplication.class.getSimpleName(),
                                MEMBERSHIP_PATH,
                                MembershipBaseResource.class.getSimpleName(),
                                MembershipRolesAllowedResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 21: the catalog entry's Dagger binding is substituted with a subclass that adds a resource method",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(Case21Resource.class);
                            return substitutedBindingComponent(config()).routerMounts();
                        },
                        List.of(Case21Resource.class.getSimpleName(), Case21SubclassResource.class.getSimpleName()),
                        false),
                new MembershipCase(
                        "case 22: a hand-written catalog entry's provider returns an instance of an unrelated type",
                        () -> {
                            MembershipCaseApplication.classesSupplier = () -> Set.of(Case22Resource.class);
                            return handWrittenEntryComponent(config()).routerMounts();
                        },
                        List.of(Case22Resource.class.getSimpleName(), Case22UnrelatedResource.class.getSimpleName()),
                        false));
    }

    /**
     * One TP-005 case: a name, the composition action to run (which also configures
     * {@link MembershipCaseApplication}'s suppliers, when applicable, before building its
     * component), the message fragments the thrown exception must contain, and whether the
     * application-construction counter must stay {@code 0}.
     *
     * @param name                              the case's display name, also used in every
     *                                          assertion failure message
     * @param action                            builds the case's component and resolves
     *                                          {@code Set<RouterMount>}, throwing
     *                                          {@link RestConfigurationException}
     * @param expectedMessageFragments          substrings the thrown exception's message chain must
     *                                          contain
     * @param expectZeroApplicationConstructions whether {@link MembershipCaseApplication#CONSTRUCTIONS}
     *                                          must stay {@code 0} (cases 8 and 14, C-COMPOSE step 1)
     */
    private record MembershipCase(
            String name,
            Supplier<Set<RouterMount>> action,
            List<String> expectedMessageFragments,
            boolean expectZeroApplicationConstructions) {

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    @DisplayName(
            "An AOP-proxy-shaped manual subclass matches its listed base class; composition succeeds and mounts the subclass instance")
    void aopShapedManualSubclassMatchesListedClass() {
        MembershipCaseApplication.classesSupplier = () -> Set.of(MembershipBaseResource.class);
        MembershipCaseApplication.singletonsSupplier = Set::of;

        AopProxyMatchComponent component = aopProxyMatchComponent(config());
        Set<RouterMount> mounts = assertDoesNotThrow(
                component::routerMounts, "an AOP-proxy-shaped manual subclass must satisfy sameSurface");

        Map<String, JaxRsRouterMount> byPath = mountsByPath(mounts);
        LOG.info("TP-018 mount paths: {}", byPath.keySet());
        assertTrue(
                byPath.containsKey(MEMBERSHIP_MOUNT_PATH),
                () -> "expected a mount at " + MEMBERSHIP_MOUNT_PATH + " but found " + byPath.keySet());
        assertTrue(
                byPath.get(MEMBERSHIP_MOUNT_PATH).meta().resourceTypes().contains(MembershipAopProxyResource.class),
                "the mount must hold the manual AOP-proxy-shaped subclass instance, not the base class");

        List<String> unselectedWarnings = composerMessagesAt(Level.WARN).stream()
                .filter(message -> message.contains("not selected by any Application"))
                .toList();
        LOG.info("TP-018 unselected warnings: {}", unselectedWarnings);
        assertTrue(unselectedWarnings.isEmpty(), "the AOP-proxy-shaped instance must not be reported as unselected");
    }

    // --- Membership-suite component-building helpers ---

    private static StandardViolationComponent standardViolationComponent(JsonObject config) {
        return DaggerMembershipComponents_StandardViolationComponent.factory().create(config);
    }

    private static DuplicateRegistrationComponent duplicateRegistrationComponent(JsonObject config) {
        return DaggerMembershipComponents_DuplicateRegistrationComponent.factory()
                .create(config);
    }

    private static DuplicateCatalogEntryComponent duplicateCatalogEntryComponent(JsonObject config) {
        return DaggerMembershipComponents_DuplicateCatalogEntryComponent.factory()
                .create(config);
    }

    private static MismatchedFactoryComponent mismatchedFactoryComponent(JsonObject config) {
        return DaggerMembershipComponents_MismatchedFactoryComponent.factory().create(config);
    }

    private static SubstitutedBindingComponent substitutedBindingComponent(JsonObject config) {
        return DaggerMembershipComponents_SubstitutedBindingComponent.factory().create(config);
    }

    private static HandWrittenEntryComponent handWrittenEntryComponent(JsonObject config) {
        return DaggerMembershipComponents_HandWrittenEntryComponent.factory().create(config);
    }

    private static SubclassOwnMethodComponent subclassOwnMethodComponent(JsonObject config) {
        return DaggerMembershipComponents_SubclassOwnMethodComponent.factory().create(config);
    }

    private static SubclassClassPathComponent subclassClassPathComponent(JsonObject config) {
        return DaggerMembershipComponents_SubclassClassPathComponent.factory().create(config);
    }

    private static SubclassNewInterfaceComponent subclassNewInterfaceComponent(JsonObject config) {
        return DaggerMembershipComponents_SubclassNewInterfaceComponent.factory()
                .create(config);
    }

    private static SubclassGrandchildComponent subclassGrandchildComponent(JsonObject config) {
        return DaggerMembershipComponents_SubclassGrandchildComponent.factory().create(config);
    }

    private static SubclassRolesAllowedComponent subclassRolesAllowedComponent(JsonObject config) {
        return DaggerMembershipComponents_SubclassRolesAllowedComponent.factory()
                .create(config);
    }

    private static AopProxyMatchComponent aopProxyMatchComponent(JsonObject config) {
        return DaggerMembershipComponents_AopProxyMatchComponent.factory().create(config);
    }

    // --- Component-building helpers ---

    private static StandardComponent standardComponent(JsonObject config) {
        return DaggerCompositionComponents_StandardComponent.factory().create(config);
    }

    private static ZeroDeclarationComponent zeroDeclarationComponent(JsonObject config) {
        return DaggerCompositionComponents_ZeroDeclarationComponent.factory().create(config);
    }

    private static LazinessComponent lazinessComponent(JsonObject config) {
        return DaggerCompositionComponents_LazinessComponent.factory().create(config);
    }

    private static DiscoverySoloComponent discoverySoloComponent(JsonObject config) {
        return DaggerCompositionComponents_DiscoverySoloComponent.factory().create(config);
    }

    private static DiscoveryComponent discoveryComponent(JsonObject config) {
        return DaggerCompositionComponents_DiscoveryComponent.factory().create(config);
    }

    private static ReentrantComponent reentrantComponent(JsonObject config) {
        return DaggerCompositionComponents_ReentrantComponent.factory().create(config);
    }

    private static ThreeRegistrationsComponent threeRegistrationsComponent(JsonObject config) {
        return DaggerCompositionComponents_ThreeRegistrationsComponent.factory().create(config);
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

    /**
     * Maps each {@link JaxRsRouterMount} in the given set to its mount path, so assertions read as
     * a table. Application mounts are identified by {@code mountPath()}/{@code meta()}, never by
     * the package-private {@code applicationType()}, which this test package cannot reach directly.
     *
     * @param mounts the resolved mount set
     * @return the mounts keyed by path
     */
    private static Map<String, JaxRsRouterMount> mountsByPath(Set<RouterMount> mounts) {
        Map<String, JaxRsRouterMount> byPath = new LinkedHashMap<>();
        for (RouterMount mount : mounts) {
            JaxRsRouterMount jaxRsMount =
                    assertInstanceOf(JaxRsRouterMount.class, mount, "every mount in this suite is a JaxRsRouterMount");
            byPath.put(jaxRsMount.mountPath(), jaxRsMount);
        }
        return byPath;
    }

    /**
     * Maps each mount's path to its {@code meta().resourceTypes()}.
     *
     * @param mounts the resolved mount set
     * @return the mounts' resource types keyed by path
     */
    private static Map<String, Set<Class<?>>> resourceTypesByPath(Set<RouterMount> mounts) {
        Map<String, Set<Class<?>>> byPath = new LinkedHashMap<>();
        mountsByPath(mounts)
                .forEach((path, mount) -> byPath.put(path, mount.meta().resourceTypes()));
        return byPath;
    }

    /**
     * Asserts that a mount exists at {@code path} before reading its ordered resources, then
     * returns their classes in iteration order.
     *
     * @param mounts the resolved mount set
     * @param path   the expected application mount path
     * @return the mount's resource classes, in iteration order
     */
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
