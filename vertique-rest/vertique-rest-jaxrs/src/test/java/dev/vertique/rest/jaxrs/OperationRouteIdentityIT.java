// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves, through a real {@link HttpVerticle}, that a request which matches a JAX-RS operation
 * route produces a {@link RestRequestCompletedEvent} carrying that route's operation id and route
 * template, whatever the outcome, and that two requests sharing one keep-alive connection each
 * carry their own operation.
 *
 * <ul>
 *   <li>{@link #matchedOperationCarriesItsIdentityOnEveryOutcome(Case)} (TP-001): one
 *       {@code POST /identity/items} per outcome, 200, 401, 403, 415 and 400. The rejections come
 *       from, in route order, the authentication handler, the {@code @Consumes} 415 gate, the
 *       validation gate and an authorization stand-in contributor. Each event must carry the
 *       {@code createItem} descriptor's identity, so it must be recorded before authentication.</li>
 *   <li>{@link #keepAliveRequestsEachCarryTheirOwnOperation()} (TP-002): {@code GET
 *       /identity/catalog} and then {@code POST /identity/items} on one pinned keep-alive
 *       connection. Each event carries its own operation, so no identity leaks across requests on
 *       the same connection.</li>
 * </ul>
 *
 * <p><strong>The server.</strong> Every test deploys a fresh {@link HttpVerticle}, built with its
 * public five-argument constructor and bound to {@code 127.0.0.1} on port 0. The port is read back
 * from the {@code http.port} shared-data entry, as {@code JaxRsApplicationMountConflictIT} does. Its
 * ROOT middlewares are the real {@link RequestContextLifecycle}, a real
 * {@link RestRequestCompletionEmitter} whose listener appends to {@link #EVENTS}, the
 * {@link CaseBarrier} and the {@link ConnectionRecorder}. Its one mount is a
 * {@link JaxRsRouterMount} at {@code /*}, built with {@link TestFactories#builder()}, serving
 * {@link IdentityResource}. The mount installs {@link CredentialScheme}, selects
 * {@link InvalidHeaderGate} as its validation strategy, and registers {@link DenyingAuthorization}
 * at priority 100. At router build, {@code DenyingAuthorization} also records each operation's
 * descriptor, the same instance the registrar hands to every contributor. The expectations are read
 * from those recorded descriptors, never restated as literals.
 *
 * <p><strong>Waiting for emission.</strong> Each request carries an {@value #CASE_HEADER} header.
 * {@link CaseBarrier} registers an {@code afterClose} task for that case on the request's lifecycle
 * handle. The lifecycle registers its end handler first, so under Vert.x Web's reverse end-handler
 * order it fires last, after the emitter's. The barrier therefore completes only after the
 * request's event was dispatched, with no settle window. The requests are sequential, so the events
 * appended between a request's send and its barrier are that request's events.
 *
 * <p><strong>Clients.</strong> Both tests use {@link WebClient}, with no raw-client exemption. The
 * shared client lives for the class. TP-002 binds a dedicated client to {@link #keepAliveClient},
 * with keep-alive and an HTTP/1 pool of at most one connection ({@link PoolOptions#setHttp1MaxSize}).
 * Its two requests must therefore share one connection, which {@link ConnectionRecorder} records as
 * a precondition. The class uses the {@link VertxExtension}-injected {@link Vertx} and does not own
 * it, so the fire-and-forget {@link WebClient#close()} is sufficient.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class OperationRouteIdentityIT {

    private static final Logger LOG = LoggerFactory.getLogger(OperationRouteIdentityIT.class);

    // --- Constants ---

    /** Loopback address used for the bind and every client connection. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Bound wait for every asynchronous step; the class-level {@code @Timeout} is the backstop. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** Shared-data map and key under which {@link HttpVerticle} publishes its bound port. */
    private static final String SHARED_DATA_MAP = "vertique";

    private static final String HTTP_PORT_KEY = "http.port";

    /** Request header naming the case, read by {@link CaseBarrier} and {@link ConnectionRecorder}. */
    private static final String CASE_HEADER = "X-Case";

    /** Request header carrying the credential {@link CredentialScheme} checks. */
    private static final String CREDENTIAL_HEADER = "X-Credential";

    /** The only credential value {@link CredentialScheme} accepts. */
    private static final String ACCEPTED_CREDENTIAL = "ok";

    /** Request header whose presence makes {@link DenyingAuthorization} fail the request with 403. */
    private static final String DENY_HEADER = "X-Deny";

    /** Request header whose presence makes {@link InvalidHeaderGate} fail the request with 400. */
    private static final String INVALID_HEADER = "X-Invalid";

    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    /** Security scheme name declared by {@link IdentityResource#createItem(Item)}. */
    private static final String CREDENTIAL_SCHEME = "credential";

    private static final String CREATE_ITEM = "createItem";

    private static final String LIST_CATALOG = "listCatalog";

    private static final String ITEMS_PATH = "/identity/items";

    private static final String CATALOG_PATH = "/identity/catalog";

    /** JSON request body for every {@code POST /identity/items}; a fresh {@link Buffer} per send. */
    private static final String ITEM_JSON = "{\"name\":\"widget\"}";

    /** TP-002's case names: the two requests sent on the keep-alive connection, in order. */
    private static final String CATALOG_CASE = "keep-alive catalog";

    private static final String ITEM_CASE = "keep-alive item";

    // --- Recorded state: filled by the fixtures, reset after every test ---

    /** Every completion event the emitter's listener received, in emission order. */
    private static final List<RestRequestCompletedEvent> EVENTS = new CopyOnWriteArrayList<>();

    /** The descriptor each operation route registered, keyed by operation id, recorded at router build. */
    private static final Map<String, RestOperationDescriptor> RECORDED = new ConcurrentHashMap<>();

    /** Per-case barriers, completed by the request's lifecycle {@code afterClose} task. */
    private static final Map<String, CompletableFuture<Void>> BARRIERS = new ConcurrentHashMap<>();

    /** Per-case {@code System.identityHashCode} of the server-side connection that carried the request. */
    private static final Map<String, Integer> CONNECTIONS = new ConcurrentHashMap<>();

    // --- Class-scoped resources ---

    private static Vertx vertx;
    private static WebClient client;

    // --- Per-test resources ---

    private String deploymentId;
    private int port;

    /** TP-002's dedicated keep-alive client; {@code null} in every other test. */
    private WebClient keepAliveClient;

    /**
     * Captures the class-scoped {@link Vertx} and creates the shared {@link WebClient}, with
     * redirect following off.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClass(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /** Closes the shared {@link WebClient}; its {@code close()} is {@code void}, with nothing to await. */
    @AfterAll
    static void tearDownClass() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    /**
     * Deploys a fresh {@link HttpVerticle} (see the class Javadoc) and reads its bound port back from
     * the {@code http.port} shared-data entry.
     *
     * @throws Exception if the deployment does not complete within the async bound
     */
    @BeforeEach
    void deploy() throws Exception {
        clearPublishedPort();
        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost(LOOPBACK).setPort(0),
                Set.of(),
                rootMiddlewares(),
                Set.of(identityMount()),
                Set.of());
        deploymentId = await(vertx.deployVerticle(verticle));
        Integer published =
                (Integer) vertx.sharedData().getLocalMap(SHARED_DATA_MAP).get(HTTP_PORT_KEY);
        assertNotNull(published, "the deployed HttpVerticle must publish its bound port as http.port");
        port = published;
    }

    /**
     * Closes TP-002's dedicated client first, then undeploys the verticle, clears {@code http.port},
     * and resets the captured events, the recorded descriptors, the barriers and the connection
     * identities. Each step runs even when an earlier one fails.
     *
     * @throws Exception if the undeploy does not complete within the async bound
     */
    @AfterEach
    void tearDown() throws Exception {
        try {
            if (keepAliveClient != null) {
                keepAliveClient.close();
                keepAliveClient = null;
            }
        } finally {
            try {
                if (deploymentId != null) {
                    await(vertx.undeploy(deploymentId));
                    deploymentId = null;
                }
            } finally {
                clearPublishedPort();
                EVENTS.clear();
                RECORDED.clear();
                BARRIERS.clear();
                CONNECTIONS.clear();
            }
        }
    }

    // --- TP-001 ---

    /**
     * TP-001's named cases, one per outcome. Every row posts the same JSON payload; the headers alone
     * select the outcome, so each rejection differs from the 200 row by the one input that triggers
     * it.
     *
     * @return the five cases, in the contract's order
     */
    static Stream<Case> outcomeCases() {
        return Stream.of(
                new Case(
                        "200",
                        request -> request.putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                                .putHeader(CONTENT_TYPE_HEADER, MediaType.APPLICATION_JSON),
                        200),
                new Case("401", request -> request.putHeader(CONTENT_TYPE_HEADER, MediaType.APPLICATION_JSON), 401),
                new Case(
                        "403",
                        request -> request.putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                                .putHeader(CONTENT_TYPE_HEADER, MediaType.APPLICATION_JSON)
                                .putHeader(DENY_HEADER, "true"),
                        403),
                new Case(
                        "415",
                        request -> request.putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                                .putHeader(CONTENT_TYPE_HEADER, MediaType.TEXT_PLAIN),
                        415),
                new Case(
                        "400",
                        request -> request.putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                                .putHeader(CONTENT_TYPE_HEADER, MediaType.APPLICATION_JSON)
                                .putHeader(INVALID_HEADER, "true"),
                        400));
    }

    /**
     * TP-001 (FR-002; the identity half of AC-002.1): whatever the outcome, the one completion event
     * of a request that matched {@code POST /identity/items} carries the {@code createItem}
     * descriptor's operation id and route template. The 401, 415 and 400 rows are rejected before any
     * contributor runs, so an identity recorded only at the contributor stage misses all three; the
     * 401 row's event carries it only when it is recorded ahead of authentication.
     *
     * @param testCase the outcome under test
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("outcomeCases")
    @DisplayName("A request that matched an operation route carries its operation id and route template on every "
            + "outcome")
    void matchedOperationCarriesItsIdentityOnEveryOutcome(Case testCase) throws Exception {
        Exchange exchange = sendAndAwait(testCase);
        RestOperationDescriptor createItem = recordedDescriptor(CREATE_ITEM);

        int status = exchange.response().statusCode();
        assertEquals(
                testCase.expectedStatus(),
                status,
                () -> "case " + testCase + ": unexpected response status; body: "
                        + exchange.response().bodyAsString());

        RestRequestCompletedEvent event = exchange.event();
        assertAll(
                "case " + testCase + ": the completion event",
                () -> assertEquals("POST", event.method(), "method()"),
                () -> assertEquals(status, event.statusCode(), "statusCode() must be the response status"),
                () -> assertEquals(
                        createItem.operationId(),
                        event.operationId(),
                        "operationId() must be the matched createItem descriptor's"),
                () -> assertEquals(
                        createItem.routeTemplate(),
                        event.routeTemplate(),
                        "routeTemplate() must be the matched createItem descriptor's"));
    }

    // --- TP-002 ---

    /**
     * TP-002 (AC-003.2): two requests sent in sequence on one keep-alive connection each carry their
     * own operation's identity, the first {@code listCatalog}'s and the second {@code createItem}'s.
     * That both requests travelled on the same connection is a precondition, checked by
     * {@link #sameConnection(String, String)}.
     *
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @Test
    @DisplayName("Two requests on one keep-alive connection each carry their own operation's identity")
    void keepAliveRequestsEachCarryTheirOwnOperation() throws Exception {
        keepAliveClient = WebClient.create(
                vertx,
                new WebClientOptions()
                        .setProtocolVersion(HttpVersion.HTTP_1_1)
                        .setKeepAlive(true)
                        .setFollowRedirects(false),
                new PoolOptions().setHttp1MaxSize(1));

        Exchange catalog = sendAndAwait(CATALOG_CASE, keepAliveClient.get(port, LOOPBACK, CATALOG_PATH), null);
        Exchange item = sendAndAwait(
                ITEM_CASE,
                keepAliveClient
                        .post(port, LOOPBACK, ITEMS_PATH)
                        .putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                        .putHeader(CONTENT_TYPE_HEADER, MediaType.APPLICATION_JSON),
                Buffer.buffer(ITEM_JSON));

        sameConnection(CATALOG_CASE, ITEM_CASE);
        assertEquals(200, catalog.response().statusCode(), "precondition: GET /identity/catalog must answer 200");
        assertEquals(200, item.response().statusCode(), "precondition: POST /identity/items must answer 200");

        List<RestRequestCompletedEvent> events = List.copyOf(EVENTS);
        assertEquals(2, events.size(), () -> "two events must have been emitted: " + events);
        assertAll(
                "the events must be in request order",
                () -> assertEquals("GET", events.get(0).method(), "first event method()"),
                () -> assertEquals(CATALOG_PATH, events.get(0).path(), "first event path()"),
                () -> assertEquals("POST", events.get(1).method(), "second event method()"),
                () -> assertEquals(ITEMS_PATH, events.get(1).path(), "second event path()"));

        RestOperationDescriptor listCatalog = recordedDescriptor(LIST_CATALOG);
        RestOperationDescriptor createItem = recordedDescriptor(CREATE_ITEM);
        assertAll(
                "each request on the keep-alive connection must carry its own operation",
                () -> assertEquals(
                        listCatalog.operationId(),
                        events.get(0).operationId(),
                        "first event operationId() must be listCatalog's"),
                () -> assertEquals(
                        listCatalog.routeTemplate(),
                        events.get(0).routeTemplate(),
                        "first event routeTemplate() must be listCatalog's"),
                () -> assertEquals(
                        createItem.operationId(),
                        events.get(1).operationId(),
                        "second event operationId() must be createItem's"),
                () -> assertEquals(
                        createItem.routeTemplate(),
                        events.get(1).routeTemplate(),
                        "second event routeTemplate() must be createItem's"));
    }

    // --- Exchange helpers ---

    /**
     * Sends TP-001's {@code POST /identity/items} for {@code testCase} on the shared client, with the
     * case's headers and the JSON payload.
     *
     * @param testCase the case to send
     * @return the response and that request's one completion event
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    private Exchange sendAndAwait(Case testCase) throws Exception {
        HttpRequest<Buffer> request = client.post(port, LOOPBACK, ITEMS_PATH);
        testCase.requestCustomizer().accept(request);
        return sendAndAwait(testCase.name(), request, Buffer.buffer(ITEM_JSON));
    }

    /**
     * Sends {@code request} tagged with {@code caseName}, awaits the aggregated response and then the
     * case's lifecycle barrier, and returns the response with the one event captured for it.
     *
     * <p>The barrier is registered before the send. It completes only after the request's end
     * handlers ran (see the class Javadoc), and the requests are sequential, so the events appended
     * between the send and the barrier are exactly this request's. Exactly one must have been.
     *
     * @param caseName the {@value #CASE_HEADER} value that keys the barrier and the connection record
     * @param request  the request to send, already carrying the case's other headers
     * @param body     the body to send, or {@code null} to send none
     * @return the response and that request's one completion event
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    private Exchange sendAndAwait(String caseName, HttpRequest<Buffer> request, @Nullable Buffer body)
            throws Exception {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        BARRIERS.put(caseName, barrier);
        int before = EVENTS.size();
        request.putHeader(CASE_HEADER, caseName);

        HttpResponse<Buffer> response = await(body != null ? request.sendBuffer(body) : request.send());
        awaitBarrier(caseName, barrier);

        List<RestRequestCompletedEvent> captured = List.copyOf(EVENTS.subList(before, EVENTS.size()));
        assertEquals(
                1,
                captured.size(),
                () -> "case " + caseName + ": exactly one completion event must be captured for the request, but was "
                        + captured);
        RestRequestCompletedEvent event = captured.get(0);
        LOG.info(
                "case {}: response status={}, event method={} status={} operationId={} routeTemplate={}",
                caseName,
                response.statusCode(),
                event.method(),
                event.statusCode(),
                event.operationId(),
                event.routeTemplate());
        return new Exchange(response, event);
    }

    /**
     * Asserts the precondition that the two named requests travelled on one server-side connection,
     * by the {@code System.identityHashCode} of {@code request().connection()} that
     * {@link ConnectionRecorder} recorded for each.
     *
     * @param first  the first request's case name
     * @param second the second request's case name
     */
    private static void sameConnection(String first, String second) {
        Integer firstConnection = CONNECTIONS.get(first);
        Integer secondConnection = CONNECTIONS.get(second);
        LOG.info("connection identities: {}={}, {}={}", first, firstConnection, second, secondConnection);
        assertNotNull(firstConnection, () -> "precondition: no connection recorded for case " + first);
        assertNotNull(secondConnection, () -> "precondition: no connection recorded for case " + second);
        assertEquals(
                firstConnection,
                secondConnection,
                () -> "precondition: both requests must share one keep-alive connection (" + first + "="
                        + firstConnection + ", " + second + "=" + secondConnection + ")");
    }

    /**
     * Returns the descriptor {@link DenyingAuthorization} recorded for {@code operationId} at router
     * build, after checking that it is present and carries a route template, so no identity
     * assertion can pass by comparing {@code null} with {@code null}.
     *
     * @param operationId the operation id to look up
     * @return the recorded descriptor
     */
    private static RestOperationDescriptor recordedDescriptor(String operationId) {
        RestOperationDescriptor descriptor = RECORDED.get(operationId);
        assertNotNull(
                descriptor,
                () -> "precondition: no descriptor recorded for " + operationId + "; recorded: " + RECORDED.keySet());
        assertEquals(operationId, descriptor.operationId(), "precondition: the recorded descriptor's operationId()");
        assertNotNull(descriptor.routeTemplate(), "precondition: the recorded descriptor's routeTemplate()");
        return descriptor;
    }

    /**
     * Waits for a case's lifecycle barrier, failing with a message naming the case instead of an
     * opaque class-level timeout.
     *
     * @param caseName the case whose barrier is awaited
     * @param barrier  the barrier registered for the case
     * @throws Exception if the barrier completes exceptionally or the wait is interrupted
     */
    private static void awaitBarrier(String caseName, CompletableFuture<Void> barrier) throws Exception {
        try {
            barrier.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError(
                    "case " + caseName + ": the lifecycle afterClose barrier did not complete within "
                            + ASYNC_TIMEOUT_SECONDS + "s",
                    timeout);
        }
    }

    // --- Deployment helpers ---

    /**
     * The ROOT middlewares: the lifecycle, the emitter recording into {@link #EVENTS}, the barrier
     * and the connection recorder. {@link HttpVerticle} orders them by phase, then priority.
     *
     * @return the ROOT middleware set
     */
    private static Set<Middleware> rootMiddlewares() {
        RestRequestCompletionEmitter emitter =
                new RestRequestCompletionEmitter(Optional.empty(), new DefaultContextHolder(), Set.of(EVENTS::add));
        return Set.of(
                new RequestContextLifecycle(), emitter, new CaseBarrier(BARRIERS), new ConnectionRecorder(CONNECTIONS));
    }

    /**
     * Builds the one {@link JaxRsRouterMount}, at {@code /*}, serving {@link IdentityResource} with
     * {@link CredentialScheme}, the selected {@link InvalidHeaderGate} and
     * {@link DenyingAuthorization}.
     *
     * @return the mount
     */
    private static JaxRsRouterMount identityMount() {
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(Set.of(new CredentialScheme()))
                .validationStrategies(Set.of(new InvalidHeaderGate()))
                .jaxRsConfig(JaxRsConfig.builder()
                        .validationStrategy(InvalidHeaderGate.ID)
                        .build())
                .operationHandlerContributors(Set.of(new DenyingAuthorization(RECORDED)))
                .build();
        return factory.create("/*", "openapi.json", Set.of(new IdentityResource()));
    }

    /** Clears the published {@code http.port}, so a stale value from an earlier test is never read. */
    private static void clearPublishedPort() {
        vertx.sharedData().getLocalMap(SHARED_DATA_MAP).remove(HTTP_PORT_KEY);
    }

    /**
     * Blocks the JUnit thread for {@code future}'s result, bounded by {@link #ASYNC_TIMEOUT_SECONDS}.
     *
     * @param future the future to await
     * @param <T>    the result type
     * @return the result
     * @throws Exception if the future fails or does not complete in time
     */
    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // --- Test-local types ---

    /**
     * A TP-001 row.
     *
     * @param name              the case name, also sent as {@value #CASE_HEADER}
     * @param requestCustomizer sets the case's headers on the {@code POST /identity/items} request
     * @param expectedStatus    the response status the case must produce
     */
    record Case(String name, Consumer<HttpRequest<Buffer>> requestCustomizer, int expectedStatus) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * One request's observable outcome.
     *
     * @param response the aggregated response
     * @param event    the one completion event captured for the request
     */
    private record Exchange(HttpResponse<Buffer> response, RestRequestCompletedEvent event) {}

    // --- Fixtures ---

    /**
     * The resource under test: a secured, JSON-consuming {@code POST /identity/items} and an
     * unsecured {@code GET /identity/catalog}.
     */
    @Path("/identity")
    public static class IdentityResource {

        /**
         * Creates an item. Secured at method level by {@link CredentialScheme}, and declares
         * {@code @Consumes(APPLICATION_JSON)}, so its route carries an authentication handler and the
         * {@code @Consumes} 415 gate.
         *
         * @param item the JSON body
         * @return the created item's name
         */
        @POST
        @Path("/items")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = CREDENTIAL_SCHEME)
        @Operation(operationId = CREATE_ITEM)
        public String createItem(Item item) {
            return "created=" + item.name;
        }

        /**
         * Lists the catalog. Unsecured.
         *
         * @return a constant body
         */
        @GET
        @Path("/catalog")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = LIST_CATALOG)
        public String listCatalog() {
            return "catalog";
        }
    }

    /** The {@code POST /identity/items} JSON body. */
    public static class Item {
        /** The item name. */
        public String name;
    }

    /**
     * The {@value #CREDENTIAL_SCHEME} security scheme, with the {@code CredentialAuthHandler} shape of
     * {@code AnnotationDrivenRoutingIT}: a real Vert.x {@link SimpleAuthenticationHandler}, so the
     * route's handler is classified {@code AUTHENTICATION}. It authenticates a request carrying
     * {@value #CREDENTIAL_HEADER}{@code : }{@value #ACCEPTED_CREDENTIAL} and fails any other with a
     * 401 {@link HttpException}.
     */
    static final class CredentialScheme implements SecuritySchemeHandler {

        @Override
        public String schemeName() {
            return CREDENTIAL_SCHEME;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            SimpleAuthenticationHandler authentication = SimpleAuthenticationHandler.create();
            authentication.authenticate(routingContext -> {
                if (ACCEPTED_CREDENTIAL.equals(routingContext.request().getHeader(CREDENTIAL_HEADER))) {
                    return Future.succeededFuture(User.create(new JsonObject().put("sub", CREDENTIAL_SCHEME)));
                }
                return Future.failedFuture(new HttpException(401));
            });
            registry.authenticationHandler(authentication);
        }
    }

    /**
     * The selected validation strategy. Its gate fails a request carrying {@value #INVALID_HEADER}
     * with 400 and passes every other.
     */
    static final class InvalidHeaderGate implements RequestValidationStrategy {

        /** The id the mount's {@code JaxRsConfig.validationStrategy} selects. */
        static final String ID = "invalid-header-gate";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.of(ctx -> {
                if (ctx.request().getHeader(INVALID_HEADER) != null) {
                    ctx.fail(400);
                } else {
                    ctx.next();
                }
            });
        }
    }

    /**
     * The authorization stand-in: an {@link OperationHandlerContributor} at priority 100. At router
     * build it records each operation's descriptor, keyed by operation id. At request time it fails
     * a request carrying {@value #DENY_HEADER} with 403 and passes every other.
     */
    static final class DenyingAuthorization implements OperationHandlerContributor {

        /** The authorization band's priority. */
        static final int PRIORITY = 100;

        private final Map<String, RestOperationDescriptor> recorded;

        /**
         * Creates the stand-in.
         *
         * @param recorded receives each operation's descriptor at router build
         */
        DenyingAuthorization(Map<String, RestOperationDescriptor> recorded) {
            this.recorded = recorded;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            recorded.put(context.operation().operationId(), context.operation());
            context.route().addHandler(ctx -> {
                if (ctx.request().getHeader(DENY_HEADER) != null) {
                    ctx.fail(403);
                } else {
                    ctx.next();
                }
            });
        }
    }

    /**
     * ROOT middleware, the {@code barrierHandler} pattern of {@code RestRequestCompletionEmitterTest}
     * keyed by {@value #CASE_HEADER}: for a request whose case has a registered barrier, it registers
     * an {@code afterClose} task on the request's lifecycle handle that completes the barrier. A
     * request without a registered case passes untouched.
     */
    static final class CaseBarrier implements Middleware {

        /** After the lifecycle, which must have created the handle. */
        static final int PRIORITY = RequestContextLifecycle.ORDER + 20;

        private final Map<String, CompletableFuture<Void>> barriers;

        /**
         * Creates the barrier middleware.
         *
         * @param barriers the per-case barriers, registered by the test before each send
         */
        CaseBarrier(Map<String, CompletableFuture<Void>> barriers) {
            this.barriers = barriers;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            String caseName = rc.request().getHeader(CASE_HEADER);
            CompletableFuture<Void> barrier = caseName != null ? barriers.get(caseName) : null;
            if (barrier != null) {
                RequestContextLifecycle.fromRoutingContext(rc).afterClose(() -> barrier.complete(null));
            }
            rc.next();
        }
    }

    /**
     * ROOT middleware recording, for each request with a {@value #CASE_HEADER}, the
     * {@code System.identityHashCode} of the server-side connection that carried it.
     */
    static final class ConnectionRecorder implements Middleware {

        static final int PRIORITY = RequestContextLifecycle.ORDER + 21;

        private final Map<String, Integer> connections;

        /**
         * Creates the recorder.
         *
         * @param connections receives each case's connection identity
         */
        ConnectionRecorder(Map<String, Integer> connections) {
            this.connections = connections;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            String caseName = rc.request().getHeader(CASE_HEADER);
            if (caseName != null) {
                connections.put(caseName, System.identityHashCode(rc.request().connection()));
            }
            rc.next();
        }
    }
}
