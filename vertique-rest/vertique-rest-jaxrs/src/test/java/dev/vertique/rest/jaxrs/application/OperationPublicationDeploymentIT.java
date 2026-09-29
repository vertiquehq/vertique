// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.jaxrs.RouteRegistrationException;
import dev.vertique.rest.jaxrs.application.unitb.ManagementApi;
import dev.vertique.rest.jaxrs.application.unitb.PublicApi;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.publication.fixture.CountingSchemaSource;
import dev.vertique.rest.jaxrs.publication.fixture.MountPathRecordingCustomizer;
import dev.vertique.rest.jaxrs.publication.fixture.PublicationEventRecorder;
import dev.vertique.rest.jaxrs.publication.fixture.PublicationEventRecordingSink;
import dev.vertique.rest.jaxrs.publication.fixture.RecordingSink;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T006's integration proofs, built from {@link PublicationComponents}: every JAX-RS mount publishes
 * exactly once (TP-001), a sink exception or failed future fails startup while a failed
 * registration never publishes (TP-006), and with no sink or no detail requested, routing and
 * schema-source calls are unchanged (TP-007, INV-1).
 *
 * <p>One class-scoped {@link Vertx} and {@link WebClient}; each test undeploys every deployment it
 * starts and clears the {@code vertique} local map's {@code http.port} entry before returning
 * control (directly, since a test method here runs several sequential deployments rather than one).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OperationPublicationDeploymentIT {

    private static final String LOCAL_MAP_NAME = "vertique";
    private static final String HTTP_PORT_KEY = "http.port";

    private static Vertx vertx;
    private static WebClient client;

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link WebClient}.
     *
     * @param v   the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = WebClient.create(v, new WebClientOptions().setFollowRedirects(false));
        ctx.completeNow();
    }

    /**
     * Closes the shared {@link WebClient}.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    /** Clears the {@code vertique} local map's {@code http.port} entry after every test. */
    @AfterEach
    void clearHttpPortEntry() {
        vertx.sharedData().getLocalMap(LOCAL_MAP_NAME).remove(HTTP_PORT_KEY);
    }

    // --- TP-001 ---

    @Test
    @DisplayName("Every JAX-RS mount publishes exactly once, naming its application when it has one")
    void everyJaxRsMountPublishesOnce() throws Exception {
        // Given: composition (a), zero declarations.
        JsonObject configA = baseConfig();
        PublicationComponents.ZeroDeclarationEventsComponent componentA = zeroDeclarationEventsComponent(configA);

        // When: composition (a)'s HttpVerticle is deployed.
        String deploymentIdA = deploy(componentA::httpVerticle, new DeploymentOptions());
        try {
            PublicationEventRecordingSink sinkA = componentA.eventRecordingSink();
            PublicationEventRecorder recorderA = componentA.eventRecorder();

            // Then: the default legacy mount publishes its three operations, applicationName and
            // declaringType both null, strategyId the configured "none".
            MountPublication defaultMount = sinkA.onlyReceivedFor("/*");
            assertEquals("jaxrs:/*", defaultMount.mountId());
            assertNull(defaultMount.applicationName());
            assertNull(defaultMount.declaringType());
            assertEquals("none", defaultMount.strategyId());
            assertEquals(
                    List.of("zeroDeclarationCatalog", "zeroDeclarationExtra", "zeroDeclarationManual"),
                    operationIds(defaultMount));
            assertNoDetail(defaultMount);

            // The hand-built /api/mgmt/* mount: also null identity, its own operations, most
            // specific path first.
            MountPublication mgmtMount =
                    sinkA.onlyReceivedFor(PublicationComponents.HandBuiltMountsModule.MGMT_MOUNT_PATH);
            assertEquals("jaxrs:/api/mgmt/*", mgmtMount.mountId());
            assertNull(mgmtMount.applicationName());
            assertNull(mgmtMount.declaringType());
            assertEquals(List.of("echoAgain", "bad", "head", "echo"), operationIds(mgmtMount));
            assertNoDetail(mgmtMount);

            // The empty mount publishes zero operations with the configured strategyId, and null
            // identity like every non-application mount (S6-007).
            MountPublication emptyMount =
                    sinkA.onlyReceivedFor(PublicationComponents.HandBuiltMountsModule.EMPTY_MOUNT_PATH);
            assertEquals("jaxrs:" + PublicationComponents.HandBuiltMountsModule.EMPTY_MOUNT_PATH, emptyMount.mountId());
            assertNull(emptyMount.applicationName());
            assertNull(emptyMount.declaringType());
            assertEquals(List.of(), emptyMount.operations());
            assertEquals("none", emptyMount.strategyId());
            assertNoDetail(emptyMount);

            // The non-JAX-RS mount never publishes.
            assertTrue(sinkA.received().stream().noneMatch(p -> p.mountPath().equals("/plain/*")));

            // Event order: afterRouterCreated -> mountBuilt -> customize for non-empty mounts,
            // mountBuilt -> customize for the empty mount.
            assertEquals(List.of("afterRouterCreated", "mountBuilt", "customize"), recorderA.eventsFor("/*"));
            assertEquals(
                    List.of("afterRouterCreated", "mountBuilt", "customize"),
                    recorderA.eventsFor(PublicationComponents.HandBuiltMountsModule.MGMT_MOUNT_PATH));
            assertEquals(
                    List.of("mountBuilt", "customize"),
                    recorderA.eventsFor(PublicationComponents.HandBuiltMountsModule.EMPTY_MOUNT_PATH));
        } finally {
            undeploy(deploymentIdA);
        }

        // Given: composition (b), T023's ported unitb declarations, both applications active.
        JsonObject configB = baseConfig(
                "unitb.publicApplication.active", true,
                "unitb.managementApplication.active", true);
        PublicationComponents.UnitBApplicationsEventsComponent componentB = unitBApplicationsEventsComponent(configB);

        // When: composition (b)'s HttpVerticle is deployed. Both compositions deploy, so Dagger
        // injected the package-private Factory constructor from a component outside its package
        // (PublicationComponents lives in dev.vertique.rest.jaxrs.application).
        String deploymentIdB = deploy(componentB::httpVerticle, new DeploymentOptions());
        try {
            PublicationEventRecordingSink sinkB = componentB.eventRecordingSink();
            PublicationEventRecorder recorderB = componentB.eventRecorder();

            MountPublication publicMount = sinkB.onlyReceivedFor("/api/public/*");
            assertEquals("jaxrs:/api/public/*", publicMount.mountId());
            assertEquals("public", publicMount.applicationName());
            assertEquals(PublicApi.class, publicMount.declaringType());
            assertEquals(List.of("blobLike", "catalog", "scoped"), operationIds(publicMount));
            assertNoDetail(publicMount);

            MountPublication mgmtAppMount = sinkB.onlyReceivedFor("/api/mgmt/*");
            assertEquals("jaxrs:/api/mgmt/*", mgmtAppMount.mountId());
            assertEquals("mgmt", mgmtAppMount.applicationName());
            assertEquals(ManagementApi.class, mgmtAppMount.declaringType());
            assertEquals(List.of("extra"), operationIds(mgmtAppMount));
            assertNoDetail(mgmtAppMount);

            assertEquals(
                    List.of("afterRouterCreated", "mountBuilt", "customize"), recorderB.eventsFor("/api/public/*"));
            assertEquals(List.of("afterRouterCreated", "mountBuilt", "customize"), recorderB.eventsFor("/api/mgmt/*"));
        } finally {
            undeploy(deploymentIdB);
        }
    }

    // --- TP-006 ---

    @Test
    @DisplayName("A sink exception or failed future fails startup; a failed registration never publishes")
    void sinkExceptionFailsStartup() {
        JsonObject config = baseConfig();

        // E12: rows (a), (b), and (c) are evaluated independently.
        assertAll(
                "TP-006 rows (a), (b), and (c)",
                () -> verifyThrowingSinkRejectsMgmtMount(config),
                () -> verifyDuplicateOperationIdNeverPublishes(config),
                () -> verifyFailingFutureRejectsMgmtMount(config));
    }

    private void verifyThrowingSinkRejectsMgmtMount(JsonObject config) throws Exception {
        // Given: composition (a) with a sink whose mountBuilt throws for /api/mgmt/*.
        RestConfigurationException thrown = new RestConfigurationException("sink rejected /api/mgmt/*");
        PublicationComponents.ThrowingSinkComponent component = throwingSinkComponent(config, thrown);

        // When: the composition is deployed.
        Throwable cause = awaitFailure(deployFuture(component::httpVerticle, new DeploymentOptions()));

        // Then: the deployment fails with that exact exception instance, and no http.port is published.
        assertSame(thrown, cause);
        assertNull(vertx.sharedData().getLocalMap(LOCAL_MAP_NAME).get(HTTP_PORT_KEY));
    }

    private void verifyDuplicateOperationIdNeverPublishes(JsonObject config) throws Exception {
        // Given: the same mounts, a recording sink, and a second /api/mgmt/* resource declaring a
        // duplicate operation id (guard: green at the staged baseline and after implementation —
        // duplicate-operationId detection predates T006).
        PublicationComponents.DuplicateOperationComponent component = duplicateOperationComponent(config);

        // When: the composition is deployed.
        Throwable cause = awaitFailure(deployFuture(component::httpVerticle, new DeploymentOptions()));

        // Then: the deployment fails with the registrar's RouteRegistrationException, and the
        // recording sink holds no publication for /api/mgmt/*.
        assertInstanceOf(RouteRegistrationException.class, cause);
        RecordingSink sink = component.recordingSink();
        assertTrue(sink.received().stream()
                .noneMatch(p -> p.mountPath().equals(PublicationComponents.HandBuiltMountsModule.MGMT_MOUNT_PATH)));
    }

    private void verifyFailingFutureRejectsMgmtMount(JsonObject config) throws Exception {
        // Given: composition (a) with a sink whose mountBuilt returns a failed future for
        // /api/mgmt/*, and TP-001's counting MountCustomizer.
        RestConfigurationException failure = new RestConfigurationException("sink failed /api/mgmt/*");
        PublicationComponents.FailingFutureSinkComponent component = failingFutureSinkComponent(config, failure);

        // When: the composition is deployed.
        Throwable cause = awaitFailure(deployFuture(component::httpVerticle, new DeploymentOptions()));

        // Then: the deployment fails with that exact failure instance, no http.port is published,
        // and the customizer never ran for /api/mgmt/*.
        assertSame(failure, cause);
        assertNull(vertx.sharedData().getLocalMap(LOCAL_MAP_NAME).get(HTTP_PORT_KEY));
        MountPathRecordingCustomizer customizer = component.customizerRecorder();
        assertTrue(customizer.customizedMountPaths().stream()
                .noneMatch(p -> p.equals(PublicationComponents.HandBuiltMountsModule.MGMT_MOUNT_PATH)));
    }

    // --- TP-007 ---

    /**
     * The fixed request table TP-007 sends to every build: one success per mount that has
     * operations (the default legacy mount, the hand-built {@code /api/mgmt/*} mount twice, and the
     * non-JAX-RS mount twice), one {@code 400} the mgmt resource itself returns, one {@code 404}
     * against an undeclared path, one {@code 405} against an existing path with an unsupported
     * method, and one {@code HEAD}.
     */
    private static final List<RequestSpec> FIXED_REQUESTS = List.of(
            new RequestSpec("legacy catalog", HttpMethod.GET, "/catalog"),
            new RequestSpec("legacy extra", HttpMethod.GET, "/extra"),
            new RequestSpec("legacy manual", HttpMethod.GET, "/manual"),
            new RequestSpec("mgmt echo", HttpMethod.GET, "/api/mgmt/echo"),
            new RequestSpec("mgmt echo again", HttpMethod.GET, "/api/mgmt/echo/again"),
            new RequestSpec("plain status", HttpMethod.GET, "/plain/status"),
            new RequestSpec("plain health", HttpMethod.GET, "/plain/health"),
            new RequestSpec("mgmt bad (the resource itself returns 400)", HttpMethod.GET, "/api/mgmt/echo/bad"),
            new RequestSpec("undeclared path (404)", HttpMethod.GET, "/this-path-does-not-exist"),
            new RequestSpec("unsupported method on an existing path (405)", HttpMethod.DELETE, "/api/mgmt/echo"),
            new RequestSpec("mgmt head (HEAD)", HttpMethod.HEAD, "/api/mgmt/echo/head"));

    @Test
    @DisplayName("With no sink or no detail, requests and source calls are unchanged (INV-1)")
    void noSinkKeepsRoutingAndSourceCalls() throws Exception {
        JsonObject config = baseConfig("jaxrs.validationStrategy", "recording-strategy");

        // Build 1: the @Multibinds sink set with no contribution (empty).
        PublicationComponents.NoSinkComponent noSinkComponent = noSinkComponent(config);
        String deploymentId1 = deploy(noSinkComponent::httpVerticle, new DeploymentOptions());
        List<ResponseTriple> responses1;
        int calls1;
        try {
            int port = readPort();
            responses1 = sendAll(port);
            CountingSchemaSource source1 = noSinkComponent.countingSchemaSource();
            calls1 = source1.calls();
        } finally {
            undeploy(deploymentId1);
        }
        vertx.sharedData().getLocalMap(LOCAL_MAP_NAME).remove(HTTP_PORT_KEY);

        // Build 2: a recording sink wanting no detail.
        PublicationComponents.SinkWantsNoDetailComponent noDetailComponent = sinkWantsNoDetailComponent(config);
        String deploymentId2 = deploy(noDetailComponent::httpVerticle, new DeploymentOptions());
        List<ResponseTriple> responses2;
        int calls2;
        try {
            int port = readPort();
            responses2 = sendAll(port);
            CountingSchemaSource source2 = noDetailComponent.countingSchemaSource();
            calls2 = source2.calls();
        } finally {
            undeploy(deploymentId2);
        }
        vertx.sharedData().getLocalMap(LOCAL_MAP_NAME).remove(HTTP_PORT_KEY);

        // Build 3: a recording sink wanting detail for every mount.
        PublicationComponents.SinkWantsAllDetailComponent allDetailComponent = sinkWantsAllDetailComponent(config);
        String deploymentId3 = deploy(allDetailComponent::httpVerticle, new DeploymentOptions());
        List<ResponseTriple> responses3;
        int calls3;
        try {
            int port = readPort();
            responses3 = sendAll(port);
            CountingSchemaSource source3 = allDetailComponent.countingSchemaSource();
            calls3 = source3.calls();
        } finally {
            undeploy(deploymentId3);
        }

        // Then: status, Content-Type, and body are identical across the three builds for every
        // request, and the source's call count after startup is identical across the three.
        assertEquals(responses1, responses2, "empty sink set vs no-detail sink must answer identically");
        assertEquals(responses1, responses3, "empty sink set vs all-detail sink must answer identically");
        assertEquals(calls1, calls2, "the schema source's call count must not depend on a no-detail sink");
        assertEquals(calls1, calls3, "the schema source's call count must not depend on an all-detail sink");
    }

    private static List<ResponseTriple> sendAll(int port) throws Exception {
        List<ResponseTriple> triples = new ArrayList<>();
        for (RequestSpec spec : FIXED_REQUESTS) {
            triples.add(send(port, spec));
        }
        return triples;
    }

    private static ResponseTriple send(int port, RequestSpec spec) throws Exception {
        HttpResponse<Buffer> response = await(
                client.request(spec.method(), port, "127.0.0.1", spec.path()).send());
        return new ResponseTriple(response.statusCode(), response.getHeader("Content-Type"), response.bodyAsString());
    }

    /** One row of TP-007's fixed request table. */
    private record RequestSpec(String name, HttpMethod method, String path) {}

    /** One request's observed (status, Content-Type, body) triple. */
    private record ResponseTriple(int status, String contentType, String body) {}

    // --- Component-building helpers ---

    private static PublicationComponents.ZeroDeclarationEventsComponent zeroDeclarationEventsComponent(
            JsonObject config) {
        return DaggerPublicationComponents_ZeroDeclarationEventsComponent.factory()
                .create(config);
    }

    private static PublicationComponents.UnitBApplicationsEventsComponent unitBApplicationsEventsComponent(
            JsonObject config) {
        return DaggerPublicationComponents_UnitBApplicationsEventsComponent.factory()
                .create(config);
    }

    private static PublicationComponents.ThrowingSinkComponent throwingSinkComponent(
            JsonObject config, RestConfigurationException sinkFailure) {
        return DaggerPublicationComponents_ThrowingSinkComponent.factory().create(config, sinkFailure);
    }

    private static PublicationComponents.DuplicateOperationComponent duplicateOperationComponent(JsonObject config) {
        return DaggerPublicationComponents_DuplicateOperationComponent.factory().create(config);
    }

    private static PublicationComponents.FailingFutureSinkComponent failingFutureSinkComponent(
            JsonObject config, RestConfigurationException sinkFailure) {
        return DaggerPublicationComponents_FailingFutureSinkComponent.factory().create(config, sinkFailure);
    }

    private static PublicationComponents.NoSinkComponent noSinkComponent(JsonObject config) {
        return DaggerPublicationComponents_NoSinkComponent.factory().create(config);
    }

    private static PublicationComponents.SinkWantsNoDetailComponent sinkWantsNoDetailComponent(JsonObject config) {
        return DaggerPublicationComponents_SinkWantsNoDetailComponent.factory().create(config);
    }

    private static PublicationComponents.SinkWantsAllDetailComponent sinkWantsAllDetailComponent(JsonObject config) {
        return DaggerPublicationComponents_SinkWantsAllDetailComponent.factory().create(config);
    }

    // --- Assertion helper ---

    private static List<String> operationIds(MountPublication publication) {
        return publication.operations().stream()
                .map(OperationPublication::operationId)
                .toList();
    }

    /**
     * Asserts every operation of {@code publication} carries a {@code null} {@code detail} (S6-007:
     * looped over every operation, rather than checked on one mount only).
     *
     * @param publication the publication to check
     */
    private static void assertNoDetail(MountPublication publication) {
        for (OperationPublication operation : publication.operations()) {
            assertNull(
                    operation.detail(),
                    "operation '" + operation.operationId() + "' of mount '" + publication.mountPath()
                            + "' must have no detail");
        }
    }

    // --- Configuration-literal helper ---

    /**
     * Builds a loopback deployment configuration ({@code http.port=0}, {@code http.host=127.0.0.1},
     * {@code jaxrs.validationStrategy=none}) plus dotted-path key/value overrides.
     *
     * @param dottedKeyValuePairs alternating dotted-path key ({@link String}) and value arguments
     * @return the assembled configuration object
     */
    private static JsonObject baseConfig(Object... dottedKeyValuePairs) {
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

    // --- Deployment helpers ---

    /**
     * Deploys {@code supplier} and returns the resolved deployment id, undeploying nothing itself.
     *
     * @param supplier creates a fresh {@link Verticle} instance
     * @param options  the deployment options
     * @return the deployment id
     * @throws Exception if the deployment fails
     */
    private static String deploy(Supplier<Verticle> supplier, DeploymentOptions options) throws Exception {
        return await(deployFuture(supplier, options));
    }

    /**
     * Undeploys the given deployment id, when non-{@code null}.
     *
     * @param deploymentId the deployment id to undeploy, or {@code null}
     * @throws Exception if the undeploy fails
     */
    private static void undeploy(String deploymentId) throws Exception {
        if (deploymentId != null) {
            await(vertx.undeploy(deploymentId));
        }
    }

    /**
     * Bounds {@code vertx.deployVerticle(supplier, options)} against a synchronously escaping
     * {@link Throwable}, the way a sink exception or a validated-mark refusal can escape
     * {@code HttpVerticle.start} before it ever returns a future.
     *
     * @param supplier creates a fresh {@link Verticle} instance
     * @param options  the deployment options
     * @return the deployment future, failed with the escaping throwable if one occurred
     */
    private static Future<String> deployFuture(Supplier<Verticle> supplier, DeploymentOptions options) {
        try {
            return vertx.deployVerticle(supplier, options);
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Blocks for {@code future}'s successful result, bounded by the class timeout.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the future's result
     * @throws Exception if the future fails
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /**
     * Blocks for {@code future}'s failure, bounded by the class timeout.
     *
     * @param future the future expected to fail
     * @return the future's failure cause
     * @throws Exception       if awaiting itself fails for a reason other than the expected failure
     * @throws AssertionError if {@code future} succeeds instead of failing
     */
    private static Throwable awaitFailure(Future<?> future) throws Exception {
        try {
            Object result = future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            throw new AssertionError("expected the deployment to fail, but it succeeded with: " + result);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /**
     * Reads the published port from the {@code vertique} local map, set by {@link HttpVerticle} once
     * it starts listening.
     *
     * @return the published port
     */
    private static int readPort() {
        Map<String, Object> localMap = vertx.sharedData().getLocalMap(LOCAL_MAP_NAME);
        Integer port = (Integer) localMap.get(HTTP_PORT_KEY);
        assertNotNull(port, "http.port must be published after a successful deployment");
        return port;
    }
}
