// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.correlation.CorrelationContextFactory;
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
import dev.vertique.rest.security.SecurityPolicyEnforcer;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises the T004 optional-authentication and canonical-identity contract of the MCP discovery
 * mount through the real plain Vert.x pipeline.
 *
 * <p>Each row drives one {@code server/discover} request against a port-0 mount named
 * {@code vertique-test/1.0}, isolating one boundary of §4.7 stage 3 identity establishment: absent,
 * valid, and invalid credentials; canonical-anonymous binding; a credential-shaped header ignored
 * when no scheme is configured; fail-closed pre-authentication and post-authentication states; and
 * the decisive case where no scheme is configured yet an ambient routing user is present. Error rows
 * bind no substitute identity and emit exactly one terminal/completion pair.
 *
 * <p>The decisive red row is {@code shouldRemainCanonicalAnonymousDespiteAmbientRoutingUserWhenNoScheme}:
 * §4.7 stage 3 requires the mount to bind a canonical anonymous context without consulting ambient
 * Router user/evidence when no scheme is configured, but the current {@code McpIdentityEstablisher.admit}
 * rejects any pre-existing ambient user unconditionally, so the request is answered 401 instead of a
 * 200 anonymous discovery.
 *
 * <p>The registry-visibility row (an unconfigured public/deny-all registry acceptance and an
 * unconfigured restricted-registry rejection) is deferred to T006, which owns the tool registry and
 * a composition-validator seam that do not exist in T004.
 *
 * <p>The client is a {@link WebClient} wrapping a raw {@link HttpClient}: {@code WebClient}
 * aggregates the body before its future resolves, while the wrapped raw client keeps the awaitable
 * {@code close()} this test needs because it owns its {@link Vertx}. The raw client never issues a
 * request itself.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class McpDiscoverIT {

    private static final String ANON_AND_AUTH_ROW = "shouldReturnFinal2026DiscoveryForAnonymousAndAuthenticatedCallers";
    private static final String STARTUP_FAILURE_ROW = "shouldFailStartupForUnknownOrNonOptionalAuthenticationScheme";
    private static final String DENIAL_ONCE_ROW =
            "shouldObserveAuthHandlerDenialOnceWithAuthenticationErrorAndNullSecurity";
    private static final String INVALID_NO_DOWNGRADE_ROW = "shouldFailInvalidCredentialWithoutAnonymousDowngrade";
    private static final String CANONICAL_ANON_ABSENT_ROW = "shouldBindCanonicalAnonymousContextWhenCredentialIsAbsent";
    private static final String IGNORE_HEADER_NO_SCHEME_ROW =
            "shouldIgnoreCredentialShapedHeaderWhenNoSchemeAndRemainAnonymous";
    // deferred to T006: shouldAllowUnconfiguredPublicOrDenyAllRegistryAndRejectUnconfiguredRestrictedRegistry
    private static final String PREEXISTING_USER_ROW =
            "shouldFailClosedOnPreexistingUserOrEvidenceBeforeSelectedScheme";
    private static final String POST_AUTH_INCONSISTENT_ROW =
            "shouldFailClosedOnUserOnlyOrEvidenceOnlyPostAuthenticationState";
    private static final String AMBIENT_USER_NO_SCHEME_ROW =
            "shouldRemainCanonicalAnonymousDespiteAmbientRoutingUserWhenNoScheme";
    private static final String PRIVILEGED_AMBIENT_USER_NO_SCHEME_ROW =
            "shouldStripPrivilegedAmbientUserClaimsFromCanonicalAnonymousWhenNoScheme";
    private static final String AMBIENT_EVIDENCE_NO_SCHEME_ROW =
            "shouldStripAmbientEvidenceFromCanonicalAnonymousWhenNoScheme";

    private static final String SERVER_NAME = "vertique-test";
    private static final String SERVER_VERSION = "1.0";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String VALID_CREDENTIAL = "Bearer valid-alice";
    private static final String INVALID_CREDENTIAL = "Bearer tampered";

    private final Vertx vertx = Vertx.vertx();

    private McpDiscoverITFixture fixture;
    private HttpServer server;
    private HttpClient rawClient;
    private WebClient client;

    private static Stream<String> t004ContractRows() {
        return Stream.of(
                ANON_AND_AUTH_ROW,
                STARTUP_FAILURE_ROW,
                DENIAL_ONCE_ROW,
                INVALID_NO_DOWNGRADE_ROW,
                CANONICAL_ANON_ABSENT_ROW,
                IGNORE_HEADER_NO_SCHEME_ROW,
                PREEXISTING_USER_ROW,
                POST_AUTH_INCONSISTENT_ROW,
                AMBIENT_USER_NO_SCHEME_ROW,
                PRIVILEGED_AMBIENT_USER_NO_SCHEME_ROW,
                AMBIENT_EVIDENCE_NO_SCHEME_ROW);
    }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("t004ContractRows")
    @DisplayName("T004 discovery matrix: optional authentication and canonical identity establishment")
    void shouldEnforceT004ContractMatrix(String row) throws Exception {
        switch (row) {
            case ANON_AND_AUTH_ROW -> {
                startServer(McpDiscoverITFixture.options().scheme("bearer").handler(new BearerRouteAuthHandler()));

                // Given: no credential, then the one valid bearer, against the same scheme.
                HttpResponse<Buffer> anonymous = await(post().sendBuffer(discoverBody()));
                assertThat(anonymous.statusCode()).isEqualTo(200);
                assertDiscoverResult(anonymous);
                assertBoundIdentity(PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
                assertEstablishedSecurity(fixture.awaitCompleted(), PrincipalType.ANONYMOUS, "anonymous");

                HttpResponse<Buffer> authenticated = await(
                        post().putHeader("Authorization", VALID_CREDENTIAL).sendBuffer(discoverBody()));
                assertThat(authenticated.statusCode()).isEqualTo(200);
                assertDiscoverResult(authenticated);
                assertBoundIdentity(PrincipalType.USER, "alice", AuthMethodKind.JWT);
                assertEstablishedSecurity(fixture.awaitCompleted(), PrincipalType.USER, "alice");
            }
            case STARTUP_FAILURE_ROW -> {
                // Given: a configured scheme that no registered handler provides.
                assertThatThrownBy(() -> McpDiscoverITFixture.start(
                                vertx,
                                McpDiscoverITFixture.options().scheme("mystery").handler(new BearerRouteAuthHandler())))
                        .as("an unknown authentication scheme must fail startup")
                        .isInstanceOf(ConfigurationException.class);
            }
            case DENIAL_ONCE_ROW -> {
                startServer(McpDiscoverITFixture.options().scheme("bearer").handler(new BearerRouteAuthHandler()));

                // Given: an invalid bearer credential.
                HttpResponse<Buffer> response = await(
                        post().putHeader("Authorization", INVALID_CREDENTIAL).sendBuffer(discoverBody()));

                assertThat(response.statusCode()).isEqualTo(401);
                McpRequestCompletedEvent completed = fixture.awaitCompleted();
                assertThat(completed.terminal().httpStatus()).isEqualTo(401);
                assertThat(completed.terminal().errorType()).isEqualTo(McpErrorType.AUTHENTICATION);
                assertThat(completed.terminal().security())
                        .as("an authentication rejection binds no substitute identity")
                        .isNull();
                assertThat(fixture.boundSecurityContext()).isNull();
            }
            case INVALID_NO_DOWNGRADE_ROW -> {
                startServer(McpDiscoverITFixture.options().scheme("bearer").handler(new BearerRouteAuthHandler()));

                // Given: an invalid bearer credential that must not downgrade to anonymous.
                HttpResponse<Buffer> response = await(
                        post().putHeader("Authorization", INVALID_CREDENTIAL).sendBuffer(discoverBody()));

                assertThat(response.statusCode())
                        .as("an invalid credential must fail, never downgrade to an anonymous 200")
                        .isEqualTo(401);
                assertThat(fixture.boundSecurityContext())
                        .as("an invalid credential binds no anonymous identity")
                        .isNull();
            }
            case CANONICAL_ANON_ABSENT_ROW -> {
                startServer(McpDiscoverITFixture.options().scheme("bearer").handler(new BearerRouteAuthHandler()));

                // Given: no credential at all under a configured scheme.
                HttpResponse<Buffer> response = await(post().sendBuffer(discoverBody()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertDiscoverResult(response);
                assertBoundIdentity(PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
                assertEstablishedSecurity(fixture.awaitCompleted(), PrincipalType.ANONYMOUS, "anonymous");
            }
            case IGNORE_HEADER_NO_SCHEME_ROW -> {
                startServer(McpDiscoverITFixture.options());

                // Given: a credential-shaped header with no scheme configured to consume it.
                HttpResponse<Buffer> response = await(
                        post().putHeader("Authorization", VALID_CREDENTIAL).sendBuffer(discoverBody()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertDiscoverResult(response);
                assertBoundIdentity(PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
            }
            case PREEXISTING_USER_ROW -> {
                startServer(McpDiscoverITFixture.options()
                        .scheme("bearer")
                        .handler(new BearerRouteAuthHandler())
                        .injectAmbientUser(true));

                // Given: an ambient routing user present before a configured scheme runs.
                HttpResponse<Buffer> response = await(post().sendBuffer(discoverBody()));

                assertThat(response.statusCode())
                        .as("a pre-existing ambient user before a selected scheme must fail closed")
                        .isEqualTo(401);
                assertThat(fixture.boundSecurityContext())
                        .as("a fail-closed pre-authentication state binds no substitute identity")
                        .isNull();
            }
            case POST_AUTH_INCONSISTENT_ROW -> {
                startServer(McpDiscoverITFixture.options().scheme("useronly").handler(new UserOnlyRouteAuthHandler()));

                // Given: an optional handler that yields a user without evidence.
                HttpResponse<Buffer> response = await(post().sendBuffer(discoverBody()));

                assertThat(response.statusCode())
                        .as("an inconsistent user-only post-authentication state must fail closed")
                        .isEqualTo(401);
                assertThat(fixture.boundSecurityContext())
                        .as("a fail-closed post-authentication state binds no substitute identity")
                        .isNull();
            }
            case AMBIENT_USER_NO_SCHEME_ROW -> {
                startServer(McpDiscoverITFixture.options().injectAmbientUser(true));

                // Given: an ambient routing user with no scheme configured.
                // DECISIVE: §4.7 stage 3 requires binding a canonical anonymous context without
                // consulting the ambient Router user when no scheme is configured, so discovery must
                // answer 200 anonymous. The current admit() rejects any ambient user unconditionally
                // and answers 401, so this row is red.
                HttpResponse<Buffer> response = await(post().sendBuffer(discoverBody()));

                assertThat(response.statusCode())
                        .as("no scheme configured must bind canonical anonymous and ignore an ambient routing user")
                        .isEqualTo(200);
                assertDiscoverResult(response);
                assertBoundIdentity(PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
            }
            case PRIVILEGED_AMBIENT_USER_NO_SCHEME_ROW -> {
                startServer(McpDiscoverITFixture.options()
                        .injectAmbientUser(true)
                        .ambientUserPrincipal(
                                new JsonObject().put("sub", "ambient").put("roles", new JsonArray().add("admin"))));

                // Given: a *privileged* ambient routing user (roles=[admin]) with no scheme configured.
                // §4.7 stage 3 requires binding a canonical anonymous context without consulting the
                // ambient Router user, so its authorization claims must NOT bleed into the bound
                // identity (finding C1 — CWE-863). The status/actor assertions already pass because the
                // resolver derives anonymous from empty evidence; the decisive assertion is that the
                // bound authorization is empty despite the privileged ambient principal.
                HttpResponse<Buffer> response = await(post().sendBuffer(discoverBody()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertDiscoverResult(response);
                assertBoundIdentity(PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
                assertBoundAuthorizationEmpty();
            }
            case AMBIENT_EVIDENCE_NO_SCHEME_ROW -> {
                startServer(McpDiscoverITFixture.options().injectAmbientEvidence(true));

                // Given: ambient authentication evidence (a non-NONE JWT method carrying sub) appended
                // before the mount, with no scheme configured. §4.7 stage 3 requires the mount to bind a
                // canonical anonymous context without consulting ambient evidence: the resolver must see
                // an empty evidence list, so the bound identity is anonymous and the primary method NONE
                // rather than the leaked JWT method/subject (finding C1 — CWE-863).
                HttpResponse<Buffer> response = await(post().sendBuffer(discoverBody()));

                assertThat(response.statusCode()).isEqualTo(200);
                assertDiscoverResult(response);
                assertBoundIdentity(PrincipalType.ANONYMOUS, "anonymous", AuthMethodKind.NONE);
                assertBoundAuthorizationEmpty();
            }
            default -> fail("unknown T004 discovery row: " + row);
        }
    }

    private void startServer(McpDiscoverITFixture.Options options) throws Exception {
        fixture = McpDiscoverITFixture.start(vertx, options);
        server = fixture.server();
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private HttpRequest<Buffer> post() {
        return client.post(fixture.port(), "127.0.0.1", McpDiscoverITFixture.REQUEST_PATH)
                .putHeader("content-type", "application/json")
                .putHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .putHeader("Mcp-Method", "server/discover")
                .putHeader("Mcp-Name", "server/discover");
    }

    private static Buffer discoverBody() {
        return new JsonObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "server/discover")
                .put("params", discoverParams())
                .toBuffer();
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

    private void assertBoundIdentity(PrincipalType actorType, String actorId, AuthMethodKind methodKind) {
        SecurityContext bound = fixture.boundSecurityContext();
        assertThat(bound).as("identity resolution must bind a context").isNotNull();
        assertThat(bound.identity().actor().type()).isEqualTo(actorType);
        assertThat(bound.identity().actor().id()).isEqualTo(actorId);
        assertThat(bound.authentication().primaryMethod().normalizedKind()).isEqualTo(methodKind);
    }

    private void assertBoundAuthorizationEmpty() {
        SecurityContext bound = fixture.boundSecurityContext();
        assertThat(bound).as("identity resolution must bind a context").isNotNull();
        assertThat(bound.authorization().claims())
                .as("ambient authorization claims must not leak into the canonical-anonymous identity")
                .isEmpty();
    }

    private static void assertEstablishedSecurity(
            McpRequestCompletedEvent completed, PrincipalType actorType, String actorId) {
        assertThat(completed.terminal().method()).isEqualTo(McpMethod.SERVER_DISCOVER);
        assertThat(completed.terminal().outcome()).isEqualTo(McpOutcome.SUCCESS);
        SecurityContextSnapshot security = completed.terminal().security();
        assertThat(security)
                .as("a terminal reached after identity establishment must carry the security snapshot")
                .isNotNull();
        assertThat(security.identity().actor().type()).isEqualTo(actorType);
        assertThat(security.identity().actor().id()).isEqualTo(actorId);
    }

    private static void assertDiscoverResult(HttpResponse<Buffer> response) {
        JsonObject body = new JsonObject(response.bodyAsString());
        assertThat(body.getString("jsonrpc")).isEqualTo("2.0");
        JsonObject result = body.getJsonObject("result");
        assertThat(result).isNotNull();
        assertThat(result.getString("resultType")).isEqualTo("complete");
        assertThat(result.getJsonArray("supportedVersions")).containsExactly(PROTOCOL_VERSION);
        assertThat(result.getString("cacheScope")).isEqualTo("private");
        JsonObject serverInfo = result.getJsonObject("_meta").getJsonObject("io.modelcontextprotocol/serverInfo");
        assertThat(serverInfo.getString("name")).isEqualTo(SERVER_NAME);
        assertThat(serverInfo.getString("version")).isEqualTo(SERVER_VERSION);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- Fixture ---

    /**
     * Builds and starts one MCP discovery mount named {@code vertique-test/1.0}, optionally with a
     * configured authentication scheme, contributed route-auth handlers, and an ambient routing user
     * injected by a root handler ahead of the mount.
     */
    private static final class McpDiscoverITFixture {

        /** Path under the {@code /mcp/*} mount every row posts to. */
        static final String REQUEST_PATH = "/mcp/";

        private final AtomicReference<SecurityContext> boundSecurityContext = new AtomicReference<>();
        private final LinkedBlockingQueue<McpRequestCompletedEvent> completedEvents = new LinkedBlockingQueue<>();
        private final HttpServer server;
        private final int port;

        private McpDiscoverITFixture(Vertx vertx, Options options) throws Exception {
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName(SERVER_NAME)
                    .serverVersion(SERVER_VERSION)
                    .authenticationScheme(options.scheme)
                    .build();
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime(boundSecurityContext);
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of());
            McpRouterMount mount = new McpRouterMount(
                    config,
                    new McpServerConfigValidator(),
                    new McpRequestDispatcher(
                            config,
                            securityRuntime,
                            Set.of(recordingObserver()),
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
                                    Optional.empty())),
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    options.handlers,
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            Router router = Router.router(vertx);
            router.route().handler(new RequestContextLifecycle());
            if (options.injectAmbientUser) {
                JsonObject principal = options.ambientUserPrincipal != null
                        ? options.ambientUserPrincipal
                        : new JsonObject().put("sub", "ambient");
                router.route().handler(context -> {
                    ((UserContextInternal) context.userContext()).setUser(User.create(principal));
                    context.next();
                });
            }
            if (options.injectAmbientEvidence) {
                router.route().handler(context -> {
                    RestAuthenticationEvidence.append(
                            context,
                            new AuthenticationEvidence(
                                    DefaultAuthMethod.jwt(),
                                    Optional.of("ambient-evidence"),
                                    Instant.now(),
                                    Optional.empty(),
                                    new CustomVerificationSource("test", Map.of()),
                                    Map.of("sub", "ambient-evidence")));
                    context.next();
                });
            }
            router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
            this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
            this.port = server.actualPort();
        }

        static McpDiscoverITFixture start(Vertx vertx, Options options) throws Exception {
            return new McpDiscoverITFixture(vertx, options);
        }

        static Options options() {
            return new Options(null, Set.of(), false, null, false);
        }

        HttpServer server() {
            return server;
        }

        int port() {
            return port;
        }

        SecurityContext boundSecurityContext() {
            return boundSecurityContext.get();
        }

        McpRequestCompletedEvent awaitCompleted() throws Exception {
            McpRequestCompletedEvent event = completedEvents.poll(10, TimeUnit.SECONDS);
            assertThat(event)
                    .as("a completion event must be observed within the bound")
                    .isNotNull();
            return event;
        }

        private McpRequestLifecycleObserver recordingObserver() {
            return startedAt -> new McpRequestObservation() {
                @Override
                public void onCompleted(McpRequestCompletedEvent event) {
                    completedEvents.add(event);
                }
            };
        }

        private IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new SubjectEvidenceIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        /** Immutable per-row fixture configuration. */
        private record Options(
                String scheme,
                Set<RouteAuthHandler> handlers,
                boolean injectAmbientUser,
                JsonObject ambientUserPrincipal,
                boolean injectAmbientEvidence) {
            Options scheme(String scheme) {
                return new Options(scheme, handlers, injectAmbientUser, ambientUserPrincipal, injectAmbientEvidence);
            }

            Options handler(RouteAuthHandler handler) {
                return new Options(
                        scheme, Set.of(handler), injectAmbientUser, ambientUserPrincipal, injectAmbientEvidence);
            }

            Options injectAmbientUser(boolean injectAmbientUser) {
                return new Options(scheme, handlers, injectAmbientUser, ambientUserPrincipal, injectAmbientEvidence);
            }

            Options ambientUserPrincipal(JsonObject ambientUserPrincipal) {
                return new Options(scheme, handlers, injectAmbientUser, ambientUserPrincipal, injectAmbientEvidence);
            }

            Options injectAmbientEvidence(boolean injectAmbientEvidence) {
                return new Options(scheme, handlers, injectAmbientUser, ambientUserPrincipal, injectAmbientEvidence);
            }
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

    /**
     * A misbehaving optional-authentication scheme whose optional handler binds a user but appends no
     * evidence, producing the inconsistent user-only post-authentication state that must fail closed.
     */
    private static final class UserOnlyRouteAuthHandler implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "useronly";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return RoutingContext::next;
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                ((UserContextInternal) context.userContext())
                        .setUser(User.create(new JsonObject().put("sub", "user-only")));
                context.next();
            });
        }
    }
}
