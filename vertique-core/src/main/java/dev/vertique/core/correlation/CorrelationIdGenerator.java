// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.correlation;

/**
 * SPI for plugging in a custom correlation ID generation strategy.
 *
 * <p>Implementations must return a non-null, non-blank string that is suitable for use as a
 * correlation or request identifier. Common strategies include UUID v4 generation, ULID
 * generation, and prefixed sequential identifiers.
 *
 * <p>Implementations must be thread-safe. The {@link #generate()} method may be called
 * concurrently from multiple Vert.x event-loop threads.
 *
 * <p>Example — UUID-based generator:
 * <pre>{@code
 * CorrelationIdGenerator uuidGenerator = () -> UUID.randomUUID().toString();
 * }</pre>
 */
@FunctionalInterface
public interface CorrelationIdGenerator {

    /**
     * Generates a new unique correlation identifier.
     *
     * @return a non-null, non-blank identifier string
     */
    String generate();
}
