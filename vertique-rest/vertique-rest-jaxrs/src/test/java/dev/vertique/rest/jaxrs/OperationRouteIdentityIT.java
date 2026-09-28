// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
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
import io.vertx.core.http.HttpMethod;
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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Iterator;
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
 * Proves, through a real {@link HttpVerticle}, that a request a JAX-RS operation route claimed
 * produces exactly one {@link RestRequestCompletedEvent} whose {@code operation()} is that route's
 * registered descriptor, whatever the outcome; that a request no transport claimed produces exactly
 * one {@link HttpRequestCompletedEvent} instead; and that the emitter's placement keeps both true.
 *
 * <p><strong>The tests.</strong> T002's two tests keep T002's hand-built harness:
 *
 * <ul>
 *   <li>{@link #matchedOperationCarriesItsIdentityOnEveryOutcome(Case)} (T002 TP-001): one
 *       {@code POST /identity/items} per outcome, 200, 401, 403, 415 and 400. The rejections come
 *       from, in route order, the authentication handler, the {@code @Consumes} 415 gate, the
 *       validation gate and an authorization stand-in contributor. Each event must carry the
 *       {@code createItem} descriptor itself, so the identity must be recorded before
 *       authentication.</li>
 *   <li>{@link #keepAliveRequestsEachCarryTheirOwnOperation()} (T002 TP-002): {@code GET
 *       /identity/catalog} and then {@code POST /identity/items} on one pinned keep-alive
 *       connection. Each event carries its own operation, so no identity leaks across requests on
 *       the same connection.</li>
 * </ul>
 *
 * <p>T003's six tests run on the Dagger component harness:
 *
 * <ul>
 *   <li>{@link #claimedRequestEmitsOneRestEventCarryingRegisteredDescriptor(ClaimedRow)} (TP-001):
 *       200, 401, 403, per-route 415 and validation 400 on operation routes each emit one REST event
 *       whose {@code operation()} is {@code assertSame} the descriptor {@link DescriptorCapture}
 *       captured at router build, and no HTTP event. This pins the documented identity guarantee of
 *       {@code operation()}.</li>
 *   <li>{@link #unclaimedRequestEmitsOneHttpEvent(UnclaimedRow)} (TP-002): a ROOT 429, a correlation
 *       REJECT 400, a 404, a 405, a body 413 and an API-scope 415 each emit one HTTP event and no REST
 *       event.</li>
 *   <li>{@link #holderRemovedBeforeMatchLeavesRequestUnclaimedWithOriginalStartTime()} (TP-003).</li>
 *   <li>{@link #listenerReadsOperationIdAndRouteTemplateFromEvent()} (TP-004).</li>
 *   <li>{@link #applicationRootMiddlewareEndingResponseFirstYieldsOneHttpEvent()} (TP-015).</li>
 *   <li>{@link #emitterReadsBindingsBeforeLifecycleCleanup()} (TP-016).</li>
 * </ul>
 *
 * <p><strong>Two harnesses, one per test.</strong> No shared deployment runs before the tests. Each
 * test first deploys exactly one harness, with {@link #deployHandBuilt()} or
 * {@link #deployComponent()}; {@link #deploy(HttpVerticle)} refuses a second one. Either harness binds
 * {@code 127.0.0.1} on port 0, and the port is read back from the {@code http.port} shared-data entry,
 * as {@code JaxRsApplicationMountConflictIT} does.
 *
 * <ul>
 *   <li><strong>Hand-built (T002).</strong> An {@link HttpVerticle} built with its public
 *       five-argument constructor. Its ROOT middlewares are the real {@link RequestContextLifecycle},
 *       a real {@link RestRequestCompletionEmitter} whose listener appends to {@link #EVENTS}, the
 *       {@link CaseBarrier} and the {@link ConnectionRecorder}. Its one mount is a
 *       {@link JaxRsRouterMount} at {@code /*}, built with {@link TestFactories#builder()}, serving
 *       {@link IdentityResource}. The mount installs {@link CredentialScheme}, selects
 *       {@link InvalidHeaderGate} as its validation strategy, and registers {@link DenyingAuthorization}
 *       at priority 100. At router build, {@code DenyingAuthorization} records each operation's
 *       descriptor into {@link #RECORDED}, the same instance the registrar hands to every
 *       contributor.</li>
 *   <li><strong>Component (T003).</strong> The {@link HttpVerticle} of
 *       {@link OperationRouteIdentityComponents.RouteIdentityComponent}: {@link RestModule}, and so the
 *       production ROOT middlewares of {@code RestCoreModule} including the emitter, which
 *       {@code HttpVerticle} sorts itself, plus the component's support module. That module
 *       contributes {@link IdentityResource}, {@link CredentialScheme} (a real Vert.x
 *       {@code AuthenticationHandler}), {@link DescriptorCapture} (into {@link #REGISTERED}),
 *       {@link DenyingAuthorization}, the capturing listeners {@link RestEventCapture},
 *       {@link OperationIdentityReader} and {@link HttpEventCapture}, and four ROOT test middlewares:
 *       the {@link CaseBarrier}, the {@link RootRejecter}, the {@link HolderRemover} and the
 *       {@link ShortCircuit}. Its configuration ({@link #componentConfig()}) sets
 *       {@code http.maxBodySize} to {@value #MAX_BODY_SIZE}, the {@code none} validation strategy and
 *       {@code correlation.ingress.invalidValuePolicy=REJECT}.</li>
 * </ul>
 *
 * <p><strong>Waiting for emission.</strong> Each request carries an {@value #CASE_HEADER} header.
 * {@link CaseBarrier} registers an {@code afterClose} task for that case on the request's lifecycle
 * handle. The lifecycle registers its end handler first, so under Vert.x Web's reverse end-handler
 * order it fires last, after the emitter's. The barrier therefore completes only after the
 * request's event was dispatched, with no settle window, and every absence ("no REST event", "no
 * HTTP event") is asserted after it. In the component harness the barrier sorts before correlation
 * ingress, so a correlation REJECT still registers it; {@link ShortCircuit}, which ends the response
 * before the barrier runs, registers the barrier itself. The requests are sequential and the captures
 * are reset before every test, so the events captured when a test's barrier fires are that test's.
 *
 * <p><strong>Clients.</strong> Every test uses {@link WebClient}, with no raw-client exemption: one
 * client per test, created in {@link #setUp()} and closed in {@link #tearDown()}. T002 TP-002 binds
 * a dedicated client to {@link #keepAliveClient}, with keep-alive and an HTTP/1 pool of at most one
 * connection ({@link PoolOptions#setHttp1MaxSize}). Its two requests must therefore share one
 * connection, which {@link ConnectionRecorder} records as a precondition. The class uses the
 * {@link VertxExtension}-injected {@link Vertx} and does not own it, so the fire-and-forget
 * {@link WebClient#close()} is sufficient.
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

    /**
     * Request header whose presence makes {@link DenyingAuthorization} fail the request with 403. With
     * the {@link #ACCEPTED_CREDENTIAL}, it is the fixture's denied credential: authenticated, then
     * denied.
     */
    private static final String DENY_HEADER = "X-Deny";

    /** Request header whose presence makes {@link InvalidHeaderGate} fail the request with 400. */
    private static final String INVALID_HEADER = "X-Invalid";

    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    /** Security scheme name declared by {@link IdentityResource}'s secured operations. */
    private static final String CREDENTIAL_SCHEME = "credential";

    private static final String CREATE_ITEM = "createItem";

    private static final String LIST_CATALOG = "listCatalog";

    private static final String GET_USER = "getUser";

    private static final String GET_SECURED = "getSecured";

    private static final String ITEMS_PATH = "/identity/items";

    private static final String CATALOG_PATH = "/identity/catalog";

    /** {@link IdentityResource#getUser(String, int)}'s route template, with its placeholder. */
    private static final String USER_ROUTE_TEMPLATE = "/identity/users/{id}";

    /** A concrete path {@link IdentityResource#getUser(String, int)}'s route matches. */
    private static final String USER_PATH = "/identity/users/7";

    private static final String SECURED_PATH = "/identity/secured";

    /** A path no route matches. */
    private static final String UNMATCHED_PATH = "/nope";

    /** {@link IdentityResource#getUser(String, int)}'s {@code int} query parameter. */
    private static final String LIMIT_PARAM = "limit";

    /** A {@value #LIMIT_PARAM} value that does not convert to {@code int}. */
    private static final String UNCONVERTIBLE_INT = "not-a-number";

    /** JSON request body for every {@code POST /identity/items}; a fresh {@link Buffer} per send. */
    private static final String ITEM_JSON = "{\"name\":\"widget\"}";

    /** The component harness's {@code http.maxBodySize}, in bytes. */
    private static final int MAX_BODY_SIZE = 1024;

    /** A JSON body four times larger than {@value #MAX_BODY_SIZE} bytes, for TP-002's 413 row. */
    private static final String OVERSIZED_ITEM_JSON = "{\"name\":\"" + "x".repeat(4 * MAX_BODY_SIZE) + "\"}";

    /** A media type outside the API scope's accepted set, for TP-002's API-scope 415 row. */
    private static final String IMAGE_PNG = "image/png";

    /** A small non-empty body sent as {@value #IMAGE_PNG}. */
    private static final String PNG_BODY = "PNG-bytes";

    /** The request-id header correlation ingress reads and, by default, echoes on the response. */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** An {@value #REQUEST_ID_HEADER} value outside the header allow-list: spaces trigger REJECT. */
    private static final String INVALID_REQUEST_ID = "bad value with spaces";

    /** Request header that makes {@link RootRejecter} fail the request with 429. */
    private static final String ROOT_REJECT_HEADER = "X-Root-Reject";

    /** Request header that makes {@link HolderRemover} remove the request's completion-state holder. */
    private static final String REMOVE_HOLDER_HEADER = "X-Remove-Holder";

    /** Request header that makes {@link ShortCircuit} end the response without calling {@code next()}. */
    private static final String SHORT_CIRCUIT_HEADER = "X-Short-Circuit";

    /** The status {@link ShortCircuit} ends the response with. */
    private static final int SHORT_CIRCUIT_STATUS = 503;

    /** The delay after which {@link HolderRemover} calls {@code next()}. */
    private static final long HOLDER_REMOVAL_DELAY_MS = 50;

    /** TP-004's expected identity: the declared operation id, the route template with its placeholder, GET. */
    private static final OperationIdentity GET_USER_IDENTITY =
            new OperationIdentity(GET_USER, USER_ROUTE_TEMPLATE, "GET");

    /** T002 TP-002's case names: the two requests sent on the keep-alive connection, in order. */
    private static final String CATALOG_CASE = "keep-alive catalog";

    private static final String ITEM_CASE = "keep-alive item";

    /** The case names of T003's single-request tests. */
    private static final String HOLDER_REMOVED_CASE = "holder removed";

    private static final String IDENTITY_READ_CASE = "identity read";

    private static final String SHORT_CIRCUIT_CASE = "short circuit";

    private static final String BINDINGS_CASE = "bindings";

    // --- Recorded state, reset before every test ---

    /** The hand-built harness: every completion event its emitter's listener received, in order. */
    private static final List<RestRequestCompletedEvent> EVENTS = new CopyOnWriteArrayList<>();

    /** The hand-built harness: each operation's descriptor, keyed by operation id, recorded at router build. */
    private static final Map<String, RestOperationDescriptor> RECORDED = new ConcurrentHashMap<>();

    /** Per-case barriers, completed by the request's lifecycle {@code afterClose} task (both harnesses). */
    static final Map<String, CompletableFuture<Void>> BARRIERS = new ConcurrentHashMap<>();

    /** Per-case {@code System.identityHashCode} of the server-side connection that carried the request. */
    private static final Map<String, Integer> CONNECTIONS = new ConcurrentHashMap<>();

    /** The component harness: every {@link RestRequestCompletedEvent} {@link RestEventCapture} received. */
    static final List<RestRequestCompletedEvent> REST_EVENTS = new CopyOnWriteArrayList<>();

    /** The component harness: every {@link HttpRequestCompletedEvent} {@link HttpEventCapture} received. */
    static final List<HttpRequestCompletedEvent> HTTP_EVENTS = new CopyOnWriteArrayList<>();

    /** The component harness: each identity {@link OperationIdentityReader} read inside {@code onCompleted}. */
    static final List<OperationIdentity> IDENTITY_READS = new CopyOnWriteArrayList<>();

    /** The component harness: each operation's descriptor, keyed by operation id, captured at router build. */
    static final Map<String, RestOperationDescriptor> REGISTERED = new ConcurrentHashMap<>();

    /** The component harness: what {@link HolderRemover} recorded, keyed by case. */
    static final Map<String, HolderRemover.Removal> HOLDER_REMOVALS = new ConcurrentHashMap<>();

    // --- Class-scoped resources ---

    private static Vertx vertx;

    // --- Per-test resources ---

    /** The test's client; created in {@link #setUp()}, closed in {@link #tearDown()}. */
    private WebClient client;

    private String deploymentId;
    private int port;

    /** T002 TP-002's dedicated keep-alive client; {@code null} in every other test. */
    private WebClient keepAliveClient;

    /**
     * Captures the class-scoped {@link Vertx}.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClass(Vertx injectedVertx) {
        vertx = injectedVertx;
    }

    /**
     * Resets every capture, the recorded descriptors, the barriers and the published port, then
     * creates the test's {@link WebClient}, with redirect following off. No harness is deployed
     * here: each test deploys its own.
     */
    @BeforeEach
    void setUp() {
        clearPublishedPort();
        EVENTS.clear();
        RECORDED.clear();
        BARRIERS.clear();
        CONNECTIONS.clear();
        REST_EVENTS.clear();
        HTTP_EVENTS.clear();
        IDENTITY_READS.clear();
        REGISTERED.clear();
        HOLDER_REMOVALS.clear();
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes T002 TP-002's dedicated client and the test's client, then undeploys the verticle and
     * clears {@code http.port}. Each step runs even when an earlier one fails.
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
                if (client != null) {
                    client.close();
                    client = null;
                }
            } finally {
                try {
                    if (deploymentId != null) {
                        await(vertx.undeploy(deploymentId));
                    }
                } finally {
                    deploymentId = null;
                    clearPublishedPort();
                }
            }
        }
    }

    // --- T002 TP-001 (hand-built harness) ---

    /**
     * T002 TP-001's named cases, one per outcome. Every row posts the same JSON payload; the headers
     * alone select the outcome, so each rejection differs from the 200 row by the one input that
     * triggers it.
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
     * T002 TP-001 (FR-002; the identity half of AC-002.1), on the hand-built harness: whatever the
     * outcome, the one completion event of a request that matched {@code POST /identity/items}
     * carries the {@code createItem} descriptor itself as {@code operation()}. The 401, 415 and 400
     * rows are rejected before any contributor runs, so an identity recorded only at the contributor
     * stage misses all three; the 401 row's event carries it only when it is recorded ahead of
     * authentication.
     *
     * @param testCase the outcome under test
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("outcomeCases")
    @DisplayName("A request that matched an operation route carries its operation descriptor on every outcome")
    void matchedOperationCarriesItsIdentityOnEveryOutcome(Case testCase) throws Exception {
        deployHandBuilt();
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
                () -> assertSame(
                        createItem,
                        event.operation(),
                        () -> "operation() must be the matched createItem descriptor (" + describe(createItem)
                                + "), but was " + describe(event.operation())));
    }

    // --- T002 TP-002 (hand-built harness) ---

    /**
     * T002 TP-002 (AC-003.2), on the hand-built harness: two requests sent in sequence on one
     * keep-alive connection each carry their own operation's descriptor, the first
     * {@code listCatalog}'s and the second {@code createItem}'s. That both requests travelled on the
     * same connection is a precondition, checked by {@link #sameConnection(String, String)}.
     *
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @Test
    @DisplayName("Two requests on one keep-alive connection each carry their own operation's identity")
    void keepAliveRequestsEachCarryTheirOwnOperation() throws Exception {
        deployHandBuilt();
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
        assertEquals(2, events.size(), () -> "two events must have been emitted: " + describeRest(events));
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
                () -> assertSame(
                        listCatalog,
                        events.get(0).operation(),
                        () -> "first event operation() must be listCatalog's descriptor, but was "
                                + describe(events.get(0).operation())),
                () -> assertSame(
                        createItem,
                        events.get(1).operation(),
                        () -> "second event operation() must be createItem's descriptor, but was "
                                + describe(events.get(1).operation())));
    }

    // --- TP-001 (component harness) ---

    /**
     * TP-001's rows.
     *
     * @return every {@link ClaimedRow}, in the contract's order
     */
    static Stream<ClaimedRow> claimedRows() {
        return Stream.of(ClaimedRow.values());
    }

    /**
     * TP-001 (FR-007, FR-021; the identity guarantee of {@code operation()}): a request a JAX-RS
     * operation route claimed, whether it succeeds or is denied on that route, emits exactly one
     * {@link RestRequestCompletedEvent}. Its {@code operation()} is the very descriptor
     * {@link DescriptorCapture} received as {@code OperationRegistrationContext.operation()} at router
     * build, its {@code path()} and {@code statusCode()} are the request's, and no
     * {@link HttpRequestCompletedEvent} exists. This test is the proof of the documented Stable
     * identity guarantee.
     *
     * @param row the request under test
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("claimedRows")
    @DisplayName("A claimed JAX-RS request emits exactly one REST event carrying the registered descriptor")
    void claimedRequestEmitsOneRestEventCarryingRegisteredDescriptor(ClaimedRow row) throws Exception {
        deployComponent();

        HttpResponse<Buffer> response = send(row);

        assertEquals(
                row.expectedStatus,
                response.statusCode(),
                () -> "row " + row + ": response status; body: " + response.bodyAsString());
        List<RestRequestCompletedEvent> restEvents = restEvents();
        List<HttpRequestCompletedEvent> httpEvents = httpEvents();
        assertEquals(
                1,
                restEvents.size(),
                () -> "row " + row + ": exactly one RestRequestCompletedEvent; captured: " + describeRest(restEvents));
        RestRequestCompletedEvent event = restEvents.get(0);
        RestOperationDescriptor registered = registeredDescriptor(row.operationId);
        assertAll(
                "row " + row + ": the REST event",
                () -> assertSame(
                        registered,
                        event.operation(),
                        () -> "operation() must be the descriptor registered for " + row.operationId + " ("
                                + describe(registered) + "), but was " + describe(event.operation())),
                () -> assertEquals(row.path, event.path(), "path()"),
                () -> assertEquals(row.expectedStatus, event.statusCode(), "statusCode()"),
                () -> assertEquals(
                        0,
                        httpEvents.size(),
                        () -> "no HttpRequestCompletedEvent may exist; captured: " + describeHttp(httpEvents)));
    }

    // --- TP-002 (component harness) ---

    /**
     * TP-002's rows.
     *
     * @return every {@link UnclaimedRow}, in the contract's order
     */
    static Stream<UnclaimedRow> unclaimedRows() {
        return Stream.of(UnclaimedRow.values());
    }

    /**
     * TP-002 (FR-008, AC-001.2): a request that a rejection stops before any operation route claimed
     * it emits exactly one {@link HttpRequestCompletedEvent} carrying the request's method, path and
     * status, with {@code startTime() ≤ endTime()} and a {@code null} {@code safeFailureMessage()},
     * and no {@link RestRequestCompletedEvent}. Every row but the 404 and the 405 targets a JAX-RS
     * operation path, so only the rejection prevents the claim.
     *
     * @param row the rejection under test
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unclaimedRows")
    @DisplayName("An unclaimed request emits exactly one HTTP event and no REST event")
    void unclaimedRequestEmitsOneHttpEvent(UnclaimedRow row) throws Exception {
        deployComponent();

        HttpResponse<Buffer> response = send(row);

        assertEquals(
                row.expectedStatus,
                response.statusCode(),
                () -> "row " + row + ": response status; body: " + response.bodyAsString());
        HttpRequestCompletedEvent event = onlyHttpEvent("row " + row);
        assertAll(
                "row " + row + ": the HTTP event",
                () -> assertEquals(row.method.name(), event.method(), "method()"),
                () -> assertEquals(row.path, event.path(), "path()"),
                () -> assertEquals(row.expectedStatus, event.statusCode(), "statusCode()"),
                () -> assertFalse(
                        event.startTime().isAfter(event.endTime()),
                        () -> "startTime() " + event.startTime() + " must not be after endTime() " + event.endTime()),
                () -> assertNull(event.safeFailureMessage(), "safeFailureMessage()"));
    }

    // --- TP-003 (component harness) ---

    /**
     * TP-003 (AC-006.2): a ROOT middleware ordered after the emitter removes the request's
     * completion-state holder, then continues after {@value #HOLDER_REMOVAL_DELAY_MS} ms. The
     * operation route still answers 200, but its identity handler finds no holder, so the request
     * is unclaimed: exactly one {@link HttpRequestCompletedEvent}, no {@link RestRequestCompletedEvent},
     * and the event keeps the start time the emitter's closure holds, from before the removal.
     *
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @Test
    @DisplayName("Removing the holder before a match leaves the request unclaimed with its original start time")
    void holderRemovedBeforeMatchLeavesRequestUnclaimedWithOriginalStartTime() throws Exception {
        deployComponent();

        HolderRemoval removal = sendRemovingHolder();

        assertEquals(200, removal.status(), "the unsecured operation must still answer 200");
        assertEquals(
                1, removal.removedCount(), "exactly one holder entry must have been removed; guards a vacuous pass");
        HttpRequestCompletedEvent event = onlyHttpEvent("holder removed");
        Instant earliestEnd = removal.removedAt().plusMillis(HOLDER_REMOVAL_DELAY_MS);
        assertAll(
                "the start time is unchanged: sentAt=" + removal.sentAt() + ", removedAt=" + removal.removedAt()
                        + ", startTime()=" + event.startTime() + ", endTime()=" + event.endTime(),
                () -> assertFalse(event.startTime().isBefore(removal.sentAt()), "sentAt <= startTime()"),
                () -> assertFalse(event.startTime().isAfter(removal.removedAt()), "startTime() <= removedAt"),
                () -> assertFalse(event.endTime().isBefore(earliestEnd), "endTime() >= removedAt + 50 ms"));
    }

    // --- TP-004 (component harness) ---

    /**
     * TP-004 (FR-007): a listener that reads {@code event.operation().operationId()},
     * {@code .routeTemplate()} and {@code .httpMethod()} inside {@code onCompleted}, as
     * {@link OperationIdentityReader} does, records exactly one identity for a {@code GET} with a
     * concrete path value: the declared operation id, the route template with its placeholder (not
     * the concrete path), and {@code GET}.
     *
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @Test
    @DisplayName("A listener reads the operation id, route template and method from operation()")
    void listenerReadsOperationIdAndRouteTemplateFromEvent() throws Exception {
        deployComponent();

        HttpResponse<Buffer> response = send(IDENTITY_READ_CASE, HttpMethod.GET, USER_PATH, request -> {}, null);

        assertEquals(200, response.statusCode(), "precondition: GET " + USER_PATH + " must answer 200");
        assertEquals(
                List.of(GET_USER_IDENTITY),
                List.copyOf(IDENTITY_READS),
                "exactly one identity, read from operation() inside onCompleted");
    }

    // --- TP-015 (component harness) ---

    /**
     * TP-015 (FR-001, AC-001.2; Codex CX-F-001): {@link ShortCircuit}, an application-phase ROOT
     * middleware at {@code Integer.MIN_VALUE}, ends a marked request with {@value #SHORT_CIRCUIT_STATUS}
     * without calling {@code next()}. The emitter still registered its end handler first, so the
     * request, which no operation route could claim, emits exactly one
     * {@link HttpRequestCompletedEvent} and no {@link RestRequestCompletedEvent}.
     *
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @Test
    @DisplayName("An application ROOT middleware that ends the response first still yields one HTTP event")
    void applicationRootMiddlewareEndingResponseFirstYieldsOneHttpEvent() throws Exception {
        deployComponent();

        HttpResponse<Buffer> response = send(
                SHORT_CIRCUIT_CASE,
                HttpMethod.GET,
                USER_PATH,
                request -> request.putHeader(SHORT_CIRCUIT_HEADER, "true"),
                null);

        assertEquals(SHORT_CIRCUIT_STATUS, response.statusCode(), "the short circuit's status");
        HttpRequestCompletedEvent event = onlyHttpEvent("short circuit");
        assertAll(
                "the HTTP event",
                () -> assertEquals("GET", event.method(), "method()"),
                () -> assertEquals(USER_PATH, event.path(), "path()"),
                () -> assertEquals(SHORT_CIRCUIT_STATUS, event.statusCode(), "statusCode()"),
                () -> assertFalse(
                        event.startTime().isAfter(event.endTime()),
                        () -> "startTime() " + event.startTime() + " must not be after endTime() " + event.endTime()));
    }

    // --- TP-016 (component harness) ---

    /**
     * TP-016 (preservation): the emitter's end handler still fires before the lifecycle's cleanup, so
     * the event carries the request's correlation snapshot, whose request id is the one correlation
     * ingress echoed on the response. Correlation ingress closes its binding through the lifecycle
     * handle, so a present snapshot shows the order.
     *
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @Test
    @DisplayName("The emitter still reads the request's bindings before the lifecycle's cleanup")
    void emitterReadsBindingsBeforeLifecycleCleanup() throws Exception {
        deployComponent();

        HttpResponse<Buffer> response = send(BINDINGS_CASE, HttpMethod.GET, USER_PATH, request -> {}, null);

        assertEquals(200, response.statusCode(), "precondition: GET " + USER_PATH + " must answer 200");
        List<RestRequestCompletedEvent> restEvents = restEvents();
        assertEquals(
                1,
                restEvents.size(),
                () -> "exactly one RestRequestCompletedEvent when the barrier fires; captured: "
                        + describeRest(restEvents));
        CorrelationContextSnapshot correlation = restEvents.get(0).correlationContext();
        assertNotNull(correlation, "correlationContext() must be present when the event is built");
        assertEquals(
                response.getHeader(REQUEST_ID_HEADER),
                correlation.requestId().value(),
                "the snapshot's request id must be the " + REQUEST_ID_HEADER + " the response echoed");
    }

    // --- Exchange helpers: the hand-built harness ---

    /**
     * Sends T002 TP-001's {@code POST /identity/items} for {@code testCase} on the test's client, with
     * the case's headers and the JSON payload.
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
                        + describeRest(captured));
        RestRequestCompletedEvent event = captured.get(0);
        LOG.info(
                "case {}: response status={}, event method={} status={} operation={}",
                caseName,
                response.statusCode(),
                event.method(),
                event.statusCode(),
                describe(event.operation()));
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
     * build in the hand-built harness, after checking that it is present and carries a route
     * template.
     *
     * @param operationId the operation id to look up
     * @return the recorded descriptor
     */
    private static RestOperationDescriptor recordedDescriptor(String operationId) {
        return presentDescriptor(RECORDED, operationId);
    }

    // --- Exchange helpers: the component harness ---

    /**
     * Sends a TP-001 row.
     *
     * @param row the row to send
     * @return the response, after the request's barrier fired
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    private HttpResponse<Buffer> send(ClaimedRow row) throws Exception {
        return send(row.name(), row.method, row.path, row.customizer, row.body);
    }

    /**
     * Sends a TP-002 row.
     *
     * @param row the row to send
     * @return the response, after the request's barrier fired
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    private HttpResponse<Buffer> send(UnclaimedRow row) throws Exception {
        return send(row.name(), row.method, row.path, row.customizer, row.body);
    }

    /**
     * Sends one request tagged with {@code caseName} on the test's client, awaits the aggregated
     * response and then the case's lifecycle barrier, and logs the response status with the events
     * captured so far, the evidence summary.
     *
     * @param caseName   the {@value #CASE_HEADER} value that keys the barrier
     * @param method     the request method
     * @param path       the request path, without a query
     * @param customizer adds the request's headers and query parameters
     * @param body       the body to send, or {@code null} to send none
     * @return the response, after the request's barrier fired
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    private HttpResponse<Buffer> send(
            String caseName,
            HttpMethod method,
            String path,
            Consumer<HttpRequest<Buffer>> customizer,
            @Nullable String body)
            throws Exception {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        BARRIERS.put(caseName, barrier);
        HttpRequest<Buffer> request =
                client.request(method, port, LOOPBACK, path).putHeader(CASE_HEADER, caseName);
        customizer.accept(request);

        HttpResponse<Buffer> response = await(body != null ? request.sendBuffer(Buffer.buffer(body)) : request.send());
        awaitBarrier(caseName, barrier);

        LOG.info(
                "case {}: response status={}, REST events={}, HTTP events={}",
                caseName,
                response.statusCode(),
                describeRest(restEvents()),
                describeHttp(httpEvents()));
        return response;
    }

    /**
     * Sends TP-003's {@code GET} to the unsecured operation with {@value #REMOVE_HOLDER_HEADER}, and
     * returns the status with the instants and count around the holder's removal.
     *
     * @return the status, {@code sentAt}, {@code removedAt} and the removed count
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    private HolderRemoval sendRemovingHolder() throws Exception {
        Instant sentAt = Instant.now();
        HttpResponse<Buffer> response = send(
                HOLDER_REMOVED_CASE,
                HttpMethod.GET,
                USER_PATH,
                request -> request.putHeader(REMOVE_HOLDER_HEADER, "true"),
                null);
        HolderRemover.Removal recorded = HOLDER_REMOVALS.get(HOLDER_REMOVED_CASE);
        assertNotNull(recorded, "precondition: the holder remover must have run for the request");
        LOG.info(
                "holder removal: sentAt={}, removedAt={}, removedCount={}",
                sentAt,
                recorded.removedAt(),
                recorded.removedCount());
        return new HolderRemoval(response.statusCode(), sentAt, recorded.removedAt(), recorded.removedCount());
    }

    /**
     * The component harness's captured REST events.
     *
     * @return a snapshot of {@link #REST_EVENTS}
     */
    private static List<RestRequestCompletedEvent> restEvents() {
        return List.copyOf(REST_EVENTS);
    }

    /**
     * The component harness's captured HTTP events.
     *
     * @return a snapshot of {@link #HTTP_EVENTS}
     */
    private static List<HttpRequestCompletedEvent> httpEvents() {
        return List.copyOf(HTTP_EVENTS);
    }

    /**
     * Asserts that exactly one {@link HttpRequestCompletedEvent} and no {@link RestRequestCompletedEvent}
     * were captured, both counts checked before either list is indexed, and returns the HTTP event.
     *
     * @param label names the request in the failure message
     * @return the one HTTP event
     */
    private static HttpRequestCompletedEvent onlyHttpEvent(String label) {
        List<HttpRequestCompletedEvent> httpEvents = httpEvents();
        List<RestRequestCompletedEvent> restEvents = restEvents();
        assertAll(
                label + ": the captured events",
                () -> assertEquals(
                        1,
                        httpEvents.size(),
                        () -> "exactly one HttpRequestCompletedEvent; captured: " + describeHttp(httpEvents)),
                () -> assertEquals(
                        0,
                        restEvents.size(),
                        () -> "no RestRequestCompletedEvent may exist; captured: " + describeRest(restEvents)));
        return httpEvents.get(0);
    }

    /**
     * Returns the descriptor {@link DescriptorCapture} captured for {@code operationId} at router build
     * in the component harness, after checking that it is present and carries a route template.
     *
     * @param operationId the operation id to look up
     * @return the registered descriptor
     */
    private static RestOperationDescriptor registeredDescriptor(String operationId) {
        return presentDescriptor(REGISTERED, operationId);
    }

    /**
     * Returns {@code descriptors}' entry for {@code operationId}, after checking that it is present
     * and carries that operation id and a route template, so no identity assertion can pass by
     * comparing {@code null} with {@code null}.
     *
     * @param descriptors the descriptors recorded at router build, keyed by operation id
     * @param operationId the operation id to look up
     * @return the descriptor
     */
    private static RestOperationDescriptor presentDescriptor(
            Map<String, RestOperationDescriptor> descriptors, String operationId) {
        RestOperationDescriptor descriptor = descriptors.get(operationId);
        assertNotNull(
                descriptor,
                () -> "precondition: no descriptor recorded for " + operationId + "; recorded: "
                        + descriptors.keySet());
        assertEquals(operationId, descriptor.operationId(), "precondition: the recorded descriptor's operationId()");
        assertNotNull(descriptor.routeTemplate(), "precondition: the recorded descriptor's routeTemplate()");
        return descriptor;
    }

    // --- Diagnostics: null-safe, never calling a descriptor's or an event's toString() ---

    /**
     * Describes a descriptor by its identity strings and identity hash.
     *
     * @param operation the descriptor, or {@code null}
     * @return the description
     */
    private static String describe(@Nullable RestOperationDescriptor operation) {
        if (operation == null) {
            return "null";
        }
        return operation.httpMethod() + " " + operation.routeTemplate() + " (" + operation.operationId() + ")@"
                + Integer.toHexString(System.identityHashCode(operation));
    }

    /**
     * Describes REST events by method, path, status and operation.
     *
     * @param events the events
     * @return the description
     */
    private static String describeRest(List<RestRequestCompletedEvent> events) {
        return events.stream()
                .map(event -> "REST[" + event.method() + " " + event.path() + " " + event.statusCode() + " operation="
                        + describe(event.operation()) + "]")
                .toList()
                .toString();
    }

    /**
     * Describes HTTP events by method, path and status.
     *
     * @param events the events
     * @return the description
     */
    private static String describeHttp(List<HttpRequestCompletedEvent> events) {
        return events.stream()
                .map(event -> "HTTP[" + event.method() + " " + event.path() + " " + event.statusCode() + "]")
                .toList()
                .toString();
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
     * Deploys T002's hand-built harness (see the class Javadoc).
     *
     * @throws Exception if the deployment does not complete within the async bound
     */
    private void deployHandBuilt() throws Exception {
        deploy(new HttpVerticle(
                new HttpServerOptions().setHost(LOOPBACK).setPort(0),
                Set.of(),
                rootMiddlewares(),
                Set.of(identityMount()),
                Set.of()));
    }

    /**
     * Deploys the component harness: the {@link HttpVerticle} of a fresh
     * {@link OperationRouteIdentityComponents.RouteIdentityComponent} built from
     * {@link #componentConfig()}.
     *
     * @throws Exception if the deployment does not complete within the async bound
     */
    private void deployComponent() throws Exception {
        deploy(DaggerOperationRouteIdentityComponents_RouteIdentityComponent.factory()
                .create(componentConfig())
                .httpVerticle());
    }

    /**
     * Deploys {@code verticle}, the test's one harness, and reads its bound port back from the
     * {@code http.port} shared-data entry.
     *
     * @param verticle the harness's verticle
     * @throws Exception if the deployment does not complete within the async bound
     */
    private void deploy(HttpVerticle verticle) throws Exception {
        assertNull(deploymentId, "precondition: a test deploys exactly one harness");
        deploymentId = await(vertx.deployVerticle(verticle));
        Integer published =
                (Integer) vertx.sharedData().getLocalMap(SHARED_DATA_MAP).get(HTTP_PORT_KEY);
        assertNotNull(published, "the deployed HttpVerticle must publish its bound port as http.port");
        port = published;
    }

    /**
     * The component harness's configuration: loopback on port 0 with a {@value #MAX_BODY_SIZE}-byte
     * {@code http.maxBodySize}, the {@code none} validation strategy, and the correlation ingress
     * REJECT policy.
     *
     * @return the configuration
     */
    private static JsonObject componentConfig() {
        return new JsonObject()
                .put(
                        "http",
                        new JsonObject().put("port", 0).put("host", LOOPBACK).put("maxBodySize", MAX_BODY_SIZE))
                .put("jaxrs", new JsonObject().put("validationStrategy", "none"))
                .put(
                        "correlation",
                        new JsonObject().put("ingress", new JsonObject().put("invalidValuePolicy", "REJECT")));
    }

    /**
     * The hand-built harness's ROOT middlewares: the lifecycle, the emitter recording into
     * {@link #EVENTS}, the barrier and the connection recorder. {@link HttpVerticle} orders them by
     * phase, then priority.
     *
     * @return the ROOT middleware set
     */
    private static Set<Middleware> rootMiddlewares() {
        RestRequestCompletionEmitter emitter =
                new RestRequestCompletionEmitter(Optional.empty(), new DefaultContextHolder(), Set.of(EVENTS::add));
        return Set.of(
                new RequestContextLifecycle(),
                emitter,
                new CaseBarrier(BARRIERS, CaseBarrier.PRIORITY),
                new ConnectionRecorder(CONNECTIONS));
    }

    /**
     * Builds the hand-built harness's one {@link JaxRsRouterMount}, at {@code /*}, serving
     * {@link IdentityResource} with {@link CredentialScheme}, the selected {@link InvalidHeaderGate}
     * and {@link DenyingAuthorization}.
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
     * A T002 TP-001 row.
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
     * One request's observable outcome in the hand-built harness.
     *
     * @param response the aggregated response
     * @param event    the one completion event captured for the request
     */
    private record Exchange(HttpResponse<Buffer> response, RestRequestCompletedEvent event) {}

    /**
     * TP-001's rows: each names the request and its expected status. Every row targets a JAX-RS
     * operation route, so the identity handler claims it before any rejection.
     */
    enum ClaimedRow {
        /** (a) The unsecured {@code GET} succeeds. */
        UNSECURED_GET_200(GET_USER, HttpMethod.GET, USER_PATH, request -> {}, null, 200),

        /** (b) The secured {@code GET} without a credential: the authentication handler answers 401. */
        SECURED_GET_WITHOUT_CREDENTIAL_401(GET_SECURED, HttpMethod.GET, SECURED_PATH, request -> {}, null, 401),

        /** (c) The secured {@code GET} with the fixture's denied credential: {@link DenyingAuthorization}'s 403. */
        SECURED_GET_WITH_DENIED_CREDENTIAL_403(
                GET_SECURED,
                HttpMethod.GET,
                SECURED_PATH,
                request -> request.putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                        .putHeader(DENY_HEADER, "true"),
                null,
                403),

        /** (d) {@code text/plain} to the {@code @Consumes("application/json")} operation: the per-route 415. */
        TEXT_PLAIN_TO_JSON_OPERATION_415(
                CREATE_ITEM,
                HttpMethod.POST,
                ITEMS_PATH,
                request -> request.putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                        .putHeader(CONTENT_TYPE_HEADER, MediaType.TEXT_PLAIN),
                ITEM_JSON,
                415),

        /** (e) An unconvertible {@code int} query parameter, rejected by binding after the route matched. */
        UNCONVERTIBLE_INT_QUERY_400(
                GET_USER,
                HttpMethod.GET,
                USER_PATH,
                request -> request.addQueryParam(LIMIT_PARAM, UNCONVERTIBLE_INT),
                null,
                400);

        final String operationId;
        final HttpMethod method;
        final String path;
        final Consumer<HttpRequest<Buffer>> customizer;

        @Nullable
        final String body;

        final int expectedStatus;

        ClaimedRow(
                String operationId,
                HttpMethod method,
                String path,
                Consumer<HttpRequest<Buffer>> customizer,
                @Nullable String body,
                int expectedStatus) {
            this.operationId = operationId;
            this.method = method;
            this.path = path;
            this.customizer = customizer;
            this.body = body;
            this.expectedStatus = expectedStatus;
        }
    }

    /**
     * TP-002's rows, each named after the rejection and stating its request-builder customizer and
     * expected status. Every row but {@link #NO_ROUTE_404} and {@link #METHOD_NOT_ALLOWED_405}
     * targets a JAX-RS operation path, so only the rejection prevents the claim.
     */
    enum UnclaimedRow {
        /** (a) A {@value #ROOT_REJECT_HEADER} header: {@link RootRejecter}, after correlation ingress, fails with 429. */
        ROOT_REJECTED_429(
                HttpMethod.GET, USER_PATH, request -> request.putHeader(ROOT_REJECT_HEADER, "true"), null, 429),

        /** (b) An invalid {@value #REQUEST_ID_HEADER}: correlation ingress's REJECT policy fails with 400. */
        CORRELATION_REJECTED_400(
                HttpMethod.GET,
                USER_PATH,
                request -> request.putHeader(REQUEST_ID_HEADER, INVALID_REQUEST_ID),
                null,
                400),

        /** (c) A path no route matches. */
        NO_ROUTE_404(HttpMethod.GET, UNMATCHED_PATH, request -> {}, null, 404),

        /** (d) {@code DELETE} on the {@code GET}-only operation path. */
        METHOD_NOT_ALLOWED_405(HttpMethod.DELETE, USER_PATH, request -> {}, null, 405),

        /** (e) A JSON body over {@code http.maxBodySize} to the {@code @Consumes} operation: the body handler's 413. */
        BODY_TOO_LARGE_413(
                HttpMethod.POST,
                ITEMS_PATH,
                request -> request.putHeader(CONTENT_TYPE_HEADER, MediaType.APPLICATION_JSON),
                OVERSIZED_ITEM_JSON,
                413),

        /** (f) An {@value #IMAGE_PNG} body to the same path: the API-scope content-type 415. */
        API_SCOPE_UNSUPPORTED_MEDIA_TYPE_415(
                HttpMethod.POST,
                ITEMS_PATH,
                request -> request.putHeader(CONTENT_TYPE_HEADER, IMAGE_PNG),
                PNG_BODY,
                415);

        final HttpMethod method;
        final String path;
        final Consumer<HttpRequest<Buffer>> customizer;

        @Nullable
        final String body;

        final int expectedStatus;

        UnclaimedRow(
                HttpMethod method,
                String path,
                Consumer<HttpRequest<Buffer>> customizer,
                @Nullable String body,
                int expectedStatus) {
            this.method = method;
            this.path = path;
            this.customizer = customizer;
            this.body = body;
            this.expectedStatus = expectedStatus;
        }
    }

    /**
     * An operation's identity as a listener reads it from {@code operation()}.
     *
     * @param operationId   the operation id
     * @param routeTemplate the route template
     * @param httpMethod    the HTTP method
     */
    record OperationIdentity(String operationId, String routeTemplate, String httpMethod) {}

    /**
     * TP-003's observations around the holder's removal.
     *
     * @param status       the response status
     * @param sentAt       taken on the test thread before the request was sent
     * @param removedAt    taken by {@link HolderRemover} before it removed the holder
     * @param removedCount how many holder entries {@link HolderRemover} removed
     */
    private record HolderRemoval(int status, Instant sentAt, Instant removedAt, int removedCount) {}

    // --- Fixtures ---

    /**
     * The resource under test: a secured, JSON-consuming {@code POST /identity/items}, an unsecured
     * {@code GET /identity/catalog}, an unsecured {@code GET /identity/users/{id}} with an {@code int}
     * query parameter, and a secured {@code GET /identity/secured}.
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

        /**
         * Gets a user. Unsecured; its {@code int} query parameter fails binding, with 400, when a
         * value is present but does not convert. When the parameter is absent, its
         * {@code @DefaultValue("0")} binds {@code 0}.
         *
         * @param id    the user id, from the path
         * @param limit an {@code int} query parameter; {@code 0} when absent
         * @return the user id and limit
         */
        @GET
        @Path("/users/{id}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = GET_USER)
        public String getUser(@PathParam("id") String id, @QueryParam(LIMIT_PARAM) @DefaultValue("0") int limit) {
            return "user=" + id + ", limit=" + limit;
        }

        /**
         * Gets a secured resource. Secured at method level by {@link CredentialScheme}.
         *
         * @return a constant body
         */
        @GET
        @Path("/secured")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = CREDENTIAL_SCHEME)
        @Operation(operationId = GET_SECURED)
        public String getSecured() {
            return "secured";
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
     * The hand-built harness's selected validation strategy. Its gate fails a request carrying
     * {@value #INVALID_HEADER} with 400 and passes every other.
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
     * The component harness's descriptor-capturing {@link OperationHandlerContributor}: at router
     * build it captures each operation's {@code OperationRegistrationContext.operation()}, keyed by
     * operation id, and adds no handler. TP-001 compares the event's {@code operation()} with these
     * by identity.
     */
    static final class DescriptorCapture implements OperationHandlerContributor {

        /** In the pre-authorization band; it adds no handler, so its position is immaterial. */
        static final int PRIORITY = 0;

        private final Map<String, RestOperationDescriptor> registered;

        /**
         * Creates the capture.
         *
         * @param registered receives each operation's descriptor at router build
         */
        DescriptorCapture(Map<String, RestOperationDescriptor> registered) {
            this.registered = registered;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            registered.put(context.operation().operationId(), context.operation());
        }
    }

    /** The component harness's capturing {@link RestRequestCompletedListener}. */
    static final class RestEventCapture implements RestRequestCompletedListener {

        private final List<RestRequestCompletedEvent> events;

        /**
         * Creates the capture.
         *
         * @param events receives every event, in dispatch order
         */
        RestEventCapture(List<RestRequestCompletedEvent> events) {
            this.events = events;
        }

        @Override
        public void onCompleted(RestRequestCompletedEvent event) {
            events.add(event);
        }
    }

    /**
     * TP-004's listener: inside {@code onCompleted} it reads the operation id, route template and
     * HTTP method from {@code event.operation()} and records them. A {@code null} operation makes the
     * read throw, and the emitter isolates that, so nothing is recorded.
     */
    static final class OperationIdentityReader implements RestRequestCompletedListener {

        private final List<OperationIdentity> reads;

        /**
         * Creates the reader.
         *
         * @param reads receives each identity read
         */
        OperationIdentityReader(List<OperationIdentity> reads) {
            this.reads = reads;
        }

        @Override
        public void onCompleted(RestRequestCompletedEvent event) {
            RestOperationDescriptor operation = event.operation();
            reads.add(
                    new OperationIdentity(operation.operationId(), operation.routeTemplate(), operation.httpMethod()));
        }
    }

    /** The component harness's capturing {@link HttpRequestCompletedListener}. */
    static final class HttpEventCapture implements HttpRequestCompletedListener {

        private final List<HttpRequestCompletedEvent> events;

        /**
         * Creates the capture.
         *
         * @param events receives every event, in dispatch order
         */
        HttpEventCapture(List<HttpRequestCompletedEvent> events) {
            this.events = events;
        }

        @Override
        public void onCompleted(HttpRequestCompletedEvent event) {
            events.add(event);
        }
    }

    /**
     * ROOT middleware, the {@code barrierHandler} pattern of {@code RestRequestCompletionEmitterTest}
     * keyed by {@value #CASE_HEADER}: for a request whose case has a registered barrier, it registers
     * an {@code afterClose} task on the request's lifecycle handle that completes the barrier. A
     * request without a registered case passes untouched.
     */
    static final class CaseBarrier implements Middleware {

        /** The hand-built harness's priority: after the lifecycle, which must have created the handle. */
        static final int PRIORITY = RequestContextLifecycle.ORDER + 20;

        /**
         * The component harness's priority: still after the lifecycle, but before correlation ingress,
         * so a request its REJECT policy fails still registers its barrier.
         */
        static final int BEFORE_CORRELATION_INGRESS = CorrelationIngressMiddleware.ORDER - 1;

        private final Map<String, CompletableFuture<Void>> barriers;
        private final int priority;

        /**
         * Creates the barrier middleware.
         *
         * @param barriers the per-case barriers, registered by the test before each send
         * @param priority the middleware's priority, in the application phase
         */
        CaseBarrier(Map<String, CompletableFuture<Void>> barriers, int priority) {
            this.barriers = barriers;
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
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

    /**
     * TP-002's stand-in for the ROOT rate limiter (rest-jaxrs has no rate-limit dependency): an
     * application-phase ROOT middleware, ordered after correlation ingress, that fails a request
     * carrying {@value #ROOT_REJECT_HEADER} with 429 and passes every other. The failure reaches the
     * JAX-RS mount's failure handler, which answers 429.
     */
    static final class RootRejecter implements Middleware {

        static final int PRIORITY = RequestContextLifecycle.ORDER + 40;

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            if (rc.request().getHeader(ROOT_REJECT_HEADER) != null) {
                rc.fail(429);
            } else {
                rc.next();
            }
        }
    }

    /**
     * TP-003's holder remover: an application-phase ROOT middleware ordered after the emitter and the
     * {@link CaseBarrier}. For a request carrying {@value #REMOVE_HOLDER_HEADER} it records
     * {@code removedAt}, removes every routing-context data entry whose value's class is in the
     * emitter's package (the holder's type is package-private there, so it cannot be named), records
     * the number removed under the request's case, and calls {@code next()} from a
     * {@value #HOLDER_REMOVAL_DELAY_MS} ms timer. Every other request passes untouched.
     */
    static final class HolderRemover implements Middleware {

        static final int PRIORITY = RequestContextLifecycle.ORDER + 30;

        /** The package of the completion-state holder's value type. */
        static final String HOLDER_PACKAGE = RestRequestCompletionEmitter.class.getPackageName();

        private final Map<String, Removal> removals;

        /**
         * Creates the remover.
         *
         * @param removals receives each marked request's removal, keyed by case
         */
        HolderRemover(Map<String, Removal> removals) {
            this.removals = removals;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            if (rc.request().getHeader(REMOVE_HOLDER_HEADER) == null) {
                rc.next();
                return;
            }
            Instant removedAt = Instant.now();
            int removedCount = 0;
            Iterator<Map.Entry<String, Object>> entries = rc.data().entrySet().iterator();
            while (entries.hasNext()) {
                Object value = entries.next().getValue();
                if (value != null && HOLDER_PACKAGE.equals(value.getClass().getPackageName())) {
                    entries.remove();
                    removedCount++;
                }
            }
            String caseName = rc.request().getHeader(CASE_HEADER);
            if (caseName != null) {
                removals.put(caseName, new Removal(removedAt, removedCount));
            }
            rc.vertx().setTimer(HOLDER_REMOVAL_DELAY_MS, timerId -> rc.next());
        }

        /**
         * One marked request's removal.
         *
         * @param removedAt    taken before any entry was removed
         * @param removedCount how many entries were removed
         */
        record Removal(Instant removedAt, int removedCount) {}
    }

    /**
     * TP-015's short circuit: an ordinary application-phase ROOT middleware (no {@code phase()}
     * override) at priority {@code Integer.MIN_VALUE}. For a request carrying
     * {@value #SHORT_CIRCUIT_HEADER} it registers the request's barrier as a lifecycle
     * {@code afterClose} task itself, because {@link CaseBarrier} never runs for that request, then
     * ends the response with {@value #SHORT_CIRCUIT_STATUS} without calling {@code next()}. Every other
     * request passes untouched, so no other test is affected.
     */
    static final class ShortCircuit implements Middleware {

        static final int PRIORITY = Integer.MIN_VALUE;

        private final Map<String, CompletableFuture<Void>> barriers;

        /**
         * Creates the short circuit.
         *
         * @param barriers the per-case barriers, registered by the test before each send
         */
        ShortCircuit(Map<String, CompletableFuture<Void>> barriers) {
            this.barriers = barriers;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            if (rc.request().getHeader(SHORT_CIRCUIT_HEADER) == null) {
                rc.next();
                return;
            }
            String caseName = rc.request().getHeader(CASE_HEADER);
            CompletableFuture<Void> barrier = caseName != null ? barriers.get(caseName) : null;
            if (barrier != null) {
                RequestContextLifecycle.fromRoutingContext(rc).afterClose(() -> barrier.complete(null));
            }
            rc.response().setStatusCode(SHORT_CIRCUIT_STATUS).end();
        }
    }
}
