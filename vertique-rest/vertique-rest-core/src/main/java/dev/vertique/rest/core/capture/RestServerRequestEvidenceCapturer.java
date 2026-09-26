// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.capture;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.ext.web.RoutingContext;

/**
 * SPI for capturing neutral evidence from an incoming HTTP request.
 *
 * <p>Implementations are invoked once per request after the request body has been materialized
 * and OpenAPI validation has passed, but <em>before</em> the JAX-RS resource method is called.
 * This is the correct point to capture the request body or resolve an audit policy for later use
 * by response-side audit logic.
 *
 * <p><strong>Contract for implementors:</strong>
 * <ul>
 *   <li>Implementations MUST be side-effect-only — they must not modify the request pipeline,
 *       send a response, or call {@link RoutingContext#next()} or {@link RoutingContext#fail}.</li>
 *   <li>Implementations MUST NOT block the Vert.x event loop.</li>
 *   <li>Exceptions thrown by {@link #captureRequest} are caught by the invoking framework and
 *       logged at {@code WARN}; a throwing capturer must never break request handling. (A throw
 *       from {@link #validateRoute} is different: it rejects the route at router build.)</li>
 *   <li>When no capturers are registered the framework performs a pure no-op — zero overhead.</li>
 *   <li>Implementations MUST NOT stash sensitive evidence (resolved policy, captured bodies) on
 *       {@link RoutingContext#data()} — that map is a plain {@code Map<String, Object>} keyed by
 *       public {@code String} constants that any other component sharing the same routing context
 *       (application interceptors, route handlers) can enumerate, read, or overwrite. The in-repo
 *       audit-rest implementation keeps such evidence in its own internal, identity-keyed side
 *       table instead (GH-118). Where and how evidence is stored is implementation-private and
 *       must never be application-visible.</li>
 * </ul>
 *
 * <p>Capturers participate in the {@link OrderedExtension} ordering contract (phase → priority →
 * orderKey). Register implementations via Dagger multibinding ({@code @IntoSet}).
 *
 * @see HttpOperationMeta
 * @see OrderedExtension
 */
public interface RestServerRequestEvidenceCapturer extends OrderedExtension {

    /**
     * Validates a route at router build, before any request can reach it.
     *
     * <p>{@code meta} is the descriptor the route's requests will carry: the same method, resource
     * class, operationId, and route template {@link #captureRequest} receives. A capturer uses this
     * to validate or warm its per-route state — for example resolving the route's capture policy —
     * so a misconfiguration fails startup instead of every request.
     *
     * <p>Throwing rejects the route: the registrar records an {@code EVIDENCE_CAPTURE_REJECTED}
     * violation carrying the exception and fails router build once all routes have been checked.
     * The default accepts every route.
     *
     * <p>The registrar calls this once per route <em>per router build</em> — for every mount and every
     * HTTP verticle instance — so the same route can be validated several times, concurrently, on
     * different event loops. Implementations must be idempotent, thread-safe, and non-blocking.
     *
     * @param meta the route's operation descriptor; never {@code null}
     */
    default void validateRoute(HttpOperationMeta meta) {}

    /**
     * Captures evidence from the incoming HTTP request for later use by response-side audit logic.
     *
     * <p>Called once per request after the request body has been materialized and validated.
     * Implementations own their storage mechanism for any captured evidence; see the class-level
     * javadoc for why {@link RoutingContext#data()} is not an appropriate choice for sensitive
     * evidence.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation.
     *
     * @param ctx  the current routing context; callers must treat this as read-only with respect
     *             to routing control (do not call {@code next()}, {@code fail()}, etc.)
     * @param meta the neutral operation descriptor holding the resource {@link java.lang.reflect.Method},
     *             operationId, and route template for this request
     */
    void captureRequest(RoutingContext ctx, HttpOperationMeta meta);
}
