// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig;
import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.HttpRequestCompletedListener;
import dev.vertique.rest.core.events.RequestCompletionRecorder;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.router.HttpVerticle;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proves, through a real {@link HttpVerticle}, that a request which authenticates only after a
 * reroute emits exactly one completion event carrying the security and correlation snapshots of the
 * pass that authenticated (FR-003, AC-003.1; Codex CX-F-007).
 *
 * <p>A reroute re-runs every ROOT middleware on the same request. {@link RequestContextLifecycle}
 * must therefore keep one handle per request across the reroute and register no second cleanup:
 * a second cleanup would be registered after the emitter's end handler, fire before it, and close
 * the second pass's bindings before the event reads them. The event would then carry the first
 * pass's anonymous identity and request id, which is the CX-F-007 defect.
 *
 * <p>{@link #eventCarriesTheSnapshotsOfThePassThatAuthenticated(RerouteCase)} (TP-017) sends one
 * {@code GET /api/start} per {@link RerouteCase}, with an accepted credential and no
 * {@code X-Request-Id}. {@code /api/start} resolves an anonymous identity, because it carries no
 * credential scheme, and reroutes to the case's target, which authenticates {@value #SUBJECT}. So
 * authentication happens only on the second pass, and each pass generates its own request id. The
 * one event must carry {@value #SUBJECT} and the second pass's request id, which is also the id the
 * response echoes (security review SEC-I4). A reroute clears the claim {@code /start}'s operation
 * route made, so the reroute target's claim selects the event's type: a
 * {@link RestRequestCompletedEvent} whose {@code operation()} is the target's descriptor instance
 * when the target is an operation route, and an {@link HttpRequestCompletedEvent} when the target
 * matches no operation route and no transport claims the request.
 *
 * <p><strong>The server.</strong> Every test invocation deploys a fresh {@link HttpVerticle}, built
 * with its public five-argument constructor and bound to {@code 127.0.0.1} on port 0. The port is
 * read back from the {@code http.port} shared-data entry, as {@code JaxRsApplicationMountConflictIT}
 * does. One {@link HolderBackedSecurityRuntime} and one {@link DefaultContextHolder} are shared by
 * the emitter, the correlation middleware, the identity middleware and the probes. The ROOT
 * middlewares, in {@link HttpVerticle}'s order, are the real {@link RequestContextLifecycle}, a
 * real {@link RestRequestCompletionEmitter} whose REST listener appends to {@link #REST_EVENTS} and
 * whose HTTP listener appends to {@link #HTTP_EVENTS}, the real
 * {@link CorrelationIngressMiddleware} with its default configuration, the
 * {@link CorrelationProbe} and the {@link FirstPassBarrier}. The one mount is {@link RerouteMount}
 * at {@value #MOUNT_PATH}. Its routes run the real {@link IdentityResolutionMiddleware}, which
 * binds the resolved context through {@link RequestContextLifecycle#fromRoutingContext}, as it does
 * on a JAX-RS route at priority 80.
 *
 * <p><strong>Waiting for emission.</strong> {@link FirstPassBarrier} registers an
 * {@code afterClose} task on the lifecycle handle current on the request's first pass. That
 * handle's cleanup is the request's first-registered end handler, so it fires after every other end
 * handler, with or without the lifecycle's one-handle-per-request rule. The test therefore never
 * reads the captured event before it was emitted, and needs no settle window.
 *
 * <p><strong>Client.</strong> One shared {@link WebClient} for the class, with redirect following
 * off, and no raw-client exemption. The class uses the {@link VertxExtension}-injected
 * {@link Vertx} and does not own it, so the fire-and-forget {@link WebClient#close()} is
 * sufficient.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RerouteCompletionSnapshotIT {

    private static final Logger LOG = LoggerFactory.getLogger(RerouteCompletionSnapshotIT.class);

    // --- Constants ---

    /** Loopback address used for the bind and every client connection. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Bound wait for every asynchronous step; the class-level {@code @Timeout} is the backstop. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** Shared-data map and key under which {@link HttpVerticle} publishes its bound port. */
    private static final String SHARED_DATA_MAP = "vertique";

    private static final String HTTP_PORT_KEY = "http.port";

    /** Where {@link RerouteMount} is mounted. */
    private static final String MOUNT_PATH = "/api/*";

    /** The route every case requests; it resolves an anonymous identity and reroutes. */
    private static final String START_PATH = "/api/start";

    /** The operation-route reroute target. */
    private static final String SECURED_OP_PATH = "/api/secured-op";

    /** The reroute target that matches no operation route. */
    private static final String SECURED_PLAIN_PATH = "/api/secured-plain";

    /** Request header naming the case, read by the probes and {@link FirstPassBarrier}. */
    private static final String CASE_HEADER = "X-Case";

    /** Request header carrying the absolute path {@code /api/start} reroutes to. */
    private static final String REROUTE_HEADER = "X-Reroute-To";

    /** Request header carrying the credential {@link CredentialScheme} checks. */
    private static final String CREDENTIAL_HEADER = "X-Credential";

    /** The only credential value {@link CredentialScheme} accepts. */
    private static final String ACCEPTED_CREDENTIAL = "ok";

    /** The subject {@link CredentialScheme} authenticates. */
    private static final String SUBJECT = "alice";

    /**
     * The correlation middleware's configuration: the defaults, which generate a request id when
     * the request carries none and echo it in the {@code X-Request-Id} response header.
     */
    private static final CorrelationIngressConfig CORRELATION_CONFIG = CorrelationIngressConfig.defaults();

    /** Test-local descriptor of {@code GET /start}. */
    private static final RestOperationDescriptor START = new TestOperation("start", "GET", "/start");

    /** Test-local descriptor of {@code GET /secured-op}. */
    private static final RestOperationDescriptor SECURED_OP = new TestOperation("securedOp", "GET", "/secured-op");

    // --- Recorded state: filled by the fixtures, reset after every test ---

    /** Every {@link RestRequestCompletedEvent} the emitter's REST listener received, in emission order. */
    private static final List<RestRequestCompletedEvent> REST_EVENTS = new CopyOnWriteArrayList<>();

    /** Every {@link HttpRequestCompletedEvent} the emitter's HTTP listener received, in emission order. */
    private static final List<HttpRequestCompletedEvent> HTTP_EVENTS = new CopyOnWriteArrayList<>();

    /** Per-case barriers, handed out once by {@link FirstPassBarrier}. */
    private static final Map<String, CompletableFuture<Void>> BARRIERS = new ConcurrentHashMap<>();

    /** Per-case correlation observations, one per routing pass, in pass order. */
    private static final Map<String, List<CorrelationPass>> CORRELATION_PASSES = new ConcurrentHashMap<>();

    /** Per-case security observations, one per routing pass, in pass order. */
    private static final Map<String, List<SecurityPass>> SECURITY_PASSES = new ConcurrentHashMap<>();

    // --- Class-scoped resources ---

    private static Vertx vertx;
    private static WebClient client;

    // --- Per-test resources ---

    private String deploymentId;
    private int port;

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
     * Deploys a fresh {@link HttpVerticle} (see the class Javadoc) over one shared security runtime
     * and context holder, and reads its bound port back from the {@code http.port} shared-data
     * entry.
     *
     * @throws Exception if the deployment does not complete within the async bound
     */
    @BeforeEach
    void deploy() throws Exception {
        clearPublishedPort();
        ContextHolder holder = new DefaultContextHolder();
        SecurityRuntime runtime = new HolderBackedSecurityRuntime((securityContext, secure) -> null);
        HttpVerticle verticle = new HttpVerticle(
                new HttpServerOptions().setHost(LOOPBACK).setPort(0),
                Set.of(),
                rootMiddlewares(runtime, holder),
                Set.of(new RerouteMount(identityMiddleware(runtime, holder), new SecurityProbe(runtime))),
                Set.of());
        deploymentId = await(vertx.deployVerticle(verticle));
        Integer published =
                (Integer) vertx.sharedData().getLocalMap(SHARED_DATA_MAP).get(HTTP_PORT_KEY);
        assertNotNull(published, "the deployed HttpVerticle must publish its bound port as http.port");
        port = published;
    }

    /**
     * Undeploys the verticle, clears {@code http.port}, and resets the captured events of both
     * types, the barriers and both probes' observations. The resets run even when the undeploy
     * fails.
     *
     * @throws Exception if the undeploy does not complete within the async bound
     */
    @AfterEach
    void tearDown() throws Exception {
        try {
            if (deploymentId != null) {
                await(vertx.undeploy(deploymentId));
                deploymentId = null;
            }
        } finally {
            clearPublishedPort();
            REST_EVENTS.clear();
            HTTP_EVENTS.clear();
            BARRIERS.clear();
            CORRELATION_PASSES.clear();
            SECURITY_PASSES.clear();
        }
    }

    // --- TP-017 ---

    /**
     * TP-017's named cases. Both reroute to a route that authenticates {@value #SUBJECT}; they
     * differ only in whether that route is an operation route, and so in the type of the one event.
     *
     * @return the two cases, in the contract's order
     */
    static Stream<RerouteCase> rerouteCases() {
        return Stream.of(
                new RerouteCase("operation target", SECURED_OP_PATH, SECURED_OP),
                new RerouteCase("unmatched target", SECURED_PLAIN_PATH, null));
    }

    /**
     * TP-017 (FR-003, AC-003.1; Codex CX-F-007; security review SEC-I4): a request that
     * authenticates only on the pass after a reroute emits exactly one event carrying that pass's
     * security snapshot and request id, the request id the response echoes. For an operation target
     * the event is a {@link RestRequestCompletedEvent} whose {@code operation()} is the target's
     * descriptor instance, and no {@link HttpRequestCompletedEvent} is emitted; for a target that
     * matches no operation route it is an {@link HttpRequestCompletedEvent}, and no
     * {@link RestRequestCompletedEvent} is emitted.
     *
     * @param rerouteCase the case under test
     * @throws Exception if an asynchronous step does not complete within the async bound
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rerouteCases")
    @DisplayName("The completion event carries the security and correlation snapshots of the pass that "
            + "authenticated after a reroute")
    void eventCarriesTheSnapshotsOfThePassThatAuthenticated(RerouteCase rerouteCase) throws Exception {
        String caseName = rerouteCase.name();
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        BARRIERS.put(caseName, barrier);

        HttpResponse<Buffer> response = await(client.get(port, LOOPBACK, START_PATH)
                .putHeader(CREDENTIAL_HEADER, ACCEPTED_CREDENTIAL)
                .putHeader(CASE_HEADER, caseName)
                .putHeader(REROUTE_HEADER, rerouteCase.target())
                .send());
        awaitBarrier(caseName, barrier);

        // --- Preconditions: two passes, authentication only on the second, one request id each ---
        assertEquals(
                200,
                response.statusCode(),
                () -> "precondition, case " + rerouteCase + ": unexpected response status; body: "
                        + response.bodyAsString());

        List<CorrelationPass> correlationPasses = List.copyOf(CORRELATION_PASSES.getOrDefault(caseName, List.of()));
        List<SecurityPass> securityPasses = List.copyOf(SECURITY_PASSES.getOrDefault(caseName, List.of()));
        LOG.info("case {}: correlation passes {}, security passes {}", caseName, correlationPasses, securityPasses);
        List<String> expectedPaths = List.of(START_PATH, rerouteCase.target());
        assertAll(
                "precondition, case " + rerouteCase + ": two passes were probed, the start and the reroute target",
                () -> assertEquals(
                        expectedPaths,
                        correlationPasses.stream().map(CorrelationPass::path).toList(),
                        () -> "correlation probe passes: " + correlationPasses),
                () -> assertEquals(
                        expectedPaths,
                        securityPasses.stream().map(SecurityPass::path).toList(),
                        () -> "security probe passes: " + securityPasses));

        PrincipalRef firstActor = securityPasses.get(0).actor();
        PrincipalRef secondActor = securityPasses.get(1).actor();
        assertAll(
                "precondition, case " + rerouteCase + ": authentication happened only on the second pass",
                () -> {
                    assertNotNull(firstActor, "pass 1 must have bound a security context");
                    assertEquals(PrincipalType.ANONYMOUS, firstActor.type(), () -> "pass 1's actor: " + firstActor);
                    assertNotEquals(SUBJECT, firstActor.id(), () -> "pass 1's actor: " + firstActor);
                },
                () -> {
                    assertNotNull(secondActor, "pass 2 must have bound a security context");
                    assertEquals(SUBJECT, secondActor.id(), () -> "pass 2's actor: " + secondActor);
                });

        String firstRequestId = correlationPasses.get(0).requestId();
        String secondRequestId = correlationPasses.get(1).requestId();
        assertNotNull(firstRequestId, "precondition: pass 1 must have bound a correlation context");
        assertNotNull(secondRequestId, "precondition: pass 2 must have bound a correlation context");
        assertNotEquals(
                firstRequestId,
                secondRequestId,
                "precondition: each pass must bind its own generated request id, so the passes are distinguishable");

        // --- Exactly one event for the request, of the type the reroute target's claim selects ---
        RestOperationDescriptor expectedOperation = rerouteCase.expectedOperation();
        boolean operationTarget = expectedOperation != null;
        List<RestRequestCompletedEvent> restEvents = List.copyOf(REST_EVENTS);
        List<HttpRequestCompletedEvent> httpEvents = List.copyOf(HTTP_EVENTS);
        assertAll(
                "case " + rerouteCase + ": exactly one completion event must be emitted for the request, "
                        + (operationTarget
                                ? "a REST event, because the reroute target is an operation route"
                                : "an HTTP event, because the reroute cleared /start's claim and the target "
                                        + "matches no operation route"),
                () -> assertEquals(operationTarget ? 1 : 0, restEvents.size(), () -> "REST events: " + restEvents),
                () -> assertEquals(operationTarget ? 0 : 1, httpEvents.size(), () -> "HTTP events: " + httpEvents));

        String eventType;
        SecurityContextSnapshot security;
        CorrelationContextSnapshot correlation;
        RestOperationDescriptor eventOperation;
        if (operationTarget) {
            RestRequestCompletedEvent event = restEvents.get(0);
            eventType = RestRequestCompletedEvent.class.getSimpleName();
            security = event.securityContextSnapshot();
            correlation = event.correlationContext();
            eventOperation = event.operation();
        } else {
            HttpRequestCompletedEvent event = httpEvents.get(0);
            eventType = HttpRequestCompletedEvent.class.getSimpleName();
            security = event.securityContextSnapshot();
            correlation = event.correlationContext();
            eventOperation = null;
        }
        String echoedRequestId = response.getHeader(CORRELATION_CONFIG.requestIdHeader());
        LOG.info(
                "case {}: pass 1 requestId={}, pass 2 requestId={}; event {} actor={}, requestId={}, "
                        + "echoed {}={}, operation={}",
                caseName,
                firstRequestId,
                secondRequestId,
                eventType,
                security != null ? security.identity().actor() : null,
                correlation != null ? correlation.requestId().value() : null,
                CORRELATION_CONFIG.requestIdHeader(),
                echoedRequestId,
                eventOperation != null ? eventOperation.operationId() + " " + eventOperation.routeTemplate() : null);

        // --- Then: the event carries the second pass's snapshots and, for an operation target, its operation ---
        List<Executable> eventChecks = new ArrayList<>();
        eventChecks.add(() -> {
            assertNotNull(security, "securityContextSnapshot() must be present");
            assertEquals(
                    SUBJECT,
                    security.identity().actor().id(),
                    () -> "securityContextSnapshot() must be pass 2's authenticated actor, not pass 1's " + firstActor
                            + "; was " + security.identity().actor());
        });
        eventChecks.add(() -> {
            assertNotNull(correlation, "correlationContext() must be present");
            assertEquals(
                    secondRequestId,
                    correlation.requestId().value(),
                    () -> "correlationContext().requestId() must be pass 2's request id, not pass 1's "
                            + firstRequestId);
        });
        eventChecks.add(() -> assertEquals(
                secondRequestId,
                echoedRequestId,
                () -> "the echoed " + CORRELATION_CONFIG.requestIdHeader()
                        + " response header must be pass 2's request id, not pass 1's " + firstRequestId));
        if (operationTarget) {
            eventChecks.add(() -> assertSame(
                    expectedOperation,
                    eventOperation,
                    "operation() must be the reroute target's descriptor instance, the one its identity handler "
                            + "recorded"));
        }
        assertAll("case " + rerouteCase + ": the " + eventType, eventChecks);
    }

    // --- Helpers ---

    /**
     * The ROOT middlewares: the lifecycle, the emitter recording REST events into
     * {@link #REST_EVENTS} and HTTP events into {@link #HTTP_EVENTS} (no completion scopes), the real
     * correlation middleware, the correlation probe and the barrier.
     * {@link HttpVerticle} orders them by phase, then priority.
     *
     * @param runtime the shared security runtime the emitter reads
     * @param holder  the shared context holder
     * @return the ROOT middleware set
     */
    private static Set<Middleware> rootMiddlewares(SecurityRuntime runtime, ContextHolder holder) {
        RestRequestCompletedListener restListener = REST_EVENTS::add;
        HttpRequestCompletedListener httpListener = HTTP_EVENTS::add;
        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.of(runtime), holder, Set.of(restListener), Set.of(httpListener), Set.of());
        return Set.of(
                new RequestContextLifecycle(),
                emitter,
                correlationIngress(holder),
                new CorrelationProbe(holder),
                new FirstPassBarrier());
    }

    /**
     * The real {@link CorrelationIngressMiddleware}, built as {@code CorrelationIngressMiddlewareTest}
     * builds it, over the shared holder and with the default configuration.
     *
     * @param holder the shared context holder
     * @return the middleware
     */
    private static CorrelationIngressMiddleware correlationIngress(ContextHolder holder) {
        return new CorrelationIngressMiddleware(
                holder,
                new CorrelationContextFactory(Optional.empty()),
                new CorrelationContextMutator(holder),
                CORRELATION_CONFIG,
                Set.of(),
                Set.of(),
                Optional.empty());
    }

    /**
     * The real {@link IdentityResolutionMiddleware}, built as {@code IdentityResolutionMiddlewareIT}
     * builds it, over the shared runtime and holder.
     *
     * @param runtime the shared security runtime it binds the resolved context on
     * @param holder  the shared context holder it reads the correlation context from
     * @return the middleware
     */
    private static IdentityResolutionMiddleware identityMiddleware(SecurityRuntime runtime, ContextHolder holder) {
        return new IdentityResolutionMiddleware(
                Set.of(new DefaultSecurityIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                runtime,
                holder);
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
     * A TP-017 row.
     *
     * @param name              the case name, also sent as {@value #CASE_HEADER}
     * @param target            the absolute path {@code /api/start} reroutes to, sent as
     *                          {@value #REROUTE_HEADER}
     * @param expectedOperation the descriptor instance the one {@link RestRequestCompletedEvent}'s
     *                          {@code operation()} must be, or {@code null} when the target matches
     *                          no operation route, so the one event must be an
     *                          {@link HttpRequestCompletedEvent}
     */
    record RerouteCase(String name, String target, @Nullable RestOperationDescriptor expectedOperation) {

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * What {@link CorrelationProbe} saw on one routing pass.
     *
     * @param path      the request path on that pass
     * @param requestId the request id of the correlation context bound on that pass, or {@code null}
     *                  when none was bound
     */
    record CorrelationPass(String path, @Nullable String requestId) {}

    /**
     * What {@link SecurityProbe} saw on one routing pass.
     *
     * @param path  the request path on that pass
     * @param actor the actor of {@code runtime.current()} on that pass, or {@code null} when no
     *              security context was bound
     */
    record SecurityPass(String path, @Nullable PrincipalRef actor) {}

    /**
     * Test-local {@link RestOperationDescriptor}. Only the identity fields carry values: the
     * recorder reads nothing else, so the security policy is {@link SecurityPolicy.None} and every
     * collection is empty.
     *
     * @param operationId   the operation identifier
     * @param httpMethod    the HTTP method
     * @param routeTemplate the route template
     */
    private record TestOperation(String operationId, String httpMethod, String routeTemplate)
            implements RestOperationDescriptor {

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }

    // --- Fixtures ---

    /**
     * The mount at {@value #MOUNT_PATH}, with three routes:
     *
     * <ul>
     *   <li>{@code GET /start}: the identity handler for {@link #START}, the identity middleware
     *       (no credential scheme, so it binds an anonymous context), the {@link SecurityProbe},
     *       then a reroute to the {@value #REROUTE_HEADER} path;</li>
     *   <li>{@code GET /secured-op}: the identity handler for {@link #SECURED_OP}, the
     *       {@link CredentialScheme}, the identity middleware, the {@link SecurityProbe}, then
     *       200;</li>
     *   <li>{@code GET /secured-plain}: the same chain without an identity handler, so it matches
     *       no operation route.</li>
     * </ul>
     */
    static final class RerouteMount implements RouterMount {

        private final Handler<RoutingContext> identity;
        private final SecurityProbe securityProbe;
        private final CredentialScheme credentialScheme = new CredentialScheme();

        /**
         * Creates the mount.
         *
         * @param identity      the real identity middleware, over the shared runtime and holder
         * @param securityProbe the probe run after the identity middleware on every route
         */
        RerouteMount(Handler<RoutingContext> identity, SecurityProbe securityProbe) {
            this.identity = identity;
            this.securityProbe = securityProbe;
        }

        @Override
        public String mountPath() {
            return MOUNT_PATH;
        }

        @Override
        public Future<Router> createRouter(Vertx vertx) {
            Router router = Router.router(vertx);
            router.get("/start")
                    .handler(RequestCompletionRecorder.operationRouteHandler(START))
                    .handler(identity)
                    .handler(securityProbe)
                    .handler(RerouteMount::rerouteToTarget);
            router.get("/secured-op")
                    .handler(RequestCompletionRecorder.operationRouteHandler(SECURED_OP))
                    .handler(credentialScheme)
                    .handler(identity)
                    .handler(securityProbe)
                    .handler(RerouteMount::ok);
            router.get("/secured-plain")
                    .handler(credentialScheme)
                    .handler(identity)
                    .handler(securityProbe)
                    .handler(RerouteMount::ok);
            return Future.succeededFuture(router);
        }

        /**
         * Reroutes the request to the absolute path in {@value #REROUTE_HEADER}; a request without
         * one fails with 400, a setup defect the status precondition reports.
         *
         * @param rc the routing context of the first pass
         */
        private static void rerouteToTarget(RoutingContext rc) {
            String target = rc.request().getHeader(REROUTE_HEADER);
            if (target == null) {
                rc.fail(400);
                return;
            }
            rc.reroute(target);
        }

        /**
         * Ends the request with 200.
         *
         * @param rc the routing context of the second pass
         */
        private static void ok(RoutingContext rc) {
            rc.response().setStatusCode(200).end("ok");
        }
    }

    /**
     * The credential scheme, the evidence-appending handler of {@code IdentityResolutionMiddlewareIT}:
     * a request carrying {@value #CREDENTIAL_HEADER}{@code : }{@value #ACCEPTED_CREDENTIAL} gets JWT
     * {@link AuthenticationEvidence} for subject {@value #SUBJECT} and a Vert.x {@link User}; any
     * other request fails with 401.
     */
    static final class CredentialScheme implements Handler<RoutingContext> {

        @Override
        public void handle(RoutingContext rc) {
            if (!ACCEPTED_CREDENTIAL.equals(rc.request().getHeader(CREDENTIAL_HEADER))) {
                rc.fail(401);
                return;
            }
            AuthenticationEvidence evidence = new AuthenticationEvidence(
                    DefaultAuthMethod.jwt(),
                    Optional.of(SUBJECT),
                    Instant.now(),
                    Optional.empty(),
                    new CustomVerificationSource("test", Map.of()),
                    Map.of("sub", SUBJECT));
            RestAuthenticationEvidence.append(rc, evidence);
            ((UserContextInternal) rc.userContext()).setUser(User.create(new JsonObject().put("sub", SUBJECT)));
            rc.next();
        }
    }

    /**
     * Route handler run after the identity middleware: records, per case and per pass, the actor
     * of {@code runtime.current()}.
     */
    static final class SecurityProbe implements Handler<RoutingContext> {

        private final SecurityRuntime runtime;

        /**
         * Creates the probe.
         *
         * @param runtime the shared security runtime
         */
        SecurityProbe(SecurityRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public void handle(RoutingContext rc) {
            String caseName = rc.request().getHeader(CASE_HEADER);
            if (caseName != null) {
                SecurityContext current = runtime.current();
                SECURITY_PASSES
                        .computeIfAbsent(caseName, key -> new CopyOnWriteArrayList<>())
                        .add(new SecurityPass(
                                rc.request().path(),
                                current != null ? current.identity().actor() : null));
            }
            rc.next();
        }
    }

    /**
     * ROOT middleware run right after {@link CorrelationIngressMiddleware}: records, per case and
     * per pass, the request id of the correlation context bound on that pass.
     */
    static final class CorrelationProbe implements Middleware {

        static final int PRIORITY = CorrelationIngressMiddleware.ORDER + 1;

        private final ContextHolder holder;

        /**
         * Creates the probe.
         *
         * @param holder the shared context holder
         */
        CorrelationProbe(ContextHolder holder) {
            this.holder = holder;
        }

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            String caseName = rc.request().getHeader(CASE_HEADER);
            if (caseName != null) {
                String requestId = holder.current(CorrelationContext.class)
                        .map(context -> context.requestId().value())
                        .orElse(null);
                CORRELATION_PASSES
                        .computeIfAbsent(caseName, key -> new CopyOnWriteArrayList<>())
                        .add(new CorrelationPass(rc.request().path(), requestId));
            }
            rc.next();
        }
    }

    /**
     * ROOT middleware keyed by {@value #CASE_HEADER}: on a request's first pass, registers an
     * {@code afterClose} task that completes the case's barrier on the lifecycle handle current on
     * that pass.
     */
    static final class FirstPassBarrier implements Middleware {

        static final int PRIORITY = CorrelationProbe.PRIORITY + 1;

        @Override
        public int priority() {
            return PRIORITY;
        }

        @Override
        public void handle(RoutingContext rc) {
            String caseName = rc.request().getHeader(CASE_HEADER);
            // First pass only: remove() hands a case's barrier out once, so the reroute's pass finds
            // none. The first pass's handle is closed by the request's first-registered end handler,
            // which fires last under Vert.x Web's reverse end-handler order, with either lifecycle.
            // A lifecycle that gives the reroute's pass a new handle also registers that handle's
            // cleanup after the emitter's end handler, so the cleanup fires before the emitter's
            // end handler; a barrier on that handle would release the test before emission.
            CompletableFuture<Void> barrier = caseName != null ? BARRIERS.remove(caseName) : null;
            if (barrier != null) {
                RequestContextLifecycle.fromRoutingContext(rc).afterClose(() -> barrier.complete(null));
            }
            rc.next();
        }
    }
}
