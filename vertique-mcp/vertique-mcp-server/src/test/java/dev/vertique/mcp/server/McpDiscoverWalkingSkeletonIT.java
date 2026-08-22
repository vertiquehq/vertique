// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.mcp.lifecycle.McpErrorType;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpOutcome;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T001 discovery contract matrix through the real plain Vert.x MCP mount.
 *
 * <p>Each row drives one final-2026 {@code server/discover} request against a port-0 mount that has
 * no registered tools, an optional bearer capability, and a recording lifecycle observer. The rows
 * isolate the three credential states the walking skeleton must distinguish: absent, valid, and
 * tampered.
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient}
 * aggregates the body before its future resolves — the raw client discards body buffers that arrive
 * before a body handler is attached — while the wrapped raw client keeps the awaitable
 * {@code close()} this test needs because it owns its {@link Vertx}. The raw client never issues a
 * request itself.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpDiscoverWalkingSkeletonIT {

    private static final String ABSENT_CREDENTIALS_ROW =
            "shouldDiscoverWithAbsentCredentialsAndCanonicalAnonymousContext";
    private static final String VALID_CREDENTIALS_ROW = "shouldDiscoverWithValidCredentialsAndAuthenticatedContext";
    private static final String DENIED_CREDENTIALS_ROW = "shouldObserveAuthenticationDenialBeforeAnyToolExists";
    private static final String MULTIPART_UPLOAD_ROW = "shouldRejectMultipartUploadWithoutPersistingAnyFile";
    private static final String NULL_JSON_BODY_ROW = "shouldRejectLiteralNullJsonBodyAsProtocolFailure";
    private static final String BODYLESS_REQUEST_ROW = "shouldRejectBodylessPostAsProtocolFailure";
    private static final String PARAMLESS_DISCOVER_ROW = "shouldRejectParamlessDiscoverAsInvalidRequest";

    /**
     * Vert.x's default {@code BodyHandler} upload directory, resolved against the surefire/failsafe
     * working directory (the module base directory). The mount must never create it, because file
     * uploads are not part of the MCP HTTP contract.
     */
    private static final Path DEFAULT_UPLOADS_DIRECTORY = Path.of("file-uploads");

    /** The one protocol version this walking skeleton pins, per the vendored official schema. */
    private static final String PROTOCOL_VERSION = "2026-07-28";

    /**
     * A deliberately non-default {@code mcp.tools.ttlMs}, so the discovery TTL assertion proves the
     * value is sourced from configuration rather than written as a literal.
     */
    private static final long CONFIGURED_TTL_MS = 120_000L;

    private final Vertx vertx = Vertx.vertx();

    private McpWalkingSkeletonFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t001ContractRows() {
        return Stream.of(
                ABSENT_CREDENTIALS_ROW,
                VALID_CREDENTIALS_ROW,
                DENIED_CREDENTIALS_ROW,
                MULTIPART_UPLOAD_ROW,
                NULL_JSON_BODY_ROW,
                BODYLESS_REQUEST_ROW,
                PARAMLESS_DISCOVER_ROW);
    }

    /**
     * Closes the mount's server and the raw client, then the owned {@link Vertx} from the join
     * callback — including {@code vertx.close()} in the join itself races the event-loop shutdown.
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("t001ContractRows")
    @DisplayName("T001 discovery matrix: absent, valid, and tampered credentials")
    void shouldEnforceT001ContractMatrix(String row) throws Exception {
        fixture = McpWalkingSkeletonFixture.start(vertx);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
        JsonObject discoverRequest = new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", discoverParams());
        HttpRequest<Buffer> request = client.post(fixture.port(), "127.0.0.1", McpWalkingSkeletonFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json");

        switch (row) {
            case ABSENT_CREDENTIALS_ROW -> {
                // Given: no Authorization header at all.
                HttpResponse<Buffer> response = await(request.sendBuffer(discoverRequest.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertNoMcpHeaders(response);
                JsonObject body = new JsonObject(response.bodyAsString());
                assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
                assertDiscoverResult(body);
                SecurityContext bound = fixture.boundSecurityContext();
                assertThat(bound).isNotNull();
                assertThat(bound.identity().actor().type()).isEqualTo(PrincipalType.ANONYMOUS);
                assertThat(bound.identity().actor().id()).isEqualTo("anonymous");
                assertThat(bound.authentication().primaryMethod().normalizedKind())
                        .isEqualTo(AuthMethodKind.NONE);
                assertThat(bound.authentication().evidence()).isEmpty();
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertDispatchedDiscovery(completed);
                assertEstablishedSecurity(completed, PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
            }
            case VALID_CREDENTIALS_ROW -> {
                // Given: the one bearer credential the fixture's scheme accepts for principal alice.
                request.putHeader("Authorization", "Bearer valid-alice");
                HttpResponse<Buffer> response = await(request.sendBuffer(discoverRequest.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertNoMcpHeaders(response);
                JsonObject body = new JsonObject(response.bodyAsString());
                assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
                assertDiscoverResult(body);
                SecurityContext bound = fixture.boundSecurityContext();
                assertThat(bound).isNotNull();
                assertThat(bound.identity().actor().type()).isEqualTo(PrincipalType.USER);
                assertThat(bound.identity().actor().id()).isEqualTo("alice");
                assertThat(bound.authentication().primaryMethod().normalizedKind())
                        .isEqualTo(AuthMethodKind.JWT);
                assertThat(bound.authentication().evidence()).hasSize(1);
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertDispatchedDiscovery(completed);
                assertEstablishedSecurity(completed, PrincipalType.USER, "alice", AuthMethodKind.JWT);
            }
            case DENIED_CREDENTIALS_ROW -> {
                // Given: a tampered bearer credential for the same scheme.
                request.putHeader("Authorization", "Bearer tampered");
                HttpResponse<Buffer> response = await(request.sendBuffer(discoverRequest.toBuffer()));

                assertThat(response.statusCode()).isEqualTo(401);
                // No tool exists and dispatch never ran: the bounded authentication error carries no
                // discovery result.
                assertNoMcpHeaders(response);
                assertThat(response.bodyAsString()).isNullOrEmpty();
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertThat(completed.terminal().httpStatus()).isEqualTo(401);
                assertThat(fixture.boundSecurityContext()).isNull();
                assertThat(completed.terminal().security())
                        .as("a terminal reached before identity establishment carries no security facts")
                        .isNull();
            }
            case MULTIPART_UPLOAD_ROW -> {
                // Given: a multipart body carrying a file part, which the MCP contract never accepts.
                deleteRecursively(DEFAULT_UPLOADS_DIRECTORY);
                String boundary = "vertique-mcp-boundary";
                Buffer multipartBody = Buffer.buffer()
                        .appendString("--" + boundary + "\r\n")
                        .appendString("Content-Disposition: form-data; name=\"payload\";"
                                + " filename=\"discover-upload.bin\"\r\n")
                        .appendString("Content-Type: application/octet-stream\r\n\r\n")
                        .appendString("uploaded-bytes\r\n")
                        .appendString("--" + boundary + "--\r\n");
                request.putHeader("content-type", "multipart/form-data; boundary=" + boundary);

                HttpResponse<Buffer> response = await(request.sendBuffer(multipartBody));

                assertThat(Files.exists(DEFAULT_UPLOADS_DIRECTORY))
                        .as("the MCP mount must not persist uploads into %s", DEFAULT_UPLOADS_DIRECTORY)
                        .isFalse();
                assertThat(response.statusCode())
                        .as("a multipart body is a protocol rejection, not a discovery result")
                        .isBetween(400, 499);
            }
            case NULL_JSON_BODY_ROW -> {
                // Given: a syntactically valid JSON document that decodes to no envelope at all.
                HttpResponse<Buffer> response = await(request.sendBuffer(Buffer.buffer("null")));

                assertThat(response.statusCode()).isEqualTo(400);
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertThat(completed.terminal().httpStatus()).isEqualTo(400);
                assertThat(completed.terminal().errorType()).isEqualTo(McpErrorType.PROTOCOL);
            }
            case BODYLESS_REQUEST_ROW -> {
                // Given: a POST that carries no JSON-RPC envelope at all.
                HttpResponse<Buffer> response = await(request.send());

                assertThat(response.statusCode()).isEqualTo(400);
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertThat(completed.terminal().httpStatus()).isEqualTo(400);
                assertThat(completed.terminal().errorType()).isEqualTo(McpErrorType.PROTOCOL);
            }
            case PARAMLESS_DISCOVER_ROW -> {
                // Given: a server/discover frame that omits the schema-required params (protocol
                // version + client capabilities). Discovery now flows through the one strict-codec
                // envelope authority, so a paramless frame is an -32600 Invalid Request at HTTP 400 —
                // it is no longer admitted as a discovery result. Against pre-fix HEAD this returned
                // 200, so this row is the inverse proof guarding the enforcement.
                JsonObject paramlessDiscover =
                        new JsonObject().put("jsonrpc", "2.0").put("id", 1).put("method", "server/discover");
                HttpResponse<Buffer> response = await(request.sendBuffer(paramlessDiscover.toBuffer()));

                assertThat(response.statusCode())
                        .as("a paramless server/discover must be an Invalid Request, never an admitted 200")
                        .isEqualTo(400);
                JsonObject body = new JsonObject(response.bodyAsString());
                assertThat(body.getJsonObject("error").getInteger("code"))
                        .as("a paramless server/discover carries JSON-RPC -32600 Invalid Request")
                        .isEqualTo(-32600);
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertThat(completed.terminal().httpStatus()).isEqualTo(400);
                assertThat(completed.terminal().errorType()).isEqualTo(McpErrorType.PROTOCOL);
            }
            default -> fail("unknown T001 contract row: " + row);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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

    /** Asserts the mount leaks no {@code x-mcp-*} response header, which Phase 1 never emits. */
    private static void assertNoMcpHeaders(HttpResponse<Buffer> response) {
        assertThat(response.headers().names())
                .as("Phase 1 emits no x-mcp-* response header")
                .noneMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-mcp-"));
    }

    /**
     * Asserts the discovery payload is a conformant {@code DiscoverResult} per the vendored
     * {@code mcp/schema/2026-07-28/schema.json}: every required field is present, the cache scope is
     * the contract's fixed {@code private}, the TTL is the configured one rather than a literal, and
     * the configured server identity is stamped into result {@code _meta}.
     */
    private static void assertDiscoverResult(JsonObject body) {
        JsonObject result = body.getJsonObject("result");
        assertThat(result).isNotNull();
        assertThat(result.fieldNames())
                .as("DiscoverResult requires every schema-mandated field")
                .contains("cacheScope", "capabilities", "resultType", "supportedVersions", "ttlMs");
        assertThat(result.getString("resultType")).isEqualTo("complete");
        assertThat(result.getJsonArray("supportedVersions")).containsExactly(PROTOCOL_VERSION);
        assertThat(result.getJsonObject("capabilities")).isNotNull();
        assertThat(result.getString("cacheScope")).isEqualTo("private");
        assertThat(result.getLong("ttlMs")).isEqualTo(CONFIGURED_TTL_MS);
        JsonObject stampedServerInfo =
                result.getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo");
        assertThat(stampedServerInfo.getString("name")).isEqualTo("test-mcp");
        assertThat(stampedServerInfo.getString("version")).isEqualTo("1.0.0");
    }

    /** Asserts dispatch actually ran, using the recorded terminal facts rather than a wire marker. */
    private static void assertDispatchedDiscovery(McpRequestCompletedEvent completed) {
        assertThat(completed.terminal().method()).isEqualTo(McpMethod.SERVER_DISCOVER);
        assertThat(completed.terminal().outcome()).isEqualTo(McpOutcome.SUCCESS);
        assertThat(completed.terminal().httpStatus()).isEqualTo(200);
    }

    /**
     * Asserts the terminal event carries the security snapshot identity resolution established.
     *
     * <p>The lifecycle contract requires {@code security} to be present after identity establishment
     * — including for the canonical anonymous identity — and null only for a terminal outcome that
     * occurred earlier.
     */
    private static void assertEstablishedSecurity(
            McpRequestCompletedEvent completed, PrincipalType actorType, String actorId, AuthMethodKind methodKind) {
        SecurityContextSnapshot security = completed.terminal().security();
        assertThat(security)
                .as("a terminal reached after identity establishment must carry the security snapshot")
                .isNotNull();
        assertThat(security.identity().actor().type()).isEqualTo(actorType);
        assertThat(security.identity().actor().id()).isEqualTo(actorId);
        assertThat(security.authentication().primaryMethod().normalizedKind()).isEqualTo(methodKind);
    }

    /** Removes {@code path} and everything below it, so an upload assertion starts from a known state. */
    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(path)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    // --- Fixture ---

    /**
     * Builds and starts the minimal MCP mount the matrix exercises: no tools, an optional bearer
     * scheme, the real {@link IdentityResolutionMiddleware}, and a recording lifecycle observer.
     */
    private static final class McpWalkingSkeletonFixture {

        /** Path under the {@code /mcp/*} mount every row posts to. */
        static final String REQUEST_PATH = "/mcp/";

        private final AtomicReference<SecurityContext> boundSecurityContext = new AtomicReference<>();
        private final CompletableFuture<McpRequestCompletedEvent> completedEvent = new CompletableFuture<>();
        private final HttpServer server;
        private final int port;

        private McpWalkingSkeletonFixture(Vertx vertx) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("test-mcp")
                    .serverVersion("1.0.0")
                    .authenticationScheme("bearer")
                    .toolsTtlMs(CONFIGURED_TTL_MS)
                    .build();
            // The one runtime both identity resolution binds into and dispatch snapshots from, so the
            // terminal event's security facts come from the context the pipeline actually established.
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime(boundSecurityContext);
            HttpConfig httpConfig = HttpConfig.builder().build();
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config, securityRuntime, Set.of(recordingObserver()), Set.of(), httpConfig),
                    Set.of(new BearerRouteAuthHandler()),
                    identityResolution(securityRuntime),
                    httpConfig);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static McpWalkingSkeletonFixture start(Vertx vertx) throws Exception {
            return new McpWalkingSkeletonFixture(vertx);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        /** Returns the security context identity resolution bound, or {@code null} if it never ran. */
        SecurityContext boundSecurityContext() {
            return boundSecurityContext.get();
        }

        /** Waits for the one completion event the recording observer receives. */
        McpRequestCompletedEvent awaitCompleted() throws Exception {
            return completedEvent.get(10, TimeUnit.SECONDS);
        }

        private McpRequestLifecycleObserver recordingObserver() {
            return startedAt -> new McpRequestObservation() {
                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    completedEvent.complete(event);
                }
            };
        }

        /**
         * Builds the real {@link IdentityResolutionMiddleware}.
         *
         * <p>The resolver is a local stand-in rather than {@code DefaultSecurityIdentityResolver}:
         * that class's constructor is package-private to {@code dev.vertique.rest.security} (Dagger
         * calls it), so it cannot be instantiated from this package. The stand-in reproduces the
         * default's behaviour for the two evidence shapes this matrix drives — empty evidence
         * resolves to the canonical anonymous identity, and evidence carrying a {@code sub}
         * attribute resolves to that user.
         *
         * @param securityRuntime the runtime the resolved context is bound into
         */
        private IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new SubjectEvidenceIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }
    }

    /**
     * Resolves the canonical anonymous identity from empty evidence and the {@code sub}-named user
     * otherwise — the subset of the framework default resolver's behaviour this matrix exercises.
     */
    private record SubjectEvidenceIdentityResolver() implements SecurityIdentityResolver {

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

    /** A {@link SecurityRuntime} that records the bound {@link SecurityContext} for assertion. */
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

    /**
     * A hand-written optional-authentication scheme: absent credentials continue anonymously, the one
     * valid bearer authenticates principal {@code alice} with evidence, and anything else fails 401
     * without ever calling {@code next()}.
     */
    private static final class BearerRouteAuthHandler implements RouteAuthHandler {

        private static final String VALID_CREDENTIAL = "Bearer valid-alice";

        @Override
        public String schemeName() {
            return "bearer";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> {
                if (!VALID_CREDENTIAL.equals(context.request().getHeader("Authorization"))) {
                    context.fail(401);
                    return;
                }
                authenticateAsAlice(context);
            };
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    context.next();
                    return;
                }
                if (!VALID_CREDENTIAL.equals(credential)) {
                    context.fail(401);
                    return;
                }
                authenticateAsAlice(context);
            });
        }

        private static void authenticateAsAlice(RoutingContext context) {
            RestAuthenticationEvidence.append(
                    context,
                    new AuthenticationEvidence(
                            DefaultAuthMethod.jwt(),
                            Optional.of("alice"),
                            Instant.now(),
                            Optional.empty(),
                            new CustomVerificationSource("test", Map.of()),
                            Map.of("sub", "alice")));
            ((UserContextInternal) context.userContext()).setUser(User.create(new JsonObject().put("sub", "alice")));
            context.next();
        }
    }
}
