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
 *   <li>All uncaught exceptions are caught by the invoking framework and logged at {@code WARN}.
 *       A throwing capturer must never break request handling.</li>
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
     * Captures evidence from the incoming HTTP request for later use by response-side audit logic.
     *
     * <p>Called once per request after the request body has been materialized and validated.
     * Implementations own their storage mechanism for any captured evidence; see the class-level
     * javadoc for why {@link RoutingContext#data()} is not an appropriate choice for sensitive
     * evidence.
     *
     * @param ctx  the current routing context; callers must treat this as read-only with respect
     *             to routing control (do not call {@code next()}, {@code fail()}, etc.)
     * @param meta the neutral operation descriptor holding the resource {@link java.lang.reflect.Method},
     *             operationId, and route template for this request
     */
    void captureRequest(RoutingContext ctx, HttpOperationMeta meta);
}
