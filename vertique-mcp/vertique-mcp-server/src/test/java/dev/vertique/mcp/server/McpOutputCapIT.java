// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the classified protocol-error write is bounded at {@code mcp.output.maxBytes} end-to-end
 * (finding W2), even for a pathological frame whose request id is the only unbounded element.
 *
 * <p>The error response echoes the request id, and an id is bounded only by the shared ingress
 * {@code http.maxBodySize} cap (2 MiB default) — far above the minimum {@code mcp.output.maxBytes}
 * (1024). A frame carrying a multi-thousand-character string id that classifies as an error therefore
 * produced, before the fix, an over-cap error response that echoed the huge id and defeated the very
 * cap the dispatcher enforces on the discovery path. The dispatcher now degrades an over-cap error to
 * a bounded, id-less internal error, so the hard cap holds.
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient} aggregates
 * the body before its future resolves, while the wrapped raw client keeps the awaitable {@code close()}
 * this test needs because it owns its {@link Vertx}. The raw client never issues a request itself.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpOutputCapIT {

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";

    /** The minimum permitted output cap, below the size of the echoed huge id. */
    private static final int OUTPUT_MAX_BYTES = 1_024;

    /** A string id well above the cap yet under {@code jsonMaxStringChars}; only its length matters. */
    private static final String HUGE_ID = "x".repeat(4_000);

    private final Vertx vertx = Vertx.vertx();

    private Fixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    /**
     * Joins the server and raw-client closes, then closes the owned {@link Vertx} from the join
     * callback so no in-flight request meets a closed pool.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<Void> clientClose = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose)
                .onComplete(ignored -> vertx.close().onComplete(result -> closed.complete(null)));
        closed.get(10, TimeUnit.SECONDS);
        fixture = null;
        server = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("an over-cap classified protocol error degrades to a bounded id-less internal error")
    void shouldCapClassifiedProtocolErrorWithHugeId() throws Exception {
        fixture = Fixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);

        // Given: an invalid-envelope frame (jsonrpc != "2.0") carrying a huge but usable string id, so
        // the classified error would echo that id. Pre-fix the response echoed the huge id and exceeded
        // mcp.output.maxBytes; the fix degrades it to a bounded id-less internal error.
        JsonObject frame =
                new JsonObject().put("jsonrpc", "1.0").put("id", HUGE_ID).put("method", "server/discover");
        HttpResponse<Buffer> response = await(client.post(fixture.port(), "127.0.0.1", Fixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .sendBuffer(frame.toBuffer()));

        // DECISIVE: the emitted body must respect the hard output cap. Pre-fix it echoed the ~4000-char
        // id and blew past 1024 bytes; post-fix it is the minimal id-less internal error.
        assertThat(response.body().length())
                .as("an over-cap classified error must be degraded to stay within mcp.output.maxBytes")
                .isLessThanOrEqualTo(OUTPUT_MAX_BYTES);

        JsonObject body = new JsonObject(response.bodyAsString());
        assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
        assertThat(body.getValue("id"))
                .as("the degraded error drops the unbounded id to stay under the cap")
                .isNull();
        assertThat(body.getJsonObject("error").getInteger("code"))
                .as("the degraded error is a bounded internal error")
                .isEqualTo(-32603);
        assertThat(response.statusCode())
                .as("the degraded internal error carries the matching HTTP 500 status")
                .isEqualTo(500);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /**
     * Builds and starts a minimal MCP mount with no tools, no authentication scheme, and a deliberately
     * small {@code outputMaxBytes} so the over-cap error path is cheap to trigger.
     */
    private static final class Fixture {

        /** Path under the {@code /mcp/*} mount the test posts to. */
        static final String REQUEST_PATH = "/mcp/";

        private final HttpServer server;
        private final int port;

        private Fixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .outputMaxBytes(OUTPUT_MAX_BYTES)
                    .build();
            AtomicReference<SecurityContext> bound = new AtomicReference<>();
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime(bound);
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of());
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            httpConfig,
                            registry,
                            new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                                    Optional.empty(),
                                    Optional.empty(),
                                    Set.of(),
                                    new SecurityEventEmitter(Set.of()),
                                    NO_OP_CONTEXT_HOLDER,
                                    securityRuntime,
                                    Optional.empty()))),
                    Set.of(),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static Fixture start(Vertx vertx) throws Exception {
            return new Fixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        private IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new AnonymousIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    /** Resolves the canonical anonymous identity from empty evidence and the {@code sub} user otherwise. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().get(0).safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

    /** A {@link ContextHolder} that resolves nothing and discards every binding. */
    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} for dispatch to snapshot. */
    private record RecordingSecurityRuntime(AtomicReference<SecurityContext> bound) implements SecurityRuntime {

        @Override
        public SecurityContext current() {
            return bound.get();
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound.set(context);
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }
}
