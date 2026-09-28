// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.security.SecurityContextSnapshot;
import dev.vertique.security.origin.RequestOrigin;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport source event emitted exactly once for each HTTP request that completes through the
 * normal HTTP response lifecycle (success and all failure paths) without any transport claiming it.
 *
 * <p>A request that a JAX-RS operation route claimed produces a {@link RestRequestCompletedEvent}
 * instead, and a request that another transport claimed produces neither event. A <em>failed</em>
 * protocol upgrade that ends with an HTTP error response is an unclaimed request and produces this
 * event; a successful upgrade produces no completion event.
 *
 * <p>This is a transport event. It carries no operation, no capture profile, and chooses no sink.
 * Consumers observe it through {@link HttpRequestCompletedListener}.
 *
 * <p>Safety contract (the same rules as {@link RestRequestCompletedEvent}'s shared components):
 * <ul>
 *   <li>{@code safeFailureMessage} is a curated, bounded string and is NEVER a raw exception
 *       message, stack trace, SQL error, or upstream service detail. If no curated message is
 *       available it is {@code null}.</li>
 *   <li>{@code failureCode} is a low-cardinality classification string (e.g., an exception's
 *       simple class name), safe for use in metric labels.</li>
 *   <li>{@code path} is the raw, attacker-controlled request path, bounded only by the Vert.x HTTP
 *       server's request-line and header limits. It is not truncated here.</li>
 *   <li>{@code safeAttributes} is an unmodifiable map; consumers must not attempt to cast values
 *       to mutable types.</li>
 * </ul>
 *
 * <p><strong>Captured-context snapshot semantics.</strong> This event may be read after the
 * request's holder-bound context has been torn down, so every context fact it carries is a valid
 * point-in-time snapshot. Both the {@code correlationContext} and the
 * {@code securityContextSnapshot} are explicit immutable snapshots captured at emission time,
 * isolating the event from any later rebind of the live holder-bound contexts.
 *
 * <p>Evolution: new components are only appended, after the last existing one, and each addition
 * keeps the previous-arity constructor. Record-pattern deconstruction binds components by
 * position, so it is outside the compatibility promise.
 *
 * @param startTime          the instant at which the emitter registered the request; never
 *                           {@code null}
 * @param endTime            the instant at which the request completion was observed; never
 *                           {@code null}
 * @param method             the HTTP method name (e.g., {@code "GET"}, {@code "POST"}); never
 *                           {@code null}
 * @param path               the raw request path; never {@code null}
 * @param statusCode         the HTTP response status code actually sent
 * @param failureCode        a low-cardinality failure classification (e.g., the exception's simple
 *                           class name), or {@code null} when no failure was recorded
 * @param safeFailureMessage a curated, bounded human-readable message describing the failure, or
 *                           {@code null} when no curated message is available — NEVER a raw
 *                           exception message or stack trace
 * @param wireFailureCode    a low-cardinality post-handoff wire-failure classification, with the
 *                           same meaning as {@link RestRequestCompletedEvent#wireFailureCode()}, or
 *                           {@code null} when no wire failure was observed
 * @param securityContextSnapshot an immutable {@link SecurityContextSnapshot} captured at emission
 *                           time; {@code null} when the security module is not active or no
 *                           context was bound
 * @param correlationContext an immutable {@link CorrelationContextSnapshot} captured at emission
 *                           time; {@code null} when the correlation middleware has not run
 * @param origin             the network-envelope origin snapshot; never {@code null} as an
 *                           {@link Optional} (use {@code Optional.empty()} when origin is not
 *                           available)
 * @param safeAttributes     an unmodifiable map of additional attributes contributed by the emitter
 *                           or future enrichment hooks; never {@code null}, may be empty
 */
public record HttpRequestCompletedEvent(
        Instant startTime,
        Instant endTime,
        String method,
        String path,
        int statusCode,
        @Nullable String failureCode,
        @Nullable String safeFailureMessage,
        @Nullable String wireFailureCode,
        @Nullable SecurityContextSnapshot securityContextSnapshot,
        @Nullable CorrelationContextSnapshot correlationContext,
        Optional<RequestOrigin> origin,
        Map<String, Object> safeAttributes) {

    /**
     * Compact constructor that validates required fields and defensively copies mutable inputs.
     *
     * @throws NullPointerException if {@code method}, {@code path}, {@code startTime},
     *                              {@code endTime}, or {@code origin} is {@code null}
     */
    public HttpRequestCompletedEvent {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        Objects.requireNonNull(origin, "origin");
        // Defensively copy to an unmodifiable map; skip allocation for the empty/null case.
        safeAttributes = (safeAttributes == null || safeAttributes.isEmpty()) ? Map.of() : Map.copyOf(safeAttributes);
    }
}
