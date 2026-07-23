// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable reference to a session-level correlation identifier extracted from an inbound
 * request (e.g. a JWT claim, a session cookie, or an OAuth token).
 *
 * <p>The {@code id} carries the session identifier value. The {@code kind} labels the session
 * type (e.g. {@code "jwt"}, {@code "cookie"}, {@code "oauth2"}). The {@code source} labels
 * where it was extracted from (e.g. {@code "auth-filter"}, {@code "cookie-filter"}).
 * The optional {@code claimName} records the specific claim or field within the session token
 * that was used (e.g. {@code "jti"}, {@code "sid"}).
 *
 * <p>The canonical constructor ensures the attributes map is an unmodifiable defensive copy.
 *
 * @param id           the session identifier value; must not be null or blank
 * @param kind         the session type label; must not be null or blank
 * @param source       the extraction origin label; must not be null or blank
 * @param claimName    the claim or field name within the session token; may be {@code null}
 * @param durableSafe  {@code true} if this session reference is safe to persist in durable storage
 * @param attributes   optional extra metadata; {@code null} is treated as empty
 */
public record CorrelationSessionRef(
        String id,
        String kind,
        String source,
        @Nullable String claimName,
        boolean durableSafe,
        Map<String, String> attributes) {

    /**
     * Constructs a {@link CorrelationSessionRef}, validating required fields and making a
     * defensive immutable copy of {@code attributes}.
     *
     * @param id          the session identifier value
     * @param kind        the session type label
     * @param source      the extraction origin label
     * @param claimName   the claim/field name; may be {@code null}
     * @param durableSafe whether safe for durable storage
     * @param attributes  optional metadata map; {@code null} is treated as empty
     * @throws NullPointerException     if {@code id}, {@code kind}, or {@code source} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code id}, {@code kind}, or {@code source} is blank
     */
    public CorrelationSessionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        if (id.isBlank()) {
            throw new IllegalArgumentException("CorrelationSessionRef id must not be blank");
        }
        if (kind.isBlank()) {
            throw new IllegalArgumentException("CorrelationSessionRef kind must not be blank");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("CorrelationSessionRef source must not be blank");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
