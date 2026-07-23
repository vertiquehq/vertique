// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

import java.util.Objects;

/**
 * An immutable correlation or request identifier paired with its origin label.
 *
 * <p>The {@code value} carries the identifier string (e.g. a UUID, ULID, or opaque token).
 * The {@code source} labels where the identifier came from (e.g. {@code "X-Request-Id"},
 * {@code "generated"}, {@code "kafka-header"}), which aids observability and audit.
 *
 * <p>Instances are safe to share across threads.
 *
 * @param value  the identifier value; must not be null or blank
 * @param source the origin label describing how this identifier was obtained;
 *               must not be null or blank
 */
public record CorrelationIdentifier(String value, String source) {

    /**
     * Constructs a {@link CorrelationIdentifier}, validating that neither component is null
     * or blank.
     *
     * @param value  the identifier value
     * @param source the origin label
     * @throws NullPointerException     if {@code value} or {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code value} or {@code source} is blank
     */
    public CorrelationIdentifier {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CorrelationIdentifier value must not be blank");
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("CorrelationIdentifier source must not be blank");
        }
    }
}
