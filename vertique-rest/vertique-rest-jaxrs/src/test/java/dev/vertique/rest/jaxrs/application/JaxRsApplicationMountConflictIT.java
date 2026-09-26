// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.application.ConflictDeploymentComponents.NestedHandBuiltNoApplicationComponent;
import dev.vertique.rest.jaxrs.application.ConflictDeploymentComponents.Provisions;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.LegacyOuterMountModule;
import dev.vertique.rest.jaxrs.application.conflict.handbuilt.LegacyReportsMountModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidAlphaApplication;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidAlphaListResource;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidBetaApplication;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidBetaListResource;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidHandBuiltOneMountModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidHandBuiltOneResource;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidHandBuiltTwoMountModule;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidHandBuiltTwoResource;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidInheritedFirstApplication;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidInheritedFirstResource;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidInheritedSecondApplication;
import dev.vertique.rest.jaxrs.application.conflict.opid.OpidInheritedSecondResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.RootApplication;
import dev.vertique.rest.jaxrs.application.conflict.paths.RootResource;
import dev.vertique.rest.jaxrs.application.conflict.spy.CountingRouterLifecycleHook;
import dev.vertique.rest.jaxrs.application.conflict.spy.CountingRouterMount;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unitb.ManagementApplication;
import dev.vertique.rest.jaxrs.application.unitb.PublicApplication;
import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.Application;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Proves T004's rest-jaxrs {@code MountCompositionValidator} contribution under real,
 * loopback-bound {@link dev.vertique.rest.core.router.HttpVerticle HttpVerticle} deployments:
 * TP-004, naming a hand-built JAX-RS mount that conflicts with a declared application, or one
 * that does not, or no application at all; TP-005, the cross-mount operationId owner rule
 * (FR-014); and TP-006, {@code createRouter}'s refusal of an application mount no composition
 * validator marked valid.
 *
 * <p>Every case is one named nested Dagger component: TP-004's from
 * {@link ConflictDeploymentComponents}, TP-005's from {@link OperationIdComponents}, TP-006's
 * from {@link ValidatedMarkComponents}. The {@link #deploy} helper bounds a synchronously
 * escaping {@link Throwable} the way {@code JaxRsApplicationDeploymentIT} does (R10), turning it
 * into a failed {@link Future} instead of letting it propagate out of
 * {@code vertx.deployVerticle(...)}.
 *
 * <p>{@code HttpVerticle}'s existing containment-overlap warning is captured by its fully
 * qualified logger name, {@link #HTTP_VERTICLE_LOGGER_NAME}, because a captured logger is looked
 * up by name (R2), never by class reference to a class this test does not otherwise depend on.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class JaxRsApplicationMountConflictIT {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(JaxRsApplicationMountConflictIT.class);

    /** {@code HttpVerticle}'s fully qualified logger name, captured by name per R2. */
    private static final String HTTP_VERTICLE_LOGGER_NAME = "dev.vertique.rest.core.router.HttpVerticle";

    private Logger httpVerticleLogger;
    private Level previousHttpVerticleLevel;
    private ListAppender<ILoggingEvent> httpVerticleAppender;

    @BeforeEach
    void resetFixtures() {
        CountingRouterMount.reset();
        CountingRouterLifecycleHook.reset();
        PublicApplication.reset();
        CatalogResource.reset();
        ExtraResource.reset();
        DisabledResource.reset();
        RootApplication.reset();
        RootResource.reset();
    }

    @BeforeEach
    void captureHttpVerticleLogs() {
        httpVerticleLogger = (Logger) LoggerFactory.getLogger(HTTP_VERTICLE_LOGGER_NAME);
        previousHttpVerticleLevel = httpVerticleLogger.getLevel();
        httpVerticleLogger.setLevel(Level.WARN);
        httpVerticleAppender = new ListAppender<>();
        httpVerticleAppender.start();
        httpVerticleLogger.addAppender(httpVerticleAppender);
    }

    @AfterEach
    void releaseHttpVerticleLogs() {
        httpVerticleLogger.detachAppender(httpVerticleAppender);
        httpVerticleAppender.stop();
        httpVerticleLogger.setLevel(previousHttpVerticleLevel);
    }

    // --- TP-004 ---

    /**
     * The reason fragment a genuine literal path overlap's failure must contain (G2-03): cases (a),
     * (e), and (f), where {@code JaxRsMountPaths.conflict} holds between the application's and the
     * hand-built mount's paths.
     */
    private static final String OVERLAP_CONFLICT_REASON = "their mount paths overlap";

    /**
     * The reason fragment a router-pattern-only conflict's failure must contain (G2-03): case (d),
     * where the hand-built mount's path is a router pattern that conflicts with every application
     * regardless of any literal prefix relation, so the failure must not claim a literal overlap.
     */
    private static final String ROUTER_PATTERN_CONFLICT_REASON =
            "a router-pattern mount path conflicts with every application mount";

    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictCases")
    @DisplayName("A hand-built JAX-RS mount that conflicts with a declared application fails deployment before any "
            + "router is created; a non-conflicting pair, and a pair with no application at all, still deploy")
    void handBuiltJaxRsMountConflictFailsBeforeInstall(Case testCase, Vertx vertx) throws Exception {
        Provisions provisions = testCase.component().get();
        DeployOutcome outcome = deploy(vertx, provisions::httpVerticle);

        if (testCase.expectConflict()) {
            assertConflictFailure(testCase, vertx, outcome);
            return;
        }

        assertTrue(
                outcome.succeeded(),
                () -> testCase.name() + ": deployment must succeed: " + describe(outcome.failure()));
        LOG.info("TP-004 {} deployed on port {}", testCase.name(), outcome.port());

        switch (testCase.id()) {
            case B -> assertPublicPublicityBothServe(vertx, outcome.intPort());
            case C -> assertLegacyOverlapWarningLogged();
            default -> throw new AssertionError("unexpected non-conflict case: " + testCase.name());
        }
    }

    /**
     * Asserts a conflicting case's shared failure shape: the deployment fails with the existing
     * {@code IllegalStateException("Invalid mount configuration:...")} (C-SPI's Start sequence,
     * step 3) naming every expected fragment, both spies stay at {@code 0}, and no
     * {@code http.port} is published.
     *
     * @param testCase the failing case
     * @param vertx    the test's {@link Vertx} instance
     * @param outcome  the deployment outcome
     */
    private void assertConflictFailure(Case testCase, Vertx vertx, DeployOutcome outcome) {
        assertConflictFailureShape("TP-004", testCase.name(), testCase.expectedFragments(), vertx, outcome);
    }

    /**
     * Case (b)'s extra verification: both the application mount and the non-conflicting hand-built
     * mount still serve their own routes.
     *
     * @param vertx the test's {@link Vertx} instance
     * @param port  the published {@code http.port}
     */
    private void assertPublicPublicityBothServe(Vertx vertx, int port) throws Exception {
        WebClient client = WebClient.create(vertx);
        try {
            HttpResponse<Buffer> publicResponse =
                    await(client.get(port, "127.0.0.1", "/api/public/catalog").send());
            assertEquals(
                    200,
                    publicResponse.statusCode(),
                    "(b): PublicApplication's mount must still serve /api/public/catalog");
            assertEquals("catalog", publicResponse.bodyAsString());

            HttpResponse<Buffer> publicityResponse =
                    await(client.get(port, "127.0.0.1", "/api/publicity/publicity-probe")
                            .send());
            assertEquals(
                    200,
                    publicityResponse.statusCode(),
                    "(b): the hand-built /api/publicity/* mount must still serve its own route");
            assertEquals("publicity", publicityResponse.bodyAsString());
        } finally {
            client.close();
        }
    }

    /**
     * Case (c)'s extra verification: {@code HttpVerticle}'s existing containment-overlap warning is
     * logged, naming both nested hand-built mount paths, unchanged.
     */
    private void assertLegacyOverlapWarningLogged() {
        List<String> overlapWarnings = httpVerticleMessagesAt(Level.WARN).stream()
                .filter(message -> message.contains(LegacyOuterMountModule.MOUNT_PATH)
                        && message.contains(LegacyReportsMountModule.MOUNT_PATH))
                .toList();
        LOG.info("TP-004 (c) captured overlap warning(s): {}", overlapWarnings);
        assertEquals(
                1,
                overlapWarnings.size(),
                () -> "(c): HttpVerticle's existing containment-overlap warning must be logged exactly once, "
                        + "unchanged: " + overlapWarnings);
    }

    // --- TP-005 ---

    /**
     * The operationId every TP-005 fixture method named {@code list} carries by default (no fixture
     * uses an explicit {@code @Operation}), asserted as one of the message facts a rejected case's
     * failure must name.
     */
    private static final String SHARED_OPERATION_ID = "list";

    @ParameterizedTest(name = "{0}")
    @MethodSource("operationIdCases")
    @DisplayName("A cross-mount operationId collision fails deployment once any application is declared, unless "
            + "both operations share the same owner; with no application declared, only the existing per-mount "
            + "rule applies")
    void duplicateOperationIdsAcrossMountsFail(OperationIdCase testCase, Vertx vertx) throws Exception {
        OperationIdComponents.Provisions provisions = testCase.component().get();
        DeployOutcome outcome = deploy(vertx, provisions::httpVerticle);

        if (testCase.expectConflict()) {
            assertOperationIdConflictFailure(testCase, vertx, outcome);
            return;
        }

        assertTrue(
                outcome.succeeded(),
                () -> testCase.name() + ": deployment must succeed: " + describe(outcome.failure()));
        LOG.info("TP-005 {} deployed on port {}", testCase.name(), outcome.port());
    }

    /**
     * Asserts a TP-005 conflicting case's shared failure shape: the deployment fails with the
     * existing {@code IllegalStateException("Invalid mount configuration:...")} shape (C-SPI's
     * Start sequence, step 3) naming every expected fragment — both operations' normalized owner
     * class and the shared method name, and both quoted mount paths — both spies stay at {@code 0},
     * and no {@code http.port} is published.
     *
     * @param testCase the failing case
     * @param vertx    the test's {@link Vertx} instance
     * @param outcome  the deployment outcome
     */
    private void assertOperationIdConflictFailure(OperationIdCase testCase, Vertx vertx, DeployOutcome outcome) {
        assertConflictFailureShape("TP-005", testCase.name(), testCase.expectedFragments(), vertx, outcome);
    }

    /**
     * Asserts the failure shape shared by every TP-004 and TP-005 rejected case: the deployment
     * fails with the existing {@code IllegalStateException("Invalid mount configuration:...")}
     * (C-SPI's Start sequence, step 3) naming every expected fragment, both spies stay at
     * {@code 0}, and no {@code http.port} is published.
     *
     * @param tpLabel           the test-plan label ("TP-004" or "TP-005") the failure is logged under
     * @param name              the failing case's display name
     * @param expectedFragments the message fragments the failure must contain
     * @param vertx             the test's {@link Vertx} instance
     * @param outcome           the deployment outcome
     */
    private void assertConflictFailureShape(
            String tpLabel, String name, List<String> expectedFragments, Vertx vertx, DeployOutcome outcome) {
        assertTrue(outcome.failed(), () -> name + ": deployment must fail");
        Throwable cause = outcome.failure();
        LOG.info("{} {} failure: {}", tpLabel, name, cause.getMessage());

        assertInstanceOf(
                IllegalStateException.class,
                cause,
                () -> name + ": a rejected composition must fail start with the existing IllegalStateException shape");
        for (String fragment : expectedFragments) {
            assertTrue(
                    cause.getMessage() != null && cause.getMessage().contains(fragment),
                    () -> name + ": expected the failure to name '" + fragment + "': " + cause.getMessage());
        }
        assertEquals(
                0,
                CountingRouterMount.CREATIONS.get(),
                () -> name + ": the non-JAX-RS spy's createRouter must never be called");
        assertEquals(
                0,
                CountingRouterLifecycleHook.CREATIONS.get(),
                () -> name + ": no JAX-RS router (hand-built or application) may be created");
        assertNull(
                vertx.sharedData().getLocalMap("vertique").get("http.port"),
                () -> name + ": no http.port must be published after a rejected composition");
    }

    // --- TP-005's six named cases ---

    /**
     * TP-005's six cases, named (a) to (f) per the contract's case list.
     *
     * @return the six TP-005 cases, in contract order
     */
    private static Stream<OperationIdCase> operationIdCases() {
        JsonObject configA = deploymentConfig("conflict.opid.alpha.active", true, "conflict.opid.beta.active", true);
        JsonObject configB =
                deploymentConfig("conflict.opid.shareOne.active", true, "conflict.opid.shareTwo.active", true);
        JsonObject configC = deploymentConfig("jaxrs.basePath", "/api/*");
        JsonObject configD = deploymentConfig(
                "conflict.opid.inheritedFirst.active", true, "conflict.opid.inheritedSecond.active", true);
        JsonObject configE = deploymentConfig();
        JsonObject configF = deploymentConfig();

        return Stream.of(
                new OperationIdCase(
                        "(a) two applications, each listing a different resource declaring 'list': no common owner, conflicts",
                        () -> oneApplicationEachListResourceComponent(configA),
                        true,
                        List.of(
                                OpidAlphaListResource.class.getName(),
                                OpidBetaListResource.class.getName(),
                                SHARED_OPERATION_ID,
                                "'" + OpidAlphaApplication.PATH + "/*'",
                                "'" + OpidBetaApplication.PATH + "/*'")),
                new OperationIdCase(
                        "(b) control: one resource declaring 'list', listed by both applications: same owner, deploys",
                        () -> sharedListResourceComponent(configB),
                        false,
                        List.of()),
                new OperationIdCase(
                        "(c) control: zero declarations, default mount and hand-built /other/* each declare 'list':"
                                + " only the per-mount rule applies, deploys",
                        () -> zeroDeclarationComponent(configC),
                        false,
                        List.of()),
                new OperationIdCase(
                        "(d) two applications, each listing its own subclass inheriting 'list': different normalized"
                                + " owners, conflicts",
                        () -> inheritedListMethodComponent(configD),
                        true,
                        List.of(
                                OpidInheritedFirstResource.class.getName(),
                                OpidInheritedSecondResource.class.getName(),
                                SHARED_OPERATION_ID,
                                "'" + OpidInheritedFirstApplication.PATH + "/*'",
                                "'" + OpidInheritedSecondApplication.PATH + "/*'")),
                new OperationIdCase(
                        "(e) one inactive registration beside two hand-built mounts declaring 'list': an application"
                                + " is declared though none is active, conflicts",
                        () -> inactiveRegistrationComponent(configE),
                        true,
                        List.of(
                                OpidHandBuiltOneResource.class.getName(),
                                OpidHandBuiltTwoResource.class.getName(),
                                SHARED_OPERATION_ID,
                                "'" + OpidHandBuiltOneMountModule.MOUNT_PATH + "'",
                                "'" + OpidHandBuiltTwoMountModule.MOUNT_PATH + "'")),
                new OperationIdCase(
                        "(f) control: an AOP-shaped subclass overriding 'list' beside an unproxied base instance:"
                                + " same normalized owner, deploys",
                        () -> aopProxyOverrideComponent(configF),
                        false,
                        List.of()));
    }

    /**
     * One TP-005 case: its name (shown by {@code @ParameterizedTest(name = "{0}")} and so in
     * {@code TEST-*.xml}), the component supplier building this case's composition, whether it must
     * fail with a cross-mount operationId collision, and (for a conflicting case) the message
     * fragments the thrown {@link IllegalStateException} must contain.
     *
     * @param name              the case's display name
     * @param component         builds this case's {@link OperationIdComponents.Provisions}, fresh
     *                          per invocation
     * @param expectConflict    {@code true} when this case must fail with an operationId collision
     * @param expectedFragments the message fragments the failure must contain (empty for a
     *                          non-conflict case)
     */
    private record OperationIdCase(
            String name,
            Supplier<OperationIdComponents.Provisions> component,
            boolean expectConflict,
            List<String> expectedFragments) {

        @Override
        public String toString() {
            return name;
        }
    }

    // --- TP-005 component-building helpers ---

    private static OperationIdComponents.OneApplicationEachListResourceComponent
            oneApplicationEachListResourceComponent(JsonObject config) {
        return DaggerOperationIdComponents_OneApplicationEachListResourceComponent.factory()
                .create(config);
    }

    private static OperationIdComponents.SharedListResourceComponent sharedListResourceComponent(JsonObject config) {
        return DaggerOperationIdComponents_SharedListResourceComponent.factory().create(config);
    }

    private static OperationIdComponents.ZeroDeclarationComponent zeroDeclarationComponent(JsonObject config) {
        return DaggerOperationIdComponents_ZeroDeclarationComponent.factory().create(config);
    }

    private static OperationIdComponents.InheritedListMethodComponent inheritedListMethodComponent(JsonObject config) {
        return DaggerOperationIdComponents_InheritedListMethodComponent.factory()
                .create(config);
    }

    private static OperationIdComponents.InactiveRegistrationComponent inactiveRegistrationComponent(
            JsonObject config) {
        return DaggerOperationIdComponents_InactiveRegistrationComponent.factory()
                .create(config);
    }

    private static OperationIdComponents.AopProxyOverrideComponent aopProxyOverrideComponent(JsonObject config) {
        return DaggerOperationIdComponents_AopProxyOverrideComponent.factory().create(config);
    }

    // --- TP-006 ---

    /** Fragment every TP-006 refusal message must contain (RL-2). */
    private static final String WITHOUT_COMPOSITION_VALIDATORS_FRAGMENT = "without composition validators";

    /** Fragment every TP-006 refusal message must contain (RL-2). */
    private static final String FROM_DAGGER_FRAGMENT = "from Dagger";

    @ParameterizedTest(name = "{0}")
    @MethodSource("validatedMarkCases")
    @DisplayName("An application mount's createRouter refuses to run when no composition validator marked it valid: "
            + "a five-argument-built HttpVerticle, a second Set<RouterMount> resolution from the same component, "
            + "and an all-disabled selection are all refused before any http.port is published; a composition the "
            + "validators reject also fails createRouter called directly")
    void applicationMountRefusesUnvalidatedRouter(ValidatedMarkCase testCase, Vertx vertx) throws Exception {
        switch (testCase.id()) {
            case A, D -> assertFiveArgumentMountRefused(testCase, vertx);
            case B -> assertUnmarkedMountCreateRouterThrows(testCase, vertx);
            case C -> assertSequentialResolutionsDiverge(testCase, vertx);
        }
    }

    /**
     * Cases (a) and (d): a five-argument-built {@code HttpVerticle}, from a fresh
     * {@code Set<RouterMount>} resolution holding {@code PublicApplication}'s mount, must fail to
     * deploy, because no composition validator ran to mark that mount valid.
     *
     * @param testCase the case
     * @param vertx    the test's {@link Vertx} instance
     */
    private void assertFiveArgumentMountRefused(ValidatedMarkCase testCase, Vertx vertx) throws Exception {
        ValidatedMarkComponents.PublicApplicationComponent component = publicApplicationComponent(testCase.config());

        DeployOutcome outcome = deploy(vertx, () -> fiveArgumentVerticle(component.routerMounts()));
        assertTrue(
                outcome.failed(),
                () -> testCase.name() + ": a five-argument-built HttpVerticle must refuse to deploy an unvalidated "
                        + "application mount");
        assertRefusalNames(testCase.name(), outcome.failure(), PublicApplication.class);
        assertNoHttpPortPublished(testCase.name(), vertx);
    }

    /**
     * Case (b): {@code PublicApplication} beside a conflicting hand-built mount. The component's
     * validators, called directly on the mounting-order-sorted {@code Set<RouterMount>}, must
     * report at least one violation; the application mount's own {@code createRouter}, called
     * directly (bypassing {@code HttpVerticle} entirely), must then throw the same refusal, since
     * the validated mark is set only when the validators' checks pass.
     *
     * @param testCase the case
     * @param vertx    the test's {@link Vertx} instance
     */
    private void assertUnmarkedMountCreateRouterThrows(ValidatedMarkCase testCase, Vertx vertx) {
        ValidatedMarkComponents.PublicApplicationAdminConflictComponent component =
                publicApplicationAdminConflictComponent(testCase.config());

        List<RouterMount> sortedMounts = mountingOrder(component.routerMounts());
        List<String> violations = new ArrayList<>();
        for (MountCompositionValidator validator : component.mountCompositionValidators()) {
            violations.addAll(validator.validate(List.copyOf(sortedMounts)));
        }
        LOG.info("TP-006 {} validator violations: {}", testCase.name(), violations);
        assertFalse(
                violations.isEmpty(),
                () -> testCase.name()
                        + ": the validators must report at least one violation for the conflicting composition");

        JaxRsRouterMount applicationMount = applicationMount(sortedMounts, PublicApplication.class, testCase.name());
        RestConfigurationException ex = assertThrows(
                RestConfigurationException.class,
                () -> applicationMount.createRouter(vertx),
                () -> testCase.name() + ": createRouter must refuse an application mount the validators rejected");
        LOG.info("TP-006 {} createRouter refusal: {}", testCase.name(), ex.getMessage());
        assertRefusalMessage(testCase.name(), ex.getMessage(), PublicApplication.class);
    }

    /**
     * Case (c): one component, two sequential (not nested) {@code Set<RouterMount>} resolutions.
     * Composition 1, deployed through the Dagger-built {@code HttpVerticle}, runs the component's
     * validators and deploys. Composition 2 is a second, independent resolution — new, unmarked
     * mount instances — deployed through a five-argument-built {@code HttpVerticle}; it must fail,
     * because a mark never carries over between compositions.
     *
     * @param testCase the case
     * @param vertx    the test's {@link Vertx} instance
     */
    private void assertSequentialResolutionsDiverge(ValidatedMarkCase testCase, Vertx vertx) throws Exception {
        ValidatedMarkComponents.PublicApplicationComponent component = publicApplicationComponent(testCase.config());

        DeployOutcome first = deploy(vertx, component::httpVerticle);
        assertTrue(
                first.succeeded(),
                () -> testCase.name() + ": composition 1 (Dagger-built HttpVerticle) must deploy: "
                        + describe(first.failure()));
        LOG.info("TP-006 {} composition 1 deployed on port {}", testCase.name(), first.port());

        DeployOutcome second = deploy(vertx, () -> fiveArgumentVerticle(component.routerMounts()));
        assertTrue(
                second.failed(),
                () -> testCase.name() + ": composition 2 (a second, five-argument-built resolution) must fail, "
                        + "because its mount instances are new and unmarked");
        assertRefusalNames(testCase.name(), second.failure(), PublicApplication.class);
        assertNoHttpPortPublished(testCase.name(), vertx);
    }

    /**
     * Asserts that {@code failure}'s cause chain contains a {@link RestConfigurationException} (the
     * refusal may arrive wrapped, since it is thrown from inside {@code HttpVerticle}'s mount
     * chain) naming {@code applicationType}, {@link #WITHOUT_COMPOSITION_VALIDATORS_FRAGMENT}, and
     * {@link #FROM_DAGGER_FRAGMENT}.
     *
     * @param label           the case name, prefixed to every assertion message
     * @param failure         the deployment's failure cause
     * @param applicationType the application class the refusal must name
     */
    private void assertRefusalNames(String label, @Nullable Throwable failure, Class<?> applicationType) {
        RestConfigurationException refusal = findRestConfigurationException(failure);
        assertNotNull(
                refusal,
                () -> label + ": expected a RestConfigurationException in the failure chain: " + describe(failure));
        LOG.info("TP-006 {} refusal: {}", label, refusal.getMessage());
        assertRefusalMessage(label, refusal.getMessage(), applicationType);
    }

    /**
     * Asserts that {@code message} names {@code applicationType}, the cause
     * ({@link #WITHOUT_COMPOSITION_VALIDATORS_FRAGMENT}), and the remedy
     * ({@link #FROM_DAGGER_FRAGMENT}) (RL-2).
     *
     * @param label           the case name, prefixed to every assertion message
     * @param message         the refusal's message
     * @param applicationType the application class the refusal must name
     */
    private void assertRefusalMessage(String label, @Nullable String message, Class<?> applicationType) {
        assertTrue(
                message != null && message.contains(applicationType.getName()),
                () -> label + ": expected the refusal to name " + applicationType.getName() + ": " + message);
        assertTrue(
                message != null && message.contains(WITHOUT_COMPOSITION_VALIDATORS_FRAGMENT),
                () -> label + ": expected the refusal to contain '" + WITHOUT_COMPOSITION_VALIDATORS_FRAGMENT + "': "
                        + message);
        assertTrue(
                message != null && message.contains(FROM_DAGGER_FRAGMENT),
                () -> label + ": expected the refusal to contain '" + FROM_DAGGER_FRAGMENT + "': " + message);
    }

    /**
     * Asserts that no {@code http.port} is published after a refused application mount's
     * deployment failed.
     *
     * @param label the case name, prefixed to the assertion message
     * @param vertx the test's {@link Vertx} instance
     */
    private void assertNoHttpPortPublished(String label, Vertx vertx) {
        assertNull(
                vertx.sharedData().getLocalMap("vertique").get("http.port"),
                () -> label + ": no http.port must be published after a refused application mount");
    }

    /**
     * Walks {@code throwable}'s cause chain (including itself) for a {@link RestConfigurationException}.
     *
     * @param throwable the throwable to search, or {@code null}
     * @return the first {@link RestConfigurationException} found, or {@code null}
     */
    private static @Nullable RestConfigurationException findRestConfigurationException(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestConfigurationException restConfigurationException) {
                return restConfigurationException;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Sorts {@code mounts} into {@code HttpVerticle}'s own mounting order: phase ascending,
     * priority ascending, mount path ascending, order key ascending — the same
     * {@link Comparator} {@code HttpVerticle.start} builds, reproduced here (RL-2 test
     * readability) so case (b) can call the component's validators the same way
     * {@code HttpVerticle} does.
     *
     * @param mounts the mounts to sort
     * @return the mounts, in mounting order
     */
    private static List<RouterMount> mountingOrder(Set<RouterMount> mounts) {
        return mounts.stream()
                .sorted(Comparator.comparing(RouterMount::phase)
                        .thenComparingInt(RouterMount::priority)
                        .thenComparing(RouterMount::mountPath)
                        .thenComparing(RouterMount::orderKey))
                .toList();
    }

    /**
     * Finds the {@link JaxRsRouterMount} built for the given declared application, identified
     * through {@link ApplicationMountTestAccess#applicationType(JaxRsRouterMount)}.
     *
     * @param mounts          the mounts to search, in any order
     * @param applicationType the declared application type to find
     * @param label           the case name, used in the failure message when no such mount exists
     * @return the matching application mount
     */
    private static JaxRsRouterMount applicationMount(
            List<RouterMount> mounts, Class<? extends Application> applicationType, String label) {
        for (RouterMount mount : mounts) {
            if (mount instanceof JaxRsRouterMount jaxRsRouterMount
                    && applicationType.equals(ApplicationMountTestAccess.applicationType(jaxRsRouterMount))) {
                return jaxRsRouterMount;
            }
        }
        throw new AssertionError(label + ": expected an application mount for " + applicationType.getName());
    }

    /**
     * Builds an {@code HttpVerticle} through the public five-argument constructor — so no
     * composition validator runs — bound to loopback on a dynamic port, from the given mounts and
     * empty router-customizer, middleware, and mount-customizer sets.
     *
     * @param mounts the mounts to compose
     * @return the constructed verticle
     */
    private static HttpVerticle fiveArgumentVerticle(Set<RouterMount> mounts) {
        return new HttpVerticle(
                new HttpServerOptions().setHost("127.0.0.1").setPort(0), Set.of(), Set.of(), mounts, Set.of());
    }

    // --- TP-006's four named cases ---

    /**
     * TP-006's four cases, named (a) to (d) per the contract's case list.
     *
     * @return the four TP-006 cases, in contract order
     */
    private static Stream<ValidatedMarkCase> validatedMarkCases() {
        JsonObject configA = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        JsonObject configB = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        JsonObject configC = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        JsonObject configD = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(DisabledResource.class.getName())));

        return Stream.of(
                new ValidatedMarkCase(
                        ValidatedMarkCaseId.A,
                        "(a) PublicApplication's mount, five-argument HttpVerticle (no validator runs): refused",
                        configA),
                new ValidatedMarkCase(
                        ValidatedMarkCaseId.B,
                        "(b) PublicApplication beside a conflicting hand-built mount: the validators report a "
                                + "violation, then createRouter refuses directly",
                        configB),
                new ValidatedMarkCase(
                        ValidatedMarkCaseId.C,
                        "(c) one component, two sequential Set<RouterMount> resolutions: the Dagger-built "
                                + "HttpVerticle deploys, a second five-argument HttpVerticle from a fresh "
                                + "resolution is refused",
                        configC),
                new ValidatedMarkCase(
                        ValidatedMarkCaseId.D,
                        "(d) T002 TP-014's all-disabled selection, five-argument HttpVerticle: refused before the "
                                + "empty-mount early return",
                        configD));
    }

    /** Identifies which lettered TP-006 case a {@link ValidatedMarkCase} is, for the test method's dispatch. */
    private enum ValidatedMarkCaseId {
        A,
        B,
        C,
        D
    }

    /**
     * One TP-006 case: its id (used by the test method to dispatch the right assertion helper),
     * its name (shown by {@code @ParameterizedTest(name = "{0}")} and so in {@code TEST-*.xml}),
     * and the deployment configuration its component's factory is bound to.
     *
     * @param id     the case's letter
     * @param name   the case's display name
     * @param config the deployment configuration for this case's component
     */
    private record ValidatedMarkCase(ValidatedMarkCaseId id, String name, JsonObject config) {

        @Override
        public String toString() {
            return name;
        }
    }

    // --- TP-006 component-building helpers ---

    private static ValidatedMarkComponents.PublicApplicationComponent publicApplicationComponent(JsonObject config) {
        return DaggerValidatedMarkComponents_PublicApplicationComponent.factory()
                .create(config);
    }

    private static ValidatedMarkComponents.PublicApplicationAdminConflictComponent
            publicApplicationAdminConflictComponent(JsonObject config) {
        return DaggerValidatedMarkComponents_PublicApplicationAdminConflictComponent.factory()
                .create(config);
    }

    // --- TP-004's six named cases ---

    /**
     * TP-004's six cases, named (a) to (f) per the contract's case list.
     *
     * @return the six TP-004 cases, in contract order
     */
    private static Stream<Case> conflictCases() {
        JsonObject configA = deploymentConfig("unitb.managementApplication.active", true);
        JsonObject configB = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        JsonObject configC = deploymentConfig();
        JsonObject configD = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        JsonObject configE = deploymentConfig(
                "unitb.publicApplication.active",
                true,
                PublicApplication.CLASSES_CONFIG_KEY,
                new JsonArray(List.of(CatalogResource.class.getName())));
        JsonObject configF = deploymentConfig("conflict.paths.root.active", true);

        return Stream.of(
                new Case(
                        CaseId.A,
                        "(a) ManagementApplication at /api/mgmt beside hand-built /api/*: conflicts",
                        () -> managementApiPrefixConflictComponent(configA),
                        true,
                        List.of(
                                "'/api/*'",
                                "'/api/mgmt/*'",
                                ManagementApplication.class.getName(),
                                OVERLAP_CONFLICT_REASON)),
                new Case(
                        CaseId.B,
                        "(b) control: PublicApplication at /api/public beside hand-built /api/publicity/*: deploys",
                        () -> publicPublicityNonConflictComponent(configB),
                        false,
                        List.of()),
                new Case(
                        CaseId.C,
                        "(c) two nested hand-built JAX-RS mounts, no application: deploys with the existing overlap warning",
                        () -> nestedHandBuiltNoApplicationComponent(configC),
                        false,
                        List.of()),
                new Case(
                        CaseId.D,
                        "(d) PublicApplication at /api/public beside hand-built /:tenant/*: pattern-path conflicts with every application",
                        () -> publicTenantPatternConflictComponent(configD),
                        true,
                        List.of(
                                "'/:tenant/*'",
                                "'/api/public/*'",
                                PublicApplication.class.getName(),
                                ROUTER_PATTERN_CONFLICT_REASON)),
                new Case(
                        CaseId.E,
                        "(e) PublicApplication at /api/public beside hand-built /api/public/admin/* (contained): conflicts in the reverse direction",
                        () -> publicAdminReverseConflictComponent(configE),
                        true,
                        List.of(
                                "'/api/public/admin/*'",
                                "'/api/public/*'",
                                PublicApplication.class.getName(),
                                OVERLAP_CONFLICT_REASON)),
                new Case(
                        CaseId.F,
                        "(f) root application at / beside hand-built /other/*: the root application conflicts with everything",
                        () -> rootApplicationConflictComponent(configF),
                        true,
                        List.of("'/other/*'", "'/*'", RootApplication.class.getName(), OVERLAP_CONFLICT_REASON)));
    }

    /** Identifies which lettered TP-004 case a {@link Case} is, for the non-conflict cases' extra verification. */
    private enum CaseId {
        A,
        B,
        C,
        D,
        E,
        F
    }

    /**
     * One TP-004 case: its name (shown by {@code @ParameterizedTest(name = "{0}")} and so in
     * {@code TEST-*.xml}), the component supplier building this case's composition, whether it
     * must fail with a path conflict, and (for a conflicting case) the message fragments the
     * thrown {@link IllegalStateException} must contain.
     *
     * @param id                the case's letter, used to dispatch a non-conflict case's extra verification
     * @param name              the case's display name
     * @param component         builds this case's {@link Provisions}, fresh per invocation
     * @param expectConflict    {@code true} when this case must fail with a path conflict
     * @param expectedFragments the message fragments the failure must contain (empty for a non-conflict case)
     */
    private record Case(
            CaseId id,
            String name,
            Supplier<Provisions> component,
            boolean expectConflict,
            List<String> expectedFragments) {

        @Override
        public String toString() {
            return name;
        }
    }

    // --- Component-building helpers ---

    private static ConflictDeploymentComponents.ManagementApiPrefixConflictComponent
            managementApiPrefixConflictComponent(JsonObject config) {
        return DaggerConflictDeploymentComponents_ManagementApiPrefixConflictComponent.factory()
                .create(config);
    }

    private static ConflictDeploymentComponents.PublicPublicityNonConflictComponent publicPublicityNonConflictComponent(
            JsonObject config) {
        return DaggerConflictDeploymentComponents_PublicPublicityNonConflictComponent.factory()
                .create(config);
    }

    private static NestedHandBuiltNoApplicationComponent nestedHandBuiltNoApplicationComponent(JsonObject config) {
        return DaggerConflictDeploymentComponents_NestedHandBuiltNoApplicationComponent.factory()
                .create(config);
    }

    private static ConflictDeploymentComponents.PublicTenantPatternConflictComponent
            publicTenantPatternConflictComponent(JsonObject config) {
        return DaggerConflictDeploymentComponents_PublicTenantPatternConflictComponent.factory()
                .create(config);
    }

    private static ConflictDeploymentComponents.PublicAdminReverseConflictComponent publicAdminReverseConflictComponent(
            JsonObject config) {
        return DaggerConflictDeploymentComponents_PublicAdminReverseConflictComponent.factory()
                .create(config);
    }

    private static ConflictDeploymentComponents.RootApplicationConflictComponent rootApplicationConflictComponent(
            JsonObject config) {
        return DaggerConflictDeploymentComponents_RootApplicationConflictComponent.factory()
                .create(config);
    }

    // --- Configuration-literal helper ---

    /**
     * Builds a deployment configuration {@link JsonObject} from loopback defaults
     * ({@code http.port=0}, {@code http.host=127.0.0.1}, {@code jaxrs.validationStrategy=none})
     * plus dotted-path key/value overrides, so each case's configuration reads as a flat table.
     *
     * @param dottedKeyValuePairs alternating dotted-path key ({@link String}) and value arguments
     * @return the assembled configuration object
     */
    private static JsonObject deploymentConfig(Object... dottedKeyValuePairs) {
        JsonObject root = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
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

    // --- Deployment outcome ---

    /**
     * The result of one {@link #deploy} call: either the published {@code http.port} (success) or
     * the deployment's failure cause — never both.
     *
     * @param port    the published port, or {@code null} on failure
     * @param failure the deployment's failure cause, or {@code null} on success
     */
    private record DeployOutcome(
            @Nullable Integer port, @Nullable Throwable failure) {

        boolean succeeded() {
            return failure == null;
        }

        boolean failed() {
            return failure != null;
        }

        /**
         * Unwraps the published port for a successful outcome's caller, which already knows
         * {@link #succeeded()} holds.
         *
         * @return the published port
         */
        int intPort() {
            return port;
        }

        static DeployOutcome success(int port) {
            return new DeployOutcome(port, null);
        }

        static DeployOutcome failure(Throwable failure) {
            return new DeployOutcome(null, failure);
        }
    }

    // --- Deployment helpers ---

    /**
     * Clears the published {@code http.port} shared-data entry, then deploys {@code supplier} and
     * returns the outcome: the published port on success, or the failure cause (including a
     * synchronously escaping {@link Throwable}, per R10) on failure.
     *
     * @param vertx    the test's {@link Vertx} instance
     * @param supplier creates a fresh {@link Verticle} instance
     * @return the deployment outcome
     */
    private static DeployOutcome deploy(Vertx vertx, Supplier<Verticle> supplier) throws Exception {
        clearPublishedPort(vertx);
        AsyncResult<String> outcome = awaitOutcome(safeDeploy(vertx, supplier, new DeploymentOptions()));
        if (outcome.failed()) {
            return DeployOutcome.failure(outcome.cause());
        }
        Integer port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
        return DeployOutcome.success(port);
    }

    /**
     * Clears the published {@code http.port} shared-data entry, so a later "absent" assertion is
     * meaningful even if an earlier case on the same {@link Vertx} published one.
     *
     * @param vertx the test's {@link Vertx} instance
     */
    private static void clearPublishedPort(Vertx vertx) {
        vertx.sharedData().getLocalMap("vertique").remove("http.port");
    }

    /**
     * Bounds {@code vertx.deployVerticle(supplier, options)} against a synchronously escaping
     * {@link Throwable} (R10): Vert.x 5.1.6 catches only {@link Exception} around the supplier it
     * invokes while constructing verticle instances, so an unwrapped {@link Error} would otherwise
     * propagate out of this call instead of failing the returned {@link Future}.
     *
     * @param vertx    the test's {@link Vertx} instance
     * @param supplier creates a fresh {@link Verticle} instance
     * @param options  the deployment options
     * @return the deployment future, failed with the escaping throwable if one occurred
     */
    private static Future<String> safeDeploy(Vertx vertx, Supplier<Verticle> supplier, DeploymentOptions options) {
        try {
            return vertx.deployVerticle(supplier, options);
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Blocks the calling (JUnit) thread for the given future's successful result, bounded by the
     * class timeout.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's result
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /**
     * Blocks the calling (JUnit) thread for the given future's outcome, bounded by the class
     * timeout, without throwing on failure.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's outcome, successful or failed
     */
    private static <T> AsyncResult<T> awaitOutcome(Future<T> future) throws Exception {
        CompletableFuture<AsyncResult<T>> outcome = new CompletableFuture<>();
        future.onComplete(outcome::complete);
        return outcome.get(15, TimeUnit.SECONDS);
    }

    // --- Description helper ---

    private static String describe(@Nullable Throwable throwable) {
        if (throwable == null) {
            return "none";
        }
        return throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
    }

    // --- Log-capture helper ---

    private List<String> httpVerticleMessagesAt(Level level) {
        return httpVerticleAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
