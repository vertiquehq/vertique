// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.security.ClientRef;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import java.util.Map;
import java.util.Optional;

/** Minimal {@link SecurityIdentity} construction helpers for subject-resolution fixtures. */
final class RateLimitIdentityFixtures {

    private RateLimitIdentityFixtures() {}

    /** An identity with only an actor — no subject, delegation, or client. */
    static SecurityIdentity actorOnly(String actorId) {
        return new SecurityIdentity(user(actorId), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** An identity whose {@code subject()} is present (a delegation-style caller). */
    static SecurityIdentity withSubject(String actorId, String subjectId) {
        return new SecurityIdentity(user(actorId), Optional.of(user(subjectId)), Optional.empty(), Optional.empty());
    }

    /** An identity whose {@code client()} is present (an OAuth-client-attributed caller). */
    static SecurityIdentity withClient(String actorId, String clientId) {
        return new SecurityIdentity(user(actorId), Optional.empty(), Optional.empty(), Optional.of(client(clientId)));
    }

    private static PrincipalRef user(String id) {
        return new PrincipalRef(PrincipalType.USER, id, Map.of());
    }

    private static ClientRef client(String clientId) {
        return new ClientRef(clientId, "test-fixture", Map.of());
    }
}
