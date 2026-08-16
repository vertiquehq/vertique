// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.logging.MDCContexts;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for the {@link IdentityResolutionMiddleware} + {@link RequestContextLifecycle}
 * interaction, replacing the deleted {@code SecurityContextMiddlewareIT}.
 *
 * <p>Covers:
 * <ul>
 *   <li>An authenticated HTTP request reaches a handler that sees the expected
 *       {@link SecurityContext} via {@link HolderBackedSecurityRuntime#current()} and via the
 *       unified {@link ContextValues} facade.</li>
 *   <li>A second HTTP request to the same Vert.x instance sees {@code null} from
 *       {@code runtime.current()} — proves the SC scope was properly closed after the first
 *       request and does not leak across requests.</li>
 *   <li>MDC keys ({@code userId}, {@code clientId}) are populated during the request and not
 *       present during a subsequent request (scope restored by lifecycle close).</li>
 *   <li>The {@code authMethod} MDC key is absent for an anonymous request where
 *       evidence is empty ({@code normalizedKind() == AuthMethodKind.NONE}).</li>
 * </ul>
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * Every test here asserts on server-side captured state (the bound {@link SecurityContext}, the MDC
 * copy) rather than on the body, so the raw idiom is latent rather than actively broken here — but a
 * {@link WebClient} aggregates the response before completing the send, which removes the trap for
 * whoever next adds a body assertion. The responses are consequently projected to their status codes:
 * the previous body reads existed only to complete the exchange, and the aggregation makes them
 * redundant.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class IdentityResolutionMiddlewareIT {

    private HttpServer server;
    private WebClient client;
    private HolderBackedSecurityRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new HolderBackedSecurityRuntime((sc, secure) -> null);
    }

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    // --- Helpers ---

    /**
     * Builds the middleware with the default resolver set and a no-op context holder (no
     * CorrelationContext available for event emission; that path is tested in the unit test).
     *
     * @return a configured {@link IdentityResolutionMiddleware}
     */
    private IdentityResolutionMiddleware buildMiddleware() {
        return buildMiddleware(emptyHolder());
    }

    private IdentityResolutionMiddleware buildMiddleware(ContextHolder holder) {
        return new IdentityResolutionMiddleware(
                Set.of(new DefaultSecurityIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                runtime,
                holder);
    }

    private static ContextHolder emptyHolder() {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    // --- Tests ---

    @Test
    @DisplayName("Authenticated request: handler sees expected SC via runtime.current() and ContextValues")
    void authenticatedRequestHandlerSeesSecurityContext(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<SecurityContext> capturedViaRuntime = new AtomicReference<>();
        AtomicReference<SecurityContext> capturedViaValues = new AtomicReference<>();

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        // Simulate auth handler stashing evidence with sub + azp attributes
        router.route("/secure").handler(rc -> {
            AuthenticationEvidence evidence = new AuthenticationEvidence(
                    DefaultAuthMethod.jwt(),
                    Optional.of("alice"),
                    Instant.now(),
                    Optional.empty(),
                    new CustomVerificationSource("test", Map.of()),
                    Map.of("sub", "alice", "azp", "my-client"));
            RestAuthenticationEvidence.append(rc, evidence);
            ((UserContextInternal) rc.userContext())
                    .setUser(User.create(new JsonObject()
                            .put("sub", "alice")
                            .put("azp", "my-client")
                            .put("scope", "read write")));
            rc.next();
        });
        router.route("/secure").handler(buildMiddleware());
        router.route("/secure").handler(rc -> {
            capturedViaRuntime.set(runtime.current());
            capturedViaValues.set(ContextValues.current(SecurityContext.class).orElse(null));
            rc.response().setStatusCode(200).end("ok");
        });

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            client.get(s.actualPort(), "127.0.0.1", "/secure")
                    .send()
                    .map(resp -> {
                        assertEquals(200, resp.statusCode());
                        return resp.statusCode();
                    })
                    .onComplete(ctx.succeeding(status -> {
                        SecurityContext sc = capturedViaRuntime.get();
                        assertNotNull(sc, "runtime.current() must return the bound SC");
                        assertEquals(PrincipalType.USER, sc.identity().actor().type());
                        assertEquals("alice", sc.identity().actor().id());
                        assertEquals(
                                AuthMethodKind.JWT,
                                sc.authentication().primaryMethod().normalizedKind());
                        assertSame(
                                sc,
                                capturedViaValues.get(),
                                "ContextValues.current() must return the same instance as runtime.current()");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    @DisplayName("SC scope closes after request end: second request sees null from runtime.current()")
    void secondRequestSeesNullAfterFirstRequestEnds(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<SecurityContext> secondRequestSc = new AtomicReference<>();

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        // Route 1: authenticated — stashes evidence → binds SC
        router.route("/first").handler(rc -> {
            AuthenticationEvidence evidence = new AuthenticationEvidence(
                    DefaultAuthMethod.jwt(),
                    Optional.of("bob"),
                    Instant.now(),
                    Optional.empty(),
                    new CustomVerificationSource("test", Map.of()),
                    Map.of("sub", "bob"));
            RestAuthenticationEvidence.append(rc, evidence);
            ((UserContextInternal) rc.userContext()).setUser(User.create(new JsonObject().put("sub", "bob")));
            rc.next();
        });
        router.route("/first").handler(buildMiddleware());
        router.route("/first").handler(rc -> rc.response().setStatusCode(200).end("first"));

        // Route 2: captures runtime.current() BEFORE the middleware binds any new SC.
        router.route("/second").handler(rc -> {
            secondRequestSc.set(runtime.current());
            rc.next();
        });
        router.route("/second").handler(buildMiddleware());
        router.route("/second").handler(rc -> rc.response().setStatusCode(200).end("second"));

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            int port = s.actualPort();

            client.get(port, "127.0.0.1", "/first")
                    .send()
                    .compose(first -> Future.<Void>future(p -> vertx.setTimer(50, id -> p.complete(null))))
                    .compose(ignored -> client.get(port, "127.0.0.1", "/second").send())
                    .onComplete(ctx.succeeding(second -> {
                        assertNull(
                                secondRequestSc.get(),
                                "runtime.current() must be null inside the second request "
                                        + "— SC scope from first request must have closed");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    @DisplayName("MDC keys userId/clientId are present during request and absent in a subsequent request")
    void mdcKeysAreScopedToRequest(Vertx vertx, VertxTestContext ctx) {
        AtomicReference<Map<String, String>> duringFirst = new AtomicReference<>();
        AtomicReference<Map<String, String>> duringSecond = new AtomicReference<>();

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());

        // Route 1: authenticated with userId + clientId via safe attributes
        router.route("/first").handler(rc -> {
            AuthenticationEvidence evidence = new AuthenticationEvidence(
                    DefaultAuthMethod.jwt(),
                    Optional.of("carol"),
                    Instant.now(),
                    Optional.empty(),
                    new CustomVerificationSource("test", Map.of()),
                    Map.of("sub", "carol", "azp", "cli-app"));
            RestAuthenticationEvidence.append(rc, evidence);
            ((UserContextInternal) rc.userContext())
                    .setUser(User.create(new JsonObject().put("sub", "carol").put("azp", "cli-app")));
            rc.next();
        });
        router.route("/first").handler(buildMiddleware());
        router.route("/first").handler(rc -> {
            duringFirst.set(MDCContexts.copy());
            rc.response().setStatusCode(200).end("first");
        });

        // Route 2: anonymous — MDC keys from first request must not be present
        router.route("/second").handler(buildMiddleware());
        router.route("/second").handler(rc -> {
            duringSecond.set(MDCContexts.copy());
            rc.response().setStatusCode(200).end("second");
        });

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            int port = s.actualPort();

            client.get(port, "127.0.0.1", "/first")
                    .send()
                    .compose(first -> Future.<Void>future(p -> vertx.setTimer(50, id -> p.complete(null))))
                    .compose(ignored -> client.get(port, "127.0.0.1", "/second").send())
                    .onComplete(ctx.succeeding(second -> {
                        Map<String, String> firstMdc = duringFirst.get();
                        assertNotNull(firstMdc);
                        assertEquals(
                                "carol", firstMdc.get("userId"), "userId must be in MDC during authenticated request");
                        assertEquals(
                                "cli-app",
                                firstMdc.get("clientId"),
                                "clientId must be in MDC during authenticated request");

                        Map<String, String> secondMdc = duringSecond.get();
                        assertNotNull(secondMdc);
                        assertFalse(
                                secondMdc.containsKey("userId"),
                                "userId must not leak into subsequent anonymous request");
                        assertFalse(
                                secondMdc.containsKey("clientId"),
                                "clientId must not leak into subsequent anonymous request");
                        ctx.completeNow();
                    }));
        }));
    }

    @Test
    @DisplayName("authMethod MDC key is absent for an anonymous request (no evidence)")
    void authMethodMdcKeyAbsentForAnonymousRequest(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean authMethodPresent = new AtomicBoolean(false);

        Router router = Router.router(vertx);
        router.route("/*").handler(new RequestContextLifecycle());
        // No evidence appended — anonymous
        router.route("/anon").handler(buildMiddleware());
        router.route("/anon").handler(rc -> {
            authMethodPresent.set(MDCContexts.copy().containsKey("authMethod"));
            rc.response().setStatusCode(200).end("anon");
        });

        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1").onComplete(ctx.succeeding(s -> {
            server = s;
            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            client.get(s.actualPort(), "127.0.0.1", "/anon")
                    .send()
                    .map(resp -> resp.statusCode())
                    .onComplete(ctx.succeeding(status -> {
                        assertFalse(
                                authMethodPresent.get(), "authMethod MDC key must be absent for anonymous requests");
                        ctx.completeNow();
                    }));
        }));
    }
}
