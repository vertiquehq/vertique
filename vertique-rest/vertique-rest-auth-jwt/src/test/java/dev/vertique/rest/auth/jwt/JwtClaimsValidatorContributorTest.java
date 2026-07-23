// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.UserContext;
import io.vertx.ext.web.impl.UserContextInternal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtClaimsValidatorContributor}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>When the {@link JwtClaimsValidator} succeeds, {@code ctx.next()} is called and no
 *       {@link CredentialRejectedEvent} is emitted.</li>
 *   <li>When the {@link JwtClaimsValidator} throws, a {@link CredentialRejectedEvent} is emitted
 *       with reason code {@code JWT_CLAIMS_INVALID} before {@code ctx.fail(401, e)} is called
 *       — matching the requirement in F-W6.</li>
 *   <li>When the routing context has no authenticated user ({@code ctx.user() == null}), the
 *       handler skips validation and calls {@code ctx.next()} without emitting a rejection
 *       event.</li>
 *   <li>The rejection event carries the correct {@code reasonCode} regardless of which exception
 *       type the validator throws.</li>
 *   <li>The request fails with HTTP 401 on claims validation failure.</li>
 * </ul>
 *
 * <p>Tests use a Mockito mock for {@link RoutingContext} and a {@link CapturingObserver} to
 * intercept emitted {@link CredentialRejectedEvent}s, following the pattern established by
 * {@link JwtBearerSecuritySchemeHandlerTest}.
 */
class JwtClaimsValidatorContributorTest {

    // --- Test doubles ---

    /**
     * Capturing {@link SecurityEventObserver} that records every {@link CredentialRejectedEvent}
     * emitted, in emission order. Non-final so tests can subclass it for call-order assertions.
     */
    static class CapturingObserver implements SecurityEventObserver {

