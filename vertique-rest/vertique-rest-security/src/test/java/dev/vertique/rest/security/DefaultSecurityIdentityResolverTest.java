// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.security.AuthMethodKind;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.ClientRef;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.IdentityResolutionError;
import dev.vertique.security.resolver.IdentityResolutionException;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.verification.ApiKeyRegistryVerificationSource;
import dev.vertique.security.verification.JwksVerificationSource;
import dev.vertique.security.verification.MtlsTrustStoreVerificationSource;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultSecurityIdentityResolver}.
 *
 * <p>Verifies the full resolution contract:
 * <ul>
 *   <li>Empty evidence list → {@link SecurityIdentity#anonymous()}</li>
 *   <li>JWT evidence with {@code sub} claim → {@code USER} actor</li>
 *   <li>JWT evidence with {@code sub} + {@code client_id} → {@code USER} actor + {@link ClientRef}</li>
 *   <li>JWT evidence with only {@code client_id} (no {@code sub}) → {@code SERVICE} actor</li>
 *   <li>API-key evidence → {@code SERVICE} actor</li>
 *   <li>mTLS evidence → {@code SERVICE} actor regardless of subject</li>
 *   <li>Evidence with no derivable id ({@code sub}/{@code client_id}/{@code azp} all absent) → fails
 *       resolution with an {@link IdentityResolutionException} rather than fabricating a shared id
 *       or falling back to anonymous (FR-ID-CA-012)</li>
 *   <li>Negative AC-CO-3 cases: no input combination produces {@code PrincipalType.SYSTEM}</li>
 *   <li>{@link DefaultSecurityIdentityResolver#priority()} returns 100</li>
 *   <li>{@link DefaultSecurityIdentityResolver#id()} returns the FQCN</li>
 *   <li>The {@code Future} completes synchronously (no I/O)</li>
 * </ul>
 */
class DefaultSecurityIdentityResolverTest {

    // --- Fixtures ---

    private static final JwksVerificationSource JWKS_SOURCE = new JwksVerificationSource(
            Optional.of("https://issuer.example.com"),
            Optional.of("https://issuer.example.com/.well-known/jwks.json"),
            Optional.of("key-id-123"),
            Optional.of("RS256"));

    private static final MtlsTrustStoreVerificationSource MTLS_SOURCE =
            new MtlsTrustStoreVerificationSource("mtls-trust-store", Optional.empty());

    private static final ApiKeyRegistryVerificationSource API_KEY_SOURCE =
            new ApiKeyRegistryVerificationSource("api-key-registry");

    private DefaultSecurityIdentityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultSecurityIdentityResolver();
    }

    // --- Helpers ---

    /**
     * Builds a JWT {@link AuthenticationEvidence} with the given safe attributes.
     *
     * @param safeAttrs the safe attribute map to embed
     * @return a JWT evidence record
     */
    private static AuthenticationEvidence jwtEvidence(Map<String, Object> safeAttrs) {
        return new AuthenticationEvidence(
                DefaultAuthMethod.jwt(), Optional.empty(), Instant.now(), Optional.empty(), JWKS_SOURCE, safeAttrs);
    }

    /**
     * Builds an API-key {@link AuthenticationEvidence} with the given safe attributes.
     *
     * @param safeAttrs the safe attribute map to embed
     * @return an API-key evidence record
     */
    private static AuthenticationEvidence apiKeyEvidence(Map<String, Object> safeAttrs) {
        return new AuthenticationEvidence(
                DefaultAuthMethod.apiKey(),
                Optional.empty(),
                Instant.now(),
                Optional.empty(),
                API_KEY_SOURCE,
                safeAttrs);
    }

    /**
     * Builds a mTLS {@link AuthenticationEvidence} with the given safe attributes.
     *
     * @param safeAttrs the safe attribute map to embed
     * @return an mTLS evidence record
     */
    private static AuthenticationEvidence mtlsEvidence(Map<String, Object> safeAttrs) {
        return new AuthenticationEvidence(
                DefaultAuthMethod.mtls(), Optional.empty(), Instant.now(), Optional.empty(), MTLS_SOURCE, safeAttrs);
    }

    /**
     * Builds a minimal {@link SecurityIdentityResolutionContext} with the given evidence list and
     * no origin, correlation, or attributes.
     *
     * @param evidence the accumulated evidence entries
     * @return the resolution context
     */
    private static SecurityIdentityResolutionContext contextWith(List<AuthenticationEvidence> evidence) {
        return new SecurityIdentityResolutionContext(evidence, Optional.empty(), Optional.empty(), Map.of());
    }

    // --- Test groups ---

    @Nested
    @DisplayName("Empty evidence → anonymous fallback")
    class EmptyEvidence {

        @Test
        @DisplayName("empty evidence list resolves to SecurityIdentity.anonymous()")
        void emptyEvidenceResolvesToAnonymous() {
            SecurityIdentityResolutionContext ctx = contextWith(List.of());
            Optional<SecurityIdentity> result = resolver.resolve(ctx).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent(), "empty evidence must produce a non-empty Optional");
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.ANONYMOUS, identity.actor().type(), "actor type must be ANONYMOUS");
            assertEquals("anonymous", identity.actor().id(), "actor id must be 'anonymous'");
            assertTrue(identity.client().isEmpty(), "client must be absent for anonymous identity");
        }
    }

    @Nested
    @DisplayName("JWT evidence classification")
    class JwtEvidence {

        @Test
        @DisplayName("JWT with sub=alice → USER(alice)")
        void jwtWithSubProducesUser() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("sub", "alice"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.USER, identity.actor().type(), "JWT with sub → USER");
            assertEquals("alice", identity.actor().id(), "actor id must match sub claim");
            assertTrue(identity.client().isEmpty(), "no client_id → no ClientRef");
        }

        @Test
        @DisplayName("JWT with sub=alice + client_id=foo-svc → USER(alice) with ClientRef(foo-svc)")
        void jwtWithSubAndClientIdProducesUserWithClientRef() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("sub", "alice", "client_id", "foo-svc"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.USER, identity.actor().type(), "sub present → USER");
            assertEquals("alice", identity.actor().id());
            assertTrue(identity.client().isPresent(), "client_id present → ClientRef must be populated");
            ClientRef client = identity.client().get();
            assertEquals("foo-svc", client.clientId(), "ClientRef must use the client_id claim value");
        }

        @Test
        @DisplayName("JWT with no sub but client_id=foo-svc → SERVICE(foo-svc)")
        void jwtWithNoSubButClientIdProducesService() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("client_id", "foo-svc"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.SERVICE, identity.actor().type(), "no sub + client_id → SERVICE");
            assertEquals("foo-svc", identity.actor().id(), "actor id falls back to client_id");
            assertTrue(identity.client().isPresent(), "client_id present → ClientRef must be populated");
            assertEquals("foo-svc", identity.client().get().clientId());
        }

        @Test
        @DisplayName("JWT with no sub but azp=foo-svc → SERVICE(foo-svc)")
        void jwtWithNoSubButAzpProducesService() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("azp", "foo-svc"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.SERVICE, identity.actor().type(), "no sub + azp → SERVICE");
            assertEquals("foo-svc", identity.actor().id(), "actor id falls back to azp");
            assertTrue(identity.client().isPresent(), "azp present → ClientRef must be populated");
            assertEquals("foo-svc", identity.client().get().clientId());
        }
    }

    @Nested
    @DisplayName("API-key evidence classification")
    class ApiKeyEvidence {

        @Test
        @DisplayName("API-key with client_id=order-svc → SERVICE(order-svc)")
        void apiKeyWithClientIdProducesService() {
            AuthenticationEvidence evidence = apiKeyEvidence(Map.of("client_id", "order-svc"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.SERVICE, identity.actor().type(), "API-key → SERVICE");
            assertEquals("order-svc", identity.actor().id());
            assertTrue(identity.client().isPresent(), "client_id present → ClientRef");
            assertEquals("order-svc", identity.client().get().clientId());
        }
    }

    @Nested
    @DisplayName("mTLS evidence classification")
    class MtlsEvidence {

        @Test
        @DisplayName("mTLS evidence → SERVICE regardless of sub attribute")
        void mtlsProducesService() {
            AuthenticationEvidence evidence = mtlsEvidence(Map.of("sub", "cn=client,o=example"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertEquals(PrincipalType.SERVICE, identity.actor().type(), "mTLS → SERVICE");
        }

        @Test
        @DisplayName("mTLS evidence with no derivable id fails resolution, not anonymous (FR-ID-CA-012)")
        void mtlsWithNoDerivableIdFailsResolution() {
            AuthenticationEvidence evidence = mtlsEvidence(Map.of());
            Future<Optional<SecurityIdentity>> future = resolver.resolve(contextWith(List.of(evidence)));

            assertTrue(future.isComplete(), "Future must complete synchronously");
            // FR-ID-CA-012: an underivable id must never be fabricated as a shared "unknown" id —
            // that would collide across principals — nor silently downgraded to anonymous, which
            // would hide a genuine verified-credential-without-stable-id condition. Resolution
            // MUST fail closed instead.
            assertTrue(future.failed(), "underivable id must fail resolution, not resolve to anonymous");
            Throwable cause = future.cause();
            assertInstanceOf(
                    IdentityResolutionException.class,
                    cause,
                    "failure must be an IdentityResolutionException, not an arbitrary exception type");
            IdentityResolutionException ex = (IdentityResolutionException) cause;
            assertEquals(
                    IdentityResolutionError.UNDERIVABLE_PRINCIPAL_ID,
                    ex.error(),
                    "error classification must be UNDERIVABLE_PRINCIPAL_ID");
            assertTrue(
                    ex.getMessage().contains(AuthMethodKind.MTLS.name()),
                    "message must name the auth method kind: " + ex.getMessage());
            assertFalse(
                    ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("anonymous"),
                    "message must not describe this as an anonymous fallback: " + ex.getMessage());
        }
    }

    @Nested
    @DisplayName("AC-CO-3 — negative SYSTEM invariant")
    class NegativeSystemInvariant {

        @Test
        @DisplayName("JWT with sub=SYSTEM → USER(SYSTEM) — literal string preserved, type stays USER")
        void jwtSubSystemPreservesStringButTypeIsUser() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("sub", "SYSTEM"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            // The literal string "SYSTEM" is an ordinary sub value; PrincipalType must NOT be SYSTEM
            assertNotEquals(
                    PrincipalType.SYSTEM, identity.actor().type(), "PrincipalType must NEVER be SYSTEM from evidence");
            assertEquals(PrincipalType.USER, identity.actor().type(), "sub=SYSTEM → USER, not SYSTEM");
            assertEquals("SYSTEM", identity.actor().id(), "sub value preserved as actor id");
        }

        @Test
        @DisplayName("JWT with safe attr type=SYSTEM → USER or SERVICE, never SYSTEM")
        void jwtSafeAttrTypeSystemNeverYieldsSystemPrincipal() {
            // `sub` is included so the id is derivable — this test isolates the AC-CO-3 hostile-
            // attribute guard from the unrelated FR-ID-CA-012 underivable-id failure path.
            AuthenticationEvidence evidence = jwtEvidence(Map.of("type", "SYSTEM", "sub", "alice"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertNotEquals(
                    PrincipalType.SYSTEM,
                    identity.actor().type(),
                    "type=SYSTEM attribute must not produce PrincipalType.SYSTEM");
        }

        @Test
        @DisplayName("JWT with safe attr system=true → USER or SERVICE, never SYSTEM")
        void jwtSafeAttrSystemTrueNeverYieldsSystemPrincipal() {
            // `sub` is included so the id is derivable — this test isolates the AC-CO-3 hostile-
            // attribute guard from the unrelated FR-ID-CA-012 underivable-id failure path.
            AuthenticationEvidence evidence = jwtEvidence(Map.of("system", Boolean.TRUE, "sub", "alice"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertNotEquals(
                    PrincipalType.SYSTEM,
                    identity.actor().type(),
                    "system=true attribute must not produce PrincipalType.SYSTEM");
        }

        @Test
        @DisplayName("API-key with client_id=SYSTEM → SERVICE(SYSTEM), not SYSTEM principal type")
        void apiKeyClientIdSystemIsService() {
            AuthenticationEvidence evidence = apiKeyEvidence(Map.of("client_id", "SYSTEM"));
            Optional<SecurityIdentity> result =
                    resolver.resolve(contextWith(List.of(evidence))).result();

            assertNotNull(result, "Future must complete synchronously");
            assertTrue(result.isPresent());
            SecurityIdentity identity = result.get();
            assertNotEquals(PrincipalType.SYSTEM, identity.actor().type(), "API-key must NEVER produce SYSTEM type");
            assertEquals(PrincipalType.SERVICE, identity.actor().type());
            assertEquals("SYSTEM", identity.actor().id(), "client_id value preserved as actor id");
        }
    }

    @Nested
    @DisplayName("Blank safe-attribute claims are treated as absent (CW-2)")
    class BlankClaimHandling {

        @Test
        @DisplayName("JWT with sub=\"\" (blank) fails resolution as UNDERIVABLE_PRINCIPAL_ID, "
                + "not a synchronous IllegalArgumentException")
        void blankSubFailsResolutionAsUnderivable() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("sub", ""));

            Future<Optional<SecurityIdentity>> future = assertDoesNotThrow(
                    () -> resolver.resolve(contextWith(List.of(evidence))),
                    "resolve() must not throw synchronously for a blank sub claim — it must return "
                            + "a failed Future instead");

            assertTrue(future.isComplete(), "Future must complete synchronously");
            assertTrue(future.failed(), "blank sub must fail resolution, not resolve successfully");
            Throwable cause = future.cause();
            assertInstanceOf(
                    IdentityResolutionException.class,
                    cause,
                    "failure must be an IdentityResolutionException, not IllegalArgumentException or "
                            + "any other exception type: " + cause);
            IdentityResolutionException ex = (IdentityResolutionException) cause;
            assertEquals(
                    IdentityResolutionError.UNDERIVABLE_PRINCIPAL_ID,
                    ex.error(),
                    "a blank sub claim must classify as an underivable id, per FR-ID-CA-012");
        }

        @Test
        @DisplayName("JWT with sub=\"\" but client_id=svc-x falls back to SERVICE(svc-x) — "
                + "blank sub must not mask a valid client_id")
        void blankSubFallsBackToValidClientId() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("sub", "", "client_id", "svc-x"));

            Future<Optional<SecurityIdentity>> future = assertDoesNotThrow(
                    () -> resolver.resolve(contextWith(List.of(evidence))),
                    "resolve() must not throw synchronously — a blank sub claim must not prevent "
                            + "the client_id fallback from being consulted");

            assertTrue(future.isComplete(), "Future must complete synchronously");
            assertTrue(future.succeeded(), "a valid client_id fallback must resolve successfully");
            SecurityIdentity identity = future.result().orElseThrow();
            assertEquals(
                    PrincipalType.SERVICE,
                    identity.actor().type(),
                    "blank sub must fall back to SERVICE classification via client_id");
            assertEquals("svc-x", identity.actor().id(), "actor id must come from the unmasked client_id");
            assertTrue(identity.client().isPresent(), "client_id present → ClientRef must be populated");
            assertEquals("svc-x", identity.client().get().clientId());
        }

        @Test
        @DisplayName("JWT with client_id=\"\" and azp=\"\" (no sub) fails resolution as "
                + "UNDERIVABLE_PRINCIPAL_ID, not a synchronous IllegalArgumentException")
        void blankClientChainFailsResolutionAsUnderivable() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("client_id", "", "azp", ""));

            Future<Optional<SecurityIdentity>> future = assertDoesNotThrow(
                    () -> resolver.resolve(contextWith(List.of(evidence))),
                    "resolve() must not throw synchronously when the entire client-id chain is blank");

            assertTrue(future.isComplete(), "Future must complete synchronously");
            assertTrue(future.failed(), "an all-blank client-id chain must fail resolution, not succeed");
            Throwable cause = future.cause();
            assertInstanceOf(
                    IdentityResolutionException.class,
                    cause,
                    "failure must be an IdentityResolutionException, not IllegalArgumentException or "
                            + "any other exception type: " + cause);
            IdentityResolutionException ex = (IdentityResolutionException) cause;
            assertEquals(
                    IdentityResolutionError.UNDERIVABLE_PRINCIPAL_ID,
                    ex.error(),
                    "blank client_id/azp claims must classify as an underivable id, per FR-ID-CA-012");
        }
    }

    @Nested
    @DisplayName("Resolver metadata — priority and id")
    class ResolverMetadata {

        @Test
        @DisplayName("priority() returns 100 — runs last among framework defaults")
        void priorityIsOneHundred() {
            assertEquals(100, resolver.priority(), "default resolver must report priority 100");
        }

        @Test
        @DisplayName("id() returns fully qualified class name")
        void idIsFullyQualifiedClassName() {
            assertEquals(DefaultSecurityIdentityResolver.class.getName(), resolver.id(), "id must equal FQCN");
        }
    }

    @Nested
    @DisplayName("Async contract — synchronous completion")
    class AsyncContract {

        @Test
        @DisplayName("resolve() returns an already-completed Future for empty evidence")
        void futureIsAlreadyCompleteForEmptyEvidence() {
            var future = resolver.resolve(contextWith(List.of()));
            assertTrue(future.isComplete(), "Future must be already complete — no I/O in default resolver");
            assertTrue(future.succeeded(), "Future must succeed");
        }

        @Test
        @DisplayName("resolve() returns an already-completed Future for non-empty evidence")
        void futureIsAlreadyCompleteForNonEmptyEvidence() {
            AuthenticationEvidence evidence = jwtEvidence(Map.of("sub", "bob"));
            var future = resolver.resolve(contextWith(List.of(evidence)));
            assertTrue(future.isComplete(), "Future must be already complete — no I/O in default resolver");
            assertTrue(future.succeeded(), "Future must succeed");
        }
    }

    @Nested
    @DisplayName("Null argument rejection")
    class NullArguments {

        @Test
        @DisplayName("resolve(null) throws NullPointerException with message 'context'")
        void resolveNullContextThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> resolver.resolve(null));
            assertEquals("context", ex.getMessage());
        }
    }
}
