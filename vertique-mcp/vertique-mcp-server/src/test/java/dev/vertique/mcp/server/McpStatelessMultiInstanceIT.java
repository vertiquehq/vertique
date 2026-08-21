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
import java.util.Locale;
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
 * Guards the stateless multi-instance invariant of the MCP mount: two independently created port-0
 * servers share no state, so discovery requests alternate between them with no initialization
 * handshake, cookie, session id, or affinity header.
 *
 * <p>This is a green invariant guard, not a from-red proof. Two independent servers already serve
 * discovery statelessly, so the value of the test is proving that T004's HTTP/lifecycle hardening
 * (origin admission, session-header rejection, completion-coordinator wiring) introduces no session
 * affinity. Its decisive assertion is the sensitivity mutation: closing only one instance drops that
 * instance's discovery success from 1 to 0 while the other instance remains independently available.
 * If a future change made the two instances share session state, that mutation would stop isolating
 * the failure and this guard would fail.
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient}
 * aggregates the body before its future resolves, while the wrapped raw client keeps the awaitable
 * {@code close()} this test needs because it owns its {@link Vertx}. The raw client never issues a
 * request itself.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpStatelessMultiInstanceIT {

    private static final String INSTANCE_A = "instance-a";
    private static final String INSTANCE_B = "instance-b";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";

    private final Vertx vertx = Vertx.vertx();

    private HttpServer serverA;
    private HttpServer serverB;
    private HttpClient rawClient;
    private WebClient client;

    /**
     * Joins the (surviving) server and raw-client closes, then closes the owned {@link Vertx} from
     * the join callback so no in-flight request meets a closed pool. {@code serverA} may already be
     * closed by the sensitivity mutation, so every handle is null-guarded.
     *
     * @throws Exception if teardown does not complete within its bound
     */
    @AfterEach
    void tearDown() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        Future<Void> closeA = serverA != null ? serverA.close() : Future.succeededFuture();
        Future<Void> closeB = serverB != null ? serverB.close() : Future.succeededFuture();
        Future<Void> closeClient = rawClient != null ? rawClient.close() : Future.succeededFuture();
        Future.join(closeA, closeB, closeClient)
                .onComplete(ignored -> vertx.close().onComplete(result -> closed.complete(null)));
        closed.get(10, TimeUnit.SECONDS);
        serverA = null;
        serverB = null;
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("serves independent discovery across two instances with no initialization or affinity")
    void shouldServeIndependentRequestsAcrossInstancesWithoutInitializationOrAffinity() throws Exception {
        McpStatelessMultiInstanceITFixture fixtureA = McpStatelessMultiInstanceITFixture.start(vertx, INSTANCE_A);
        McpStatelessMultiInstanceITFixture fixtureB = McpStatelessMultiInstanceITFixture.start(vertx, INSTANCE_B);
        serverA = fixtureA.server();
        serverB = fixtureB.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        int portA = fixtureA.port();
        int portB = fixtureB.port();

        // Alternate discovery between the two instances; each answers with its own server identity
        // and no request ever carries an initialization handshake.
        HttpResponse<Buffer> firstA = discover(portA);
        assertServerInfo(firstA, INSTANCE_A);
        assertNoSessionOrAffinity(firstA);
        assertServerInfo(discover(portB), INSTANCE_B);
        assertServerInfo(discover(portA), INSTANCE_A);
        assertServerInfo(discover(portB), INSTANCE_B);

        // DECISIVE (sensitivity mutation): close only instance A. Its discovery success must drop
        // 1 -> 0 while instance B remains independently available — proving no shared session state
        // or affinity binds the two instances.
        assertThat(discoverSucceeds(portA, INSTANCE_A))
                .as("instance A serves discovery independently before the mutation")
                .isTrue();
        await(serverA.close());
        serverA = null;
        assertThat(discoverSucceeds(portA, INSTANCE_A))
                .as("closing only instance A drops its discovery success from 1 to 0")
                .isFalse();
        assertThat(discoverSucceeds(portB, INSTANCE_B))
                .as("instance B remains independently available after instance A is closed")
                .isTrue();
    }

    private HttpResponse<Buffer> discover(int port) throws Exception {
        JsonObject request = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", discoverParams());
        return await(client.post(port, "127.0.0.1", McpStatelessMultiInstanceITFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .sendBuffer(request.toBuffer()));
    }

    /**
     * Builds a schema-valid {@code params._meta} for a {@code server/discover} frame, carrying the
     * candidate protocol version and an empty client-capabilities object — both members are required
     * by the vendored {@code RequestMetaObject} definition of {@code mcp/schema/2026-07-28}.
     */
    private static JsonObject discoverParams() {
        return new JsonObject()
                .put(
                        "_meta",
                        new JsonObject()
                                .put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION)
                                .put("io.modelcontextprotocol/clientCapabilities", new JsonObject()));
    }

    private boolean discoverSucceeds(int port, String expectedName) {
        try {
            HttpResponse<Buffer> response = discover(port);
            if (response.statusCode() != 200) {
                return false;
            }
            return expectedName.equals(serverName(response));
        } catch (Exception connectionRefusedOrClosed) {
            return false;
        }
    }

    private static void assertServerInfo(HttpResponse<Buffer> response, String expectedName) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(serverName(response)).isEqualTo(expectedName);
    }

    private static String serverName(HttpResponse<Buffer> response) {
        JsonObject body = new JsonObject(response.bodyAsString());
        return body.getJsonObject("result")
                .getJsonObject("_meta")
                .getJsonObject("io.modelcontextprotocol/serverInfo")
                .getString("name");
    }

    /** Asserts the stateless protocol emits no session cookie, session id, or affinity header. */
    private static void assertNoSessionOrAffinity(HttpResponse<Buffer> response) {
        assertThat(response.headers().names())
                .as("stateless discovery must emit no session or affinity response header")
                .noneMatch(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.equals("set-cookie") || lower.startsWith("mcp-session") || lower.contains("affinity");
                });
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /** Builds and starts one independent, stateless MCP discovery mount named for its instance. */
    private static final class McpStatelessMultiInstanceITFixture {

        /** Path under the {@code /mcp/*} mount every request posts to. */
        static final String REQUEST_PATH = "/mcp/";

        private final HttpServer server;
        private final int port;

        private McpStatelessMultiInstanceITFixture(Vertx vertx, String serverName) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(serverName)
                    .serverVersion(SERVER_VERSION)
                    .build();
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime(new AtomicReference<>());
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(config, securityRuntime, Set.of(), Set.of()),
                    Set.of(),
                    identityResolution(securityRuntime),
                    HttpConfig.builder().build());
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static McpStatelessMultiInstanceITFixture start(Vertx vertx, String serverName) throws Exception {
            return new McpStatelessMultiInstanceITFixture(vertx, serverName);
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

    /** Resolves the canonical anonymous identity from empty evidence. */
    private record AnonymousIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
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
