// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.verification.JwksVerificationSource;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityIdentityResolutionContext}.
 *
 * <p>Verifies: construction with empty and populated fields; null rejection for each required
 * component; defensive copies of the evidence list and attributes map so callers cannot mutate
 * what was passed in; returned collections are immutable.
 */
class SecurityIdentityResolutionContextTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final AuthMethod JWT = DefaultAuthMethod.jwt();
    private static final JwksVerificationSource JWKS = new JwksVerificationSource(
            Optional.of("https://idp.example.com"),
            Optional.of("https://idp.example.com/.well-known/jwks.json"),
            Optional.of("key-001"),
            Optional.of("RS256"));

    private static AuthenticationEvidence minimalEvidence() {
        return new AuthenticationEvidence(JWT, Optional.empty(), NOW, Optional.empty(), JWKS, Map.of());
    }

    private static CorrelationContext minimalCorrelation() {
        CorrelationContextSnapshot snapshot = CorrelationContextSnapshot.of(
                new CorrelationIdentifier("req-test", "header"), new CorrelationIdentifier("corr-test", "header"));
        return new CorrelationContext() {
            @Override
            public CorrelationIdentifier requestId() {
                return snapshot.requestId();
            }

            @Override
            public CorrelationIdentifier correlationId() {
                return snapshot.correlationId();
            }

            @Override
            @Nullable
            public CorrelationIdentifier causationId() {
                return snapshot.causationId();
            }

            @Override
            @Nullable
            public dev.vertique.core.correlation.TraceReference trace() {
                return snapshot.trace();
            }

            @Override
            public List<dev.vertique.core.correlation.ProtocolCorrelationRef> protocolCorrelations() {
                return snapshot.protocolCorrelations();
            }

            @Override
            @Nullable
            public dev.vertique.core.correlation.CorrelationSessionRef session() {
                return snapshot.session();
            }

            @Override
            public Map<String, String> attributes() {
                return snapshot.attributes();
            }

            @Override
            public CorrelationContextSnapshot snapshot() {
                return snapshot;
            }
        };
    }

    private static RequestOrigin minimalOrigin() {
        return new RequestOrigin(
                "10.0.0.1", 443, List.of(), 0, false, "10.0.0.1", "https", "api.example.com", Optional.empty());
    }

    // --- minimal construction ---

    @Test
    @DisplayName("constructs with empty evidence, empty origin, empty correlation and empty attributes")
    void minimalConstruction() {
        SecurityIdentityResolutionContext ctx =
                new SecurityIdentityResolutionContext(List.of(), Optional.empty(), Optional.empty(), Map.of());

        assertTrue(ctx.evidence().isEmpty());
        assertTrue(ctx.origin().isEmpty());
        assertTrue(ctx.correlation().isEmpty());
        assertTrue(ctx.attributes().isEmpty());
    }

    // --- populated construction ---

    @Test
    @DisplayName("constructs with populated evidence, origin, correlation and attributes")
    void populatedConstruction() {
        AuthenticationEvidence ev = minimalEvidence();
        RequestOrigin origin = minimalOrigin();
        CorrelationContext correlation = minimalCorrelation();
        Map<String, Object> attrs = Map.of("key", "value");

        SecurityIdentityResolutionContext ctx = new SecurityIdentityResolutionContext(
                List.of(ev), Optional.of(origin), Optional.of(correlation), attrs);

        assertEquals(1, ctx.evidence().size());
        assertEquals(ev, ctx.evidence().get(0));
        assertEquals(Optional.of(origin), ctx.origin());
        assertEquals(Optional.of(correlation), ctx.correlation());
        assertEquals("value", ctx.attributes().get("key"));
    }

    // --- null rejections ---

    @Nested
    @DisplayName("null rejection")
    class NullRejection {

        @Test
        @DisplayName("rejects null evidence list")
        void rejectsNullEvidence() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityIdentityResolutionContext(null, Optional.empty(), Optional.empty(), Map.of()));
        }

        @Test
        @DisplayName("rejects null origin Optional")
        void rejectsNullOrigin() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityIdentityResolutionContext(List.of(), null, Optional.empty(), Map.of()));
        }

        @Test
        @DisplayName("rejects null correlation Optional")
        void rejectsNullCorrelation() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityIdentityResolutionContext(List.of(), Optional.empty(), null, Map.of()));
        }

        @Test
        @DisplayName("rejects null attributes map")
        void rejectsNullAttributes() {
            assertThrows(
                    NullPointerException.class,
                    () -> new SecurityIdentityResolutionContext(List.of(), Optional.empty(), Optional.empty(), null));
        }
    }

    // --- defensive copies ---

    @Test
    @DisplayName("mutating source evidence list after construction does not affect the record")
    void evidenceDefensiveCopy() {
        List<AuthenticationEvidence> mutable = new ArrayList<>();
        mutable.add(minimalEvidence());

        SecurityIdentityResolutionContext ctx =
                new SecurityIdentityResolutionContext(mutable, Optional.empty(), Optional.empty(), Map.of());
        mutable.add(minimalEvidence());

        assertEquals(1, ctx.evidence().size());
    }

    @Test
    @DisplayName("returned evidence list is immutable")
    void evidenceListIsImmutable() {
        SecurityIdentityResolutionContext ctx = new SecurityIdentityResolutionContext(
                List.of(minimalEvidence()), Optional.empty(), Optional.empty(), Map.of());

        assertThrows(UnsupportedOperationException.class, () -> ctx.evidence().add(minimalEvidence()));
    }

    @Test
    @DisplayName("mutating source attributes map after construction does not affect the record")
    void attributesDefensiveCopy() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v1");

        SecurityIdentityResolutionContext ctx =
                new SecurityIdentityResolutionContext(List.of(), Optional.empty(), Optional.empty(), mutable);
        mutable.put("k", "v2");

        assertEquals("v1", ctx.attributes().get("k"));
    }

    @Test
    @DisplayName("returned attributes map is immutable")
    void attributesMapIsImmutable() {
        SecurityIdentityResolutionContext ctx =
                new SecurityIdentityResolutionContext(List.of(), Optional.empty(), Optional.empty(), Map.of("x", "y"));

        assertThrows(UnsupportedOperationException.class, () -> ctx.attributes().put("z", "w"));
    }
}
