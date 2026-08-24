// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.server.support.McpSubprocessHarness;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T028 TP-001 — exercises anonymous, valid-bearer, and invalid-bearer flows through the pinned
 * stable TypeScript client and one real final-2026 Streamable HTTP server.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class McpTypeScriptClientInteropIT {

    private static final String ANONYMOUS_ROW = "shouldDiscoverListAndCallAsAnonymous";
    private static final String BEARER_ROW = "shouldCallTheRestrictedToolAsBearerAlice";
    private static final String INVALID_BEARER_ROW = "shouldFailInvalidBearerWithoutAnonymousDowngrade";
    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final String PUBLIC_TOOL = "interop.public";
    private static final String RESTRICTED_TOOL = "interop.restricted";
    private static final String PUBLIC_RESULT = "public tool called";
    private static final String RESTRICTED_RESULT = "restricted tool called by alice";
    private static final String BEARER_ALICE = "Bearer alice";
    private static final String CLIENT_INTEGRITY =
            "sha512-8f1OghQ2rjzIOfqgUCP+8GiUWqRs89njoWLNqAe8kWmDePv3s1fZXseej+QXemssEuuOvLLmLO/kqM3IQHtISw==";
    private static final String PIN_RESOURCE = "mcp/pins/external-pins.json";
    private static final String LOCK_RESOURCE = "mcp/clients/typescript/package-lock.json";
    private static final String CLIENT_RESOURCE = "mcp/clients/typescript/client.mjs";

    private static TypeScriptClientFixture fixture;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext context) {
        assertLockMatchesPin();
        TypeScriptClientFixture.start(vertx).onComplete(context.succeeding(started -> {
            fixture = started;
            context.completeNow();
        }));
    }

    @AfterAll
    static void tearDown(VertxTestContext context) {
        if (fixture == null) {
            context.completeNow();
            return;
        }
        fixture.server().close().onComplete(context.succeeding(ignored -> context.completeNow()));
    }

    private static Stream<String> t028ContractRows() {
        return Stream.of(ANONYMOUS_ROW, BEARER_ROW, INVALID_BEARER_ROW);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t028ContractRows")
    @DisplayName("T028 stable TypeScript client interoperability matrix")
    void shouldEnforceT028ContractMatrix(String row) throws Exception {
        fixture.resetObservations();

        Path clientScript = resourcePath(CLIENT_RESOURCE);
        McpSubprocessHarness.Invocation invocation = McpSubprocessHarness.Invocation.of(
                        List.of("node", clientScript.toString(), "--url", fixture.serverUrl(), "--scenario", row))
                .withSensitiveValues(Set.of(BEARER_ALICE, "Bearer invalid"));
        McpSubprocessHarness.Result process =
                new McpSubprocessHarness(Duration.ofSeconds(60)).run(invocation).result();

        assertThat(process.timedOut())
                .as(row + " must settle within the process bound")
                .isFalse();
        assertThat(process.exitCode())
                .as(() -> row + " client stderr:\n" + process.stderr())
                .isZero();
        assertThat(process.normalExitCount()).isEqualTo(1);
        assertThat(process.forcedTerminationCount()).isZero();
        assertThat(process.workspace()).doesNotExist();
        assertThat(process.stdout()).doesNotContain(BEARER_ALICE, "Bearer invalid");
        assertThat(process.stderr())
                .as(row + " must not trigger stable-client tool-definition exclusion")
                .doesNotContain("[mcp-sdk] excluding tool");

        JsonObject report = resultReport(process.stdout());
        assertThat(report.getString("scenario")).isEqualTo(row);
        switch (row) {
            case ANONYMOUS_ROW -> assertSuccessfulCall(report, PUBLIC_TOOL, PUBLIC_RESULT, List.of(PUBLIC_TOOL));
            case BEARER_ROW ->
                assertSuccessfulCall(report, RESTRICTED_TOOL, RESTRICTED_RESULT, List.of(PUBLIC_TOOL, RESTRICTED_TOOL));
            case INVALID_BEARER_ROW -> assertInvalidBearerFailure(report);
            default -> throw new AssertionError("unrecognized T028 row: " + row);
        }
    }

    private static void assertSuccessfulCall(
            JsonObject report, String expectedTool, String expectedText, List<String> expectedVisibleTools) {
        assertThat(report.getString("protocolEra")).isEqualTo("modern");
        assertThat(report.getString("negotiatedProtocolVersion")).isEqualTo(PROTOCOL_VERSION);
        assertThat(report.getJsonArray("discoveredProtocolVersions").stream().map(String.class::cast))
                .contains(PROTOCOL_VERSION);
        assertThat(report.getJsonArray("toolNames").stream().map(String.class::cast))
                .containsExactlyInAnyOrderElementsOf(expectedVisibleTools);
        assertThat(report.getString("calledTool")).isEqualTo(expectedTool);
        JsonObject result = report.getJsonObject("result");
        // The modern client validates the mandatory wire discriminator, classifies the result, and
        // deliberately removes resultType from the public CallToolResult it returns. R22's direct
        // production-wire proof owns the byte-level assertion; reaching this API result proves the
        // pinned client accepted that discriminator.
        assertThat(result.getBoolean("isError")).isFalse();
        assertThat(result.getJsonArray("content").getJsonObject(0).getString("text"))
                .isEqualTo(expectedText);

        if (PUBLIC_TOOL.equals(expectedTool)) {
            assertThat(fixture.publicInvocations().get()).isEqualTo(1);
            assertThat(fixture.restrictedInvocations().get()).isZero();
            assertThat(fixture.absentCredentials().get()).isPositive();
            assertThat(fixture.validCredentials().get()).isZero();
        } else {
            assertThat(fixture.publicInvocations().get()).isZero();
            assertThat(fixture.restrictedInvocations().get()).isEqualTo(1);
            assertThat(fixture.absentCredentials().get()).isZero();
            assertThat(fixture.validCredentials().get()).isPositive();
        }
        assertThat(fixture.invalidCredentials().get()).isZero();
    }

    private static void assertInvalidBearerFailure(JsonObject report) {
        assertThat(report.getBoolean("failed")).isTrue();
        assertThat(report.getString("errorMessage")).isNotBlank();
        assertThat(fixture.invalidCredentials().get()).isPositive();
        assertThat(fixture.absentCredentials().get())
                .as("the client must not retry the invalid credential as anonymous")
                .isZero();
        assertThat(fixture.validCredentials().get()).isZero();
        assertThat(fixture.publicInvocations().get()).isZero();
        assertThat(fixture.restrictedInvocations().get()).isZero();
    }

    private static void assertLockMatchesPin() {
        JsonObject pinLock = new JsonObject(readResource(PIN_RESOURCE));
        JsonObject clientPin = pinLock.getJsonArray("artifacts").stream()
                .map(JsonObject.class::cast)
                .filter(value -> "typescript-client".equals(value.getString("id")))
                .findFirst()
                .orElseThrow();
        JsonObject packageLock = new JsonObject(readResource(LOCK_RESOURCE));
        JsonObject clientPackage =
                packageLock.getJsonObject("packages").getJsonObject("node_modules/@modelcontextprotocol/client");
        assertThat(clientPackage.getString("version"))
                .isEqualTo(clientPin.getString("version"))
                .isEqualTo("2.0.0");
        assertThat(clientPackage.getString("integrity"))
                .isEqualTo(clientPin.getString("integrity"))
                .isEqualTo(CLIENT_INTEGRITY);
        assertThat(clientPackage.getString("resolved")).isEqualTo(clientPin.getString("sourceReference"));
    }

    private static JsonObject resultReport(String output) {
        String prefix = "T028_RESULT=";
        List<String> reports = output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .toList();
        assertThat(reports)
                .as("the TypeScript fixture must emit exactly one result report")
                .singleElement();
        return new JsonObject(reports.getFirst());
    }

    private static String readResource(String resource) {
        try (InputStream input =
                McpTypeScriptClientInteropIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("could not read test resource: " + resource, failure);
        }
    }

    private static Path resourcePath(String resource) {
        try {
            var url = McpTypeScriptClientInteropIT.class.getClassLoader().getResource(resource);
            if (url == null) {
                throw new AssertionError("missing test resource: " + resource);
            }
            return Path.of(url.toURI());
        } catch (URISyntaxException failure) {
            throw new AssertionError("invalid resource URI: " + resource, failure);
        }
    }

    private record TypeScriptClientFixture(
            HttpServer server,
            AtomicInteger publicInvocations,
            AtomicInteger restrictedInvocations,
            AtomicInteger absentCredentials,
            AtomicInteger validCredentials,
            AtomicInteger invalidCredentials) {

        private static final McpToolAnnotations ANNOTATIONS = new McpToolAnnotations(true, false, true, false);
        private static final String CLOSED_OBJECT_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false}";

        static Future<TypeScriptClientFixture> start(Vertx vertx) {
            AtomicInteger publicInvocations = new AtomicInteger();
            AtomicInteger restrictedInvocations = new AtomicInteger();
            AtomicInteger absentCredentials = new AtomicInteger();
            AtomicInteger validCredentials = new AtomicInteger();
            AtomicInteger invalidCredentials = new AtomicInteger();
            McpServerConfig config = McpServerConfig.builder()
                    .enabled(true)
                    .serverName("vertique-typescript-interop")
                    .serverVersion("1.0")
                    .authenticationScheme("bearer")
                    .build();
            McpToolRegistry registry = McpToolRegistry.build(Set.of(
                    tool(
                            PUBLIC_TOOL,
                            new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null),
                            PUBLIC_RESULT,
                            publicInvocations),
                    tool(
                            RESTRICTED_TOOL,
                            new McpToolAccess(McpAccessMode.RESTRICTED, List.of("ops"), null),
                            RESTRICTED_RESULT,
                            restrictedInvocations)));
            RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
            McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                    Optional.empty(),
                    Optional.empty(),
                    Set.of(),
                    new SecurityEventEmitter(Set.of()),
                    NO_OP_CONTEXT_HOLDER,
                    securityRuntime,
                    Optional.empty()));
            HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();
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
                            policyEnforcer,
                            NO_OP_CONTEXT_HOLDER,
                            new CorrelationContextFactory(Optional.empty())),
                    Set.of(new BearerRouteAuthHandler(absentCredentials, validCredentials, invalidCredentials)),
                    identityResolution(securityRuntime),
                    httpConfig,
                    registry);
            return mount.createRouter(vertx).compose(mcpRouter -> {
                Router router = Router.router(vertx);
                router.route().handler(new RequestContextLifecycle());
                router.route(config.mountPath()).subRouter(mcpRouter);
                return vertx.createHttpServer()
                        .requestHandler(router)
                        .listen(0, "127.0.0.1")
                        .map(server -> new TypeScriptClientFixture(
                                server,
                                publicInvocations,
                                restrictedInvocations,
                                absentCredentials,
                                validCredentials,
                                invalidCredentials));
            });
        }

        private static McpToolInvoker tool(
                String name, McpToolAccess access, String resultText, AtomicInteger invocationCount) {
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    name,
                    null,
                    "TypeScript interop fixture tool " + name,
                    ANNOTATIONS,
                    CLOSED_OBJECT_SCHEMA,
                    null,
                    access);
            return new McpToolInvoker() {
                @Override
                public McpToolDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
                    return new McpPreparedToolCall() {
                        @Override
                        public Map<String, Object> normalizedArguments() {
                            return Map.of();
                        }

                        @Override
                        public Future<McpToolResult<?>> invoke() {
                            invocationCount.incrementAndGet();
                            return Future.succeededFuture(McpToolResult.text(resultText));
                        }
                    };
                }
            };
        }

        private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
            return new IdentityResolutionMiddleware(
                    Set.of(new SubjectRoleIdentityResolver()),
                    Optional.of(new DefaultSecurityClaimMapper()),
                    new SecurityEventEmitter(Set.of()),
                    securityRuntime,
                    NO_OP_CONTEXT_HOLDER);
        }

        String serverUrl() {
            return "http://127.0.0.1:" + server.actualPort() + "/mcp/";
        }

        void resetObservations() {
            publicInvocations.set(0);
            restrictedInvocations.set(0);
            absentCredentials.set(0);
            validCredentials.set(0);
            invalidCredentials.set(0);
        }
    }

    private record SubjectRoleIdentityResolver() implements SecurityIdentityResolver {

        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
            }
            Object subject = context.evidence().getFirst().safeAttributes().get("sub");
            return Future.succeededFuture(Optional.of(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, String.valueOf(subject), Map.of()))));
        }
    }

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

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    private record BearerRouteAuthHandler(
            AtomicInteger absentCredentials, AtomicInteger validCredentials, AtomicInteger invalidCredentials)
            implements RouteAuthHandler {

        @Override
        public String schemeName() {
            return "bearer";
        }

        @Override
        public Handler<RoutingContext> createHandler() {
            return context -> context.fail(401);
        }

        @Override
        public Optional<Handler<RoutingContext>> createOptionalHandler() {
            return Optional.of(context -> {
                String credential = context.request().getHeader("Authorization");
                if (credential == null) {
                    absentCredentials.incrementAndGet();
                    context.next();
                    return;
                }
                if (!BEARER_ALICE.equals(credential)) {
                    invalidCredentials.incrementAndGet();
                    context.fail(401);
                    return;
                }
                validCredentials.incrementAndGet();
                RestAuthenticationEvidence.append(
                        context,
                        new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("alice"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", "alice")));
                ((UserContextInternal) context.userContext())
                        .setUser(User.create(
                                new JsonObject().put("sub", "alice").put("roles", new JsonArray().add("ops"))));
                context.next();
            });
        }
    }
}
