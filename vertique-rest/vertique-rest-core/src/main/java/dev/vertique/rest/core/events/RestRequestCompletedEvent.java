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
 * REST-owned transport source event emitted exactly once per handled request that completes
 * through the normal HTTP response lifecycle (success and all failure paths).
 *
 * <p>This is a transport event. It carries no capture profile and chooses no sink. Its purpose is
 * to serve as a single source of truth that metrics, analytics, and operational observers can each
 * consume independently — without wiring their own end-handler registration.
 *
 * <p><strong>Protocol-upgrade exclusion.</strong> Successful protocol upgrades (e.g. WebSocket 101)
 * complete out-of-band via {@code RequestContextLifecycle.completeNow()} and do NOT produce a
 * completion event; channel lifecycle observers receive those transitions separately. A
 * <em>failed</em> upgrade that ends with an HTTP error response DOES produce a completion event.
 *
 * <p>Safety contract:
 * <ul>
 *   <li>{@code safeFailureMessage} is a curated, bounded string and is NEVER a raw exception
 *       message, stack trace, SQL error, or upstream service detail. If no curated message is
 *       available it is {@code null} (§10.3 of the REST events spec).</li>
 *   <li>{@code failureCode} is a low-cardinality classification string (e.g., an exception's
 *       simple class name), safe for use in metric labels.</li>
 *   <li>{@code safeAttributes} is an unmodifiable map; consumers must not attempt to cast values
 *       to mutable types.</li>
 * </ul>
 *
 * <p><strong>Captured-context snapshot semantics.</strong> This event is consumed by independent
 * operational observers and may be read after the request's holder-bound context has
 * been torn down, so every context fact it carries must be a valid point-in-time snapshot. Both
 * the {@code correlationContext} and the {@code securityContextSnapshot} are explicit immutable
 * snapshots captured at emission time, isolating the event from any later rebind of the live
 * holder-bound contexts. This is consistent with how {@link CorrelationContextSnapshot} is used
 * for the correlation context.
 *
 * @param startTime          the instant at which the middleware registered the request; never
 *                           {@code null}
 * @param endTime            the instant at which the request completion was observed; never
 *                           {@code null}
 * @param method             the HTTP method name (e.g., {@code "GET"}, {@code "POST"}); never
 *                           {@code null}
 * @param path               the raw request path; never {@code null}
 * @param routeTemplate      the OpenAPI path template (e.g., {@code "/users/{id}"}), or
 *                           {@code null} when the request did not reach operation dispatch
 * @param operationId        the OpenAPI {@code operationId}, or {@code null} when the request did
 *                           not reach operation dispatch (e.g., pre-operation validation failure)
 * @param statusCode         the HTTP response status code actually sent
 * @param failureCode        a low-cardinality failure classification (e.g., the exception's simple
 *                           class name), or {@code null} when no failure was recorded
 * @param safeFailureMessage a curated, bounded human-readable message describing the failure, or
 *                           {@code null} when no curated message is available — NEVER a raw
 *                           exception message or stack trace
 * @param securityContextSnapshot an immutable {@link SecurityContextSnapshot} captured at emission
 *                           time, isolating the event from any later rebind of the live security
 *                           context; {@code null} when the security module is not active or no
 *                           context was bound
 * @param correlationContext an immutable {@link CorrelationContextSnapshot} captured at emission
 *                           time, isolating the event from later mutation of the live
 *                           {@code MutableCorrelationContext}; {@code null} when the correlation
 *                           middleware has not run
 * @param origin             the network-envelope origin snapshot; never {@code null} as an
 *                           {@link Optional} (use {@code Optional.empty()} when origin is not
 *                           available)
 * @param safeAttributes     an unmodifiable map of additional attributes contributed by the emitter
 *                           or future enrichment hooks; never {@code null}, may be empty
 */
public record RestRequestCompletedEvent(
        Instant startTime,
        Instant endTime,
        String method,
        String path,
        @Nullable String routeTemplate,
        @Nullable String operationId,
        int statusCode,
        @Nullable String failureCode,
        @Nullable String safeFailureMessage,
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
    public RestRequestCompletedEvent {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        Objects.requireNonNull(origin, "origin");
        // Defensively copy to an unmodifiable map; skip allocation for the empty/null case.
        safeAttributes = (safeAttributes == null || safeAttributes.isEmpty()) ? Map.of() : Map.copyOf(safeAttributes);
    }
}
