// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityContextSnapshot}.
 *
 * <p>Verifies: construction via {@link SecurityContextSnapshot#from(SecurityContext)} copies
 * identity, authentication, and origin (same references); null handling for optional origin;
 * null rejections on required fields; rebind survival (snapshot is decoupled from any live context
 * holder); and the {@link SecurityContext#snapshot()} default method.
 */
class SecurityContextSnapshotTest {

    // --- shared fixtures ---

    private static SecurityIdentity anonymousIdentity() {
        return SecurityIdentity.anonymous();
    }

    private static AuthenticationState noneAuth() {
        return new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
    }

    private static RequestOrigin sampleOrigin() {
        return new RequestOrigin(
                "10.0.0.1", 443, List.of(), 0, false, "10.0.0.1", "https", "example.com", Optional.empty());
    }

    /** Minimal inline SecurityContext implementation for tests. */
    private static SecurityContext contextOf(
            SecurityIdentity identity, AuthenticationState authentication, Optional<RequestOrigin> origin) {
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return authentication;
            }

            @Override
            public AuthorizationClaims authorization() {
                return AuthorizationClaims.empty();
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return origin;
            }
        };
    }

    // --- from(SecurityContext) field copying ---

    @Nested
    @DisplayName("from(SecurityContext) field copying")
    class FromFieldCopying {

        @Test
        @DisplayName("copies identity, authentication, and origin from context (same references)")
        void copiesAllThreeFields() {
            SecurityIdentity identity = anonymousIdentity();
            AuthenticationState auth = noneAuth();
            RequestOrigin origin = sampleOrigin();
            SecurityContext ctx = contextOf(identity, auth, Optional.of(origin));

            SecurityContextSnapshot snapshot = SecurityContextSnapshot.from(ctx);

            assertNotNull(snapshot);
            assertSame(identity, snapshot.identity());
            assertSame(auth, snapshot.authentication());
            assertTrue(snapshot.origin().isPresent());
            assertSame(origin, snapshot.origin().get());
        }

        @Test
        @DisplayName("context with empty origin() produces snapshot with Optional.empty() origin")
        void emptyOriginBecomesEmptyOptional() {
            SecurityContext ctx = contextOf(anonymousIdentity(), noneAuth(), Optional.empty());

            SecurityContextSnapshot snapshot = SecurityContextSnapshot.from(ctx);

            assertTrue(snapshot.origin().isEmpty());
        }
    }

    // --- null origin in direct record construction ---

    @Nested
    @DisplayName("null origin in record constructor")
    class NullOriginHandling {

        @Test
        @DisplayName("constructing record directly with null origin yields Optional.empty()")
        void nullOriginInRecordBecomesEmpty() {
            SecurityContextSnapshot snapshot = new SecurityContextSnapshot(anonymousIdentity(), noneAuth(), null);

            assertTrue(snapshot.origin().isEmpty());
        }
    }

    // --- null rejections ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("from(null) throws NullPointerException")
        void fromNullContextThrowsNpe() {
            assertThrows(NullPointerException.class, () -> SecurityContextSnapshot.from(null));
        }

        @Test
        @DisplayName("record constructor: null identity throws NullPointerException")
        void nullIdentityThrowsNpe() {
            assertThrows(
                    NullPointerException.class, () -> new SecurityContextSnapshot(null, noneAuth(), Optional.empty()));
        }

        @Test
        @DisplayName("record constructor: null authentication throws NullPointerException")
        void nullAuthenticationThrowsNpe() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityContextSnapshot(anonymousIdentity(), null, Optional.empty()));
        }
    }

    // --- rebind survival ---

    @Nested
    @DisplayName("rebind survival")
    class RebindSurvival {

        @Test
        @DisplayName("snapshot holds context A's fields after context B is constructed")
        void snapshotIsDecoupledFromSubsequentContexts() {
            SecurityIdentity identityA =
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-a", Map.of()));
            AuthenticationState authA = new AuthenticationState(
                    DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());
            RequestOrigin originA = sampleOrigin();
            SecurityContext ctxA = contextOf(identityA, authA, Optional.of(originA));

            SecurityContextSnapshot snapshot = SecurityContextSnapshot.from(ctxA);

            // Build a completely different context B — snapshot must not change
            SecurityIdentity identityB =
                    SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "svc-b", Map.of()));
            AuthenticationState authB = new AuthenticationState(
                    DefaultAuthMethod.mtls(), List.of(), Optional.empty(), Optional.empty(), Map.of());

            @SuppressWarnings("unused")
            SecurityContext ctxB = contextOf(identityB, authB, Optional.empty());

            // Snapshot still holds A's values
            assertSame(identityA, snapshot.identity());
            assertSame(authA, snapshot.authentication());
            assertTrue(snapshot.origin().isPresent());
            assertSame(originA, snapshot.origin().get());
        }
    }

    // --- SecurityContext.snapshot() default method ---

    @Nested
    @DisplayName("SecurityContext.snapshot() default method")
    class DefaultSnapshotMethod {

        @Test
        @DisplayName("context.snapshot() equals SecurityContextSnapshot.from(context)")
        void defaultMethodEqualsStaticFactory() {
            SecurityIdentity identity = anonymousIdentity();
            AuthenticationState auth = noneAuth();
            SecurityContext ctx = contextOf(identity, auth, Optional.empty());

            SecurityContextSnapshot viaDefault = ctx.snapshot();
            SecurityContextSnapshot viaFactory = SecurityContextSnapshot.from(ctx);

            assertEquals(viaFactory, viaDefault);
            assertSame(identity, viaDefault.identity());
            assertSame(auth, viaDefault.authentication());
        }
    }
}
