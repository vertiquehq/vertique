// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.context;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies the durable destination a carrier row was written for.
 *
 * <p>A target names the durable substrate ({@code kind}, e.g. {@code "kafka"} or {@code "outbox"}),
 * the concrete destination within that substrate ({@code address}, e.g. a topic or table name), and
 * an optional logical {@code messageType} when the substrate distinguishes message shapes. It is the
 * destination component of a {@link DurableCarrierDescriptor}.
 *
 * <p>The type lives in {@code vertique-core} because the durable seam is a core concept; it is also
 * referenced by the security snapshot envelope in downstream modules.
 *
 * @param kind the durable substrate identifier (e.g. {@code "kafka"}, {@code "outbox"}); never
 *     {@code null} or blank
 * @param address the concrete destination within the substrate (e.g. topic or table name); never
 *     {@code null} or blank
 * @param messageType the optional logical message type for the target; never {@code null}, empty
 *     when the substrate carries no message-type distinction
 */
public record DurableTarget(String kind, String address, Optional<String> messageType) {

    /**
     * Canonical constructor.
     *
     * @param kind the durable substrate identifier
     * @param address the concrete destination within the substrate
     * @param messageType the optional logical message type for the target
     * @throws NullPointerException if {@code kind}, {@code address}, or {@code messageType} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code kind} or {@code address} is blank
     */
    public DurableTarget {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        if (kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (address.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
    }
}
