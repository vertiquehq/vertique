// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.core.correlation.CorrelationSessionRef;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared test fixtures for {@code dev.vertique.security.events} tests.
 *
 * <p>Provides minimal stubs and factory methods used across multiple test classes in this
 * package to avoid duplication.
 */
final class EventTestFixtures {

    private EventTestFixtures() {}

    // --- correlation ---

    /**
     * Returns a minimal {@link CorrelationContext} stub backed by a
     * {@link CorrelationContextSnapshot} with fixed identifiers.
     *
     * @return a minimal correlation context; never null
     */
    static CorrelationContext minimalCorrelation() {
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
            public CorrelationSessionRef session() {
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

    // --- security context stub ---

    /**
     * Minimal anonymous {@link SecurityContext} stub for use in channel-lifecycle event tests.
     *
     * <p>Returns an anonymous identity ({@link SecurityIdentity#anonymous()}), an empty
     * {@link AuthenticationState} with {@link DefaultAuthMethod#none()} as primary method,
     * empty {@link AuthorizationClaims}, and no origin.
     *
     * @return a minimal security context stub; never null
     */
    static SecurityContext minimalSecurityContext() {
        SecurityIdentity identity = SecurityIdentity.anonymous();
        AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims authorization = AuthorizationClaims.empty();
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
                return authorization;
            }

            @Override
            public Optional<dev.vertique.security.origin.RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }

    // --- request origin ---

    /**
     * Builds a minimal {@link RequestOrigin} suitable for use in event record tests.
     *
     * @return a minimal request origin; never null
     */
    static RequestOrigin minimalRequestOrigin() {
        return new RequestOrigin(
                "10.0.0.1", 443, List.of(), 0, false, "10.0.0.1", "https", "example.com", Optional.empty());
    }
}
