// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.document.PublishedDocument;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.ContextRecordingRouterMount;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MarkerRouterMount;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import dev.vertique.rest.openapi.docs.fixture.RecordingPublicationHook;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.BackOfficeApi;
import dev.vertique.rest.openapi.docs.fixture.conformance.shared.OrderBodySwitchingSchemaSource;
import dev.vertique.rest.openapi.docs.fixture.conformance.support.StoreLogCapture;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import dev.vertique.rest.openapi.docs.fixture.support.Cleanup;
import dev.vertique.rest.openapi.docs.fixture.support.Deployments;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests;
import dev.vertique.rest.openapi.docs.fixture.support.DocumentRequests.Answer;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.publication.DocumentStore;
import dev.vertique.rest.validation.WebValidationStrategy;
import io.vertx.core.Context;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Integration proof that one component's compositions share one document per application: each
 * complete document, a public one and a protected one, is assembled and stored once per component
 * and served byte-identically by every composition, whether the compositions start one after the
 * other or race; a later composition whose fingerprint differs from the stored one fails its startup
 * with a message that names where, never what; and a racing composition neither assembles again nor
 * blocks its event loop while the first composition's assembly runs on a worker thread.
 *
 * <p>The sharing scenario runs under two configurations: the one the divergence scenario uses, below,
 * and the shared fixture's configured {@code apidocs} section with the {@code none} validation
 * strategy, whose only documented application is {@value #NAME} at {@value #MOUNT_PATH}. Its store log
 * lines are also counted over every logger of the module's package, by the keyword classification
 * below.
 *
 * <p>The sharing and divergence scenarios use the applications {@code public} at {@code /api/public}
 * ({@code @ApiDocs(policy = PublicDocsPolicy.class)}) and {@code management} at {@code /api/mgmt} ({@code
 * @ApiDocs(policy = ..., securityScheme = "bearerAuth")} whose policy allows the role {@code admin}); their
 * resources have query, header, and path parameters, a JSON request body, inferred and declared
 * responses, and guarded operations. The management document is read with an {@code admin} bearer
 * token. A marker mount placed after every other mount answers any request the documentation mount
 * does not, so every document response is checked to come from the documentation mount. Requests
 * travel over separate connections, and no assertion depends on which instance of a two-instance
 * deployment answered. Their store log lines are classified by {@link StoreLogCapture}.
 *
 * <p>The gated worker-thread scenario uses the shared fixture's documented application {@value
 * #NAME}. Its log lines are captured at {@code DEBUG} on the module's package logger, with each
 * event's thread name, and classified by level and keyword:
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
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class OpenApiDocsMultiInstanceIT {

    // --- Sharing and divergence scenarios: the public and management applications ---

    /** The longest one deployment, request, or undeployment is awaited. */
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** The public application's name. */
    private static final String PUBLIC_NAME = "public";

    /** The public application's mount path, as the store's log lines name it. */
    private static final String PUBLIC_MOUNT = "/api/public/*";

    /** The management application's name. */
    private static final String MANAGEMENT_NAME = "management";

    /** The management application's mount path, as the store's log lines and failures name it. */
    private static final String MANAGEMENT_MOUNT = "/api/mgmt/*";

    /** The public document's JSON form. */
    private static final String PUBLIC_JSON = "/apidocs/public/openapi.json";

    /** The public document's YAML form. */
    private static final String PUBLIC_YAML = "/apidocs/public/openapi.yaml";

    /** The management document's JSON form. */
    private static final String MANAGEMENT_JSON = "/apidocs/management/openapi.json";

    /** The management document's YAML form. */
    private static final String MANAGEMENT_YAML = "/apidocs/management/openapi.yaml";

    /** The operation ids the public document lists. */
    private static final Set<String> PUBLIC_OPERATIONS = Set.of("listEntries", "getEntry");

    /** The operation ids the management document lists. */
    private static final Set<String> MANAGEMENT_OPERATIONS = Set.of("createOrder", "readOrder");

    /** The operation ids the shared fixture's public document lists. */
    private static final Set<String> SHARED_FIXTURE_OPERATIONS =
            Set.of(CatalogResource.LIST_ITEMS, CatalogResource.GET_ITEM, CatalogResource.CREATE_ITEM);

    /** The operation whose request body the switching source varies. */
    private static final String CREATE_ORDER = "createOrder";

    /** The component holding the request body of {@value #CREATE_ORDER}. */
    private static final String CREATE_ORDER_BODY_KEY = "createOrder.request";

    /** The reference from {@value #CREATE_ORDER}'s request body to its component. */
    private static final String CREATE_ORDER_BODY_REF = "#/components/schemas/createOrder.request";

    /** The property names of the order body's first variant. */
    private static final Set<String> FIRST_VARIANT_PROPERTIES = Set.of("item", "quantity");

    /** The property names of the order body's second variant: one more than the first. */
    private static final Set<String> SECOND_VARIANT_PROPERTIES = Set.of("item", "quantity", "giftNote");

    /** The one property only the second variant has: the schema text that varies between compositions. */
    private static final String SECOND_VARIANT_ONLY_PROPERTY = "giftNote";

    /** The compositions the sequential scenario starts: one deployment after the other. */
    private static final int SEQUENTIAL_COMPOSITIONS = 2;

    /** The instances of the single two-instance deployment. */
    private static final int INSTANCES = 2;

    /** The assembly lines expected per application per component: the single flight assembles once. */
    private static final int ASSEMBLY_LINES_PER_APPLICATION = 1;

    /** The "stored" lines expected per application per component: only one composition stores. */
    private static final int STORED_LINES_PER_APPLICATION = 1;

    /** The comparison lines expected per application per component: the other composition compares. */
    private static final int COMPARISON_LINES_PER_APPLICATION = 1;

    /** The prefix every comparison line of the document store starts with, whatever the application. */
    private static final String COMPARISON_PREFIX = "Compared the snapshot of application '";

    /** The requests sent per form of each document on each published port, each on its own connection. */
    private static final int REQUESTS_PER_FORM = 8;

    /** A path no application or documentation route matches, so only the marker mount answers it. */
    private static final String UNMATCHED_PATH = "/no-such-route";

    /** Every lowercase hex SHA-256 rendering, which no failure message may contain. */
    private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");

    // --- Gated worker-thread scenario: the shared fixture's documented application ---

    /** The documentation module's package logger, the parent of every logger the module uses. */
    private static final String DOCS_LOGGER = "dev.vertique.rest.openapi.docs";

    /** The keyword of the {@code INFO} line logged once per stored document. */
    private static final String STORED_KEYWORD = "stored";

    /** The keyword of the {@code DEBUG} line logged once per completed assembly. */
    private static final String ASSEMBLY_KEYWORD = "assembl";

    /** The keyword of the {@code DEBUG} line logged once per fingerprint comparison. */
    private static final String COMPARISON_KEYWORD = "compar";

    /** The documented application of the gated scenario. */
    private static final String NAME = PublicApi.NAME;

    /** The gated scenario's documented application's mount path. */
    private static final String MOUNT_PATH = PublicApi.MOUNT_PATH;

    /** The number of compositions the gated scenario starts. */
    private static final int COMPOSITIONS = 2;

    /** Assembly lines expected per application per component: the single flight assembles once. */
    private static final int EXPECTED_ASSEMBLY_LINES = 1;

    /** "stored" lines expected per application per component: only the assembling composition stores. */
    private static final int EXPECTED_STORED_LINES = 1;

    /** Comparison lines expected: the one composition that did not assemble compares. */
    private static final int EXPECTED_COMPARISON_LINES = 1;

    /** The name of the gated test's one-thread worker pool, shared by the gate and the deployment. */
    private static final String GATED_POOL = "apidocs-single-flight-gated-pool";

    /**
     * The gated test's bounds. Their sum stays below the test's timeout, so the gate is released and
     * the Vert.x instance closed even when a composition deadlocks the event loop.
     */
    private static final Duration GATE_START_BOUND = Duration.ofSeconds(2);

    /** The longest the gated test waits for both compositions to reach the hooks. */
    private static final Duration BOTH_CALLS_BOUND = Duration.ofSeconds(6);

    /** The longest the gated test waits for its deployment, and then for the gate's outcome. */
    private static final Duration GATED_DEPLOY_BOUND = Duration.ofSeconds(6);

    /** The longest the gated test waits for its undeployment, and then for its Vert.x instance to close. */
    private static final Duration GATED_CLOSE_BOUND = Duration.ofSeconds(3);

    private Logger docsLogger;
    private Level previousDocsLevel;
    private CapturingAppender appender;

    /** One document form and the bearer token it is requested with. */
    private record Form(String path, @Nullable String token) {}

    /** One documented application, as the store's log lines name it. */
    private record Application(String name, String mount) {}

    /**
     * One complete document of the sharing scenario: its JSON and YAML forms, whether it is read with
     * the {@code admin} bearer token, and the operation ids it lists.
     */
    private record Document(String json, String yaml, boolean protectedByBearer, Set<String> operations) {}

    /** One built component of the sharing scenario: its verticle supplier and its source's call counts. */
    private record Shared(Supplier<Verticle> verticles, IntSupplier calls, ToIntFunction<String> callsFor) {}

    /**
     * One configuration of the sharing scenario: how a fresh component is built, its documented
     * applications, its documents, and the operations whose schema-source calls are counted.
     */
    private record Sharing(
            Function<Vertx, Shared> component,
            List<Application> applications,
            List<Document> documents,
            List<String> countedOperations) {}

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

    /**
     * The two configurations the sharing scenario runs under, each named after what it configures.
     *
     * @return one named argument per configuration
     */
    static Stream<Arguments> sharingConfigurations() {
        return Stream.of(
                Arguments.of(Named.of(
                        "no apidocs section, web-validation strategy",
                        new Sharing(
                                vertx -> {
                                    OpenApiDocsMultiInstanceTestComponents.CountingSourceComponent component =
                                            DaggerOpenApiDocsMultiInstanceTestComponents_CountingSourceComponent
                                                    .factory()
                                                    .create(vertx, webValidationConfig());
                                    return new Shared(
                                            component::httpVerticle,
                                            () -> component.countingSource().calls(),
                                            operationId ->
                                                    component.countingSource().calls(operationId));
                                },
                                List.of(
                                        new Application(PUBLIC_NAME, PUBLIC_MOUNT),
                                        new Application(MANAGEMENT_NAME, MANAGEMENT_MOUNT)),
                                List.of(
                                        new Document(PUBLIC_JSON, PUBLIC_YAML, false, PUBLIC_OPERATIONS),
                                        new Document(MANAGEMENT_JSON, MANAGEMENT_YAML, true, MANAGEMENT_OPERATIONS)),
                                List.of("listEntries", "getEntry", "createOrder", "readOrder")))),
                Arguments.of(Named.of(
                        "configured apidocs section, none validation strategy",
                        new Sharing(
                                vertx -> {
                                    DocsTestComponents.SharedComponent component =
                                            DaggerDocsTestComponents_SharedComponent.factory()
                                                    .create(DocsConfigs.shared());
                                    return new Shared(
                                            component::httpVerticle,
                                            () -> component.schemaSource().calls(),
                                            operationId ->
                                                    component.schemaSource().calls(operationId));
                                },
                                List.of(new Application(NAME, MOUNT_PATH)),
                                List.of(new Document(PUBLIC_JSON, PUBLIC_YAML, false, SHARED_FIXTURE_OPERATIONS)),
                                List.of(
                                        CatalogResource.LIST_ITEMS,
                                        CatalogResource.GET_ITEM,
                                        CatalogResource.CREATE_ITEM,
                                        ManagementResource.GET_STATUS)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharingConfigurations")
    @DisplayName("Two compositions assemble and store each complete document once and serve identical bytes and"
            + " entity tags")
    void compositionsShareOneDocumentPerApplication(Sharing sharing, Vertx vertx) throws Exception {
        List<String> deployments = new ArrayList<>();
        WebClient client = DocumentRequests.separateConnectionsClient(vertx);
        String adminToken = SharedDeployment.alice(SharedDeployment.jwtAuth(vertx));
        List<Form> forms = new ArrayList<>();
        for (Document document : sharing.documents()) {
            String token = document.protectedByBearer() ? adminToken : null;
            forms.add(new Form(document.json(), token));
            forms.add(new Form(document.yaml(), token));
        }
        try {
            // (i) Given: one component whose schema source counts its calls.
            Shared sequential = sharing.component().apply(vertx);

            // When: its HttpVerticle supplier is deployed twice, one deployment after the other.
            int firstPort;
            int secondPort;
            appender.clear();
            try (StoreLogCapture logs = StoreLogCapture.attach()) {
                firstPort = Deployments.deployAndReadPort(
                        vertx, sequential.verticles(), new DeploymentOptions(), deployments, WAIT);
                secondPort = Deployments.deployAndReadPort(
                        vertx, sequential.verticles(), new DeploymentOptions(), deployments, WAIT);

                // Then: per application, one assembly, one stored document, and one comparison.
                assertAssembledStoredAndComparedOnce(
                        logs, appender.lines(), sharing.applications(), "one deployment after the other");
            }

            // Then: every operation was resolved once per composition, the docs module adding none.
            int sequentialCallsAfterStartup = sequential.calls().getAsInt();
            for (String operationId : sharing.countedOperations()) {
                assertEquals(
                        SEQUENTIAL_COMPOSITIONS,
                        sequential.callsFor().applyAsInt(operationId),
                        () -> "schema-source calls for " + operationId + " over two sequential compositions");
            }

            // Given: the marker mount is live, so a document answered without its header came from the
            // documentation mount.
            Answer unmatched = DocumentRequests.get(client, firstPort, UNMATCHED_PATH, null, WAIT);
            assertEquals(200, unmatched.status(), "the marker mount's status for an unmatched path");
            assertEquals(
                    MarkerRouterMount.LAST,
                    unmatched.headers().get(MarkerRouterMount.HEADER),
                    "the marker mount did not answer an unmatched path");

            // When: each form is requested repeatedly on both ports.
            // Then: the documentation mount answers, and both ports serve the same bytes and one entity
            // tag per form.
            assertNotEquals(firstPort, secondPort);
            for (Form form : forms) {
                Set<String> bodies = new HashSet<>();
                Set<String> etags = new HashSet<>();
                List<byte[]> firstBodyPerPort = new ArrayList<>();
                for (int port : List.of(firstPort, secondPort)) {
                    firstBodyPerPort.add(collect(client, port, form, bodies, etags));
                }
                assertArrayEquals(
                        firstBodyPerPort.get(0),
                        firstBodyPerPort.get(1),
                        () -> form.path() + ": the two ports' bodies differ");
                assertEquals(1, bodies.size(), () -> form.path() + ": distinct bodies over both ports");
                assertEquals(1, etags.size(), () -> form.path() + ": distinct entity tags over both ports: " + etags);
            }
            assertOperations(client, firstPort, sharing.documents(), adminToken);

            // Then: the requests performed no schema-source call.
            assertEquals(
                    sequentialCallsAfterStartup,
                    sequential.calls().getAsInt(),
                    "schema-source calls after the requests, sequential compositions");

            // (ii) Given: a fresh component. When: it is deployed once with two instances.
            Shared twoInstances = sharing.component().apply(vertx);
            int sharedPort;
            appender.clear();
            try (StoreLogCapture logs = StoreLogCapture.attach()) {
                sharedPort = Deployments.deployAndReadPort(
                        vertx,
                        twoInstances.verticles(),
                        new DeploymentOptions().setInstances(INSTANCES),
                        deployments,
                        WAIT);

                // Then: the same counts per application, whatever the interleaving of the two compositions.
                assertAssembledStoredAndComparedOnce(
                        logs, appender.lines(), sharing.applications(), "one deployment of two instances");
            }
            int twoInstanceCallsAfterStartup = twoInstances.calls().getAsInt();
            assertTrue(twoInstanceCallsAfterStartup > 0, "the counting source was not asked at startup");

            // When: each form is requested repeatedly over separate connections.
            // Then: the documentation mount answers with one body and one entity tag per form,
            // whichever instance answered.
            for (Form form : forms) {
                Set<String> bodies = new HashSet<>();
                Set<String> etags = new HashSet<>();
                collect(client, sharedPort, form, bodies, etags);
                assertEquals(1, bodies.size(), () -> form.path() + ": distinct bodies over two instances");
                assertEquals(
                        1, etags.size(), () -> form.path() + ": distinct entity tags over two instances: " + etags);
            }
            assertOperations(client, sharedPort, sharing.documents(), adminToken);

            // Then: the requests performed no schema-source call.
            assertEquals(
                    twoInstanceCallsAfterStartup,
                    twoInstances.calls().getAsInt(),
                    "schema-source calls after the requests, two instances");
        } finally {
            client.close();
            Deployments.undeployAll(vertx, deployments, WAIT);
        }
    }

    @Test
    @DisplayName("A later composition whose fingerprint differs fails its startup naming where, never what")
    void divergentFingerprintFailsStartupWithApplicationAndMountNames(Vertx vertx) throws Exception {
        List<String> deployments = new ArrayList<>();
        WebClient client = DocumentRequests.separateConnectionsClient(vertx);
        String adminToken = SharedDeployment.alice(SharedDeployment.jwtAuth(vertx));
        try {
            // Given: a component whose source describes the order body's first variant on its first
            // resolution and the second variant on every later one.
            OpenApiDocsMultiInstanceTestComponents.FirstVariantComponent component =
                    DaggerOpenApiDocsMultiInstanceTestComponents_FirstVariantComponent.factory()
                            .create(vertx, webValidationConfig());
            OrderBodySwitchingSchemaSource source = component.switchingSource();
            DocumentStore store = component.documentStore();

            int winnerPort;
            Answer winnerJsonBefore;
            Answer winnerYamlBefore;
            Optional<PublishedDocument> storedBefore;
            Throwable comparerFailure;
            try (StoreLogCapture logs = StoreLogCapture.attach()) {
                // (i) When: the winner deploys with one instance; what it stored and serves is recorded.
                winnerPort = Deployments.deployAndReadPort(
                        vertx, component::httpVerticle, new DeploymentOptions(), deployments, WAIT);
                storedBefore = store.lookup(MANAGEMENT_NAME);
                winnerJsonBefore = DocumentRequests.get(client, winnerPort, MANAGEMENT_JSON, adminToken, WAIT);
                winnerYamlBefore = DocumentRequests.get(client, winnerPort, MANAGEMENT_YAML, adminToken, WAIT);

                // Then: it serves the first variant's properties from the documentation mount.
                assertServedByDocs(winnerJsonBefore, "winner: " + MANAGEMENT_JSON);
                assertServedByDocs(winnerYamlBefore, "winner: " + MANAGEMENT_YAML);
                assertEquals(
                        FIRST_VARIANT_PROPERTIES,
                        createOrderBodyProperties(winnerJsonBefore),
                        "winner: the createOrder request component's properties");
                assertEquals(1, source.orderResolutions(), "winner: resolutions of createOrder");

                // (ii) When: the comparer, the same supplier, deploys after the winner completed.
                comparerFailure = Deployments.failureOf(vertx, component::httpVerticle, new DeploymentOptions(), WAIT);

                // Then: the comparer compared its management fingerprint, and only the winner assembled.
                assertEquals(
                        COMPARISON_LINES_PER_APPLICATION,
                        logs.comparisonLines(MANAGEMENT_NAME, MANAGEMENT_MOUNT).size(),
                        () -> "comparer: comparison lines for management: " + logs.allMessages());
                assertEquals(
                        ASSEMBLY_LINES_PER_APPLICATION,
                        logs.assemblyLines(MANAGEMENT_NAME, MANAGEMENT_MOUNT).size(),
                        () -> "winner and comparer: assembly lines for management: " + logs.allMessages());
            }

            // Then: the comparer did render its own, differing, schema: the source was consulted once per
            // composition.
            assertEquals(2, source.orderResolutions(), "comparer: resolutions of createOrder");

            // Then: it fails with the fingerprint comparison's configuration failure naming the application,
            // its declaring interface, its mount, and the differing operation.
            assertNotNull(comparerFailure, "the comparer started although its snapshot differs");
            RestConfigurationException divergence = assertInstanceOf(
                    RestConfigurationException.class,
                    comparerFailure,
                    () -> "comparer: the failure's class: "
                            + comparerFailure.getClass().getName() + ": " + comparerFailure);
            String message = divergence.getMessage();
            assertNotNull(message, "comparer: the failure has no message");
            for (String part : List.of(
                    "application '" + MANAGEMENT_NAME + "'",
                    BackOfficeApi.class.getName(),
                    "at '" + MANAGEMENT_MOUNT + "'",
                    "differs from the document already published for the application",
                    "operation '" + CREATE_ORDER + "' differs")) {
                assertTrue(message.contains(part), () -> "comparer: the message lacks <" + part + ">: " + message);
            }

            // Then: it is not the redaction-data refusal, and it carries neither the varying schema text,
            // nor any schema, nor the source's name, nor a digest.
            assertFalse(
                    message.toLowerCase(Locale.ROOT).contains("manifest"),
                    () -> "comparer: the message is the redaction-data refusal: " + message);
            assertFalse(
                    message.contains(OrderBodySwitchingSchemaSource.class.getSimpleName()),
                    () -> "comparer: the message names the schema source: " + message);
            assertFalse(
                    message.contains(SECOND_VARIANT_ONLY_PROPERTY),
                    () -> "comparer: the message echoes the varying schema property: " + message);
            for (String schemaText : List.of("\"properties\"", "\"type\"", "{")) {
                assertFalse(
                        message.contains(schemaText),
                        () -> "comparer: the message carries schema text " + schemaText + ": " + message);
            }
            assertFalse(HEX_DIGEST.matcher(message).find(), () -> "comparer: the message carries a digest: " + message);

            // Then: the stored entry is unchanged.
            Optional<PublishedDocument> storedAfter = store.lookup(MANAGEMENT_NAME);
            assertTrue(storedBefore.isPresent(), "the winner stored no document");
            assertTrue(storedAfter.isPresent(), "the failed comparer removed the stored document");
            assertArrayEquals(storedBefore.get().json(), storedAfter.get().json(), "the stored JSON bytes changed");
            assertArrayEquals(storedBefore.get().yaml(), storedAfter.get().yaml(), "the stored YAML bytes changed");
            assertEquals(
                    storedBefore.get().fingerprint(), storedAfter.get().fingerprint(), "the stored snapshot changed");

            // Then: the winner still serves the bytes and entity tag it served before, in both forms.
            Answer winnerJsonAfter = DocumentRequests.get(client, winnerPort, MANAGEMENT_JSON, adminToken, WAIT);
            Answer winnerYamlAfter = DocumentRequests.get(client, winnerPort, MANAGEMENT_YAML, adminToken, WAIT);
            assertEquals(200, winnerJsonAfter.status(), "winner after the comparer: JSON status");
            assertEquals(200, winnerYamlAfter.status(), "winner after the comparer: YAML status");
            assertArrayEquals(winnerJsonBefore.body(), winnerJsonAfter.body(), "winner after the comparer: JSON body");
            assertEquals(winnerJsonBefore.etag(), winnerJsonAfter.etag(), "winner after the comparer: JSON entity tag");
            assertArrayEquals(winnerYamlBefore.body(), winnerYamlAfter.body(), "winner after the comparer: YAML body");
            assertEquals(winnerYamlBefore.etag(), winnerYamlAfter.etag(), "winner after the comparer: YAML entity tag");

            // (iii) When: the control, whose source describes the second variant from the start,
            // deploys and its management document is fetched.
            OpenApiDocsMultiInstanceTestComponents.SecondVariantComponent control =
                    DaggerOpenApiDocsMultiInstanceTestComponents_SecondVariantComponent.factory()
                            .create(vertx, webValidationConfig());
            int variantTwoControlPort = Deployments.deployAndReadPort(
                    vertx, control::httpVerticle, new DeploymentOptions(), deployments, WAIT);
            Answer variantTwoControl =
                    DocumentRequests.get(client, variantTwoControlPort, MANAGEMENT_JSON, adminToken, WAIT);

            // Then: it serves the second variant's properties, so that variant alone publishes.
            assertEquals(200, variantTwoControl.status(), "variantTwoControl: status of the management document");
            assertEquals(
                    SECOND_VARIANT_PROPERTIES,
                    createOrderBodyProperties(variantTwoControl),
                    "variantTwoControl: the createOrder request component's properties");
            assertEquals(1, control.switchingSource().orderResolutions(), "variantTwoControl: resolutions");
        } finally {
            client.close();
            Deployments.undeployAll(vertx, deployments, WAIT);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("Racing compositions assemble once on the worker thread, and neither blocks its event loop")
    void racingCompositionsAssembleOnceOnAWorkerThread() throws Exception {
        // Given: a test-owned Vert.x instance with one event loop.
        Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        CountDownLatch gateStarted = new CountDownLatch(1);
        CountDownLatch gateRelease = new CountDownLatch(1);
        AtomicReference<String> gateThread = new AtomicReference<>();
        try (Cleanup cleanup = new Cleanup()) {
            // Teardown, last registered first: the deployment is undone, the gate released, and the
            // instance closed, each attempted and each failure or timeout reported.
            cleanup.await("close the test-owned Vert.x instance", vertx::close, GATED_CLOSE_BOUND);
            cleanup.step("release the gate", gateRelease::countDown);

            // Given: the shared fixture plus the recording hook and the SYSTEM_LAST context recorder.
            DocsTestComponents.RecordingPublicationHookComponent component =
                    DaggerDocsTestComponents_RecordingPublicationHookComponent.factory()
                            .create(DocsConfigs.shared());
            RecordingPublicationHook recordingHook = component.recordingHook();
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
                // reach the hooks while the pool is gated.
                DeploymentOptions options = new DeploymentOptions()
                        .setInstances(COMPOSITIONS)
                        .setWorkerPoolName(GATED_POOL)
                        .setWorkerPoolSize(1);
                deployment = Deployments.deploy(vertx, component::httpVerticle, options);
                bothCallsWhileGated = recordingHook.awaitCalls(MOUNT_PATH, COMPOSITIONS, BOTH_CALLS_BOUND);
                assemblyLinesWhileGated = assemblyLines(appender.lines()).size();
                storedWhileGated = store.lookup(NAME).isPresent();
            } finally {
                // When: the gate is released from this JUnit thread.
                gateRelease.countDown();
            }
            assertTrue(
                    bothCallsWhileGated,
                    () -> "startup did not reach both mountBuilt calls for " + MOUNT_PATH
                            + " while the named pool was gated; recorded calls: " + recordingHook.calls(MOUNT_PATH));

            // When: the deployment completes; then the gate's own outcome is observed.
            String deploymentId = Futures.await(deployment, GATED_DEPLOY_BOUND);
            cleanup.await("undeploy the gated deployment", () -> vertx.undeploy(deploymentId), GATED_CLOSE_BOUND);
            assertTrue(Futures.await(gate, GATED_DEPLOY_BOUND), "the gate was released by its bound, not by the test");
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
        }
    }

    // --- Sharing and divergence helpers ---

    /**
     * Returns the shared deployment's configuration without an {@code apidocs} section, with the
     * {@code web-validation} strategy so the schema source is asked.
     */
    private static JsonObject webValidationConfig() {
        JsonObject config = SharedDeployment.withoutApidocs();
        config.getJsonObject("jaxrs").put("validationStrategy", WebValidationStrategy.ID);
        return config;
    }

    /**
     * Asserts one assembly line, one "stored" line, and one comparison line for each application; one
     * store comparison line per application in all; and, over every logger of the module's package,
     * one {@code DEBUG} line mentioning {@value #COMPARISON_KEYWORD} per application.
     */
    private static void assertAssembledStoredAndComparedOnce(
            StoreLogCapture logs, List<LogLine> packageLines, List<Application> applications, String scenario) {
        for (Application application : applications) {
            String name = application.name();
            String mount = application.mount();
            assertEquals(
                    ASSEMBLY_LINES_PER_APPLICATION,
                    logs.assemblyLines(name, mount).size(),
                    () -> scenario + ": assembly lines for " + name + " at " + mount + ": " + logs.allMessages());
            assertEquals(
                    STORED_LINES_PER_APPLICATION,
                    logs.storedLines(name, mount).size(),
                    () -> scenario + ": stored lines for " + name + " at " + mount + ": " + logs.allMessages());
            assertEquals(
                    COMPARISON_LINES_PER_APPLICATION,
                    logs.comparisonLines(name, mount).size(),
                    () -> scenario + ": comparison lines for " + name + " at " + mount + ": " + logs.allMessages());
        }
        assertEquals(
                applications.size() * COMPARISON_LINES_PER_APPLICATION,
                logs.allMessages().stream()
                        .filter(message -> message.startsWith(COMPARISON_PREFIX))
                        .count(),
                () -> scenario + ": comparison lines over every application: " + logs.allMessages());
        assertEquals(
                applications.size() * COMPARISON_LINES_PER_APPLICATION,
                comparisonLines(packageLines).size(),
                () -> scenario + ": DEBUG lines mentioning " + COMPARISON_KEYWORD + " on any logger of the package: "
                        + packageLines);
    }

    /**
     * Requests one form repeatedly on one port, checks that the documentation mount answered each
     * request, collects each body and entity tag, and returns the first body's bytes.
     */
    private static byte[] collect(WebClient client, int port, Form form, Set<String> bodies, Set<String> etags)
            throws Exception {
        byte[] first = null;
        for (int request = 0; request < REQUESTS_PER_FORM; request++) {
            Answer answer = DocumentRequests.get(client, port, form.path(), form.token(), WAIT);
            assertServedByDocs(answer, form.path() + " on port " + port);
            if (first == null) {
                first = answer.body();
            }
            bodies.add(new String(answer.body(), StandardCharsets.UTF_8));
            etags.add(answer.etag());
        }
        return first;
    }

    /** Asserts that the documentation mount, not the marker mount, answered with a document. */
    private static void assertServedByDocs(Answer answer, String label) {
        assertEquals(200, answer.status(), () -> label + ": status");
        assertNull(
                answer.headers().get(MarkerRouterMount.HEADER),
                () -> label + ": answered by the marker mount, not the documentation mount");
        assertNotNull(answer.etag(), () -> label + ": no entity tag");
        assertTrue(answer.body().length > 0, () -> label + ": empty body");
    }

    /** Asserts that every JSON document served on a port is complete: it lists every operation. */
    private static void assertOperations(WebClient client, int port, List<Document> documents, String adminToken)
            throws Exception {
        for (Document document : documents) {
            String token = document.protectedByBearer() ? adminToken : null;
            assertEquals(
                    document.operations(),
                    operationIds(DocumentRequests.get(client, port, document.json(), token, WAIT)),
                    () -> document.json() + ": the document's operations");
        }
    }

    /** Returns the operation ids of a JSON document. */
    private static Set<String> operationIds(Answer answer) {
        JsonObject paths = document(answer).getJsonObject("paths");
        Set<String> ids = new HashSet<>();
        for (String path : paths.fieldNames()) {
            JsonObject item = paths.getJsonObject(path);
            for (String method : item.fieldNames()) {
                ids.add(item.getJsonObject(method).getString("operationId"));
            }
        }
        return ids;
    }

    /**
     * Returns the property names of {@value #CREATE_ORDER}'s request-body component, after checking
     * that the operation's JSON body references it.
     */
    private static Set<String> createOrderBodyProperties(Answer answer) {
        JsonObject document = document(answer);
        String reference = document.getJsonObject("paths")
                .getJsonObject("/orders")
                .getJsonObject("post")
                .getJsonObject("requestBody")
                .getJsonObject("content")
                .getJsonObject("application/json")
                .getJsonObject("schema")
                .getString("$ref");
        assertEquals(CREATE_ORDER_BODY_REF, reference, "the createOrder body's reference");
        JsonObject component =
                document.getJsonObject("components").getJsonObject("schemas").getJsonObject(CREATE_ORDER_BODY_KEY);
        assertNotNull(component, () -> "no component " + CREATE_ORDER_BODY_KEY + ": " + document.encode());
        return new HashSet<>(component.getJsonObject("properties").fieldNames());
    }

    /** Parses a JSON document answer. */
    private static JsonObject document(Answer answer) {
        assertEquals(200, answer.status(), "status of a JSON document");
        return new JsonObject(Buffer.buffer(answer.body()));
    }

    // --- Gated scenario log helpers ---

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
            }
        }
    }
}
