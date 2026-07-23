// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.origin.RequestOrigin;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Event fired when the framework accepts verified inbound authentication evidence.
 *
 * <p>This is a request-ingress credential-verification event, not an interactive login event.
 * It fires after the framework has successfully resolved a {@link SecurityIdentity} from the
 * presented credential material. Login-flow events (session creation, MFA challenges, lockouts)
 * are reserved for a future auth-server/login feature.
 *
 * <p>All fields are non-null after construction; the {@code origin} optional carries the
 * {@link RequestOrigin} when network envelope information was captured before authentication.
 *
 * @param occurredAt     wall-clock instant when the credential was accepted; never null
 * @param correlation    correlation context for the request that triggered this event; never null
 * @param origin         captured network-envelope facts; non-null {@link Optional} — use
 *                       {@link Optional#empty()} when origin was not captured
 * @param authentication the resolved authentication state including method, evidence, and
 *                       assurance; never null
 * @param identity       the resolved security identity; never null
 */
public record CredentialAcceptedEvent(
        Instant occurredAt,
        CorrelationContext correlation,
        Optional<RequestOrigin> origin,
        AuthenticationState authentication,
        SecurityIdentity identity) {

    /**
     * Compact constructor — validates that all fields are non-null.
     */
    public CredentialAcceptedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(identity, "identity");
    }
}
