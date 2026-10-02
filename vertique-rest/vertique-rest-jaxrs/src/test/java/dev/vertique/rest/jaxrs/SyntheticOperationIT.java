// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.rest.jaxrs.synthetic.CatchAllFailingMount;
import dev.vertique.rest.jaxrs.synthetic.CountingErrorInterceptor;
import dev.vertique.rest.jaxrs.synthetic.DaggerSyntheticOperationComponent;
import dev.vertique.rest.jaxrs.synthetic.LaterApidocsResource;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperationComponent;
import dev.vertique.rest.jaxrs.synthetic.TraceRecorder;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration proofs for {@code SyntheticOperationInstaller} over real HTTP: a synthetic document's
 * outcomes match its equally annotated resource twin, contributors run in resource order with the
 * same application-layer rejection, a denial ends on the synthetic route without falling through to
 * a later mount, and every failure ends with the standard problem body and {@code Cache-Control:
 * no-store}.
 *
 * <p>The fixture is one {@code HttpVerticle} composition built from {@link
 * SyntheticOperationComponent} (package {@code dev.vertique.rest.jaxrs.synthetic}, outside this
 * package, so resolving the installer's {@code @Binds} exercises the same cross-package path the
 * OpenAPI documentation module's own component will use): a stub {@code bearerAuth} scheme
 * authenticating from {@code X-Test-User}/{@code X-Test-Roles}; stub contributors {@code probe45},
 * {@code app60} (403 on {@code X-Reject: 1}), {@code authz100} (403 on a missing required role),
 * and {@code probe400}; a counting error interceptor; a {@code SYSTEM_FIRST} mount installing three
 * synthetic documents; a twin JAX-RS mount at {@code /api/mgmt/*}; a later {@code /apidocs/*} mount
 * declaring the same document URL plus live controls; and a {@code SYSTEM_LAST} catch-all mount.
 * {@code jaxrs.defaultHeaders.cacheControl} is configured {@code public, max-age=60}, so a
 * {@code no-store} on a failure can only come from the synthetic route's own failure handling.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class SyntheticOperationIT {

    private static final String MANAGEMENT_DOCUMENT_PATH = "/apidocs/management/openapi.json";
    private static final String AUTHENTICATED_DOCUMENT_PATH = "/apidocs/authenticated/openapi.json";
    private static final String BROKEN_DOCUMENT_PATH = "/apidocs/broken/openapi.json";
    private static final String MANAGEMENT_TWIN_PATH = "/api/mgmt/doc";
    private static final String AUTHENTICATED_TWIN_PATH = "/api/mgmt/auth-only";
    private static final String MANAGEMENT_BODY = "management-document-bytes";
    private static final String AUTHENTICATED_BODY = "authenticated-document-bytes";

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String USER_HEADER = "X-Test-User";
    private static final String ROLES_HEADER = "X-Test-Roles";
    private static final String REJECT_HEADER = "X-Reject";

    private static Vertx vertx;
    private static WebClient client;
    private static int port;
    private static TraceRecorder trace;
    private static CountingErrorInterceptor errorInterceptor;
    private static LaterApidocsResource laterApidocsResource;
    private static CatchAllFailingMount catchAllMount;

    @BeforeAll
    static void setUp(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = WebClient.create(v, new WebClientOptions().setFollowRedirects(false));

        JsonObject config = new JsonObject()
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put(
                        "jaxrs",
                        new JsonObject()
                                .put("validationStrategy", "none")
                                .put("defaultHeaders", new JsonObject().put("cacheControl", "public, max-age=60")));

        SyntheticOperationComponent component =
                DaggerSyntheticOperationComponent.factory().create(config);
        trace = component.traceRecorder();
        errorInterceptor = component.countingErrorInterceptor();
        laterApidocsResource = component.laterApidocsResource();
        catchAllMount = component.catchAllFailingMount();

        Supplier<Verticle> verticleSupplier = component::httpVerticle;
        vertx.deployVerticle(verticleSupplier, new DeploymentOptions()).onComplete(ctx.succeeding(deploymentId -> {
            port = (Integer) vertx.sharedData().getLocalMap("vertique").get("http.port");
            ctx.completeNow();
        }));
    }

    /**
     * Closes the client, failing the teardown if the close fails. The injected {@link Vertx} is owned
     * and closed by the extension.
     *
     * @throws Exception the first cleanup failure, carrying later ones as suppressed
     */
    @AfterAll
    static void tearDown() throws Exception {
        CleanupFailures cleanup = new CleanupFailures();
        cleanup.attempt(() -> {
            if (client != null) {
                client.close();
            }
        });
        cleanup.rethrowIfAny();
    }

    // --- Outcome parity with the resource twin ---

    @Test
    @DisplayName("Over HTTP, a synthetic operation's outcomes match its resource twin")
    void outcomesMatchAnEquallyAnnotatedResource() {
        assertAll(
                "role-protected and authenticated-only pairs",
                () -> assertParityRow(MANAGEMENT_DOCUMENT_PATH, MANAGEMENT_TWIN_PATH, null, null, 401, null),
                () -> assertParityRow(MANAGEMENT_DOCUMENT_PATH, MANAGEMENT_TWIN_PATH, "user", "", 403, null),
                () -> assertParityRow(
                        MANAGEMENT_DOCUMENT_PATH, MANAGEMENT_TWIN_PATH, "admin", "admin", 200, MANAGEMENT_BODY),
                () -> assertParityRow(AUTHENTICATED_DOCUMENT_PATH, AUTHENTICATED_TWIN_PATH, null, null, 401, null),
                () -> assertParityRow(
                        AUTHENTICATED_DOCUMENT_PATH, AUTHENTICATED_TWIN_PATH, "user", "", 200, AUTHENTICATED_BODY));
    }

    private void assertParityRow(
            String documentPath, String twinPath, String user, String roles, int expectedStatus, String expectedBody) {
        String documentTraceId = "doc-" + UUID.randomUUID();
        String twinTraceId = "twin-" + UUID.randomUUID();

        HttpResponse<Buffer> documentResponse = send(HttpMethod.GET, documentPath, documentTraceId, user, roles, false);
        HttpResponse<Buffer> twinResponse = send(HttpMethod.GET, twinPath, twinTraceId, user, roles, false);

        String rowLabel = "(" + documentPath + ", " + twinPath + ", user=" + user + ")";
        assertEquals(expectedStatus, documentResponse.statusCode(), rowLabel + ": document status");
        assertEquals(expectedStatus, twinResponse.statusCode(), rowLabel + ": twin status");

        List<String> documentTrace = trace.trace(documentTraceId);
        if (expectedStatus == 200) {
            assertEquals(expectedBody, documentResponse.bodyAsString(), rowLabel + ": document body");
            assertTrue(
                    !documentTrace.isEmpty() && "terminal".equals(documentTrace.get(documentTrace.size() - 1)),
                    rowLabel + ": an allowed document's trace must end with terminal, was " + documentTrace);
        } else {
            assertTrue(
                    !documentTrace.contains("terminal"),
                    rowLabel + ": a denied document's trace must lack terminal, was " + documentTrace);
        }
    }

    // --- Contributor order and application rejection parity ---

    @Test
    @DisplayName("Over HTTP, contributors run in resource order and an application 403 matches the twin")
    void contributorOrderAndApplicationRejectionMatchTheResource() {
        String documentAllowedTrace = "doc-allowed-" + UUID.randomUUID();
        String twinAllowedTrace = "twin-allowed-" + UUID.randomUUID();
        HttpResponse<Buffer> documentAllowed =
                send(HttpMethod.GET, MANAGEMENT_DOCUMENT_PATH, documentAllowedTrace, "admin", "admin", false);
        HttpResponse<Buffer> twinAllowed =
                send(HttpMethod.GET, MANAGEMENT_TWIN_PATH, twinAllowedTrace, "admin", "admin", false);

        List<String> expectedAllowedTrace = List.of("probe45", "app60", "authz100", "probe400", "terminal");
        assertAll(
                "without the reject marker",
                () -> assertEquals(200, documentAllowed.statusCode(), "document status"),
                () -> assertEquals(200, twinAllowed.statusCode(), "twin status"),
                () -> assertEquals(
                        expectedAllowedTrace, trace.trace(documentAllowedTrace), "document contributor order"),
                () -> assertEquals(expectedAllowedTrace, trace.trace(twinAllowedTrace), "twin contributor order"));

        String documentRejectedTrace = "doc-rejected-" + UUID.randomUUID();
        String twinRejectedTrace = "twin-rejected-" + UUID.randomUUID();
        HttpResponse<Buffer> documentRejected =
                send(HttpMethod.GET, MANAGEMENT_DOCUMENT_PATH, documentRejectedTrace, "admin", "admin", true);
        HttpResponse<Buffer> twinRejected =
                send(HttpMethod.GET, MANAGEMENT_TWIN_PATH, twinRejectedTrace, "admin", "admin", true);

        List<String> expectedRejectedTrace = List.of("probe45", "app60");
        assertAll(
                "with the reject marker",
                () -> assertEquals(403, documentRejected.statusCode(), "document status"),
                () -> assertEquals(403, twinRejected.statusCode(), "twin status"),
                () -> assertEquals(
                        expectedRejectedTrace, trace.trace(documentRejectedTrace), "document contributor order"),
                () -> assertEquals(expectedRejectedTrace, trace.trace(twinRejectedTrace), "twin contributor order"),
                () -> assertTrue(
                        !trace.trace(documentRejectedTrace).contains("terminal"), "document must not reach terminal"),
                () -> assertTrue(!trace.trace(twinRejectedTrace).contains("terminal"), "twin must not reach terminal"));
    }

    // --- Denial never falls through to a later mount ---

    @Test
    @DisplayName("Over HTTP, a denial ends on the synthetic route and never reaches a later mount")
    void denialNeverFallsThrough() {
        HttpResponse<Buffer> anonymousResponse =
                send(HttpMethod.GET, MANAGEMENT_DOCUMENT_PATH, "denial-anon-" + UUID.randomUUID(), null, null, false);
        HttpResponse<Buffer> userResponse =
                send(HttpMethod.GET, MANAGEMENT_DOCUMENT_PATH, "denial-user-" + UUID.randomUUID(), "user", "", false);

        // Snapshot each counter right after the denials and before any control is sent, so the
        // zero assertions below prove the state at this point in the sequence rather than the
        // state after the controls have already run.
        int managementCountAfterDenials = laterApidocsResource.managementCount();
        int interceptorCountAfterDenials = errorInterceptor.count(MANAGEMENT_DOCUMENT_PATH);
        int catchAllCountAfterDenials = catchAllMount.failureCount();

        assertAll(
                "a denial never falls through to the later mount",
                () -> assertEquals(401, anonymousResponse.statusCode(), "anonymous must be denied 401"),
                () -> assertEquals(403, userResponse.statusCode(), "user without roles must be denied 403"),
                () -> assertEquals(
                        0,
                        managementCountAfterDenials,
                        "the later mount's resource must never run for a denied document"),
                () -> assertEquals(
                        0,
                        interceptorCountAfterDenials,
                        "the later mount's error interceptor must never run for a denied document"),
                () -> assertEquals(
                        0,
                        catchAllCountAfterDenials,
                        "the catch-all failure handler must never run for a denied document"));

        HttpResponse<Buffer> otherResponse =
                send(HttpMethod.GET, "/apidocs/other", "control-other-" + UUID.randomUUID(), "admin", "admin", false);
        HttpResponse<Buffer> failsResponse =
                send(HttpMethod.GET, "/apidocs/fails", "control-fails-" + UUID.randomUUID(), "admin", "admin", false);
        HttpResponse<Buffer> nowhereResponse =
                send(HttpMethod.GET, "/nowhere", "control-nowhere-" + UUID.randomUUID(), null, null, false);

        assertAll(
                "each zero above is proven live by its own control",
                () -> assertEquals(200, otherResponse.statusCode(), "control: /apidocs/other must succeed"),
                () -> assertTrue(
                        laterApidocsResource.otherCount() > 0,
                        "control: /apidocs/other must increment the later mount's resource count"),
                () -> assertEquals(500, failsResponse.statusCode(), "control: /apidocs/fails must answer 500"),
                () -> assertTrue(
                        errorInterceptor.count("/apidocs/fails") > 0,
                        "control: /apidocs/fails must increment the later mount's error interceptor count"),
                () -> assertEquals(404, nowhereResponse.statusCode(), "control: /nowhere must answer 404"),
                () -> assertTrue(
                        catchAllMount.failureCount() > catchAllCountAfterDenials,
                        "control: /nowhere must increment the catch-all failure handler count above its"
                                + " post-denial snapshot"));
    }

    // --- Failure body and headers ---

    @Test
    @DisplayName("Over HTTP, every failure ends with the problem body and no-store")
    void failureHandlerWritesProblemAndNoStore() {
        HttpResponse<Buffer> anonymousResponse =
                send(HttpMethod.GET, MANAGEMENT_DOCUMENT_PATH, "problem-anon-" + UUID.randomUUID(), null, null, false);
        HttpResponse<Buffer> userResponse =
                send(HttpMethod.GET, MANAGEMENT_DOCUMENT_PATH, "problem-user-" + UUID.randomUUID(), "user", "", false);
        HttpResponse<Buffer> brokenResponse = send(
                HttpMethod.GET, BROKEN_DOCUMENT_PATH, "problem-broken-" + UUID.randomUUID(), "admin", "admin", false);
        HttpResponse<Buffer> headResponse = sendHead(MANAGEMENT_DOCUMENT_PATH, "problem-head-" + UUID.randomUUID());

        assertAll(
                "every failure ends with the standard problem body and no-store",
                () -> assertProblemBody(anonymousResponse, 401, "Unauthorized"),
                () -> assertProblemBody(userResponse, 403, "Forbidden"),
                () -> assertProblemBody(brokenResponse, 500, "Internal Server Error"),
                () -> assertEquals(401, headResponse.statusCode(), "HEAD must be denied 401"),
                () -> assertEquals(
                        "no-store",
                        headResponse.getHeader("Cache-Control"),
                        "HEAD failure must carry Cache-Control: no-store"),
                () -> assertTrue(
                        headResponse.body() == null || headResponse.body().length() == 0, "HEAD must carry no body"));
    }

    private void assertProblemBody(HttpResponse<Buffer> response, int expectedStatus, String expectedTitle) {
        assertEquals(expectedStatus, response.statusCode(), "status");
        String contentType = response.getHeader("Content-Type");
        assertTrue(
                contentType != null && contentType.startsWith("application/problem+json"),
                "Content-Type must be application/problem+json but was: " + contentType);
        assertEquals("no-store", response.getHeader("Cache-Control"), "Cache-Control must be no-store");

        JsonObject body;
        try {
            body = new JsonObject(response.bodyAsString());
        } catch (RuntimeException e) {
            fail("expected a JSON problem body but got: " + response.bodyAsString(), e);
            return;
        }
        JsonObject expected = new JsonObject()
                .put("type", "about:blank")
                .put("title", expectedTitle)
                .put("status", expectedStatus);
        assertEquals(expected, body, "problem body");
    }

    // --- Request helpers ---

    private HttpResponse<Buffer> send(
            HttpMethod method, String path, String traceId, String user, String roles, boolean reject) {
        HttpRequest<Buffer> request = client.request(method, port, "127.0.0.1", path);
        request.putHeader(TRACE_ID_HEADER, traceId);
        if (user != null) {
            request.putHeader(USER_HEADER, user);
        }
        if (roles != null) {
            request.putHeader(ROLES_HEADER, roles);
        }
        if (reject) {
            request.putHeader(REJECT_HEADER, "1");
        }
        return await(request.send());
    }

    private HttpResponse<Buffer> sendHead(String path, String traceId) {
        HttpRequest<Buffer> request = client.request(HttpMethod.HEAD, port, "127.0.0.1", path);
        request.putHeader(TRACE_ID_HEADER, traceId);
        return await(request.send());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AssertionError("request failed: " + e.getMessage(), e);
        }
    }
}
