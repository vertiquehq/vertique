// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RouteRegistrationException;
import dev.vertique.rest.jaxrs.RouteRegistrationViolation;
import dev.vertique.rest.jaxrs.application.policy.PermitAllResource;
import dev.vertique.rest.jaxrs.application.policy.PermitAllScopedResource;
import dev.vertique.rest.jaxrs.application.policy.RequiresActionResource;
import dev.vertique.rest.jaxrs.application.policy.ScopelessRequirementResource;
import dev.vertique.rest.jaxrs.application.policy.UnannotatedResource;
import dev.vertique.rest.jaxrs.application.policy.app.ManagementApplication;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * T005 TP-002 and TP-004: the FR-013 application-mount warning and its silence off an application
 * mount.
 *
 * <p>TP-002 ({@link #applicationMountWarnsPerImplicitOperation}) composes {@code policy.app}'s
 * {@code ManagementApplication} mount ({@link ExplicitPolicyComponents.ApplicationMountComponent})
 * around one of the {@code policy} resources unit's five variants at a time: (a) is fully
 * unannotated, with two implicit operations that must both be named, sorted, in the mount's one
 * WARN event; (b) to (e) each carry a declaration that C-POLICY classifies as either declared
 * public or restricting callers, so none of them warns.
 *
 * <p>TP-003 ({@link #requireExplicitPolicyFailsAnyJaxRsMount}) turns the opt-in on
 * ({@code jaxrs.security.requireExplicitPolicy: true}) and composes the same variant (a) resource
 * onto both mount shapes: the {@code ManagementApplication} mount and the legacy default mount of a
 * zero-declaration component. Both must fail {@code createRouter} with a {@link
 * RouteRegistrationException} carrying one {@code NO_EXPLICIT_SECURITY_POLICY} violation per
 * implicit operation and log no FR-013 warning; a control row per mount shape, hosting variant (b)
 * ({@code @PermitAll}, every operation explicit), must succeed with no violation.
 *
 * <p>TP-004 ({@link #legacyMountLogsNoPolicyWarningWithoutOptIn}) composes the same variant (a)
 * resource onto a zero-declaration, legacy default mount
 * ({@link ExplicitPolicyComponents.LegacyDefaultMountComponent}) — I-1's preservation guard: the
 * application-mount condition on the warning means even two implicit operations on a non-application
 * mount stay silent without the opt-in.
 *
 * <p>Every composition satisfies the validated mark (C-CONFLICT, T004) by running the component's
 * own {@code Set<MountCompositionValidator>} over the resolved {@code Set<RouterMount>} before
 * calling {@code createRouter}, as {@code HttpVerticle.start} does — the unit-proof form the
 * contract's "Integration variant" clause permits.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ExplicitSecurityPolicyTest {

    private static final String APPLICATION_MOUNT_PREFIX = "/api/mgmt";
    private static final String APPLICATION_MOUNT_PATH = APPLICATION_MOUNT_PREFIX + "/*";
    private static final String LEGACY_MOUNT_PATH = "/*";

    /** The exact FR-013 WARN-substring every capture is filtered on (RL-2). */
    private static final String WARNING_FRAGMENT = "no explicit security policy";

    private Logger mountLogger;
    private Level previousMountLevel;
    private ListAppender<ILoggingEvent> mountAppender;

    @BeforeEach
    void captureMountLogs() {
        mountLogger = (Logger) LoggerFactory.getLogger(JaxRsRouterMount.class);
        previousMountLevel = mountLogger.getLevel();
        mountLogger.setLevel(Level.WARN);
        mountAppender = new ListAppender<>();
        mountAppender.start();
        mountLogger.addAppender(mountAppender);
    }

    @AfterEach
    void releaseMountLogs() {
        mountLogger.detachAppender(mountAppender);
        mountAppender.stop();
        mountLogger.setLevel(previousMountLevel);
    }

    // --- TP-002 ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("applicationMountCases")
    @DisplayName("An application mount warns once per composition about its implicit operations only")
    void applicationMountWarnsPerImplicitOperation(ExplicitPolicyCase testCase, Vertx vertx) {
        JsonObject config = applicationConfig(testCase.variant(), testCase.resourceType());
        ExplicitPolicyComponents.ApplicationMountComponent component =
                DaggerExplicitPolicyComponents_ApplicationMountComponent.factory()
                        .create(config);

        ComposeResult result =
                composeAndCreate(component, APPLICATION_MOUNT_PATH, Set.of(testCase.resourceType()), vertx);

        assertAllSucceeded(testCase.label(), result);

        if (testCase.expectWarning()) {
            assertEquals(
                    List.of(expectedUnannotatedWarning()),
                    result.warnings(),
                    () -> testCase.label() + ": expected exactly one FR-013 warning naming both implicit operations, "
                            + "got: " + result.warnings());
        } else {
            assertTrue(
                    result.warnings().isEmpty(),
                    () -> testCase.label() + ": expected no FR-013 warning, got: " + result.warnings());
        }
    }

    /**
     * TP-002's five named rows, (a) to (e) per the contract's case list.
     *
     * @return the five TP-002 cases, in contract order
     */
    private static Stream<ExplicitPolicyCase> applicationMountCases() {
        return Stream.of(
                new ExplicitPolicyCase(
                        "(a) unannotated resource with two implicit operations",
                        "unannotated",
                        UnannotatedResource.class,
                        true),
                new ExplicitPolicyCase(
                        "(b) class-level @PermitAll resource", "permitAll", PermitAllResource.class, false),
                new ExplicitPolicyCase(
                        "(c) scopeless @SecurityRequirement resource",
                        "scopeless",
                        ScopelessRequirementResource.class,
                        false),
                new ExplicitPolicyCase(
                        "(d) @RequiresAction resource", "requiresAction", RequiresActionResource.class, false),
                new ExplicitPolicyCase(
                        "(e) @PermitAll with a scoped @SecurityRequirement",
                        "permitAllScoped",
                        PermitAllScopedResource.class,
                        false));
    }

    /**
     * The WARN message RL-2 fixes for TP-002 (a): {@link ManagementApplication}'s FQN, the quoted
     * application mount path, and both of {@link UnannotatedResource}'s operations, sorted by full
     * path.
     *
     * @return the expected WARN message text
     */
    private static String expectedUnannotatedWarning() {
        return "Application " + ManagementApplication.class.getName() + " at '" + APPLICATION_MOUNT_PATH
                + "' has operations with no explicit security policy: " + "GET " + APPLICATION_MOUNT_PREFIX
                + "/console/items (operationId 'mgmtItems'), " + "GET " + APPLICATION_MOUNT_PREFIX
                + "/console/status (operationId 'mgmtStatus')";
    }

    // --- TP-003 ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("optInCases")
    @DisplayName("The opt-in fails startup on an application mount and on the legacy default mount")
    void requireExplicitPolicyFailsAnyJaxRsMount(OptInCase testCase, Vertx vertx) {
        JsonObject config = testCase.applicationMount()
                ? applicationConfigWithOptIn(testCase.variant(), testCase.resourceType())
                : legacyConfigWithOptIn(testCase.variant());
        ExplicitPolicyComponents.Provisions component = testCase.applicationMount()
                ? DaggerExplicitPolicyComponents_ApplicationMountComponent.factory()
                        .create(config)
                : DaggerExplicitPolicyComponents_LegacyDefaultMountComponent.factory()
                        .create(config);
        String expectedMountPath = testCase.applicationMount() ? APPLICATION_MOUNT_PATH : LEGACY_MOUNT_PATH;
        String pathPrefix = testCase.applicationMount() ? APPLICATION_MOUNT_PREFIX : "";

        ComposeResult result = composeAndCreate(component, expectedMountPath, Set.of(testCase.resourceType()), vertx);

        assertTrue(
                result.warnings().isEmpty(),
                () -> testCase.label() + ": no FR-013 warning expected with the opt-in on, got: " + result.warnings());

        if (testCase.expectFailure()) {
            assertEquals(1, result.outcomes().size(), () -> testCase.label() + ": expected exactly one composed mount");
            RouterOutcome outcome = result.outcomes().get(0);
            assertTrue(
                    outcome.failure() != null,
                    () -> testCase.label() + ": createRouter must fail for mount "
                            + outcome.mount().mountPath());
            assertEquals(
                    RouteRegistrationException.class,
                    outcome.failure().getClass(),
                    () -> testCase.label() + ": expected exactly RouteRegistrationException, got: "
                            + describe(outcome.failure()));

            RouteRegistrationException exception = (RouteRegistrationException) outcome.failure();
            List<RouteRegistrationViolation> violations = exception.violations();
            assertEquals(
                    2,
                    violations.size(),
                    () -> testCase.label() + ": expected exactly one violation per implicit operation, got: "
                            + violations);
            for (RouteRegistrationViolation violation : violations) {
                assertEquals(
                        RouteRegistrationViolation.ViolationType.NO_EXPLICIT_SECURITY_POLICY,
                        violation.type(),
                        () -> testCase.label() + ": violation type must be NO_EXPLICIT_SECURITY_POLICY: " + violation);
            }
            Map<String, String> expectedByOperationId = Map.of(
                    "mgmtItems",
                    "GET " + pathPrefix
                            + "/console/items has no explicit security policy, which"
                            + " jaxrs.security.requireExplicitPolicy requires",
                    "mgmtStatus",
                    "GET " + pathPrefix
                            + "/console/status has no explicit security policy, which"
                            + " jaxrs.security.requireExplicitPolicy requires");
            Map<String, String> actualByOperationId = violations.stream()
                    .collect(Collectors.toMap(
                            RouteRegistrationViolation::operationId, RouteRegistrationViolation::message));
            assertEquals(
                    expectedByOperationId,
                    actualByOperationId,
                    () -> testCase.label() + ": each violation's operationId must pair with its message");
        } else {
            assertAllSucceeded(testCase.label(), result);
        }
    }

    /**
     * TP-003's four named rows: (a) and (b) drive the unannotated two-operation resource through
     * the opt-in on the application mount and the legacy default mount respectively, expecting
     * failure; the two controls drive the {@code @PermitAll} resource through the same mount
     * shapes, expecting success.
     *
     * @return the four TP-003 cases, in contract order
     */
    private static Stream<OptInCase> optInCases() {
        return Stream.of(
                new OptInCase(
                        "(a) application mount with the unannotated resource",
                        true,
                        "unannotated",
                        UnannotatedResource.class,
                        true),
                new OptInCase(
                        "(b) legacy default mount with the unannotated resource",
                        false,
                        "unannotated",
                        UnannotatedResource.class,
                        true),
                new OptInCase(
                        "control-application: application mount with the @PermitAll resource",
                        true,
                        "permitAll",
                        PermitAllResource.class,
                        false),
                new OptInCase(
                        "control-legacy: legacy default mount with the @PermitAll resource",
                        false,
                        "permitAll",
                        PermitAllResource.class,
                        false));
    }

    /**
     * Builds an application-mount composition's configuration with the opt-in on: same shape as
     * {@link #applicationConfig(String, Class)}, plus {@code jaxrs.security.requireExplicitPolicy}
     * set to {@code true}.
     *
     * @param variant      the {@code policy.<variant>.enabled} segment to enable
     * @param resourceType the single resource class {@code ManagementApplication#getClasses()}
     *                     must select
     * @return the configuration
     */
    private static JsonObject applicationConfigWithOptIn(String variant, Class<?> resourceType) {
        return new JsonObject()
                .put(
                        "jaxrs",
                        new JsonObject()
                                .put("validationStrategy", "none")
                                .put("security", new JsonObject().put("requireExplicitPolicy", true)))
                .put(
                        "policy",
                        new JsonObject()
                                .put(variant, new JsonObject().put("enabled", true))
                                .put(
                                        "app",
                                        new JsonObject().put("classes", new JsonArray().add(resourceType.getName()))));
    }

    /**
     * Builds a legacy-default-mount composition's configuration with the opt-in on: same shape as
     * {@link #legacyConfig()}, generalized to any variant, plus
     * {@code jaxrs.security.requireExplicitPolicy} set to {@code true}.
     *
     * @param variant the {@code policy.<variant>.enabled} segment to enable
     * @return the configuration
     */
    private static JsonObject legacyConfigWithOptIn(String variant) {
        return new JsonObject()
                .put(
                        "jaxrs",
                        new JsonObject()
                                .put("validationStrategy", "none")
                                .put("security", new JsonObject().put("requireExplicitPolicy", true)))
                .put("policy", new JsonObject().put(variant, new JsonObject().put("enabled", true)));
    }

    /**
     * One TP-003 case: its display label, whether it targets the application mount (versus the
     * legacy default mount), the {@code policy.<variant>.enabled} segment to enable, the resource
     * class under test, and whether {@code createRouter} must fail.
     *
     * @param label            the case's display label
     * @param applicationMount {@code true} for the {@code ManagementApplication} mount,
     *                         {@code false} for the legacy default mount
     * @param variant          the {@code policy.<variant>.enabled} segment
     * @param resourceType     the resource class under test
     * @param expectFailure    whether {@code createRouter} must fail with a
     *                         {@link RouteRegistrationException}
     */
    private record OptInCase(
            String label, boolean applicationMount, String variant, Class<?> resourceType, boolean expectFailure) {

        @Override
        public String toString() {
            return label;
        }
    }

    // --- TP-004 ---

    @Test
    @DisplayName("A zero-declaration mount logs no policy warning without the opt-in")
    void legacyMountLogsNoPolicyWarningWithoutOptIn(Vertx vertx) {
        JsonObject config = legacyConfig();
        ExplicitPolicyComponents.LegacyDefaultMountComponent component =
                DaggerExplicitPolicyComponents_LegacyDefaultMountComponent.factory()
                        .create(config);

        ComposeResult result = composeAndCreate(component, LEGACY_MOUNT_PATH, Set.of(UnannotatedResource.class), vertx);

        assertAllSucceeded("TP-004", result);
        assertTrue(
                result.warnings().isEmpty(),
                () -> "TP-004: no FR-013 warning expected off an application mount, got: " + result.warnings());
    }

    // --- Shared composition helper ---

    /**
     * Resolves {@code component}'s {@code Set<RouterMount>} once, asserts the composition's
     * preconditions (mount path and resource types) so a fixture defect fails loudly here rather
     * than surfacing as a misleading warning/violation assertion below, runs every composition
     * validator over the resolved mounts (satisfying the validated mark, C-CONFLICT), calls
     * {@code createRouter} on every mount, and returns the outcomes together with the WARN events
     * captured since the last {@link #captureMountLogs()}.
     *
     * @param component            the component under test
     * @param expectedMountPath    the sole mount path this composition must produce
     * @param expectedResourceTypes the sole mount's expected {@code meta().resourceTypes()}
     * @param vertx                the test's {@link Vertx} instance
     * @return the {@code createRouter} outcomes and the captured FR-013 WARN events
     */
    private ComposeResult composeAndCreate(
            ExplicitPolicyComponents.Provisions component,
            String expectedMountPath,
            Set<Class<?>> expectedResourceTypes,
            Vertx vertx) {
        Set<RouterMount> mounts = component.routerMounts();

        Set<String> mountPaths = mounts.stream().map(RouterMount::mountPath).collect(Collectors.toSet());
        assertEquals(Set.of(expectedMountPath), mountPaths, "composed mount path(s) must match the fixture");
        for (RouterMount mount : mounts) {
            assertEquals(
                    expectedResourceTypes,
                    mount.meta().resourceTypes(),
                    () -> "mount " + mount.mountPath() + ": composed resourceTypes must match the fixture");
        }

        List<RouterMount> mountList = List.copyOf(mounts);
        List<String> violations = new ArrayList<>();
        for (MountCompositionValidator validator : component.mountCompositionValidators()) {
            violations.addAll(validator.validate(mountList));
        }
        assertTrue(violations.isEmpty(), () -> "composition validators must report no violation: " + violations);

        List<RouterOutcome> outcomes = new ArrayList<>();
        for (RouterMount mount : mountList) {
            outcomes.add(createRouterOutcome(mount, vertx));
        }

        return new ComposeResult(outcomes, capturedWarnings());
    }

    /**
     * Calls {@code mount.createRouter(vertx)}, converting either a synchronous throwable or a
     * failed {@link io.vertx.core.Future} into a {@link RouterOutcome} failure, so the caller need
     * not distinguish the two.
     *
     * @param mount the mount to create a router for
     * @param vertx the test's {@link Vertx} instance
     * @return the outcome
     */
    private static RouterOutcome createRouterOutcome(RouterMount mount, Vertx vertx) {
        try {
            Router router = mount.createRouter(vertx)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            return new RouterOutcome(mount, router, null);
        } catch (ExecutionException e) {
            return new RouterOutcome(mount, null, e.getCause());
        } catch (RuntimeException e) {
            // A synchronous throw (e.g. RestConfigurationException) escapes before any Future is
            // even returned.
            return new RouterOutcome(mount, null, e);
        } catch (InterruptedException | TimeoutException e) {
            throw new AssertionError("createRouter did not complete in time for mount " + mount.mountPath(), e);
        }
    }

    /**
     * Asserts every outcome in {@code result} succeeded.
     *
     * @param label  the case label, prefixed to every assertion message
     * @param result the composition's outcomes
     */
    private static void assertAllSucceeded(String label, ComposeResult result) {
        for (RouterOutcome outcome : result.outcomes()) {
            assertTrue(
                    outcome.succeeded(),
                    () -> label + ": createRouter must succeed for mount "
                            + outcome.mount().mountPath() + ": " + describe(outcome.failure()));
        }
    }

    /**
     * Returns the WARN events captured on {@link JaxRsRouterMount}'s logger since the last
     * {@link #captureMountLogs()}, filtered to {@link #WARNING_FRAGMENT}.
     *
     * @return the captured, filtered WARN messages, in capture order
     */
    private List<String> capturedWarnings() {
        return mountAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getLoggerName().equals(JaxRsRouterMount.class.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(WARNING_FRAGMENT))
                .toList();
    }

    /**
     * Describes a possibly-{@code null} failure for an assertion message.
     *
     * @param failure the failure, or {@code null}
     * @return a short description
     */
    private static String describe(@Nullable Throwable failure) {
        return failure == null ? "<no failure>" : failure.getClass().getName() + ": " + failure.getMessage();
    }

    /**
     * Builds an application-mount composition's configuration: {@code jaxrs.validationStrategy}
     * always {@code none}, the given variant's gate enabled, and {@code policy.app.classes}
     * naming only {@code resourceType}. No {@code jaxrs.security} section (the opt-in stays off).
     *
     * @param variant      the {@code policy.<variant>.enabled} segment to enable
     * @param resourceType the single resource class {@code ManagementApplication#getClasses()}
     *                     must select
     * @return the configuration
     */
    private static JsonObject applicationConfig(String variant, Class<?> resourceType) {
        return new JsonObject()
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"))
                .put(
                        "policy",
                        new JsonObject()
                                .put(variant, new JsonObject().put("enabled", true))
                                .put(
                                        "app",
                                        new JsonObject().put("classes", new JsonArray().add(resourceType.getName()))));
    }

    /**
     * Builds TP-004's legacy-default-mount configuration: {@code jaxrs.validationStrategy} always
     * {@code none}, and only variant (a)'s gate enabled. No application module is present in
     * {@link ExplicitPolicyComponents.LegacyDefaultMountComponent}, so no {@code policy.app.*} key
     * is needed.
     *
     * @return the configuration
     */
    private static JsonObject legacyConfig() {
        return new JsonObject()
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"))
                .put("policy", new JsonObject().put("unannotated", new JsonObject().put("enabled", true)));
    }

    /**
     * One TP-002 case: its display label, the {@code policy.<variant>.enabled} segment to enable,
     * the resource class {@code ManagementApplication#getClasses()} must select, and whether the
     * mount must warn.
     *
     * @param label        the case's display label
     * @param variant      the {@code policy.<variant>.enabled} segment
     * @param resourceType the resource class under test
     * @param expectWarning whether the mount must log exactly one FR-013 warning
     */
    private record ExplicitPolicyCase(String label, String variant, Class<?> resourceType, boolean expectWarning) {

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One mount's {@code createRouter} outcome: either the created {@link Router}, or the failure
     * (synchronous throw or failed {@link io.vertx.core.Future}), never both.
     *
     * @param mount   the mount this outcome is for
     * @param router  the created router, or {@code null} on failure
     * @param failure the failure, or {@code null} on success
     */
    private record RouterOutcome(
            RouterMount mount,
            @Nullable Router router,
            @Nullable Throwable failure) {

        boolean succeeded() {
            return failure == null;
        }
    }

    /**
     * One composition's full result: every mount's {@code createRouter} outcome, and the FR-013
     * WARN events captured during that composition.
     *
     * @param outcomes the per-mount outcomes
     * @param warnings the captured, filtered FR-013 WARN messages
     */
    private record ComposeResult(List<RouterOutcome> outcomes, List<String> warnings) {}
}
