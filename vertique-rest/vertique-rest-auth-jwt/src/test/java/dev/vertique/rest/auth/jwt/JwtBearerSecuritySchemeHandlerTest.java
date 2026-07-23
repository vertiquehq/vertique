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
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.security.DefaultCredentialRejectionReporter;
import dev.vertique.rest.security.RestAuthenticationEvidence;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.JwksVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.UserContext;
import io.vertx.ext.web.handler.HttpException;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.impl.UserContextInternal;
import java.security.SignatureException;
import java.time.Instant;
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
 * Unit tests for {@link JwtBearerSecuritySchemeHandler}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Scheme name accessors work correctly.</li>
 *   <li>On JWT verification success the handler appends an {@link AuthenticationEvidence} entry via
 *       {@link RestAuthenticationEvidence} with the correct method, verification source, expiry
 *       ({@code notAfter}), and safe attributes ({@code sub} only — no raw token material).</li>
 *   <li>On JWT verification failure the handler calls {@link dev.vertique.rest.security.CredentialRejectionReporter}
 *       and a {@link CredentialRejectedEvent} is stashed with the correct {@code reasonCode}.</li>
 *   <li>No raw bearer token string appears in any {@code safeAttributes} field for rejection
 *       events.</li>
 *   <li>Missing or malformed {@code Authorization} header is handled before reaching
 *       {@link JWTAuth#authenticate} with the correct reason code.</li>
 * </ul>
 *
 * <p>Tests use a Mockito mock for {@link JWTAuth} to simulate success and failure paths, a
 * map-backed {@link RoutingContext} stub for routing-context operations, and a mocked
 * {@link ContextHolder} to supply a {@link CorrelationContext} to the rejection reporter.
 * This avoids a live Vert.x event loop while exercising the wrapper logic end-to-end.
 *
 * <p>The {@link DefaultCredentialRejectionReporter} takes a {@link SecurityEventEmitter} — tests
 * wire it with a {@link CapturingObserver} to intercept emitted {@link CredentialRejectedEvent}s
 * instead of relying on the removed {@code drain(ctx)} method.
 */
class JwtBearerSecuritySchemeHandlerTest {

    /**
     * Capturing {@link SecurityEventObserver} that records every {@link CredentialRejectedEvent}
     * emitted by the {@link DefaultCredentialRejectionReporter} under test.
     *
     * <p>A fresh instance is created for every handler created by {@link #handlerWithMockAuth},
     * stored in {@link #lastObserver}, so rejection assertions can access it via
     * {@code lastObserver.rejections}.
     */
    private static final class CapturingObserver implements SecurityEventObserver {
        /** All credential-rejected events received, in emission order. */
        final List<CredentialRejectedEvent> rejections = new ArrayList<>();

        @Override
        public Future<Void> onCredentialRejected(CredentialRejectedEvent e) {
            rejections.add(e);
            return Future.succeededFuture();
        }
    }

    /**
     * The most recently created capturing observer. Set by {@link #handlerWithMockAuth} so
     * individual tests can assert against the emitted events without requiring the method to
     * return a pair.
     */
    private CapturingObserver lastObserver;

    // --- Fixture constants ---

    private static final String ISSUER = "https://issuer.example.com";
    private static final String JWKS_URI = "https://issuer.example.com/.well-known/jwks.json";
    private static final String RAW_BEARER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig";

    // --- Shared helpers ---

    /**
     * Creates a stub {@link RoutingContext} backed by an in-memory map so tests can inspect
     * stashed values. The mock also implements {@link UserContextInternal} (via
     * {@link org.mockito.MockSettings#extraInterfaces}) so the production code's
     * {@code ((UserContextInternal) ctx.userContext()).setUser(user)} cast succeeds.
     *
     * <p>The {@code request()} stub returns an {@link HttpServerRequest} mock whose
     * {@code getHeader("Authorization")} defaults to {@code null}. Tests override this per-test
     * to supply a bearer token.
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
        doAnswer(inv -> {
                    backingMap.remove(inv.getArgument(0, String.class));
                    return null;
                })
                .when(ctx)
                .remove(anyString());

        // Set up request mock; header defaults to null (no Authorization header)
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(ctx.request()).thenReturn(request);

        // Wire userContext() to return the ctx itself (which also implements UserContextInternal)
        UserContextInternal userContextInternal = (UserContextInternal) ctx;
        when(ctx.userContext()).thenReturn((UserContext) userContextInternal);

        // Track user via setUser/user so success-path assertions on ctx.user() work
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
     * Configures the given routing context mock to present a valid bearer token in the
     * {@code Authorization} header.
     *
     * @param ctx   the mocked routing context
     * @param token the raw JWT token string to place after {@code "Bearer "}
     */
    private static void stubBearerToken(RoutingContext ctx, String token) {
        when(ctx.request().getHeader("Authorization")).thenReturn("Bearer " + token);
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
     * @return a correlation context with fixed ids
     */
    private static CorrelationContext stubCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    /**
     * Builds a mock {@link User} with the standard JWT attribute layout that Vert.x populates
     * after successful JWT verification.
     *
     * <p>The returned principal carries {@code iss = ISSUER} by default so that, with the handler's
     * post-authentication issuer-enforcement gate live (and the default test config's issuer set to
     * {@link #ISSUER}), existing success-path tests satisfy the gate. Evidence assertions are
     * unaffected because the evidence issuer is derived from config, not the token.
     *
     * @param sub the {@code sub} claim value; {@code null} to omit
     * @param exp the {@code exp} claim value (Unix epoch seconds); {@code null} to omit
     * @return a mocked user with populated attributes
     */
    private static User stubUser(String sub, Long exp) {
        return stubUser(sub, exp, null, null);
    }

    /**
     * Builds a mock {@link User} whose decoded JWT body ({@code user.principal()}) carries the
     * given claims. Production reads claims from {@code principal()} (the JWT body), not
     * {@code attributes()}, because Vert.x only promotes registered claims to attributes — custom
     * claims like {@code client_id}/{@code azp} live solely in the principal.
     *
     * <p>The returned principal carries {@code iss = ISSUER} by default (see {@link #stubUser(String,
     * Long)}) so existing success-path tests satisfy the live issuer-enforcement gate.
     *
     * @param sub      the {@code sub} claim; {@code null} to omit
     * @param exp      the {@code exp} claim (Unix epoch seconds); {@code null} to omit
     * @param clientId the {@code client_id} claim; {@code null} to omit
     * @param azp      the {@code azp} claim; {@code null} to omit
     * @return a mocked user with the claims in {@code principal()}
     */
    private static User stubUser(String sub, Long exp, String clientId, String azp) {
        io.vertx.core.json.JsonObject principal = new io.vertx.core.json.JsonObject();
        // Default iss to ISSUER so existing success-path tests satisfy the live issuer-enforcement
        // gate (the default test config's issuer is ISSUER). Evidence issuer comes from config.
        principal.put("iss", ISSUER);
        if (sub != null) {
            principal.put("sub", sub);
        }
        if (exp != null) {
            principal.put("exp", exp);
        }
        if (clientId != null) {
            principal.put("client_id", clientId);
        }
        if (azp != null) {
            principal.put("azp", azp);
        }
        return stubUserWithClaims(principal);
    }

    /**
     * Builds a mock {@link User} whose decoded JWT body ({@code user.principal()}) is exactly the
     * supplied {@link io.vertx.core.json.JsonObject}. Used by validation-enforcement tests that need
     * to set arbitrary {@code iss}/{@code aud} claims (including the single-string and JSON-array
     * forms of {@code aud}) without the {@code iss = ISSUER} default applied by {@link
     * #stubUser(String, Long)}.
     *
     * @param principal the exact JWT body to return from {@code user.principal()}
     * @return a mocked user carrying the given principal
     */
    private static User stubUserWithClaims(io.vertx.core.json.JsonObject principal) {
        User user = mock(User.class);
        when(user.principal()).thenReturn(principal);
        return user;
    }

    /**
     * Builds a {@link JwtValidationConfig} with the test issuer and no audience.
     *
     * @return a minimal validation config
     */
    private static JwtValidationConfig testConfig() {
        return JwtValidationConfig.builder().issuer(ISSUER).build();
    }

    /**
     * Creates a {@link DefaultCredentialRejectionReporter} wired with a no-capture
     * {@link SecurityEventEmitter} (empty observer set). Used in tests that only need a valid
     * reporter but do not assert on emitted rejection events.
     *
     * @param holder the context holder to supply to the reporter
     * @return a reporter that emits to an empty observer set
     */
    private static DefaultCredentialRejectionReporter stubReporter(ContextHolder holder) {
        return new DefaultCredentialRejectionReporter(holder, new SecurityEventEmitter(Set.of()));
    }

    // --- Tests ---

    @Nested
    @DisplayName("schemeName()")
    class SchemeNameTests {

        @Test
        @DisplayName("Should return the scheme name passed to the constructor")
        void shouldReturnConfiguredSchemeName() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            DefaultCredentialRejectionReporter reporter = stubReporter(holder);

            var handler = new JwtBearerSecuritySchemeHandler("bearerAuth", mock(), testConfig(), reporter);
            assertEquals("bearerAuth", handler.schemeName());
        }

        @Test
        @DisplayName("Should return a custom scheme name passed to the constructor")
        void shouldReturnCustomSchemeName() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            DefaultCredentialRejectionReporter reporter = stubReporter(holder);

            var handler = new JwtBearerSecuritySchemeHandler("myScheme", mock(), testConfig(), reporter);
            assertEquals("myScheme", handler.schemeName());
        }
    }

    @Nested
    @DisplayName("configure()")
    class ConfigureTests {

        @Test
        @DisplayName("Should register a JWTAuthHandler wrapper on the SecuritySchemeRegistry, not a RouterBuilder")
        void shouldRegisterAuthHandlerOnSecuritySchemeRegistry() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            DefaultCredentialRejectionReporter reporter = stubReporter(holder);
            var jwtAuth = mock(JWTAuth.class);

            var handler = new JwtBearerSecuritySchemeHandler("bearerAuth", jwtAuth, testConfig(), reporter);

            SecuritySchemeRegistry registry = mock(SecuritySchemeRegistry.class);

            handler.configure(registry);

            // The registered handler is our DelegatingJwtAuthHandler wrapper (a JWTAuthHandler).
            verify(registry, times(1)).authenticationHandler(any(JWTAuthHandler.class));
        }
    }

    @Nested
    @DisplayName("Success path — AuthenticationEvidence appended")
    class SuccessPath {

        private DefaultCredentialRejectionReporter reporter;
        private Map<String, Object> store;
        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            reporter = stubReporter(holder);
            store = new HashMap<>();
            ctx = stubContext(store);
            // All success-path tests supply a valid bearer token
            stubBearerToken(ctx, RAW_BEARER);
        }

        @Test
        @DisplayName("Success with sub and exp — evidence has correct method, source, notAfter, sub in safeAttributes")
        void successWithSubAndExp_evidenceFullyPopulated() {
            long expEpochSec = Instant.now().plusSeconds(3600).getEpochSecond();
            User user = stubUser("alice@example.com", expEpochSec);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));

            AtomicBoolean nextCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        nextCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .next();

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            // ctx.next() must have been called
            assertTrue(nextCalled.get(), "ctx.next() must be called on success");

            // Evidence must be stashed
            List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(ctx);
            assertEquals(1, evidence.size(), "exactly one evidence entry must be stashed");

            AuthenticationEvidence ev = evidence.get(0);
            assertEquals("jwt", ev.method().id(), "method id must be 'jwt'");

            assertInstanceOf(
                    JwksVerificationSource.class,
                    ev.verificationSource(),
                    "verificationSource must be JwksVerificationSource");
            JwksVerificationSource src = (JwksVerificationSource) ev.verificationSource();
            assertEquals(Optional.of(ISSUER), src.issuer(), "issuer must come from config");
            assertEquals(Optional.of(JWKS_URI), src.jwksUri(), "jwksUri must come from config");

            // notAfter maps exp claim
            assertTrue(ev.notAfter().isPresent(), "notAfter must be present when exp claim is set");
            assertEquals(Instant.ofEpochSecond(expEpochSec), ev.notAfter().get(), "notAfter must equal exp claim");

            // safeAttributes contains sub
            assertEquals("alice@example.com", ev.safeAttributes().get("sub"), "sub must be in safeAttributes");

            // No raw token in safeAttributes
            assertFalse(
                    ev.safeAttributes().values().stream()
                            .anyMatch(v -> v instanceof String s
                                    && s.contains(RAW_BEARER.split("\\.")[2])),
                    "raw token must not appear in safeAttributes");
        }

        @Test
        @DisplayName("Success with client_id and azp (custom claims in principal) — both reach safeAttributes")
        void successWithClientIdAndAzp_inSafeAttributes() {
            long expEpochSec = Instant.now().plusSeconds(3600).getEpochSecond();
            // client_id / azp are custom claims that live ONLY in user.principal(), not attributes()
            User user = stubUser("alice@example.com", expEpochSec, "order-service", "order-service");

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));
            doNothing().when(ctx).next();

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            AuthenticationEvidence ev = RestAuthenticationEvidence.get(ctx).get(0);
            assertEquals("order-service", ev.safeAttributes().get("client_id"), "client_id must reach safeAttributes");
            assertEquals("order-service", ev.safeAttributes().get("azp"), "azp must reach safeAttributes");
        }

        @Test
        @DisplayName("Success without sub — safeAttributes is empty")
        void successWithoutSub_safeAttributesEmpty() {
            long expEpochSec = Instant.now().plusSeconds(3600).getEpochSecond();
            User user = stubUser(null, expEpochSec);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));
            doNothing().when(ctx).next();

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(ctx);
            assertEquals(1, evidence.size());
            assertTrue(evidence.get(0).safeAttributes().isEmpty(), "safeAttributes must be empty when sub is absent");
        }

        @Test
        @DisplayName("Success without exp claim — notAfter is Optional.empty()")
        void successWithoutExp_notAfterEmpty() {
            User user = stubUser("bob@example.com", null);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));
            doNothing().when(ctx).next();

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(ctx);
            assertEquals(1, evidence.size());
            assertFalse(evidence.get(0).notAfter().isPresent(), "notAfter must be empty when exp is absent");
        }

        @Test
        @DisplayName("Success — verifiedAt is set to a recent instant")
        void success_verifiedAtIsRecent() {
            User user = stubUser("charlie@example.com", null);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));
            doNothing().when(ctx).next();

            long before = System.currentTimeMillis();
            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);
            long after = System.currentTimeMillis();

            List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(ctx);
            assertEquals(1, evidence.size());
            long evidenceMs = evidence.get(0).verifiedAt().toEpochMilli();
            assertTrue(
                    evidenceMs >= before && evidenceMs <= after + 1000,
                    "verifiedAt must be within the current time window");
        }

        @Test
        @DisplayName("Success — no rejection event emitted")
        void success_noRejectionEventStashed() {
            User user = stubUser("dave@example.com", null);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));
            doNothing().when(ctx).next();

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            assertTrue(lastObserver.rejections.isEmpty(), "no rejection events should be emitted on success");
        }
    }

    @Nested
    @DisplayName("Failure path — CredentialRejectedEvent stashed")
    class FailurePath {

        private DefaultCredentialRejectionReporter reporter;
        private Map<String, Object> store;
        private RoutingContext ctx;

        @BeforeEach
        void setup() {
            CorrelationContext correlation = stubCorrelation();
            ContextHolder holder = holderWith(correlation);
            reporter = stubReporter(holder);
            store = new HashMap<>();
            ctx = stubContext(store);
            // All authenticate-failure-path tests supply a bearer token so the handler reaches
            // jwtAuth.authenticate(); individual tests override the header for missing/malformed tests.
            stubBearerToken(ctx, RAW_BEARER);
        }

        @Test
        @DisplayName("Missing Authorization header → ctx.fail(401) + reasonCode BEARER_MISSING")
        void missingAuthorizationHeader_reasonCodeBearerMissing() {
            // Override: no Authorization header
            when(ctx.request().getHeader("Authorization")).thenReturn(null);

            AtomicBoolean failCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        failCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .fail(anyInt());

            JWTAuth mockAuth = mock(JWTAuth.class);
            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            assertTrue(failCalled.get(), "fail(int) must be called");
            // authenticate() must NOT be called — handler short-circuits
            verify(mockAuth, never()).authenticate(any());

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size(), "exactly one rejection event must be stashed");
            assertEquals("BEARER_MISSING", rejections.get(0).reasonCode(), "reasonCode must be BEARER_MISSING");
        }

        @Test
        @DisplayName("Non-bearer Authorization header → ctx.fail(401) + reasonCode BEARER_MALFORMED")
        void nonBearerAuthorizationHeader_reasonCodeBearerMalformed() {
            // Override: wrong scheme
            when(ctx.request().getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            doAnswer(inv -> null).when(ctx).fail(anyInt());

            JWTAuth mockAuth = mock(JWTAuth.class);
            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            verify(mockAuth, never()).authenticate(any());

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals("BEARER_MALFORMED", rejections.get(0).reasonCode(), "reasonCode must be BEARER_MALFORMED");
        }

        @Test
        @DisplayName("Bearer with empty token → ctx.fail(401) + reasonCode BEARER_MALFORMED")
        void bearerWithEmptyToken_reasonCodeBearerMalformed() {
            // Override: "Bearer " with no token
            when(ctx.request().getHeader("Authorization")).thenReturn("Bearer ");

            doAnswer(inv -> null).when(ctx).fail(anyInt());

            JWTAuth mockAuth = mock(JWTAuth.class);
            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            verify(mockAuth, never()).authenticate(any());

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals("BEARER_MALFORMED", rejections.get(0).reasonCode(), "reasonCode must be BEARER_MALFORMED");
        }

        @Test
        @DisplayName("Expired JWT → reasonCode JWT_EXPIRED")
        void expiredJwt_reasonCodeJwtExpired() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            // JWTAuthProviderImpl sends this as a string message (not a typed exception)
            Throwable expiredCause = new RuntimeException("Invalid JWT token: token expired.");
            HttpException wrappedException = new HttpException(401, expiredCause);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals("JWT_EXPIRED", rejections.get(0).reasonCode(), "reasonCode must be JWT_EXPIRED");
        }

        @Test
        @DisplayName("Invalid signature → reasonCode JWT_SIGNATURE_INVALID")
        void invalidSignature_reasonCodeJwtSignatureInvalid() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            SignatureException signatureEx = new SignatureException("Signature verification failed");
            HttpException wrappedException = new HttpException(401, signatureEx);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals(
                    "JWT_SIGNATURE_INVALID",
                    rejections.get(0).reasonCode(),
                    "reasonCode must be JWT_SIGNATURE_INVALID");
        }

        @Test
        @DisplayName("Audience mismatch → reasonCode JWT_AUDIENCE_INVALID")
        void audienceMismatch_reasonCodeJwtAudienceInvalid() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            RuntimeException audienceCause =
                    new RuntimeException("Invalid JWT audience. expected: [\"https://api.example.com\"]");
            HttpException wrappedException = new HttpException(401, audienceCause);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals(
                    "JWT_AUDIENCE_INVALID", rejections.get(0).reasonCode(), "reasonCode must be JWT_AUDIENCE_INVALID");
        }

        @Test
        @DisplayName("Issuer mismatch → reasonCode JWT_ISSUER_INVALID")
        void issuerMismatch_reasonCodeJwtIssuerInvalid() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            RuntimeException issuerCause = new RuntimeException("Invalid JWT issuer");
            HttpException wrappedException = new HttpException(401, issuerCause);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals("JWT_ISSUER_INVALID", rejections.get(0).reasonCode(), "reasonCode must be JWT_ISSUER_INVALID");
        }

        @Test
        @DisplayName("Unsupported algorithm → reasonCode JWT_ALG_UNSUPPORTED")
        void unsupportedAlgorithm_reasonCodeJwtAlgUnsupported() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            RuntimeException algCause = new RuntimeException("Algorithm not supported/allowed: none");
            HttpException wrappedException = new HttpException(401, algCause);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals(
                    "JWT_ALG_UNSUPPORTED", rejections.get(0).reasonCode(), "reasonCode must be JWT_ALG_UNSUPPORTED");
        }

        @Test
        @DisplayName("Unknown failure → reasonCode JWT_INVALID")
        void unknownFailure_reasonCodeJwtInvalid() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            RuntimeException unknownCause = new RuntimeException("Some unexpected JWT problem");
            HttpException wrappedException = new HttpException(401, unknownCause);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            assertEquals(
                    "JWT_INVALID", rejections.get(0).reasonCode(), "reasonCode must be JWT_INVALID for unknown cause");
        }

        @Test
        @DisplayName("Failure — no raw bearer token in rejection event safeAttributes")
        void failure_noRawTokenInSafeAttributes() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            Throwable expiredCause = new RuntimeException("Invalid JWT token: token expired.");
            HttpException wrappedException = new HttpException(401, expiredCause);

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());
            Map<String, Object> attrs = rejections.get(0).safeAttributes();
            // No value in safeAttributes should contain raw token material
            assertFalse(
                    attrs.values().stream().anyMatch(v -> v instanceof String s && s.length() > 50 && s.contains(".")),
                    "safeAttributes must not contain raw token material");
        }

        @Test
        @DisplayName("Failure — no evidence stashed on the routing context")
        void failure_noEvidenceStashed() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            HttpException wrappedException =
                    new HttpException(401, new RuntimeException("Invalid JWT token: token expired."));

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<AuthenticationEvidence> evidence = RestAuthenticationEvidence.get(ctx);
            assertTrue(evidence.isEmpty(), "no evidence should be stashed on failure");
        }

        @Test
        @DisplayName("Failure — rejection event carries JwksVerificationSource from config")
        void failure_rejectionEventCarriesVerificationSourceFromConfig() {
            doNothing().when(ctx).fail(anyInt(), any(Throwable.class));

            HttpException wrappedException =
                    new HttpException(401, new RuntimeException("Invalid JWT token: token expired."));

            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.failedFuture(wrappedException));

            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth);
            handler.handle(ctx);

            List<CredentialRejectedEvent> rejections = lastObserver.rejections;
            assertEquals(1, rejections.size());

            assertTrue(rejections.get(0).verificationSource().isPresent(), "verificationSource must be present");
            JwksVerificationSource src = (JwksVerificationSource)
                    rejections.get(0).verificationSource().get();
            assertEquals(Optional.of(ISSUER), src.issuer(), "issuer must come from config");
            assertEquals(Optional.of(JWKS_URI), src.jwksUri(), "jwksUri must come from config");
            assertEquals(Optional.empty(), src.kid(), "kid must be empty (not available before verification)");
            assertEquals(Optional.empty(), src.alg(), "alg must be empty (not available before verification)");
        }
    }

    @Nested
    @DisplayName("Validation enforcement (issuer/audience)")
    class ValidationEnforcement {

        private static final String API_AUD = "https://api.example.com";

        private Map<String, Object> store;
        private RoutingContext ctx;
        private AtomicBoolean nextCalled;

        @BeforeEach
        void setup() {
            store = new HashMap<>();
            ctx = stubContext(store);
            stubBearerToken(ctx, RAW_BEARER);
            nextCalled = new AtomicBoolean(false);
            doAnswer(inv -> {
                        nextCalled.set(true);
                        return null;
                    })
                    .when(ctx)
                    .next();
            // Validation rejections take the reportAndFailWithReason path: ctx.fail(Throwable).
            doNothing().when(ctx).fail(any(Throwable.class));
        }

        /**
         * Runs the handler against a token whose principal is {@code principal}, using a mock
         * {@link JWTAuth} that authenticates successfully (so the post-authentication gate runs).
         *
         * @param config    the validation config driving the gate
         * @param principal the JWT body returned from {@code user.principal()}
         */
        private void runWith(JwtValidationConfig config, io.vertx.core.json.JsonObject principal) {
            User user = stubUserWithClaims(principal);
            JWTAuth mockAuth = mock(JWTAuth.class);
            when(mockAuth.authenticate(any(Credentials.class))).thenReturn(Future.succeededFuture(user));
            JwtBearerSecuritySchemeHandler handler = handlerWithMockAuth(mockAuth, config);
            handler.handle(ctx);
        }

        @Test
        @DisplayName("Issuer matches → ctx.next(), one evidence entry, no rejection")
        void issuerMatches_authSucceeds_next() {
            JwtValidationConfig config =
                    JwtValidationConfig.builder().issuer(ISSUER).build();
            runWith(config, new io.vertx.core.json.JsonObject().put("iss", ISSUER));

            assertTrue(nextCalled.get(), "ctx.next() must be called when issuer matches");
            assertEquals(1, RestAuthenticationEvidence.get(ctx).size(), "exactly one evidence entry");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection on issuer match");
        }

        @Test
        @DisplayName("Issuer mismatch → handler rejects with JWT_ISSUER_INVALID, no next()")
        void issuerMismatch_handlerRejects_jwtIssuerInvalid() {
            JwtValidationConfig config =
                    JwtValidationConfig.builder().issuer(ISSUER).build();
            runWith(config, new io.vertx.core.json.JsonObject().put("iss", "https://evil.example.com"));

            assertFalse(nextCalled.get(), "ctx.next() must NOT be called on issuer mismatch");
            verify(ctx).fail(any(Throwable.class));
            assertEquals(1, lastObserver.rejections.size(), "exactly one rejection");
            assertEquals("JWT_ISSUER_INVALID", lastObserver.rejections.get(0).reasonCode());
        }

        @Test
        @DisplayName("Issuer missing in token → handler rejects with JWT_ISSUER_INVALID, no next()")
        void issuerMissingInToken_handlerRejects() {
            JwtValidationConfig config =
                    JwtValidationConfig.builder().issuer(ISSUER).build();
            runWith(config, new io.vertx.core.json.JsonObject());

            assertFalse(nextCalled.get(), "ctx.next() must NOT be called when iss is missing");
            assertEquals(1, lastObserver.rejections.size());
            assertEquals("JWT_ISSUER_INVALID", lastObserver.rejections.get(0).reasonCode());
        }

        @Test
        @DisplayName("Null-issuer config → no issuer check, ctx.next()")
        void issuerNullConfig_noCheck_succeeds() {
            JwtValidationConfig config = JwtValidationConfig.builder().build();
            runWith(config, new io.vertx.core.json.JsonObject());

            assertTrue(nextCalled.get(), "ctx.next() must be called when issuer is not configured");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection when issuer unconfigured");
        }

        @Test
        @DisplayName("Audience array overlaps → ctx.next()")
        void audienceAnyMatch_array_succeeds() {
            // Audience-only config (issuer null) to isolate the audience check.
            JwtValidationConfig config = JwtValidationConfig.builder()
                    .audience(List.of(API_AUD, "other"))
                    .build();
            runWith(
                    config,
                    new io.vertx.core.json.JsonObject().put("aud", new io.vertx.core.json.JsonArray().add(API_AUD)));

            assertTrue(nextCalled.get(), "ctx.next() must be called when audience overlaps");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection on audience overlap");
        }

        @Test
        @DisplayName("Audience disjoint → handler rejects with JWT_AUDIENCE_INVALID, no next()")
        void audienceMismatch_handlerRejects_jwtAudienceInvalid() {
            JwtValidationConfig config =
                    JwtValidationConfig.builder().audience(List.of(API_AUD)).build();
            runWith(
                    config,
                    new io.vertx.core.json.JsonObject()
                            .put("aud", new io.vertx.core.json.JsonArray().add("someone-else")));

            assertFalse(nextCalled.get(), "ctx.next() must NOT be called on audience mismatch");
            verify(ctx).fail(any(Throwable.class));
            assertEquals(1, lastObserver.rejections.size());
            assertEquals("JWT_AUDIENCE_INVALID", lastObserver.rejections.get(0).reasonCode());
        }

        @Test
        @DisplayName("Audience as single string matches → ctx.next()")
        void audienceAsString_match_succeeds() {
            JwtValidationConfig config =
                    JwtValidationConfig.builder().audience(List.of(API_AUD)).build();
            runWith(config, new io.vertx.core.json.JsonObject().put("aud", API_AUD));

            assertTrue(nextCalled.get(), "ctx.next() must be called when string aud matches");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection on string aud match");
        }

        @Test
        @DisplayName("Audience missing in token when required → handler rejects with JWT_AUDIENCE_INVALID")
        void audienceMissingInToken_whenRequired_rejects() {
            JwtValidationConfig config =
                    JwtValidationConfig.builder().audience(List.of(API_AUD)).build();
            runWith(config, new io.vertx.core.json.JsonObject());

            assertFalse(nextCalled.get(), "ctx.next() must NOT be called when aud missing but required");
            assertEquals(1, lastObserver.rejections.size());
            assertEquals("JWT_AUDIENCE_INVALID", lastObserver.rejections.get(0).reasonCode());
        }

        @Test
        @DisplayName("Null/empty audience config → no audience check, ctx.next()")
        void audienceNullConfig_noCheck_succeeds() {
            JwtValidationConfig config = JwtValidationConfig.builder().build();
            runWith(config, new io.vertx.core.json.JsonObject());

            assertTrue(nextCalled.get(), "ctx.next() must be called when audience is not configured");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection when audience unconfigured");
        }

        @Test
        @DisplayName("Issuer and audience both match → ctx.next(), evidence stashed")
        void issuerAndAudienceBothMatch_succeeds() {
            JwtValidationConfig config = JwtValidationConfig.builder()
                    .issuer(ISSUER)
                    .audience(List.of(API_AUD))
                    .build();
            runWith(
                    config,
                    new io.vertx.core.json.JsonObject()
                            .put("iss", ISSUER)
                            .put("aud", new io.vertx.core.json.JsonArray().add(API_AUD)));

            assertTrue(nextCalled.get(), "ctx.next() must be called when both checks pass");
            assertEquals(1, RestAuthenticationEvidence.get(ctx).size(), "evidence must be stashed");
            assertTrue(lastObserver.rejections.isEmpty(), "no rejection when both checks pass");
        }
    }

    // --- Private helpers ---

    /**
     * Builds a {@link JwtBearerSecuritySchemeHandler} pre-wired with the given {@link JWTAuth}
     * mock, a fresh {@link CorrelationContext}, and a {@link JwtValidationConfig} containing the
     * test issuer. Also creates a fresh {@link CapturingObserver}, assigns it to
     * {@link #lastObserver}, and wires it into the reporter's {@link SecurityEventEmitter} so
     * callers can inspect emitted {@link CredentialRejectedEvent}s via {@code lastObserver.rejections}.
     *
     * @param mockAuth the {@link JWTAuth} mock whose {@code authenticate()} behaviour has been
     *                 configured by the caller (e.g. via {@code when(mockAuth.authenticate(any()))
     *                 .thenReturn(Future.succeededFuture(user))})
     * @return a configured handler instance whose {@link JwtBearerSecuritySchemeHandler#handle}
     *         method can be invoked in tests
     */
    private JwtBearerSecuritySchemeHandler handlerWithMockAuth(JWTAuth mockAuth) {
        return handlerWithMockAuth(
                mockAuth, JwtValidationConfig.builder().issuer(ISSUER).build());
    }

    /**
     * Builds a {@link JwtBearerSecuritySchemeHandler} pre-wired with the given {@link JWTAuth} mock
     * and {@link JwtValidationConfig}, a fresh {@link CorrelationContext}, and a fresh
     * {@link CapturingObserver} (assigned to {@link #lastObserver}) wired into the reporter's
     * {@link SecurityEventEmitter}. Validation-enforcement tests use this overload to supply
     * audience / custom-issuer configs while keeping {@link #lastObserver} wired.
     *
     * @param mockAuth the {@link JWTAuth} mock whose {@code authenticate()} behaviour has been
     *                 configured by the caller
     * @param config   the JWT validation config driving the handler's post-authentication gate
     * @return a configured handler instance whose {@link JwtBearerSecuritySchemeHandler#handle}
     *         method can be invoked in tests
     */
    private JwtBearerSecuritySchemeHandler handlerWithMockAuth(JWTAuth mockAuth, JwtValidationConfig config) {
        CorrelationContext correlation = stubCorrelation();
        ContextHolder holder = holderWith(correlation);
        lastObserver = new CapturingObserver();
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(lastObserver));
        DefaultCredentialRejectionReporter reporter = new DefaultCredentialRejectionReporter(holder, emitter);
        return JwtBearerSecuritySchemeHandler.forTesting("bearerAuth", mockAuth, config, reporter);
    }
}
