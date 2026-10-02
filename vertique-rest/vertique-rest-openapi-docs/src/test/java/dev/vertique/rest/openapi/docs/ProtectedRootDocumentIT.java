// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.DecisionRecorder;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.RootApplicationModule;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.root.StatusResource;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments;
import dev.vertique.rest.openapi.docs.fixture.startup.StartupDeployments.Outcome;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.MultiMap;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;

/**
 * Integration proof that the protected document of a root application, declared at {@code /} with
 * discovery membership, is served through the same security chain as a resource with the same
 * annotations, and that a protected document listing no role logs one startup notice naming its
 * scheme.
 *
 * <p>Two components hold the root application {@code internal} as their sole declaration, with
 * {@code GET /status} (scheme {@code bearerAuth}, role {@code admin}) as its only resource and the
 * real JWT authentication module: {@code rolesPresent} declares {@code @ApiDocs(access = PROTECTED,
 * securityScheme = "bearerAuth", rolesAllowed = {"admin"})}, {@code rolesAbsent} the same without
 * {@code rolesAllowed}. The callers are anonymous, {@code alice} (role {@code admin}), and {@code
 * bob} (role {@code user}), with tokens signed by the key the component's JWT provider verifies.
 *
 * <p>Each test deploys its own component, so each outcome stands on its own. The class timeout is 30
 * seconds because one test deploys two compositions, each awaited for up to ten seconds. Cleanup
 * closes the client before undeploying; the warning appender is detached after each test. Expected
 * values are fixed literals.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ProtectedRootDocumentIT {

    private static final String HOST = "127.0.0.1";

    /** The JSON document URL of the root application {@code internal}. */
    private static final String JSON_URL = "/apidocs/internal/openapi.json";

    /** The YAML document URL of the root application {@code internal}. */
    private static final String YAML_URL = "/apidocs/internal/openapi.yaml";

    /** The documentation module's warning logger, which also carries its startup notices. */
    private static final String WARNINGS_LOGGER = "dev.vertique.rest.openapi.docs.DocumentWarnings";

    /** The configuration path every notice about the {@code internal} document starts with. */
    private static final String INTERNAL_DOCUMENT = "apidocs.documents.internal";

    /** The statement of the notice about a protected document that lists no role. */
    private static final String READABLE_BY_ANY_PRINCIPAL =
            "readable by any principal the 'bearerAuth' handler authenticates";

    /** The problem body of an anonymous request for a protected document. */
    private static final JsonObject UNAUTHORIZED_PROBLEM =
            new JsonObject("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}");

    /** The problem body of a request for a protected document by a caller lacking the role. */
    private static final JsonObject FORBIDDEN_PROBLEM =
            new JsonObject("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}");

    /** The content type of a problem body. */
    private static final String PROBLEM_TYPE = "application/problem+json";

    /** What a denied response may never contain: document content. */
    private static final List<String> DOCUMENT_FRAGMENTS = List.of("openapi", "/status");

    /** The longest one request is awaited. */
    private static final long REQUEST_SECONDS = 5;

    /** The longest an authorization decision is awaited after its request completed. */
    private static final long DECISION_MILLIS = 5_000;

    /** The two forms of the document. */
    private enum Form {
        /** The JSON form. */
        JSON(JSON_URL, "application/json"),

        /** The YAML form. */
        YAML(YAML_URL, "application/yaml");

        /** The form's document URL. */
        final String url;

        /** The content type the form is served with. */
        final String contentType;

        Form(String url, String contentType) {
            this.url = url;
            this.contentType = contentType;
        }
    }

    /** A caller of the document; {@code ANONYMOUS} sends no credentials. */
    private enum Caller {
        /** A caller sending no credentials. */
        ANONYMOUS,

        /** {@code alice}, holding the role {@code admin}. */
        ALICE,

        /** {@code bob}, holding only the role {@code user}. */
        BOB
    }

    /**
     * One response, read whole.
     *
     * @param status  the status code
     * @param headers the response headers
     * @param body    the body, or {@code null} when there was none
     */
    private record Reply(int status, MultiMap headers, Buffer body) {

        /**
         * Returns the body as a string.
         *
         * @return the body, or the empty string when there was none
         */
        String text() {
            return body == null ? "" : body.toString();
        }

        /**
         * Reports whether the response had no body bytes.
         *
         * @return {@code true} when the body is absent or empty
         */
        boolean bodyless() {
            return body == null || body.length() == 0;
        }
    }

    private Vertx vertx;
    private WebClient client;
    private final List<Outcome> deployments = new ArrayList<>();
    private Logger warningsLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp(Vertx testVertx) {
        vertx = testVertx;
        client = WebClient.create(vertx, new WebClientOptions().setDefaultHost(HOST));
        warningsLogger = (Logger) LoggerFactory.getLogger(WARNINGS_LOGGER);
        previousLevel = warningsLogger.getLevel();
        warningsLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        warningsLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (client != null) {
                client.close();
                client = null;
            }
            for (Outcome deployment : deployments) {
                StartupDeployments.undeploy(vertx, deployment);
            }
            deployments.clear();
        } finally {
            warningsLogger.detachAppender(appender);
            appender.stop();
            warningsLogger.setLevel(previousLevel);
        }
    }

    /**
     * The protected document of the root application answers exactly as a resource with the same
     * annotations: anonymous callers get 401 and callers lacking the role 403 for every form,
     * method, and conditional request, never 304, with a problem body and no entity tag; the
     * holder of the role reads both forms, with {@code HEAD} and conditional requests; and the
     * application's own resource keeps serving the role holder.
     */
    @Test
    @DisplayName("A root application's protected document is served only to a caller holding the listed role")
    void rootApplicationDocumentProtectedAlike() throws Exception {
        // Given: the root application internal, its document guarded by bearerAuth and the admin role
        ProtectedRootTestComponents.RolesPresentComponent component =
                DaggerProtectedRootTestComponents_RolesPresentComponent.factory()
                        .create(vertx, DocsConfigs.loopback());
        Outcome rolesPresent = deploy(component::httpVerticle, new DeploymentOptions());
        assertNull(rolesPresent.failure(), () -> "the deployment failed: " + rolesPresent.failure());
        assertNotNull(rolesPresent.port(), "the deployment published no port");
        int port = rolesPresent.port();
        Map<Caller, String> tokens = tokens();

        // When: alice reads each form twice, which also yields this deployment's entity tags
        List<Executable> checks = new ArrayList<>();
        Map<Form, String> tags = new LinkedHashMap<>();
        for (Form form : Form.values()) {
            Reply first = request(port, HttpMethod.GET, form.url, tokens.get(Caller.ALICE), null);
            Reply second = request(port, HttpMethod.GET, form.url, tokens.get(Caller.ALICE), null);
            String tag = first.headers().get("ETag");
            tags.put(form, tag);
            String label = form + " GET by ALICE";

            // Then: 200 with the form's content type, a strong tag, and the same document bytes each time
            checks.add(() -> assertEquals(200, first.status(), label + ": status"));
            checks.add(() -> assertTrue(
                    first.headers().get("Content-Type") != null
                            && first.headers().get("Content-Type").startsWith(form.contentType),
                    () -> label + ": content type " + first.headers().get("Content-Type")));
            checks.add(() -> assertTrue(
                    tag != null && tag.startsWith("\"") && tag.endsWith("\""),
                    () -> label + ": not a strong entity tag: " + tag));
            checks.add(() -> assertTrue(
                    first.text().contains("openapi") && first.text().contains(StatusResource.ROUTE),
                    () -> label + ": the body is not the document of internal: " + first.text()));
            checks.add(() -> assertEquals(200, second.status(), label + " again: status"));
            checks.add(() -> assertEquals(first.body(), second.body(), label + ": bytes differ across reads"));
            checks.add(() -> assertEquals(tag, second.headers().get("ETag"), label + ": tag differs across reads"));
        }

        // When: alice sends HEAD, and GET and HEAD carrying the correct tag
        for (Form form : Form.values()) {
            String tag = tags.get(form);
            Reply head = request(port, HttpMethod.HEAD, form.url, tokens.get(Caller.ALICE), null);
            String headLabel = form + " HEAD by ALICE";
            // Then: HEAD is 200 with the same tag and no body
            checks.add(() -> assertEquals(200, head.status(), headLabel + ": status"));
            checks.add(() -> assertEquals(tag, head.headers().get("ETag"), headLabel + ": tag"));
            checks.add(() -> assertTrue(head.bodyless(), () -> headLabel + ": has a body: " + head.text()));
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                Reply conditional = request(port, method, form.url, tokens.get(Caller.ALICE), tag);
                String label = form + " " + method + " by ALICE with If-None-Match";
                // Then: a matching If-None-Match is answered 304 with the tag and no body
                checks.add(() -> assertEquals(304, conditional.status(), label + ": status"));
                checks.add(() -> assertEquals(tag, conditional.headers().get("ETag"), label + ": tag"));
                checks.add(
                        () -> assertTrue(conditional.bodyless(), () -> label + ": has a body: " + conditional.text()));
            }
        }

        // When: anonymous callers and bob send every form, method, and conditional combination
        for (Form form : Form.values()) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
                for (Caller caller : List.of(Caller.ANONYMOUS, Caller.BOB)) {
                    for (boolean conditional : List.of(false, true)) {
                        Reply reply = request(
                                port, method, form.url, tokens.get(caller), conditional ? tags.get(form) : null);
                        String label =
                                form + " " + method + " by " + caller + (conditional ? " with If-None-Match" : "");
                        // Then: 401 or 403, never 304, without an entity tag or document content
                        addDenialChecks(checks, label, reply, method, caller == Caller.ANONYMOUS);
                    }
                }
            }
        }

        // When: alice calls the application's own resource
        Reply status = request(port, HttpMethod.GET, StatusResource.ROUTE, tokens.get(Caller.ALICE), null);

        // Then: the resource still answers her
        checks.add(() -> assertEquals(200, status.status(), "GET /status by ALICE: status"));
        checks.add(() -> assertEquals(StatusResource.BODY, status.text(), "GET /status by ALICE: body"));
        assertAll("the outcomes of the internal document", checks.stream());
    }

    /**
     * A protected document listing no role rejects anonymous callers with 401 and a problem body,
     * and serves any authenticated caller, here {@code bob}, whose read is recorded as one
     * permitting authorization decision.
     */
    @Test
    @DisplayName("A protected document listing no role rejects anonymous callers and serves any authenticated one")
    void authenticatedOnlyDocumentRejectsAnonymous() throws Exception {
        // Given: the root application internal, its document guarded by bearerAuth with no role
        ProtectedRootTestComponents.RolesAbsentComponent component =
                DaggerProtectedRootTestComponents_RolesAbsentComponent.factory().create(vertx, DocsConfigs.loopback());
        DecisionRecorder recorder = component.decisionRecorder();
        Outcome rolesAbsent = deploy(component::httpVerticle, new DeploymentOptions());
        assertNull(rolesAbsent.failure(), () -> "the deployment failed: " + rolesAbsent.failure());
        assertNotNull(rolesAbsent.port(), "the deployment published no port");
        int port = rolesAbsent.port();
        Map<Caller, String> tokens = tokens();

        List<Executable> checks = new ArrayList<>();
        for (Form form : Form.values()) {
            // When: an anonymous caller reads the form
            Reply anonymous = request(port, HttpMethod.GET, form.url, null, null);

            // Then: 401 with the problem body, no entity tag, and no document content
            addDenialChecks(checks, form + " GET by ANONYMOUS", anonymous, HttpMethod.GET, true);

            // When: bob, who holds no listed role, reads the form
            recorder.clear();
            Reply bob = request(port, HttpMethod.GET, form.url, tokens.get(Caller.BOB), null);
            List<AuthorizationDecisionEvent> bobDecisions = awaitDecisionsOf(recorder, "bob");
            String label = form + " GET by BOB";

            // Then: 200 with the document, and one permitting decision recorded for his read
            checks.add(() -> assertEquals(200, bob.status(), label + ": status"));
            checks.add(() -> assertTrue(
                    bob.headers().get("Content-Type") != null
                            && bob.headers().get("Content-Type").startsWith(form.contentType),
                    () -> label + ": content type " + bob.headers().get("Content-Type")));
            checks.add(() -> assertTrue(
                    bob.text().contains("openapi") && bob.text().contains(StatusResource.ROUTE),
                    () -> label + ": the body is not the document of internal: " + bob.text()));
            checks.add(() -> assertEquals(
                    1, bobDecisions.size(), () -> label + ": authorization decisions for bob: " + bobDecisions));
            checks.add(() -> assertTrue(
                    bobDecisions.stream().allMatch(event -> event.decision().permitted()),
                    () -> label + ": a decision for bob is not a permit: " + bobDecisions));
        }
        assertAll("the outcomes of the role-less internal document", checks.stream());
    }

    /**
     * A protected document listing no role logs one INFO notice, once per component even when the
     * component is deployed as two instances, starting with its configuration path and naming the
     * scheme that authenticates its readers; a protected document listing a role logs none.
     */
    @Test
    @DisplayName("A protected document listing no role logs one notice naming its scheme; one listing a role logs none")
    void rolelessProtectedDocumentLogsOneInfo() throws Exception {
        // Given: the role-less component
        ProtectedRootTestComponents.RolesAbsentComponent absentComponent =
                DaggerProtectedRootTestComponents_RolesAbsentComponent.factory().create(vertx, DocsConfigs.loopback());

        // When: it is deployed as two instances; its outcome is recorded and asserted after both deployments
        clearCapturedEvents();
        Outcome rolesAbsent = deploy(absentComponent::httpVerticle, new DeploymentOptions().setInstances(2));
        List<ILoggingEvent> absentEvents = capturedEvents();
        undeployNow(rolesAbsent);

        // Given: the component whose document lists the admin role
        ProtectedRootTestComponents.RolesPresentComponent presentComponent =
                DaggerProtectedRootTestComponents_RolesPresentComponent.factory()
                        .create(vertx, DocsConfigs.loopback());

        // When: it is deployed once
        clearCapturedEvents();
        Outcome rolesPresent = deploy(presentComponent::httpVerticle, new DeploymentOptions());
        List<ILoggingEvent> presentEvents = capturedEvents();
        undeployNow(rolesPresent);

        // Then: both deployments started, so a missing line cannot stem from a failed deployment
        assertAll(
                "both deployments started",
                () -> assertNull(
                        rolesAbsent.failure(), () -> "rolesAbsent: the deployment failed: " + rolesAbsent.failure()),
                () -> assertNotNull(rolesAbsent.port(), "rolesAbsent: the deployment published no port"),
                () -> assertNull(
                        rolesPresent.failure(), () -> "rolesPresent: the deployment failed: " + rolesPresent.failure()),
                () -> assertNotNull(rolesPresent.port(), "rolesPresent: the deployment published no port"));

        // Then: the role-less deployment logged exactly one such line, an INFO starting with the
        // document's configuration path; the deployment with a role logged none
        List<ILoggingEvent> absentNotices = notices(absentEvents);
        List<ILoggingEvent> presentNotices = notices(presentEvents);
        assertAll(
                "the role-less notices (rolesAbsent deployment failure: " + rolesAbsent.failure()
                        + "; rolesPresent deployment failure: " + rolesPresent.failure() + ")",
                () -> assertEquals(
                        1,
                        absentNotices.size(),
                        () -> "rolesAbsent: lines stating '" + READABLE_BY_ANY_PRINCIPAL + "': "
                                + messages(absentNotices)),
                () -> assertTrue(
                        absentNotices.stream().allMatch(event -> event.getLevel() == Level.INFO),
                        () -> "rolesAbsent: a notice is not INFO: " + levelsAndMessages(absentNotices)),
                () -> assertTrue(
                        absentNotices.stream()
                                .allMatch(event -> event.getFormattedMessage().startsWith(INTERNAL_DOCUMENT)),
                        () -> "rolesAbsent: a notice does not start with " + INTERNAL_DOCUMENT + ": "
                                + messages(absentNotices)),
                () -> assertEquals(
                        List.of(),
                        messages(presentNotices),
                        "rolesPresent: lines stating '" + READABLE_BY_ANY_PRINCIPAL + "'"));
    }

    // --- Helpers ---

    /** Adds the checks every denied document response must pass. */
    private static void addDenialChecks(
            List<Executable> checks, String label, Reply reply, HttpMethod method, boolean anonymous) {
        int expectedStatus = anonymous ? 401 : 403;
        checks.add(() -> assertEquals(expectedStatus, reply.status(), label + ": status"));
        checks.add(() -> assertNull(reply.headers().get("ETag"), label + ": a denial carries an entity tag"));
        for (String fragment : DOCUMENT_FRAGMENTS) {
            checks.add(() -> assertFalse(
                    reply.text().contains(fragment),
                    () -> label + ": the denial contains '" + fragment + "': " + reply.text()));
        }
        if (method == HttpMethod.GET) {
            JsonObject expectedProblem = anonymous ? UNAUTHORIZED_PROBLEM : FORBIDDEN_PROBLEM;
            checks.add(() -> assertTrue(
                    reply.headers().get("Content-Type") != null
                            && reply.headers().get("Content-Type").startsWith(PROBLEM_TYPE),
                    () -> label + ": content type " + reply.headers().get("Content-Type")));
            checks.add(() -> assertEquals(expectedProblem, parseObject(reply.text()), label + ": problem body"));
        }
    }

    /** Parses a body as a JSON object, or returns {@code null} when it is not one. */
    private static JsonObject parseObject(String text) {
        try {
            return new JsonObject(text);
        } catch (RuntimeException notAnObject) {
            return null;
        }
    }

    /** Mints a token for each authenticated caller; the anonymous caller has none. */
    private Map<Caller, String> tokens() {
        JWTAuth signer = JwtAuthFactory.fromSymmetricKey(
                vertx, RootApplicationModule.ALGORITHM, RootApplicationModule.SIGNING_KEY);
        Map<Caller, String> tokens = new LinkedHashMap<>();
        tokens.put(Caller.ANONYMOUS, null);
        tokens.put(
                Caller.ALICE,
                signer.generateToken(new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("admin"))));
        tokens.put(
                Caller.BOB,
                signer.generateToken(new JsonObject().put("sub", "bob").put("roles", new JsonArray().add("user"))));
        return tokens;
    }

    /** Deploys a verticle supplier, remembering the outcome for cleanup. */
    private Outcome deploy(Supplier<Verticle> verticles, DeploymentOptions options) throws Exception {
        Outcome outcome = StartupDeployments.deploy(vertx, verticles, options);
        deployments.add(outcome);
        return outcome;
    }

    /** Undeploys one deployment now and forgets it. */
    private void undeployNow(Outcome outcome) throws Exception {
        deployments.remove(outcome);
        StartupDeployments.undeploy(vertx, outcome);
    }

    /**
     * Sends one request to the loopback server and waits for its whole response.
     *
     * @param port        the server's port
     * @param method      the request method
     * @param url         the request URL
     * @param token       the bearer token, or {@code null} for an anonymous request
     * @param ifNoneMatch the {@code If-None-Match} value, or {@code null} for none
     * @return the response
     */
    private Reply request(int port, HttpMethod method, String url, String token, String ifNoneMatch) throws Exception {
        HttpRequest<Buffer> request = client.request(method, port, HOST, url);
        if (token != null) {
            request.putHeader("Authorization", "Bearer " + token);
        }
        if (ifNoneMatch != null) {
            request.putHeader("If-None-Match", ifNoneMatch);
        }
        HttpResponse<Buffer> response =
                request.send().toCompletionStage().toCompletableFuture().get(REQUEST_SECONDS, TimeUnit.SECONDS);
        return new Reply(response.statusCode(), response.headers(), response.body());
    }

    /**
     * Waits until the recorder holds a decision for the principal, then returns every decision
     * recorded for it; returns an empty list when none arrived in time.
     */
    private static List<AuthorizationDecisionEvent> awaitDecisionsOf(DecisionRecorder recorder, String principal)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DECISION_MILLIS);
        List<AuthorizationDecisionEvent> found = decisionsOf(recorder, principal);
        while (found.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            found = decisionsOf(recorder, principal);
        }
        return found;
    }

    /** Returns the recorded decisions whose acting principal has the given id. */
    private static List<AuthorizationDecisionEvent> decisionsOf(DecisionRecorder recorder, String principal) {
        return recorder.decisions().stream()
                .filter(event -> principal.equals(
                        event.request().securityContext().identity().actor().id()))
                .toList();
    }

    /** Returns the captured events, at any level, that state the role-less notice. */
    private static List<ILoggingEvent> notices(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> event.getFormattedMessage().contains(READABLE_BY_ANY_PRINCIPAL))
                .toList();
    }

    /** Returns the formatted messages of events, in order. */
    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Returns each event as {@code <level> <message>}, in order. */
    private static List<String> levelsAndMessages(List<ILoggingEvent> events) {
        return events.stream()
                .map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList();
    }

    /** Returns a copy of every event captured so far. */
    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    /** Forgets every event captured so far. */
    private void clearCapturedEvents() {
        synchronized (appender) {
            appender.list.clear();
        }
    }
}
