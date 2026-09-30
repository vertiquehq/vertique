// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ContextRecordingRouterMount;
import dev.vertique.rest.openapi.docs.fixture.CountingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.RecordingPublicationSink;
import dev.vertique.rest.openapi.docs.fixture.StatefulSchemaSource;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

/**
 * Integration proof that one component's compositions share one document: a documented
 * application's document is assembled once per component, whether the compositions start one after
 * the other or race; a later composition whose snapshot differs from the stored one fails its
 * startup with a message that names where, never what; and a racing composition neither assembles
 * again nor blocks its event loop while the first composition's assembly runs on a worker thread.
 *
 * <p>The documentation module's log lines are captured at {@code DEBUG} on the module's package
 * logger. The contract fixes their levels and what they name, not their wording, so this proof
 * classifies them by level and keyword:
 *
 * <ul>
 *   <li>a <em>stored</em> line is an {@code INFO} line containing {@value #STORED_KEYWORD};
 *   <li>a <em>comparison</em> line is a {@code DEBUG} line containing {@value #COMPARISON_KEYWORD}
 *       (case-insensitive);
 *   <li>an <em>assembly</em> line is a {@code DEBUG} line containing {@value #ASSEMBLY_KEYWORD}
 *       (case-insensitive) and not {@value #COMPARISON_KEYWORD}, so the two {@code DEBUG} classes
 *       never overlap.
 * </ul>
 *
 * <p>Each event's thread name is taken from {@link ILoggingEvent#getThreadName()} while the event is
 * appended, on the logging thread; logback resolves it lazily otherwise.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OpenApiDocsMultiInstanceIT {

    /** The documentation module's package logger, the parent of every logger the module uses. */
    private static final String DOCS_LOGGER = "dev.vertique.rest.openapi.docs";

    /** The keyword of the {@code INFO} line logged once per stored document. */
    private static final String STORED_KEYWORD = "stored";

    /** The keyword of the {@code DEBUG} line logged once per completed assembly. */
    private static final String ASSEMBLY_KEYWORD = "assembl";

    /** The keyword of the {@code DEBUG} line logged once per snapshot comparison. */
    private static final String COMPARISON_KEYWORD = "compar";

    /** The documented application. */
    private static final String NAME = PublicApi.NAME;

    /** The documented application's mount path. */
    private static final String MOUNT_PATH = PublicApi.MOUNT_PATH;

    /** The URL of the documented application's JSON form. */
    private static final String JSON_URL = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + NAME + "/openapi.json";

    /** The URL of the documented application's YAML form. */
    private static final String YAML_URL = DocsConfigs.DEFAULT_APIDOCS_PATH + "/" + NAME + "/openapi.yaml";

    /** Every operation of the shared fixture, over both applications. */
    private static final List<String> OPERATION_IDS = List.of(
            CatalogResource.LIST_ITEMS,
            CatalogResource.GET_ITEM,
            CatalogResource.CREATE_ITEM,
            ManagementResource.GET_STATUS);

    /** The number of compositions each scenario starts. */
    private static final int COMPOSITIONS = 2;

    /** Assembly lines expected per application per component: the single flight assembles once. */
    private static final int EXPECTED_ASSEMBLY_LINES = 1;

    /** "stored" lines expected per application per component: only the assembling composition stores. */
    private static final int EXPECTED_STORED_LINES = 1;

    /** Comparison lines expected: the one composition that did not assemble compares. */
    private static final int EXPECTED_COMPARISON_LINES = 1;

    /** Requests of the racing scenario, sent per form over non-keep-alive connections. */
    private static final int REQUESTS_PER_FORM = 8;

    /** Every lowercase hex SHA-256 rendering, which no failure message may contain. */
    private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");

    /** The longest the extension-driven tests wait for one deployment, request, or undeployment. */
    private static final long WAIT_SECONDS = 5;

    /** The name of the gated test's one-thread worker pool, shared by the gate and the deployment. */
    private static final String GATED_POOL = "apidocs-single-flight-gated-pool";

    /**
     * The gated test's bounds. Their sum stays below the class timeout, so the gate is released and
     * the Vert.x instance closed even when a composition deadlocks the event loop.
     */
    private static final Duration GATE_START_BOUND = Duration.ofSeconds(2);

    /** The longest the gated test waits for both compositions to reach the sinks. */
    private static final Duration BOTH_CALLS_BOUND = Duration.ofSeconds(6);

    /** The longest the gated test waits for its deployment, and then for the gate's outcome. */
    private static final Duration GATED_DEPLOY_BOUND = Duration.ofSeconds(6);

    /** The longest the gated test waits for its undeployment, and then for its Vert.x instance to close. */
    private static final Duration GATED_CLOSE_BOUND = Duration.ofSeconds(3);

    private Logger docsLogger;
    private Level previousDocsLevel;
    private CapturingAppender appender;

    /** Sends the document requests of the extension-driven tests; closed before the Vert.x instance. */
    private WebClient client;

    /** Sends the racing scenario's requests, each on its own connection. */
    private WebClient nonKeepAliveClient;

    @BeforeEach
    void captureDocsLogs() {
        docsLogger = (Logger) LoggerFactory.getLogger(DOCS_LOGGER);
        previousDocsLevel = docsLogger.getLevel();
        docsLogger.setLevel(Level.DEBUG);
        appender = new CapturingAppender();
        appender.start();
        docsLogger.addAppender(appender);
    }

    @AfterEach
    void releaseDocsLogs() {
        docsLogger.detachAppender(appender);
        appender.stop();
        docsLogger.setLevel(previousDocsLevel);
    }

    @Test
    @DisplayName("Two compositions of one component share one document assembled and stored once")
    void twoCompositionsShareOneDocument(Vertx vertx) throws Exception {
        List<String> deployments = new ArrayList<>();
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));
        nonKeepAliveClient = WebClient.create(
                vertx, new WebClientOptions().setDefaultHost("127.0.0.1").setKeepAlive(false));
        try {
            // (i) Given: the shared fixture with its deterministic counting source.
            DocsTestComponents.SharedComponent sequential =
                    DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());

            // When: its HttpVerticle supplier is deployed twice, one deployment after the other.
            int firstPort = deployAndReadPort(vertx, sequential::httpVerticle, new DeploymentOptions(), deployments);
            int secondPort = deployAndReadPort(vertx, sequential::httpVerticle, new DeploymentOptions(), deployments);
            List<LogLine> sequentialLines = appender.lines();

            // Then: one assembly, one stored document, and one comparison for the documented mount.
            assertEquals(
                    EXPECTED_ASSEMBLY_LINES,
                    assemblyLines(sequentialLines).size(),
                    () -> "sequential compositions: assembly lines for " + NAME + " at " + MOUNT_PATH + ": "
                            + sequentialLines);
            assertEquals(
                    EXPECTED_STORED_LINES,
                    storedLines(sequentialLines).size(),
                    () -> "sequential compositions: stored lines for " + NAME + ": " + sequentialLines);
            assertEquals(
                    EXPECTED_COMPARISON_LINES,
                    comparisonLines(sequentialLines).size(),
                    () -> "sequential compositions: comparison lines: " + sequentialLines);

            // Then: both ports serve the same bytes and entity tag for each form.
            for (String url : List.of(JSON_URL, YAML_URL)) {
                Served fromFirst = get(client, firstPort, url);
                Served fromSecond = get(client, secondPort, url);
                assertServedByDocs(fromFirst, url);
                assertServedByDocs(fromSecond, url);
                assertArrayEquals(fromFirst.body(), fromSecond.body(), () -> url + ": the two ports' bodies differ");
                assertEquals(fromFirst.etag(), fromSecond.etag(), () -> url + ": the two ports' entity tags differ");
            }

            // Then: the source was called once per operation per composition, the docs module adding none.
            CountingSchemaSource source = sequential.schemaSource();
            for (String operationId : OPERATION_IDS) {
                assertEquals(
                        COMPOSITIONS,
                        source.calls(operationId),
                        () -> "schema-source calls for " + operationId + " over " + COMPOSITIONS + " compositions");
            }

            // (ii) Given: a fresh component. When: deployed once with two instances.
            appender.clear();
            DocsTestComponents.SharedComponent racing =
                    DaggerDocsTestComponents_SharedComponent.factory().create(DocsConfigs.shared());
            int racingPort = deployAndReadPort(
                    vertx, racing::httpVerticle, new DeploymentOptions().setInstances(COMPOSITIONS), deployments);
            List<LogLine> racingLines = appender.lines();

            // Then: the same counts, whatever the interleaving of the two compositions.
            assertEquals(
                    EXPECTED_ASSEMBLY_LINES,
                    assemblyLines(racingLines).size(),
                    () -> "two instances: assembly lines for " + NAME + " at " + MOUNT_PATH + ": " + racingLines);
            assertEquals(
                    EXPECTED_STORED_LINES,
                    storedLines(racingLines).size(),
                    () -> "two instances: stored lines for " + NAME + ": " + racingLines);
            assertEquals(
                    EXPECTED_COMPARISON_LINES,
                    comparisonLines(racingLines).size(),
                    () -> "two instances: comparison lines: " + racingLines);

            // Then: requests over fresh connections return one body and one entity tag per form.
            for (String url : List.of(JSON_URL, YAML_URL)) {
                Set<String> bodies = new HashSet<>();
                Set<String> etags = new HashSet<>();
                for (int request = 0; request < REQUESTS_PER_FORM; request++) {
                    Served served = get(nonKeepAliveClient, racingPort, url);
                    assertServedByDocs(served, url);
                    bodies.add(Buffer.buffer(served.body()).toString());
                    etags.add(served.etag());
                }
                assertEquals(1, bodies.size(), () -> url + ": distinct bodies over " + REQUESTS_PER_FORM + " requests");
                assertEquals(1, etags.size(), () -> url + ": distinct entity tags: " + etags);
            }
        } finally {
            closeClients();
            undeployAll(vertx, deployments);
        }
    }

    @Test
    @DisplayName("A later composition whose snapshot differs fails its startup naming where, never what")
    void divergentSnapshotFailsStartup(Vertx vertx) throws Exception {
        List<String> deployments = new ArrayList<>();
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost("127.0.0.1"));
        try {
            // Given: the shared fixture with a stateful source whose createItem dryRun schema carries
            // the source's call number.
            DocsTestComponents.StatefulSourceComponent component =
                    DaggerDocsTestComponents_StatefulSourceComponent.factory().create(DocsConfigs.shared());
            DocumentStore store = component.documentStore();

            // When: the supplier is deployed once, and what the first composition stored and serves is
            // recorded.
            int firstPort = deployAndReadPort(vertx, component::httpVerticle, new DeploymentOptions(), deployments);
            Optional<PublishedDocument> storedBefore = store.lookup(NAME);
            Served jsonBefore = get(client, firstPort, JSON_URL);
            Served yamlBefore = get(client, firstPort, YAML_URL);

            // When: the supplier is deployed a second time.
            vertx.sharedData().getLocalMap("vertique").remove("http.port");
            Throwable failure = failureOf(deploy(vertx, component::httpVerticle, new DeploymentOptions()), deployments);

            // Then: the second composition's startup fails with a configuration failure that names the
            // application, its declaring interface, its mount, and the differing operation.
            assertNotNull(failure, "the second composition started although its snapshot differs");
            RestConfigurationException divergence = assertInstanceOf(
                    RestConfigurationException.class,
                    failure,
                    () -> "the divergence failure's class: "
                            + failure.getClass().getName() + ": " + failure);
            String message = divergence.getMessage();
            assertNotNull(message, "the divergence failure has no message");
            assertTrue(message.contains(NAME), () -> "the message does not name the application: " + message);
            assertTrue(
                    message.contains(PublicApi.class.getName()),
                    () -> "the message does not name the declaring interface: " + message);
            assertTrue(message.contains(MOUNT_PATH), () -> "the message does not name the mount: " + message);
            assertTrue(
                    message.contains(CatalogResource.CREATE_ITEM),
                    () -> "the message does not name the differing operation: " + message);

            // Then: it carries neither the varying schema text, nor any schema, nor a digest.
            assertFalse(
                    message.contains(StatefulSchemaSource.CALL_MARKER),
                    () -> "the message echoes the varying schema keyword: " + message);
            assertFalse(message.contains("{"), () -> "the message carries schema text: " + message);
            assertFalse(HEX_DIGEST.matcher(message).find(), () -> "the message carries a digest: " + message);

            // Then: the second composition did render its own, differing, schema.
            assertEquals(
                    COMPOSITIONS,
                    component.statefulSchemaSource().calls(CatalogResource.CREATE_ITEM),
                    "the stateful source was not consulted once per composition");

            // Then: only the first composition assembled.
            List<LogLine> lines = appender.lines();
            assertEquals(
                    EXPECTED_ASSEMBLY_LINES,
                    assemblyLines(lines).size(),
                    () -> "assembly lines for " + NAME + " at " + MOUNT_PATH + ": " + lines);

            // Then: the stored entry and the first composition's served bytes are unchanged.
            Optional<PublishedDocument> storedAfter = store.lookup(NAME);
            assertTrue(storedBefore.isPresent(), "the first composition stored no document");
            assertTrue(storedAfter.isPresent(), "the failed composition removed the stored document");
            assertArrayEquals(storedBefore.get().json(), storedAfter.get().json(), "the stored JSON bytes changed");
            assertArrayEquals(storedBefore.get().yaml(), storedAfter.get().yaml(), "the stored YAML bytes changed");
            assertEquals(storedBefore.get().snapshot(), storedAfter.get().snapshot(), "the stored snapshot changed");
            Served jsonAfter = get(client, firstPort, JSON_URL);
            Served yamlAfter = get(client, firstPort, YAML_URL);
            assertServedByDocs(jsonBefore, JSON_URL);
            assertServedByDocs(yamlBefore, YAML_URL);
            assertArrayEquals(jsonBefore.body(), jsonAfter.body(), "the served JSON bytes changed");
            assertEquals(jsonBefore.etag(), jsonAfter.etag(), "the served JSON entity tag changed");
            assertArrayEquals(yamlBefore.body(), yamlAfter.body(), "the served YAML bytes changed");
            assertEquals(yamlBefore.etag(), yamlAfter.etag(), "the served YAML entity tag changed");
        } finally {
            closeClients();
            undeployAll(vertx, deployments);
        }
    }

    @Test
    @DisplayName("Racing compositions assemble once on the worker thread, and neither blocks its event loop")
    void racingCompositionsAssembleOnceOnAWorkerThread() throws Exception {
        // Given: a test-owned Vert.x instance with one event loop.
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        CountDownLatch gateStarted = new CountDownLatch(1);
        CountDownLatch gateRelease = new CountDownLatch(1);
        AtomicReference<String> gateThread = new AtomicReference<>();
        String deploymentId = null;
        try {
            // Given: the shared fixture plus the recording sink and the SYSTEM_LAST context recorder.
            DocsTestComponents.RecordingSinkComponent component =
                    DaggerDocsTestComponents_RecordingSinkComponent.factory().create(DocsConfigs.shared());
            RecordingPublicationSink recordingSink = component.recordingSink();
            ContextRecordingRouterMount contextRecorder = component.contextRecordingMount();
            DocumentStore store = component.documentStore();

            // Given: the gate occupies the named pool's only thread, submitted from this JUnit thread.
            WorkerExecutor gateExecutor = vertx.createSharedWorkerExecutor(GATED_POOL, 1);
            Future<Boolean> gate = gateExecutor.executeBlocking(() -> {
                gateThread.set(Thread.currentThread().getName());
                gateStarted.countDown();
                return gateRelease.await(
                        GATE_START_BOUND
                                .plus(BOTH_CALLS_BOUND)
                                .plus(GATED_DEPLOY_BOUND)
                                .toMillis(),
                        TimeUnit.MILLISECONDS);
            });
            assertTrue(
                    gateStarted.await(GATE_START_BOUND.toMillis(), TimeUnit.MILLISECONDS),
                    "the gate task never started on the named pool");

            boolean bothCallsWhileGated;
            int assemblyLinesWhileGated;
            boolean storedWhileGated;
            Future<String> deployment;
            try {
                // When: two instances deploy with the named one-thread pool, and both compositions
                // reach the sinks while the pool is gated.
                DeploymentOptions options = new DeploymentOptions()
                        .setInstances(COMPOSITIONS)
                        .setWorkerPoolName(GATED_POOL)
                        .setWorkerPoolSize(1);
                deployment = deploy(vertx, component::httpVerticle, options);
                bothCallsWhileGated = recordingSink.awaitCalls(MOUNT_PATH, COMPOSITIONS, BOTH_CALLS_BOUND);
                assemblyLinesWhileGated = assemblyLines(appender.lines()).size();
                storedWhileGated = store.lookup(NAME).isPresent();
            } finally {
                // When: the gate is released from this JUnit thread.
                gateRelease.countDown();
            }
            assertTrue(
                    bothCallsWhileGated,
                    () -> "startup did not reach both mountBuilt calls for " + MOUNT_PATH
                            + " while the named pool was gated; recorded calls: " + recordingSink.calls(MOUNT_PATH));

            // When: the deployment completes; then the gate's own outcome is observed.
            deploymentId = awaitBounded(deployment, GATED_DEPLOY_BOUND);
            assertTrue(awaitBounded(gate, GATED_DEPLOY_BOUND), "the gate was released by its bound, not by the test");
            List<LogLine> lines = appender.lines();
            String poolThread = gateThread.get();

            // Then: exactly one assembly, logged on the pool's only thread.
            List<LogLine> assemblies = assemblyLines(lines);
            assertEquals(
                    EXPECTED_ASSEMBLY_LINES,
                    assemblies.size(),
                    () -> "assembly lines for " + NAME + " at " + MOUNT_PATH + ": " + lines);
            assertEquals(
                    poolThread, assemblies.get(0).thread(), "the assembly did not run on the named pool's only thread");

            // Then: exactly one stored document and one comparison, the comparison on the pool thread too.
            assertEquals(
                    EXPECTED_STORED_LINES, storedLines(lines).size(), () -> "stored lines for " + NAME + ": " + lines);
            List<LogLine> comparisons = comparisonLines(lines);
            assertEquals(EXPECTED_COMPARISON_LINES, comparisons.size(), () -> "comparison lines: " + lines);
            assertEquals(
                    poolThread,
                    comparisons.get(0).thread(),
                    "the waiting composition did not compare its snapshot on the named pool's only thread");

            // Then: each composition reached its last mount on its own context.
            List<Context> contexts = contextRecorder.contexts();
            assertEquals(COMPOSITIONS, contexts.size(), "the SYSTEM_LAST mount's createRouter calls");
            assertNotNull(contexts.get(0), "the first SYSTEM_LAST createRouter ran on no Vert.x context");
            assertNotNull(contexts.get(1), "the second SYSTEM_LAST createRouter ran on no Vert.x context");
            assertNotSame(
                    contexts.get(0),
                    contexts.get(1),
                    "both compositions reached their last mount on one context: one completed the other's");

            // Then: both compositions reached mountBuilt while the assembly was still gated.
            assertEquals(0, assemblyLinesWhileGated, "an assembly completed while the named pool was gated");
            assertFalse(storedWhileGated, "a document was stored while the named pool was gated");
        } finally {
            gateRelease.countDown();
            closeGatedVertx(vertx, deploymentId);
        }
    }

    // --- Deployment helpers ---

    /**
     * Deploys a supplier, records the deployment, and returns the port the deployment published in
     * the {@code vertique} local map.
     */
    private static int deployAndReadPort(
            Vertx vertx, Supplier<Verticle> supplier, DeploymentOptions options, List<String> deployments)
            throws Exception {
        vertx.sharedData().getLocalMap("vertique").remove("http.port");
        deployments.add(awaitBounded(deploy(vertx, supplier, options), Duration.ofSeconds(WAIT_SECONDS)));
        Object port = vertx.sharedData().getLocalMap("vertique").get("http.port");
        assertNotNull(port, "the deployment published no http.port");
        return (Integer) port;
    }

    /** Deploys a supplier; a synchronous throw becomes a failed future. */
    private static Future<String> deploy(Vertx vertx, Supplier<Verticle> supplier, DeploymentOptions options) {
        try {
            return vertx.deployVerticle(supplier, options);
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Waits for a deployment expected to fail and returns its failure, or {@code null} when it
     * succeeded, in which case the deployment is recorded for undeployment.
     */
    private static Throwable failureOf(Future<String> deployment, List<String> deployments) throws Exception {
        try {
            deployments.add(deployment.toCompletionStage().toCompletableFuture().get(WAIT_SECONDS, TimeUnit.SECONDS));
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    /** Waits for a future within a bound; a failure or a timeout fails the test with its cause. */
    private static <T> T awaitBounded(Future<T> future, Duration bound) throws Exception {
        try {
            return future.toCompletionStage().toCompletableFuture().get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError("the future failed: " + failed.getCause(), failed.getCause());
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the future did not complete within " + bound, incomplete);
        }
    }

    private void closeClients() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (nonKeepAliveClient != null) {
            nonKeepAliveClient.close();
            nonKeepAliveClient = null;
        }
    }

    private static void undeployAll(Vertx vertx, List<String> deployments) throws Exception {
        for (String deploymentId : deployments) {
            awaitBounded(vertx.undeploy(deploymentId), Duration.ofSeconds(WAIT_SECONDS));
        }
    }

    /**
     * Undeploys the gated deployment and closes the test-owned Vert.x instance, each within a bound,
     * so a deadlocked event loop still ends the run.
     */
    private static void closeGatedVertx(Vertx vertx, String deploymentId) {
        try {
            if (deploymentId != null) {
                vertx.undeploy(deploymentId)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(GATED_CLOSE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception undeployFailed) {
            // The close below still runs; the test's own assertions report the failure.
        }
        try {
            vertx.close()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(GATED_CLOSE_BOUND.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException notClosed) {
            // A deadlocked event loop cannot close; the fork's exit bound ends it.
        }
    }

    // --- Request helpers ---

    /** What a document request returned. */
    private record Served(int status, String marker, String contentType, String etag, byte[] body) {}

    private static Served get(WebClient webClient, int port, String url) throws Exception {
        HttpResponse<Buffer> response =
                awaitBounded(webClient.get(port, "127.0.0.1", url).send(), Duration.ofSeconds(WAIT_SECONDS));
        Buffer body = response.body();
        return new Served(
                response.statusCode(),
                response.getHeader(MarkerRouterMount.HEADER),
                response.getHeader("Content-Type"),
                response.getHeader("ETag"),
                body == null ? new byte[0] : body.getBytes());
    }

    /** Asserts that the documentation mount, not the marker mount, answered with a document. */
    private static void assertServedByDocs(Served served, String url) {
        assertEquals(200, served.status(), () -> url + ": status");
        assertNull(served.marker(), () -> url + ": answered by the marker mount, not the documentation mount");
        assertNotNull(served.etag(), () -> url + ": no entity tag");
        assertTrue(served.body().length > 0, () -> url + ": empty body");
    }

    // --- Log helpers ---

    /** One captured log event: its level, formatted message, and the thread that logged it. */
    private record LogLine(Level level, String message, String thread) {

        boolean namesPublicMount() {
            return message.contains(NAME) && message.contains(MOUNT_PATH);
        }

        boolean mentions(String keyword) {
            return message.toLowerCase(Locale.ROOT).contains(keyword);
        }
    }

    private static List<LogLine> assemblyLines(List<LogLine> lines) {
        return lines.stream()
                .filter(line -> line.level() == Level.DEBUG)
                .filter(line -> line.mentions(ASSEMBLY_KEYWORD) && !line.mentions(COMPARISON_KEYWORD))
                .filter(LogLine::namesPublicMount)
                .toList();
    }

    private static List<LogLine> storedLines(List<LogLine> lines) {
        return lines.stream()
                .filter(line -> line.level() == Level.INFO)
                .filter(line -> line.message().contains(STORED_KEYWORD))
                .filter(LogLine::namesPublicMount)
                .toList();
    }

    private static List<LogLine> comparisonLines(List<LogLine> lines) {
        return lines.stream()
                .filter(line -> line.level() == Level.DEBUG)
                .filter(line -> line.mentions(COMPARISON_KEYWORD))
                .toList();
    }

    /**
     * A list appender that resolves each event's thread name and message while the event is appended,
     * on the logging thread, and hands out copies of what it captured.
     */
    private static final class CapturingAppender extends ListAppender<ILoggingEvent> {

        private final List<LogLine> captured = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            captured.add(new LogLine(event.getLevel(), event.getFormattedMessage(), event.getThreadName()));
            super.append(event);
        }

        /** Returns a copy of every line captured so far. */
        List<LogLine> lines() {
            synchronized (this) {
                return List.copyOf(captured);
            }
        }

        /** Forgets every line captured so far. */
        void clear() {
            synchronized (this) {
                captured.clear();
                list.clear();
            }
        }
    }
}
