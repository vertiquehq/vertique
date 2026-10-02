// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.events.HttpRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.GuardedManagementApi;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.LaterDocsPrefixMount;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.SharedDeployment;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.TraceContributors;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.TwinResource;
import dev.vertique.rest.openapi.docs.fixture.support.Futures;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.support.StartupDeployments.Outcome;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves the protected document of the application {@code management} through the resource security
 * chain and checks that it behaves exactly like an equally annotated resource method, its twin
 * {@code GET /api/mgmt/twin}.
 *
 * <p>The shared graph composes the framework's REST, request-validation, JWT authentication (scheme
 * {@code bearerAuth}), and documentation modules with two declared applications: {@code public},
 * whose document is public, and {@code management}, whose declaring interface's access policy is
 * {@code PROTECTED} under scheme {@code bearerAuth} with the role {@code admin}. The
 * configured default {@code Cache-Control} is the permissive {@code public, max-age=3600}, and
 * {@code apidocs.documents.management} sets {@code enabled} and {@code serverUrl}, which never change
 * the access policy. Beside them: probe contributors at priorities 45, 90, 200, and 400, which record
 * a per-request trace keyed by the {@value Observations#REQUEST_HEADER} header and the operation
 * descriptor each route handed them; an application contributor at priority 60 that rejects a marked
 * request; a claims validator rejecting the blocked tenant; security-event and completion-event
 * recorders; a later plain mount at {@code /apidocs/*} whose routes, catch-all, and failure handler
 * count their invocations; and a counting error interceptor.
 *
 * <p>Two deployments of that graph are started once for the class: one with the shared
 * configuration, and one without any {@code apidocs} configuration. Each test first requires them to
 * have started, so a deployment failure fails every test with its cause. Requests are sent one at a
 * time; each request registers a barrier that completes once the server closed the request's
 * lifecycle, after every end handler and listener ran, so the events and the trace read after it are
 * exactly that request's. Expected values are fixed literals.
 *
 * <p>The class timeout is 60 seconds rather than the default 20, because the class starts two
 * deployments, builds a third graph with its own HTTP server, and sends over a hundred sequential
 * requests, each awaiting its lifecycle barrier.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ProtectedDocumentIT {

    private static final Logger LOG = LoggerFactory.getLogger(ProtectedDocumentIT.class);

    private static final String HOST = "127.0.0.1";

    /** The longest one asynchronous step is awaited. */
    private static final long BOUND_SECONDS = 10;

    private static final String MANAGEMENT_JSON = "/apidocs/management/openapi.json";
    private static final String MANAGEMENT_YAML = "/apidocs/management/openapi.yaml";
    private static final String PUBLIC_JSON = "/apidocs/public/openapi.json";
    private static final String UNKNOWN_JSON = "/apidocs/unknown/openapi.json";
    private static final String LATER_BOOM = "/apidocs" + LaterDocsPrefixMount.BOOM_ROUTE;
    private static final String TWIN = GuardedManagementApi.PATH + TwinResource.TWIN_PATH;
    private static final String TWIN_FAIL = GuardedManagementApi.PATH + TwinResource.FAIL_PATH;

    private static final String JSON_OPERATION = "apidocs:management:json";
    private static final String YAML_OPERATION = "apidocs:management:yaml";
    private static final String APPLICATION = "management";

    private static final String JSON_TYPE = "application/json";
    private static final String YAML_TYPE = "application/yaml";

    private static final String PROBLEM_401 = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}";
    private static final String PROBLEM_403 = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}";
    private static final String PROBLEM_404 = "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404}";
    private static final String PROBLEM_503 =
            "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503}";

    private static final String NO_STORE = "no-store";
    private static final String VALIDATION_MEMBER = "x-vertique-validation";

    private static final String PROBE45 = TraceContributors.probeStep(45);
    private static final String PROBE90 = TraceContributors.probeStep(90);
    private static final String PROBE200 = TraceContributors.probeStep(200);
    private static final String PROBE400 = TraceContributors.probeStep(400);
    private static final String APP60 = TraceContributors.APPLICATION_STEP;

    /** The full fixture trace of an admitted request, in priority order. */
    private static final List<String> FULL_TRACE = List.of(PROBE45, APP60, PROBE90, PROBE200, PROBE400);

    /** The fixture trace of a request the application contributor rejected. */
    private static final List<String> REJECTED_TRACE = List.of(PROBE45, APP60);

    private static final AtomicLong REQUESTS = new AtomicLong();

    private static Vertx vertx;
    private static WebClient client;
    private static Deployment configured;
    private static Deployment unconfigured;
    private static String alice;
    private static String bob;
    private static String carol;
    private static String foreignIssuer;

    @BeforeAll
    static void deployBothConfigurations(Vertx sharedVertx) throws Exception {
        vertx = sharedVertx;
        client = WebClient.create(vertx);
        JWTAuth minter = SharedDeployment.jwtAuth(vertx);
        alice = SharedDeployment.alice(minter);
        bob = SharedDeployment.bob(minter);
        carol = SharedDeployment.carol(minter);
        foreignIssuer = SharedDeployment.foreignIssuer(minter);
        configured = Deployment.start("configured", SharedDeployment.configured());
        unconfigured = Deployment.start("without apidocs configuration", SharedDeployment.withoutApidocs());
    }

    @AfterAll
    static void closeTheClientThenUndeploy() throws Exception {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            try {
                if (configured != null) {
                    StartupDeployments.undeploy(vertx, configured.outcome());
                }
            } finally {
                if (unconfigured != null) {
                    StartupDeployments.undeploy(vertx, unconfigured.outcome());
                }
            }
        }
    }

    @BeforeEach
    void resetObservations() {
        configured.observations().reset();
        unconfigured.observations().reset();
    }

    /**
     * In both deployments, each form of the management document answers anonymous callers 401 and
     * {@code bob} 403, never 304 and without document content or entity tag, and serves {@code
     * alice}, with conditional requests answered 304 only for her.
     */
    @Test
    @DisplayName("The management document is served only to a caller holding the role, whatever the configuration")
    void managementDocumentServedOnlyToListedRole() throws Exception {
        // Given: both deployments started, and each form's bytes and entity tag read once by alice
        configured.requireStarted();
        unconfigured.requireStarted();
        List<Executable> checks = new ArrayList<>();
        for (Deployment deployment : List.of(configured, unconfigured)) {
            for (Form form : Form.values()) {
                Exchange baseline = send(deployment.target(), HttpMethod.GET, form.path(), alice, Map.of());
                assertEquals(200, baseline.status(), () -> deployment.label() + " " + form + ": alice's first read");
                String tag = baseline.header("ETag");
                assertNotNull(tag, () -> deployment.label() + " " + form + ": alice's first read carries an ETag");
                Buffer bytes = baseline.body();

                // When: every row of (method, caller, conditional) is requested
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                    for (Caller caller : callers()) {
                        for (boolean conditional : List.of(false, true)) {
                            Map<String, String> headers = conditional ? Map.of("If-None-Match", tag) : Map.of();
                            Exchange exchange = send(deployment.target(), method, form.path(), caller.token(), headers);
                            String row = deployment.label() + " | " + form + " | " + method + " | " + caller.name()
                                    + " | " + (conditional ? "If-None-Match" : "unconditional");
                            RowOutcome expected = expectedOutcome(caller, conditional);
                            // Then: the row's outcome holds
                            checks.add(() -> assertRow(row, expected, exchange, method, form, tag, bytes));
                        }
                    }
                }
            }

            // Then: the management JSON discloses the protected validation members; the public one does not
            Exchange management = send(deployment.target(), HttpMethod.GET, MANAGEMENT_JSON, alice, Map.of());
            Exchange publicDocument = send(deployment.target(), HttpMethod.GET, PUBLIC_JSON, null, Map.of());
            String label = deployment.label();
            checks.add(() -> {
                JsonObject validation = rootValidation(label + " management", management);
                assertTrue(validation.containsKey("strategy"), label + ": the management document names its strategy");
                assertTrue(
                        validation.containsKey("enforcement"),
                        label + ": the management document names its enforcement");
            });
            checks.add(() -> {
                assertEquals(200, publicDocument.status(), label + ": the public document is read anonymously");
                JsonObject validation = rootValidation(label + " public", publicDocument);
                assertFalse(validation.containsKey("strategy"), label + ": the public document names no strategy");
                assertFalse(
                        validation.containsKey("enforcement"), label + ": the public document names no enforcement");
            });
        }
        assertAll(checks);
    }

    /**
     * {@code carol}, whose claims the bound validator rejects, and a token from a foreign issuer get
     * the same 401, problem type and title, failure code, and fixture trace from the document as from
     * the twin.
     */
    @Test
    @DisplayName("A claims-validator rejection of a document request matches the resource twin")
    void claimsValidatorRejectionMatchesResourceTwin() throws Exception {
        // Given: the shared deployment, with the claims validator rejecting carol's tenant
        configured.requireStarted();
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("carol", carol);
        tokens.put("foreign issuer", foreignIssuer);
        Map<String, String> expectedCodes =
                Map.of("carol", "JWT_CLAIMS_INVALID", "foreign issuer", "JWT_ISSUER_INVALID");

        List<Executable> checks = new ArrayList<>();
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            // When: the token requests the document and the twin
            Exchange document = send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, token.getValue(), Map.of());
            Exchange twin = send(configured.target(), HttpMethod.GET, TWIN, token.getValue(), Map.of());
            LOG.info(
                    "{}: WWW-Authenticate of the document 401: {}; of the twin 401: {}",
                    token.getKey(),
                    document.headers().getAll("WWW-Authenticate"),
                    twin.headers().getAll("WWW-Authenticate"));

            // Then: both outcomes are the same
            checks.add(() -> assertSameOutcome(token.getKey(), document, twin, expectedCodes.get(token.getKey())));
        }
        assertAll(checks);
    }

    /**
     * Per caller, the document forms and the twin emit the same security events; every document
     * request, denials included, completes as one REST operation completion carrying the very
     * descriptor its route handed the contributors, and the public document read completes as one
     * plain HTTP completion.
     */
    @Test
    @DisplayName("Document requests emit the security and completion events resource requests emit")
    void documentRequestsEmitResourceEvents() throws Exception {
        // Given: the shared deployment with its event recorders
        configured.requireStarted();
        Observations observations = configured.observations();
        List<Executable> checks = new ArrayList<>();
        for (Caller caller : callers()) {
            // When: the two forms and the twin are requested by the caller, one at a time
            Exchange json = send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, caller.token(), Map.of());
            Exchange yaml = send(configured.target(), HttpMethod.GET, MANAGEMENT_YAML, caller.token(), Map.of());
            Exchange twin = send(configured.target(), HttpMethod.GET, TWIN, caller.token(), Map.of());
            int status = caller.expectedStatus();
            String who = caller.name();

            // Then: the statuses are the caller's, and the security events match the twin's
            checks.add(() -> assertEquals(status, json.status(), who + ": JSON status"));
            checks.add(() -> assertEquals(status, yaml.status(), who + ": YAML status"));
            checks.add(() -> assertEquals(status, twin.status(), who + ": twin status"));
            checks.add(() -> assertEquals(
                    securityEvents(twin), securityEvents(json), who + ": JSON security events equal the twin's"));
            checks.add(() -> assertEquals(
                    securityEvents(twin), securityEvents(yaml), who + ": YAML security events equal the twin's"));
            checks.add(() -> assertCallerSecurityEvents(caller, twin));

            // Then: each document request completes as the REST operation of its own descriptor
            checks.add(() -> assertOperationCompletion(who + " JSON", json, observations, JSON_OPERATION));
            checks.add(() -> assertOperationCompletion(who + " YAML", yaml, observations, YAML_OPERATION));
            checks.add(() -> assertOperationCompletion(who + " twin", twin, observations, TwinResource.READ_TWIN));
        }

        // When: the public document is read anonymously
        Exchange publicDocument = send(configured.target(), HttpMethod.GET, PUBLIC_JSON, null, Map.of());

        // Then: it completes as exactly one plain HTTP request
        checks.add(() -> assertEquals(200, publicDocument.status(), "public: status"));
        checks.add(() -> assertEquals(0, publicDocument.events().rest().size(), "public: no REST completion event"));
        checks.add(() -> assertEquals(
                List.of(200),
                publicDocument.events().http().stream()
                        .map(HttpRequestCompletedEvent::statusCode)
                        .toList(),
                "public: exactly one HTTP completion event, status 200"));
        assertAll(checks);
    }

    /**
     * A denied document request ends with the synthetic route's problem body and never reaches the
     * later mount at the same prefix, its failure handler, or an error interceptor, while live
     * controls show each of those counters does count. An anonymous {@code POST} to the document's
     * URL is not answered by the document route: it ends in the later catch-all exactly as one to an
     * unknown document's URL does.
     */
    @Test
    @DisplayName("A document denial ends on the documentation route and never reaches a later mount")
    void denialNeverReachesLaterMount() throws Exception {
        // Given: the shared deployment with its later mount at /apidocs/* and the counting interceptor
        configured.requireStarted();
        Observations observations = configured.observations();

        // When: both forms are requested anonymously and by bob
        List<Exchange> denials = List.of(
                send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, null, Map.of()),
                send(configured.target(), HttpMethod.GET, MANAGEMENT_YAML, null, Map.of()),
                send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, bob, Map.of()),
                send(configured.target(), HttpMethod.GET, MANAGEMENT_YAML, bob, Map.of()));
        List<String> problems = List.of(PROBLEM_401, PROBLEM_401, PROBLEM_403, PROBLEM_403);
        int laterRoutesAfterDenials = observations.laterMountRouteHits();
        int laterManagementRouteAfterDenials = observations.laterMountManagementRouteHits();
        int laterFailureHandlerAfterDenials = observations.laterMountFailureHandlerHits();
        int errorInterceptorAfterDenials = observations.errorInterceptorHits();

        // When: the live controls run: an unknown document, a failing later-mount route, a throwing resource
        send(configured.target(), HttpMethod.GET, UNKNOWN_JSON, alice, Map.of());
        send(configured.target(), HttpMethod.GET, LATER_BOOM, alice, Map.of());
        send(configured.target(), HttpMethod.GET, TWIN_FAIL, alice, Map.of());
        int laterRoutesAfterControls = observations.laterMountRouteHits();
        int laterFailureHandlerAfterControls = observations.laterMountFailureHandlerHits();
        int errorInterceptorAfterControls = observations.errorInterceptorHits();
        LOG.info(
                "later-mount routes {} -> {}, later-mount management route {}, later failure handler {} -> {},"
                        + " error interceptor {} -> {}",
                laterRoutesAfterDenials,
                laterRoutesAfterControls,
                laterManagementRouteAfterDenials,
                laterFailureHandlerAfterDenials,
                laterFailureHandlerAfterControls,
                errorInterceptorAfterDenials,
                errorInterceptorAfterControls);

        // When: an anonymous POST is sent to the management document's URL, then to an unknown document's
        int catchAllBeforeManagementPost = observations.laterMountCatchAllHits();
        int catchAllUserBeforePosts = observations.laterMountCatchAllUserHits();
        Exchange managementPost = send(configured.target(), HttpMethod.POST, MANAGEMENT_JSON, null, Map.of());
        int catchAllAfterManagementPost = observations.laterMountCatchAllHits();
        Exchange unknownPost = send(configured.target(), HttpMethod.POST, UNKNOWN_JSON, null, Map.of());
        int catchAllAfterUnknownPost = observations.laterMountCatchAllHits();
        int catchAllUserAfterPosts = observations.laterMountCatchAllUserHits();

        // Then: each denial is exactly the problem body with no-store, and nothing after the docs route ran
        List<Executable> checks = new ArrayList<>();
        for (int i = 0; i < denials.size(); i++) {
            Exchange denial = denials.get(i);
            JsonObject problem = new JsonObject(problems.get(i));
            checks.add(() -> assertEquals(
                    problem.getInteger("status").intValue(), denial.status(), denial.label() + ": status"));
            checks.add(() -> assertEquals(problem, json(denial), denial.label() + ": exactly the problem body"));
            checks.add(
                    () -> assertEquals(NO_STORE, denial.header("Cache-Control"), denial.label() + ": Cache-Control"));
            checks.add(() -> assertEquals(List.of(), denial.headers().getAll("Vary"), denial.label() + ": no Vary"));
        }

        // Then: neither POST is answered by the document route; both end in the later mount's catch-all alike
        for (Exchange post : List.of(managementPost, unknownPost)) {
            checks.add(() -> assertEquals(404, post.status(), post.label() + ": status"));
            checks.add(() -> assertEquals(
                    LaterDocsPrefixMount.BODY, post.body().toString(), post.label() + ": the catch-all's body"));
            checks.add(() -> assertNull(json(post), post.label() + ": not a problem body: " + post.body()));
        }
        checks.add(() -> assertEquals(
                unknownPost.status(),
                managementPost.status(),
                "the management POST's status equals the unknown one's"));
        checks.add(() -> assertEquals(
                unknownPost.body(), managementPost.body(), "the management POST's body equals the unknown one's"));
        checks.add(() -> assertEquals(
                1,
                catchAllAfterManagementPost - catchAllBeforeManagementPost,
                "the management POST raised the catch-all count by one"));
        checks.add(() -> assertEquals(
                1,
                catchAllAfterUnknownPost - catchAllAfterManagementPost,
                "the unknown POST raised the catch-all count by one"));
        checks.add(() -> assertEquals(
                0, catchAllUserAfterPosts - catchAllUserBeforePosts, "the catch-all saw no user for either POST"));
        checks.add(() -> assertEquals(0, laterRoutesAfterDenials, "the later mount's routes never ran for a denial"));
        checks.add(() -> assertEquals(
                0, laterManagementRouteAfterDenials, "the later mount's document-URL route never ran for a denial"));
        checks.add(() -> assertEquals(
                0, laterFailureHandlerAfterDenials, "the later mount's failure handler never ran for a denial"));
        checks.add(() -> assertEquals(0, errorInterceptorAfterDenials, "no error interceptor ran for a denial"));

        // Then: the live controls each raised their counter
        checks.add(() -> assertTrue(
                laterRoutesAfterControls >= 1,
                "control: the later mount's routes count (" + laterRoutesAfterControls + ")"));
        checks.add(() -> assertTrue(
                laterFailureHandlerAfterControls >= 1,
                "control: the later mount's failure handler counts (" + laterFailureHandlerAfterControls + ")"));
        checks.add(() -> assertTrue(
                errorInterceptorAfterControls >= 1,
                "control: the error interceptor counts (" + errorInterceptorAfterControls + ")"));
        assertAll(checks);
    }

    /**
     * An application-supplied contributor runs in the document's chain exactly where it runs in the
     * twin's, and its rejection ends both the same way; the probes saw the application name {@code
     * management} on both document routes and the twin.
     */
    @Test
    @DisplayName("An application contributor rejects a document request exactly as it rejects the twin")
    void applicationContributorRejectsLikeResource() throws Exception {
        // Given: the shared deployment with the application contributor at priority 60
        configured.requireStarted();
        Observations observations = configured.observations();
        Map<String, String> marked = Map.of(TraceContributors.REJECT_HEADER, TraceContributors.REJECT_VALUE);

        // When: alice requests the JSON document and the twin, first unmarked, then marked
        Exchange document = send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, alice, Map.of());
        Exchange twin = send(configured.target(), HttpMethod.GET, TWIN, alice, Map.of());
        Exchange rejectedDocument = send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, alice, marked);
        Exchange rejectedTwin = send(configured.target(), HttpMethod.GET, TWIN, alice, marked);
        LOG.info(
                "traces: document {}, twin {}, marked document {}, marked twin {}",
                document.trace(),
                twin.trace(),
                rejectedDocument.trace(),
                rejectedTwin.trace());

        // Then: unmarked, both are served with the full trace in priority order
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(200, document.status(), "unmarked document: status"));
        checks.add(() -> assertTrue(
                json(document) != null && json(document).containsKey("openapi"),
                "unmarked document: the document's bytes are served"));
        checks.add(() -> assertEquals(200, twin.status(), "unmarked twin: status"));
        checks.add(() -> assertEquals(FULL_TRACE, document.trace(), "unmarked document: trace"));
        checks.add(() -> assertEquals(twin.trace(), document.trace(), "unmarked: document and twin traces are equal"));

        // Then: marked, both are rejected with 403 and their traces end at the application contributor
        checks.add(() -> assertEquals(403, rejectedDocument.status(), "marked document: status"));
        checks.add(() -> assertNoDocumentContent("marked document", rejectedDocument));
        checks.add(() -> assertEquals(403, rejectedTwin.status(), "marked twin: status"));
        checks.add(() -> assertEquals(REJECTED_TRACE, rejectedDocument.trace(), "marked document: trace"));
        checks.add(() -> assertEquals(
                rejectedTwin.trace(), rejectedDocument.trace(), "marked: document and twin traces are equal"));

        // Then: the probes saw the application name management on every route of the application
        for (String operationId : List.of(JSON_OPERATION, YAML_OPERATION, TwinResource.READ_TWIN)) {
            List<RestOperationDescriptor> recorded = observations.operations(operationId);
            checks.add(() -> assertFalse(recorded.isEmpty(), operationId + ": the probes recorded its descriptor"));
            checks.add(() -> assertEquals(
                    recorded.stream().map(descriptor -> APPLICATION).toList(),
                    recorded.stream()
                            .map(RestOperationDescriptor::applicationName)
                            .toList(),
                    operationId + ": the recorded application name"));
        }
        assertAll(checks);
    }

    /**
     * A request that reaches the protected document's route on a non-exact path ends there with 404
     * for an authorized caller and 401 for an anonymous one; nothing after the documentation route
     * runs, so the later catch-all never sees an authenticated user.
     */
    @Test
    @DisplayName("The protected terminal never continues on a non-exact path")
    void protectedTerminalNeverContinuesOnANonExactPath() throws Exception {
        // Given: the shared deployment with its later catch-all at /apidocs/* and the counting interceptor
        configured.requireStarted();
        Observations observations = configured.observations();

        // When: alice and an anonymous caller request both forms with a trailing slash
        List<Exchange> aliceRequests = List.of(
                send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON + "/", alice, Map.of()),
                send(configured.target(), HttpMethod.GET, MANAGEMENT_YAML + "/", alice, Map.of()));
        List<Exchange> anonymousRequests = List.of(
                send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON + "/", null, Map.of()),
                send(configured.target(), HttpMethod.GET, MANAGEMENT_YAML + "/", null, Map.of()));
        int catchAllAfterRequests = observations.laterMountCatchAllHits();
        int catchAllUserAfterRequests = observations.laterMountCatchAllUserHits();
        int laterFailureHandlerAfterRequests = observations.laterMountFailureHandlerHits();
        int errorInterceptorAfterRequests = observations.errorInterceptorHits();

        // When: the live control, alice requesting an unknown document
        send(configured.target(), HttpMethod.GET, UNKNOWN_JSON, alice, Map.of());
        int catchAllAfterControl = observations.laterMountCatchAllHits();
        LOG.info(
                "later catch-all {} -> {} (with a user: {}), later failure handler {}, error interceptor {}",
                catchAllAfterRequests,
                catchAllAfterControl,
                catchAllUserAfterRequests,
                laterFailureHandlerAfterRequests,
                errorInterceptorAfterRequests);

        // Then: alice's requests end 404 on the docs route; the anonymous ones end 401 in the chain
        List<Executable> checks = new ArrayList<>();
        JsonObject notFound = new JsonObject(PROBLEM_404);
        for (Exchange request : aliceRequests) {
            checks.add(() -> assertEquals(404, request.status(), request.label() + ": status"));
            checks.add(() -> assertEquals(notFound, json(request), request.label() + ": exactly the problem body"));
            checks.add(
                    () -> assertEquals(NO_STORE, request.header("Cache-Control"), request.label() + ": Cache-Control"));
            checks.add(() -> assertNoDocumentContent(request.label(), request));
            checks.add(() -> assertEquals(List.of(), request.headers().getAll("Vary"), request.label() + ": no Vary"));
        }
        for (Exchange request : anonymousRequests) {
            checks.add(() -> assertEquals(401, request.status(), request.label() + ": status"));
            checks.add(() -> assertNoDocumentContent(request.label(), request));
            checks.add(() -> assertEquals(List.of(), request.headers().getAll("Vary"), request.label() + ": no Vary"));
        }

        // Then: nothing after the docs route ran, and the control shows the catch-all counts
        checks.add(() -> assertEquals(0, catchAllAfterRequests, "the later catch-all never ran"));
        checks.add(() -> assertEquals(0, catchAllUserAfterRequests, "the later catch-all never saw a user"));
        checks.add(
                () -> assertEquals(0, laterFailureHandlerAfterRequests, "the later mount's failure handler never ran"));
        checks.add(() -> assertEquals(0, errorInterceptorAfterRequests, "no error interceptor ran"));
        checks.add(() -> assertEquals(
                1,
                catchAllAfterControl - catchAllAfterRequests,
                "control: the unknown document raised the catch-all count by one"));
        checks.add(
                () -> assertEquals(0, observations.laterMountCatchAllUserHits(), "control: the catch-all saw no user"));
        assertAll(checks);
    }

    /**
     * Variants of a document request never reach the document's bytes without passing the chain, and
     * never answer {@code 304} to a caller the chain denies:
     *
     * <ul>
     *   <li>anonymous callers and {@code bob} sending {@code If-None-Match} as {@code *}, as the weak
     *       form of alice's entity tag, or as a list holding alice's entity tag get their 401 or 403
     *       problem body, never 304 and never an entity tag;
     *   <li>paths that normalize onto the document's URL (a percent-encoded letter, a dot segment, a
     *       doubled slash) never answer an anonymous caller 200 or 304 or any document content, and
     *       answer alice with the outcome recorded per path;
     *   <li>paths that do not match the document's URL (an upper-case prefix, an upper-case document
     *       name, a path parameter) end outside the documentation route for anonymous callers and
     *       alice alike, and the later catch-all never sees a user for an anonymous caller;
     *   <li>an anonymous {@code OPTIONS} to the document's URL answers exactly what one to an unknown
     *       document's URL answers.
     * </ul>
     *
     * <p>Every response other than alice's 200 carries no document content and no entity tag.
     */
    @Test
    @DisplayName("Conditional, path, and method variants of a document request never bypass the security chain")
    void documentVariantsNeverBypassTheChain() throws Exception {
        // Given: the shared deployment, and each form's bytes and entity tag as alice reads them
        configured.requireStarted();
        Observations observations = configured.observations();
        Target target = configured.target();
        Map<Form, Exchange> baselines = new EnumMap<>(Form.class);
        for (Form form : Form.values()) {
            Exchange baseline = send(target, HttpMethod.GET, form.path(), alice, Map.of());
            assertEquals(200, baseline.status(), () -> form + ": alice's first read");
            assertNotNull(baseline.header("ETag"), () -> form + ": alice's first read carries an ETag");
            baselines.put(form, baseline);
        }
        List<Executable> checks = new ArrayList<>();

        // When: anonymous callers and bob send If-None-Match variants that name or cover alice's tag
        for (Form form : Form.values()) {
            String tag = baselines.get(form).header("ETag");
            Map<String, String> conditions = new LinkedHashMap<>();
            conditions.put("wildcard", "*");
            conditions.put("weak form of alice's tag", "W/" + tag);
            conditions.put("alice's tag in a list", tag + ", \"x\"");
            for (Caller caller : callers()) {
                if (caller.expectedStatus() == 200) {
                    continue;
                }
                for (Map.Entry<String, String> condition : conditions.entrySet()) {
                    Exchange exchange = send(
                            target,
                            HttpMethod.GET,
                            form.path(),
                            caller.token(),
                            Map.of("If-None-Match", condition.getValue()));
                    String row = form + " | " + caller.name() + " | If-None-Match " + condition.getKey();
                    String problem = caller.expectedStatus() == 401 ? PROBLEM_401 : PROBLEM_403;
                    // Then: the caller's denial, never 304
                    checks.add(() -> assertEquals(caller.expectedStatus(), exchange.status(), row + ": status"));
                    checks.add(() -> assertEquals(new JsonObject(problem), json(exchange), row + ": the problem body"));
                    checks.add(() -> assertNoDocumentContent(row, exchange));
                }
            }
        }

        // When: paths that normalize onto the document's URL are requested anonymously and by alice
        Exchange jsonBaseline = baselines.get(Form.JSON);
        for (Map.Entry<String, VariantOutcome> variant : NORMALIZING_VARIANTS.entrySet()) {
            String path = variant.getKey();
            Exchange anonymous = send(target, HttpMethod.GET, path, null, Map.of());
            Exchange byAlice = send(target, HttpMethod.GET, path, alice, Map.of());
            LOG.info("{}: anonymous {}, alice {}", path, anonymous.status(), byAlice.status());
            // Then: the anonymous caller is never served; alice gets the outcome recorded for the path
            checks.add(() -> assertNeverServed(path + " anonymous", anonymous));
            checks.add(() -> assertVariant(path + " alice", variant.getValue(), byAlice, null, jsonBaseline));
        }

        // When: paths that do not match the document's URL are requested anonymously and by alice
        for (Map.Entry<String, VariantOutcome> variant : NON_MATCHING_VARIANTS.entrySet()) {
            String path = variant.getKey();
            int catchAllBeforeAnonymous = observations.laterMountCatchAllHits();
            int catchAllUserBeforeAnonymous = observations.laterMountCatchAllUserHits();
            Exchange anonymous = send(target, HttpMethod.GET, path, null, Map.of());
            int anonymousCatchAll = observations.laterMountCatchAllHits() - catchAllBeforeAnonymous;
            int anonymousCatchAllUser = observations.laterMountCatchAllUserHits() - catchAllUserBeforeAnonymous;
            int catchAllBeforeAlice = observations.laterMountCatchAllHits();
            Exchange byAlice = send(target, HttpMethod.GET, path, alice, Map.of());
            int aliceCatchAll = observations.laterMountCatchAllHits() - catchAllBeforeAlice;
            LOG.info("{}: anonymous {}, alice {}", path, anonymous.status(), byAlice.status());
            // Then: both end outside the documentation route, and the catch-all saw no anonymous user
            checks.add(() ->
                    assertVariant(path + " anonymous", variant.getValue(), anonymous, anonymousCatchAll, jsonBaseline));
            checks.add(() -> assertEquals(0, anonymousCatchAllUser, path + " anonymous: the catch-all saw no user"));
            checks.add(() -> assertVariant(path + " alice", variant.getValue(), byAlice, aliceCatchAll, jsonBaseline));
        }

        // When: an anonymous OPTIONS is sent to the document's URL, then to an unknown document's URL
        Exchange managementOptions = send(target, HttpMethod.OPTIONS, MANAGEMENT_JSON, null, Map.of());
        Exchange unknownOptions = send(target, HttpMethod.OPTIONS, UNKNOWN_JSON, null, Map.of());
        LOG.info(
                "OPTIONS: management {} {}, unknown {} {}",
                managementOptions.status(),
                managementOptions.body(),
                unknownOptions.status(),
                unknownOptions.body());

        // Then: both answer alike, and neither carries document content
        checks.add(() -> assertEquals(
                unknownOptions.status(),
                managementOptions.status(),
                "OPTIONS: the management status equals the unknown"));
        checks.add(() -> assertEquals(
                unknownOptions.body(), managementOptions.body(), "OPTIONS: the management body equals the unknown"));
        checks.add(() -> assertNoDocumentContent(managementOptions.label(), managementOptions));
        checks.add(() -> assertNoDocumentContent(unknownOptions.label(), unknownOptions));
        assertAll(checks);
    }

    /**
     * A second graph whose store holds no document answers an authorized document request 503 on the
     * documentation route and never continues to the next route; the shared deployment, whose store
     * holds the document, still serves it.
     */
    @Test
    @DisplayName("A protected document missing from the store fails closed")
    void missingStoreEntryFailsClosed() throws Exception {
        // Given: a store-less documentation router on its own server, ahead of a counting catch-all
        configured.requireStarted();
        StorelessServer storeless = startStorelessDocsServer();
        try {
            // When: alice, then an anonymous caller, request the JSON document on that server
            Exchange authorized = send(storeless.target(), HttpMethod.GET, MANAGEMENT_JSON, alice, Map.of());
            Exchange anonymous = send(storeless.target(), HttpMethod.GET, MANAGEMENT_JSON, null, Map.of());
            int catchAll = storeless.catchAllHits().get();
            // When: alice requests the same document from the shared deployment
            Exchange shared = send(configured.target(), HttpMethod.GET, MANAGEMENT_JSON, alice, Map.of());

            // Then: 503 after the chain, 401 without credentials, nothing continued, the shared store serves
            assertAll(
                    () -> assertEquals(503, authorized.status(), "alice on the store-less server: status"),
                    () -> assertEquals(
                            new JsonObject(PROBLEM_503), json(authorized), "alice: exactly the problem body"),
                    () -> assertEquals(NO_STORE, authorized.header("Cache-Control"), "alice: Cache-Control"),
                    () -> assertNull(authorized.header("ETag"), "alice: no ETag"),
                    () -> assertEquals(List.of(), authorized.headers().getAll("Vary"), "alice: no Vary"),
                    () -> assertEquals(401, anonymous.status(), "anonymous on the store-less server: status"),
                    () -> assertEquals(List.of(), anonymous.headers().getAll("Vary"), "anonymous: no Vary"),
                    () -> assertEquals(0, catchAll, "the catch-all after the documentation router never ran"),
                    () -> assertEquals(200, shared.status(), "alice on the shared deployment: status"),
                    () -> assertTrue(
                            json(shared) != null && json(shared).containsKey("openapi"),
                            "alice on the shared deployment: the document is served"));
        } finally {
            storeless.close();
        }
    }

    // --- Row expectations ---

    /** The outcome a row of the access table expects. */
    private enum RowOutcome {
        UNAUTHORIZED,
        FORBIDDEN,
        SERVED,
        NOT_MODIFIED
    }

    /** A form of the management document. */
    private enum Form {
        JSON(MANAGEMENT_JSON, JSON_TYPE),
        YAML(MANAGEMENT_YAML, YAML_TYPE);

        private final String path;
        private final String contentType;

        Form(String path, String contentType) {
            this.path = path;
            this.contentType = contentType;
        }

        String path() {
            return path;
        }

        String contentType() {
            return contentType;
        }
    }

    /**
     * A caller.
     *
     * @param name           the caller's name
     * @param token          the bearer token, or {@code null} for an anonymous caller
     * @param expectedStatus the status an unconditional document or twin read answers the caller
     */
    private record Caller(String name, String token, int expectedStatus) {}

    private static List<Caller> callers() {
        return List.of(
                new Caller("anonymous", null, 401), new Caller("bob", bob, 403), new Caller("alice", alice, 200));
    }

    private static RowOutcome expectedOutcome(Caller caller, boolean conditional) {
        return switch (caller.expectedStatus()) {
            case 401 -> RowOutcome.UNAUTHORIZED;
            case 403 -> RowOutcome.FORBIDDEN;
            default -> conditional ? RowOutcome.NOT_MODIFIED : RowOutcome.SERVED;
        };
    }

    private static void assertRow(
            String row,
            RowOutcome expected,
            Exchange exchange,
            HttpMethod method,
            Form form,
            String tag,
            Buffer bytes) {
        boolean get = method == HttpMethod.GET;
        switch (expected) {
            case UNAUTHORIZED, FORBIDDEN -> {
                int status = expected == RowOutcome.UNAUTHORIZED ? 401 : 403;
                assertEquals(status, exchange.status(), row + ": status");
                assertNull(exchange.header("ETag"), row + ": a denial carries no ETag");
                assertFalse(exchange.trace().contains(PROBE400), row + ": the last contributor never ran");
                if (get) {
                    String problem = status == 401 ? PROBLEM_401 : PROBLEM_403;
                    assertEquals(new JsonObject(problem), json(exchange), row + ": the problem body");
                    assertNoDocumentContent(row, exchange);
                } else {
                    assertEquals(0, exchange.body().length(), row + ": HEAD has no body");
                }
            }
            case SERVED -> {
                assertEquals(200, exchange.status(), row + ": status");
                assertEquals(tag, exchange.header("ETag"), row + ": the form's entity tag");
                assertTrue(tag.startsWith("\"") && !tag.startsWith("W/"), row + ": a strong entity tag: " + tag);
                if (get) {
                    assertEquals(form.contentType(), exchange.header("Content-Type"), row + ": content type");
                    assertEquals(bytes, exchange.body(), row + ": the same bytes as the first read");
                } else {
                    assertEquals(0, exchange.body().length(), row + ": HEAD has no body");
                }
            }
            case NOT_MODIFIED -> {
                assertEquals(304, exchange.status(), row + ": status");
                assertEquals(0, exchange.body().length(), row + ": 304 has no body");
            }
        }
    }

    // --- Path variants ---

    /** How a variant of the document's path is answered. */
    private enum VariantOutcome {
        /** 200 with the bytes and entity tag of alice's read of the JSON form. */
        SERVED,
        /** The documentation route's 404 problem body. */
        DOCUMENT_NOT_FOUND,
        /** The later mount's catch-all: 404 with its body, raising its count by one. */
        LATER_CATCH_ALL,
        /** The root router's own 404, reached by no route of the later mount. */
        ROUTER_NOT_FOUND
    }

    /** The body of the root router's own 404, when no route handles a request. */
    private static final String ROUTER_NOT_FOUND_BODY = "<html><body><h1>Resource not found</h1></body></html>";

    /**
     * Paths that normalize onto the JSON document's URL, with alice's outcome. An anonymous request
     * to any of them must never be served, whatever alice's outcome.
     */
    private static final Map<String, VariantOutcome> NORMALIZING_VARIANTS = orderedVariants(
            "/apidocs/%6Danagement/openapi.json", VariantOutcome.SERVED,
            "/apidocs/public/../management/openapi.json", VariantOutcome.SERVED,
            "/apidocs//management/openapi.json", VariantOutcome.SERVED);

    /** Paths that do not match the JSON document's URL, with the outcome for every caller. */
    private static final Map<String, VariantOutcome> NON_MATCHING_VARIANTS = orderedVariants(
            "/APIDOCS/management/openapi.json", VariantOutcome.ROUTER_NOT_FOUND,
            "/apidocs/Management/openapi.json", VariantOutcome.LATER_CATCH_ALL,
            "/apidocs/management/openapi.json;x", VariantOutcome.LATER_CATCH_ALL);

    private static Map<String, VariantOutcome> orderedVariants(
            String first,
            VariantOutcome firstOutcome,
            String second,
            VariantOutcome secondOutcome,
            String third,
            VariantOutcome thirdOutcome) {
        Map<String, VariantOutcome> variants = new LinkedHashMap<>();
        variants.put(first, firstOutcome);
        variants.put(second, secondOutcome);
        variants.put(third, thirdOutcome);
        return variants;
    }

    /**
     * Asserts a variant's outcome. {@code catchAllDelta} is the rise of the later catch-all's count
     * across the request, or {@code null} when it was not measured.
     */
    private static void assertVariant(
            String label, VariantOutcome expected, Exchange exchange, Integer catchAllDelta, Exchange baseline) {
        switch (expected) {
            case SERVED -> {
                assertEquals(200, exchange.status(), label + ": status");
                assertEquals(baseline.header("ETag"), exchange.header("ETag"), label + ": alice's entity tag");
                assertEquals(baseline.body(), exchange.body(), label + ": the bytes of alice's read");
            }
            case DOCUMENT_NOT_FOUND -> {
                assertEquals(404, exchange.status(), label + ": status");
                assertEquals(new JsonObject(PROBLEM_404), json(exchange), label + ": the problem body");
                assertNoDocumentContent(label, exchange);
            }
            case LATER_CATCH_ALL -> {
                assertEquals(404, exchange.status(), label + ": status");
                assertEquals(LaterDocsPrefixMount.BODY, exchange.body().toString(), label + ": the catch-all's body");
                assertNoDocumentContent(label, exchange);
                if (catchAllDelta != null) {
                    assertEquals(1, catchAllDelta.intValue(), label + ": the catch-all count rose by one");
                }
            }
            case ROUTER_NOT_FOUND -> {
                assertEquals(404, exchange.status(), label + ": status");
                assertEquals(ROUTER_NOT_FOUND_BODY, exchange.body().toString(), label + ": the router's own 404");
                assertNoDocumentContent(label, exchange);
                if (catchAllDelta != null) {
                    assertEquals(0, catchAllDelta.intValue(), label + ": the catch-all never ran");
                }
            }
        }
    }

    /** Asserts a response neither serves nor revalidates the document. */
    private static void assertNeverServed(String label, Exchange exchange) {
        assertFalse(exchange.status() == 200, label + ": never 200, body: " + exchange.body());
        assertFalse(exchange.status() == 304, label + ": never 304");
        assertNoDocumentContent(label, exchange);
    }

    // --- Shared assertions ---

    private static void assertSameOutcome(String who, Exchange document, Exchange twin, String failureCode) {
        JsonObject documentProblem = json(document);
        JsonObject twinProblem = json(twin);
        assertAll(
                () -> assertEquals(401, document.status(), who + ": document status"),
                () -> assertEquals(401, twin.status(), who + ": twin status"),
                () -> assertNotNull(documentProblem, who + ": the document answers a problem body"),
                () -> assertNotNull(twinProblem, who + ": the twin answers a problem body"),
                () -> assertEquals(
                        twinProblem == null ? null : twinProblem.getValue("type"),
                        documentProblem == null ? null : documentProblem.getValue("type"),
                        who + ": problem type"),
                () -> assertEquals(
                        twinProblem == null ? null : twinProblem.getValue("title"),
                        documentProblem == null ? null : documentProblem.getValue("title"),
                        who + ": problem title"),
                () -> assertNoDocumentContent(who + " document", document),
                () -> assertEquals(rejectionCodes(twin), rejectionCodes(document), who + ": failure codes"),
                () -> assertTrue(
                        rejectionCodes(document).contains(failureCode),
                        who + ": the document's failure code is " + failureCode + ", was " + rejectionCodes(document)),
                () -> assertEquals(twin.trace(), document.trace(), who + ": traces"),
                () -> assertFalse(document.trace().contains(PROBE400), who + ": the last contributor never ran"));
    }

    private static void assertCallerSecurityEvents(Caller caller, Exchange twin) {
        List<String> events = securityEvents(twin);
        switch (caller.expectedStatus()) {
            case 401 ->
                assertTrue(
                        events.contains("rejected BEARER_MISSING"),
                        caller.name() + ": a missing bearer credential is rejected, events " + events);
            case 403 ->
                assertTrue(
                        events.contains("deny ROLE_MISSING"),
                        caller.name() + ": the authorization decision denies for the missing role, events " + events);
            default ->
                assertTrue(
                        events.stream().anyMatch(event -> event.startsWith("permit "))
                                && events.stream().noneMatch(event -> event.startsWith("deny ")),
                        caller.name() + ": the authorization decision permits, events " + events);
        }
    }

    private static void assertOperationCompletion(
            String label, Exchange exchange, Observations observations, String operationId) {
        List<RestOperationDescriptor> recorded = observations.operations(operationId);
        List<RestRequestCompletedEvent> rest = exchange.events().rest();
        assertEquals(
                1,
                recorded.size(),
                label + ": every probe of " + operationId + " received one and the same descriptor, recorded "
                        + recorded.size());
        assertEquals(1, rest.size(), label + ": exactly one REST completion event, was " + rest.size());
        assertEquals(0, exchange.events().http().size(), label + ": no HTTP completion event");
        RestRequestCompletedEvent event = rest.getFirst();
        assertSame(recorded.getFirst(), event.operation(), label + ": the event carries the route's own descriptor");
        assertEquals(operationId, event.operation().operationId(), label + ": operation id");
        assertEquals(APPLICATION, event.operation().applicationName(), label + ": application name");
        assertEquals(exchange.status(), event.statusCode(), label + ": the event's status is the response's");
    }

    private static void assertNoDocumentContent(String label, Exchange exchange) {
        String body = exchange.body().toString();
        assertFalse(body.contains("openapi"), label + ": no document content: " + body);
        assertFalse(body.contains(TwinResource.TWIN_PATH), label + ": no operation path: " + body);
        assertNull(exchange.header("ETag"), label + ": no ETag");
    }

    private static List<String> securityEvents(Exchange exchange) {
        List<String> summary = new ArrayList<>();
        for (Object event : exchange.events().security()) {
            if (event instanceof CredentialRejectedEvent rejected) {
                summary.add("rejected " + rejected.reasonCode());
            } else if (event instanceof AuthorizationDecisionEvent decided) {
                summary.add((decided.decision().permitted() ? "permit " : "deny ")
                        + decided.decision().reasonCode());
            }
        }
        return summary;
    }

    private static List<String> rejectionCodes(Exchange exchange) {
        return exchange.events().security().stream()
                .filter(CredentialRejectedEvent.class::isInstance)
                .map(event -> ((CredentialRejectedEvent) event).reasonCode())
                .toList();
    }

    private static JsonObject rootValidation(String label, Exchange exchange) {
        JsonObject document = json(exchange);
        assertNotNull(document, label + ": the JSON document parses");
        JsonObject validation = document.getJsonObject(VALIDATION_MEMBER);
        assertNotNull(validation, label + ": the document has a root " + VALIDATION_MEMBER);
        return validation;
    }

    private static JsonObject json(Exchange exchange) {
        try {
            Object value = exchange.body().toJsonValue();
            return value instanceof JsonObject object ? object : null;
        } catch (DecodeException notJson) {
            return null;
        }
    }

    // --- Requests ---

    /**
     * Where a request is sent.
     *
     * @param label        the server's label
     * @param port         the server's port
     * @param observations the observation hub of the graph serving the port
     */
    private record Target(String label, int port, Observations observations) {}

    /**
     * One request and what it produced.
     *
     * @param label   the request's description
     * @param status  the response status
     * @param headers the response headers
     * @param body    the response body, empty when there was none
     * @param trace   the fixture contributors that ran, in order
     * @param events  the events the request produced
     */
    private record Exchange(
            String label, int status, MultiMap headers, Buffer body, List<String> trace, Observations.Drained events) {

        String header(String name) {
            return headers.get(name);
        }
    }

    /**
     * Sends one request and waits until the server closed its lifecycle, then reads its trace and
     * drains its events.
     */
    private static Exchange send(
            Target target, HttpMethod method, String path, String token, Map<String, String> headers) throws Exception {
        String key = "request-" + REQUESTS.incrementAndGet();
        String label = target.label() + " " + method + " " + path + (token == null ? " anonymous" : "")
                + (headers.isEmpty() ? "" : " " + headers.keySet());
        CompletableFuture<Void> barrier = target.observations().expect(key);
        HttpRequest<Buffer> request =
                client.request(method, target.port(), HOST, path).putHeader(Observations.REQUEST_HEADER, key);
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        headers.forEach(request::putHeader);
        HttpResponse<Buffer> response = Futures.await(request.send(), Duration.ofSeconds(BOUND_SECONDS));
        try {
            barrier.get(BOUND_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError(
                    label + ": the request's lifecycle did not close within " + BOUND_SECONDS + "s", timeout);
        }
        Buffer body = response.body() == null ? Buffer.buffer() : response.body();
        return new Exchange(
                label,
                response.statusCode(),
                response.headers(),
                body,
                target.observations().trace(key),
                target.observations().drain());
    }

    // --- Deployments ---

    /**
     * One deployment of the shared graph.
     *
     * @param label     the deployment's label
     * @param component the graph
     * @param outcome   the deployment's outcome
     */
    private record Deployment(
            String label, ProtectedDocumentTestComponents.SharedComponent component, Outcome outcome) {

        static Deployment start(String label, JsonObject config) throws Exception {
            ProtectedDocumentTestComponents.SharedComponent component =
                    DaggerProtectedDocumentTestComponents_SharedComponent.factory()
                            .create(vertx, config);
            Outcome outcome = StartupDeployments.deploy(vertx, component::httpVerticle);
            return new Deployment(label, component, outcome);
        }

        void requireStarted() {
            assertNull(outcome.failure(), () -> "the deployment '" + label + "' failed to start: " + outcome.failure());
            assertNotNull(outcome.port(), () -> "the deployment '" + label + "' published no port");
        }

        Observations observations() {
            return component.observations();
        }

        Target target() {
            return new Target(label, outcome.port(), component.observations());
        }
    }

    /**
     * The store-less documentation router's server.
     *
     * @param server       the server
     * @param observations the observation hub of the store-less graph
     * @param catchAllHits the invocations of the catch-all route after the documentation router
     */
    private record StorelessServer(HttpServer server, Observations observations, AtomicInteger catchAllHits) {

        Target target() {
            return new Target("store-less", server.actualPort(), observations);
        }

        void close() throws Exception {
            Futures.await(server.close(), Duration.ofSeconds(BOUND_SECONDS));
        }
    }

    /**
     * Builds a second graph of the shared configuration whose JAX-RS mounts are never built, so its
     * store holds no document; marks its documentation mount by running its composition validators
     * over its mounts in the order the HTTP verticle sorts them; creates the documentation router;
     * and serves it at {@code /apidocs/*} on a new server on {@code 127.0.0.1}, behind the graph's
     * ROOT middlewares in their framework order and ahead of a counting catch-all route.
     */
    private static StorelessServer startStorelessDocsServer() throws Exception {
        ProtectedDocumentTestComponents.SharedComponent component =
                DaggerProtectedDocumentTestComponents_SharedComponent.factory()
                        .create(vertx, SharedDeployment.configured());
        List<RouterMount> mounts = component.routerMounts().stream()
                .sorted(Comparator.comparing(RouterMount::phase)
                        .thenComparingInt(RouterMount::priority)
                        .thenComparing(RouterMount::mountPath)
                        .thenComparing(RouterMount::orderKey))
                .toList();
        List<String> violations = new ArrayList<>();
        for (MountCompositionValidator validator : component.mountCompositionValidators()) {
            violations.addAll(validator.validate(mounts));
        }
        assertEquals(List.of(), violations, "the store-less graph's composition is valid");
        List<DocsRouterMount> docsMounts = mounts.stream()
                .filter(DocsRouterMount.class::isInstance)
                .map(DocsRouterMount.class::cast)
                .toList();
        assertEquals(1, docsMounts.size(), "the store-less graph holds one documentation mount");
        DocsRouterMount docsMount = docsMounts.getFirst();
        assertTrue(docsMount.isValidated(), "the composition validators marked the documentation mount");

        Router docsRouter;
        try {
            docsRouter = Futures.await(docsMount.createRouter(vertx), Duration.ofSeconds(BOUND_SECONDS));
        } catch (RuntimeException | AssertionError failure) {
            throw new AssertionError(
                    "the store-less documentation mount failed to create its router: " + failure, failure);
        }
        Router root = Router.router(vertx);
        component.middlewares().stream()
                .filter(middleware -> middleware.scope() == MiddlewareScope.ROOT)
                .sorted(OrderedExtension.comparator())
                .forEach(middleware -> root.route(middleware.path()).handler(middleware));
        root.route(docsMount.mountPath()).subRouter(docsRouter);
        AtomicInteger catchAllHits = new AtomicInteger();
        root.route().handler(ctx -> {
            catchAllHits.incrementAndGet();
            ctx.response().setStatusCode(404).end("catch-all");
        });
        HttpServer server = Futures.await(
                vertx.createHttpServer().requestHandler(root).listen(0, HOST), Duration.ofSeconds(BOUND_SECONDS));
        return new StorelessServer(server, component.observations(), catchAllHits);
    }
}