        /** All credential-rejected events received, in emission order. */
        final List<CredentialRejectedEvent> rejections = new ArrayList<>();

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent e) {
            rejections.add(e);
            return Future.succeededFuture();
        }
    }

    /** The capturing observer for the current test; set by {@link #buildFixture}. */
    private CapturingObserver lastObserver;

    // --- Fixture constants ---

    private static final String ISSUER = "https://issuer.example.com";

    // --- Shared helpers ---

    /**
     * Creates a stub {@link RoutingContext} backed by an in-memory map. The mock also implements
     * {@link UserContextInternal} (via extra interfaces) so the production code can cast
     * {@code ctx.userContext()} to {@link UserContextInternal}.
     *
     * @param backingMap the map backing routing-context data
     * @return a mocked routing context that also implements {@link UserContextInternal}
     */
    private static RoutingContext stubContext(Map<String, Object> backingMap) {
        RoutingContext ctx = mock(RoutingContext.class, withSettings().extraInterfaces(UserContextInternal.class));

        when(ctx.get(anyString())).thenAnswer(inv -> backingMap.get(inv.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(inv -> {
            backingMap.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return ctx;
        });

        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);

        UserContextInternal userContextInternal = (UserContextInternal) ctx;
        when(ctx.userContext()).thenReturn((UserContext) userContextInternal);

        AtomicReference<User> userRef = new AtomicReference<>();
        doAnswer(inv -> {
                    userRef.set(inv.getArgument(0));
                    return null;
                })
                .when(userContextInternal)
                .setUser(any());
        when(ctx.user()).thenAnswer(inv -> userRef.get());

        return ctx;
    }

    /**
     * Creates a stub {@link ContextHolder} that returns the supplied correlation from
     * {@code current(CorrelationContext.class)}.
     *
     * @param correlation the correlation context to return
     * @return a mocked holder
     */
    private static ContextHolder holderWith(CorrelationContext correlation) {
        ContextHolder holder = mock(ContextHolder.class);
        when(holder.current(CorrelationContext.class)).thenReturn(Optional.of(correlation));
        return holder;
    }

    /**
     * Builds a minimal {@link CorrelationContext} using the real factory.
     *
     * @return a correlation context with fixed test ids
     */
    private static CorrelationContext stubCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    /**
     * Builds a stub {@link User} whose {@code principal()} contains the given JSON body.
     *
     * @param principal the JWT body claims
     * @return a mocked user
     */
    private static User stubUserWithPrincipal(JsonObject principal) {
        User user = mock(User.class);
        when(user.principal()).thenReturn(principal);
        return user;
    }

    /**
     * Builds a default {@link JwtValidationConfig} with the test issuer.
     *
     * @return a validation config
     */
    private static JwtValidationConfig testValidationConfig() {
        return JwtValidationConfig.builder().issuer(ISSUER).build();
    }

    /**
     * Holds the route handler captured after {@link JwtClaimsValidatorContributor#contribute} runs,
     * and the observer wired into the reporter.
     *
     * @param handler  the routing handler registered on the {@link RouteRegistration}
     */
    record ContributorFixture(Handler<RoutingContext> handler) {}

    /**
     * Builds a {@link JwtClaimsValidatorContributor} with the given validator and the supplied
     * (possibly customised) {@link CapturingObserver}. Calls {@link #contribute} on a mocked
     * {@link OperationRegistrationContext} and captures the registered route handler.
     *
     * <p>The supplied observer is assigned to {@link #lastObserver} so tests can assert against it.
     *
     * @param validator the claims validator to run
     * @param observer  the capturing observer to wire into the reporter
     * @return a {@link ContributorFixture} holding the captured route handler
     */
    private ContributorFixture buildFixture(JwtClaimsValidator validator, CapturingObserver observer) {
        lastObserver = observer;
        CorrelationContext correlation = stubCorrelation();
        ContextHolder holder = holderWith(correlation);
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
        DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);

        JwtClaimsValidatorContributor contributor =
                new JwtClaimsValidatorContributor(validator, reporter, testValidationConfig());

        AtomicReference<Handler<RoutingContext>> handlerRef = new AtomicReference<>();
        RouteRegistration route = mock(RouteRegistration.class);
        when(route.addHandler(any())).thenAnswer(inv -> {
            handlerRef.set(inv.getArgument(0));
            return route;
        });

        OperationRegistrationContext context = mock(OperationRegistrationContext.class);
        when(context.route()).thenReturn(route);
        when(context.operationId()).thenReturn("testOperation");

        contributor.contribute(context);

        return new ContributorFixture(handlerRef.get());
    }

    /**
     * Builds a fixture using a fresh default {@link CapturingObserver}.
     *
     * @param validator the claims validator to run
     * @return a {@link ContributorFixture} holding the captured route handler
     */
    private ContributorFixture buildFixture(JwtClaimsValidator validator) {
        return buildFixture(validator, new CapturingObserver());
    }

    // --- Tests ---

    @Nested
    @DisplayName("priority()")
    class PriorityTests {

        @Test
        @DisplayName("Returns 50 — runs before authorization (100) and SecurityContext bridging (200)")
        void returnsPriority50() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);

            JwtClaimsValidatorContributor contributor =
                    new JwtClaimsValidatorContributor(claims -> {}, reporter, testValidationConfig());

            assertEquals(50, contributor.priority());
        }
    }

    @Nested
    @DisplayName("Claims validation success path")
    class SuccessPath {

        private Map<String, Object> store;
        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            store = new HashMap<>();
            ctx = stubContext(store);
        }

        @Test
        @DisplayName("Validator succeeds → ctx.next() called, no rejection event emitted")
        void validatorSucceeds_nextCalledNoRejectionEvent() {
            // Arrange
            JsonObject principal = new JsonObject().put("sub", "alice").put("tenant_id", "acme");
            User user = stubUserWithPrincipal(principal);
            doReturn(user).when(ctx).user();

            AtomicBoolean nextCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        nextCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .next();

            ContributorFixture fixture = buildFixture(claims -> {});

            // Act
            fixture.handler().handle(ctx);

            // Assert
            assertTrue(nextCalled.get(), "ctx.next() must be called when validation passes");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection event should be emitted on success");
        }

        @Test
        @DisplayName("No authenticated user (ctx.user() == null) → ctx.next() called, no rejection event")
        void noAuthenticatedUser_nextCalledNoRejectionEvent() {
            // Arrange: ctx.user() returns null (unauthenticated or anonymous)
            doReturn(null).when(ctx).user();

            AtomicBoolean nextCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        nextCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .next();

            ContributorFixture fixture = buildFixture(claims -> {
                throw new SecurityException("should never be called");
            });

            // Act
            fixture.handler().handle(ctx);

            // Assert
            assertTrue(nextCalled.get(), "ctx.next() must be called when user is absent");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection event when user is absent");
        }

        @Test
        @DisplayName("User with null principal → ctx.next() called, no rejection event")
        void userWithNullPrincipal_nextCalledNoRejectionEvent() {
            // Arrange
            User user = mock(User.class);
            when(user.principal()).thenReturn(null);
            doReturn(user).when(ctx).user();

            AtomicBoolean nextCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        nextCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .next();

            ContributorFixture fixture = buildFixture(claims -> {});

            // Act
            fixture.handler().handle(ctx);

            // Assert
            assertTrue(nextCalled.get(), "ctx.next() must be called when principal is null");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection event when principal is null");
        }
    }

    @Nested
    @DisplayName("Claims validation failure path — CredentialRejectedEvent emitted")
    class FailurePath {

        private Map<String, Object> store;
        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            store = new HashMap<>();
            ctx = stubContext(store);
            // Stub fail(int, Throwable) so the production code can call ctx.fail(401, e)
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));
        }

        @Test
        @DisplayName("Validator throws SecurityException → CredentialRejectedEvent emitted with JWT_CLAIMS_INVALID")
        void validatorThrowsSecurityException_credentialRejectedEventWithJwtClaimsInvalid() {
            // Arrange
            JsonObject principal = new JsonObject().put("sub", "alice");
            User user = stubUserWithPrincipal(principal);
            doReturn(user).when(ctx).user();

            AtomicBoolean failCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        failCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .fail(anyInt(), any(Throwable.class));

            ContributorFixture fixture = buildFixture(claims -> {
                throw new SecurityException("Missing required claim: tenant_id");
            });

            // Act
            fixture.handler().handle(ctx);

            // Assert (a): CredentialRejectedEvent emitted with JWT_CLAIMS_INVALID
            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size(), "exactly one rejection event must be emitted");
            assertEquals(
                    "JWT_CLAIMS_INVALID",
                    rejections.get(0).reasonCode(),
                    "reasonCode must be JWT_CLAIMS_INVALID for claims validation failure");

            // Assert (b): request still fails with 401
            assertTrue(failCalled.get(), "ctx.fail(401, e) must be called after emitting the rejection event");
        }

        @Test
        @DisplayName("Validator throws RuntimeException → CredentialRejectedEvent emitted with JWT_CLAIMS_INVALID")
        void validatorThrowsRuntimeException_credentialRejectedEventWithJwtClaimsInvalid() {
            // Arrange
            JsonObject principal = new JsonObject().put("sub", "bob");
            User user = stubUserWithPrincipal(principal);
            doReturn(user).when(ctx).user();

            ContributorFixture fixture = buildFixture(claims -> {
                throw new RuntimeException("Custom claims check failed");
            });

            // Act
            fixture.handler().handle(ctx);

            // Assert
            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size(), "exactly one rejection event must be emitted");
            assertEquals(
                    "JWT_CLAIMS_INVALID",
                    rejections.get(0).reasonCode(),
                    "reasonCode must be JWT_CLAIMS_INVALID regardless of exception type");
        }

        @Test
        @DisplayName("Validator fails → rejection event emitted BEFORE ctx.fail() is called")
        void validatorFails_eventEmittedBeforeCtxFail() {
            // Arrange: track call order to prove event precedes fail
            List<String> callOrder = new ArrayList<>();

            CapturingObserver orderedObserver = new CapturingObserver() {
                @Override
                public Future<Void> onCredentialRejected(CredentialRejectedEvent e) {
                    callOrder.add("event");
                    return super.onCredentialRejected(e);
                }
            };

            doAnswer(inv -> {
                        callOrder.add("fail");
                        return null;
                    })
                    .when(ctx)
                    .fail(anyInt(), any(Throwable.class));

            JsonObject principal = new JsonObject().put("sub", "charlie");
            doReturn(stubUserWithPrincipal(principal)).when(ctx).user();

            ContributorFixture fixture = buildFixture(
                    claims -> {
                        throw new SecurityException("bad claim");
                    },
                    orderedObserver);

            // Act
            fixture.handler().handle(ctx);

            // Assert: event must appear before fail in the call order
            assertEquals(List.of("event", "fail"), callOrder, "rejection event must be emitted before ctx.fail()");
        }

        @Test
        @DisplayName("Validator fails → ctx.next() is NOT called")
        void validatorFails_nextNotCalled() {
            // Arrange
            JsonObject principal = new JsonObject().put("sub", "dave");
            doReturn(stubUserWithPrincipal(principal)).when(ctx).user();

            AtomicBoolean nextCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        nextCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .next();

            ContributorFixture fixture = buildFixture(claims -> {
                throw new SecurityException("claims rejected");
            });

            // Act
            fixture.handler().handle(ctx);

            // Assert
            assertFalse(nextCalled.get(), "ctx.next() must NOT be called when validation fails");
        }

        @Test
        @DisplayName("Validator fails → rejection event auth method is 'jwt'")
        void validatorFails_rejectionEventAuthMethodIsJwt() {
            // Arrange
            JsonObject principal = new JsonObject().put("sub", "eve");
            doReturn(stubUserWithPrincipal(principal)).when(ctx).user();

            ContributorFixture fixture = buildFixture(claims -> {
                throw new SecurityException("tenant check failed");
            });

            // Act
            fixture.handler().handle(ctx);

            // Assert
            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size(), "one rejection event");
            assertEquals("jwt", rejections.get(0).attemptedMethod().id(), "auth method must be 'jwt'");
        }

        @Test
        @DisplayName("Validator fails → exactly one rejection event per request")
        void validatorFails_exactlyOneRejectionEventPerRequest() {
            // Arrange: two separate routing contexts simulating two requests
            Map<String, Object> store2 = new HashMap<>();
            RoutingContext ctx2 = stubContext(store2);
            doNothing().when(ctx2).fail(anyInt(), any(Throwable.class));

            JsonObject principal = new JsonObject().put("sub", "frank");
            doReturn(stubUserWithPrincipal(principal)).when(ctx).user();
            doReturn(stubUserWithPrincipal(principal)).when(ctx2).user();

            ContributorFixture fixture = buildFixture(claims -> {
                throw new SecurityException("reject");
            });

            // Act: two separate requests through the same contributor handler
            fixture.handler().handle(ctx);
            fixture.handler().handle(ctx2);

            // Assert: two rejection events, one per request
            assertEquals(2, lastObserver.rejections.size(), "exactly one rejection event per request");
            lastObserver.rejections.forEach(e ->
                    assertEquals("JWT_CLAIMS_INVALID", e.reasonCode(), "both events must have JWT_CLAIMS_INVALID"));
        }
    }
}
