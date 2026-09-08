// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.capture;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import io.vertx.ext.web.RoutingContext;

/**
 * SPI for coordinating a single capture-aware audit record submission after the HTTP response
 * has been fully serialized and written to the wire.
 *
 * <p>This interface is <em>distinct</em> from {@link dev.vertique.rest.core.events.RestRequestCompletedListener}
 * in two important ways:
 * <ol>
 *   <li><strong>Evidence access.</strong> A coordinator receives the live {@link RoutingContext}
 *       so it can read request and response evidence. Implementations own how that evidence is
 *       stored and retrieved (e.g. request/response body payloads, resolved capture policy).
 *       {@link RoutingContext#data()} is a plain {@code Map<String, Object>} keyed by public
 *       {@code String} constants that any other component sharing the same routing context
 *       (application interceptors, route handlers) can enumerate, read, or overwrite — it is not an
 *       appropriate storage choice for sensitive evidence (GH-118). The safe
 *       {@link dev.vertique.rest.core.events.RestRequestCompletedListener} does not have
 *       {@code RoutingContext} access and carries only the immutable, already-snapshotted fields of
 *       {@link RestRequestCompletedEvent}.</li>
 *   <li><strong>Audit responsibility.</strong> Only an audit adapter should register a coordinator.
 *       The coordinator is the integration point for submitting exactly one audit record per request
 *       (the "one record per operation" semantic of AUD-003). Application code should not implement
 *       this interface; prefer {@link dev.vertique.rest.core.events.RestRequestCompletedListener}
 *       for non-audit observability consumers.</li>
 * </ol>
 *
 * <p>Invocation contract:
 * <ul>
 *   <li>Invoked exactly once per request, from the completion emitter's end-handler, <em>after</em>
 *       the safe {@link dev.vertique.rest.core.events.RestRequestCompletedListener} set has been
 *       notified.</li>
 *   <li>The {@link RoutingContext} is still live at invocation time (the response has been sent and
 *       the response end handler is executing, so routing-context data is accessible but the
 *       response body can no longer be written).</li>
 *   <li>Implementations MUST NOT block the Vert.x event loop. Any I/O or heavy processing must be
 *       dispatched asynchronously.</li>
 *   <li>All uncaught exceptions are caught by the emitter, logged at {@code WARN}, and do not
 *       prevent other coordinators or the normal completion of the HTTP response. The exception
 *       message reaches the application log, so an implementation MUST NOT put credentials, tokens,
 *       personal data, or raw request or response values into the exception message or type it
 *       throws — audit-safe by contract rather than by enforcement.</li>
 *   <li>With no registered coordinators the emitter performs a pure no-op.</li>
 * </ul>
 *
 * <p>Coordinators participate in the {@link OrderedExtension} ordering contract (phase → priority →
 * orderKey). When multiple coordinators are registered they are invoked in sorted order. Register
 * implementations via Dagger multibinding ({@code @IntoSet}).
 *
 * @see RestRequestCompletedEvent
 * @see dev.vertique.rest.core.events.RestRequestCompletedListener
 * @see OrderedExtension
 */
public interface RestRequestCaptureCoordinator extends OrderedExtension {

    /**
     * Called once per request after the HTTP response has been fully written and after all safe
     * {@link dev.vertique.rest.core.events.RestRequestCompletedListener} instances have been
     * notified.
     *
     * <p>Implementations retrieve evidence from wherever the paired
     * {@link RestServerRequestEvidenceCapturer} implementation stored it — an implementation-private
     * side channel keyed by request identity, <em>not</em> {@code rc.data()} — and submit exactly one
     * capture-aware audit record. Evidence retrieved here MUST NOT be exposed on {@code rc.data()}.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect
     * the enclosing operation. The exception message is logged, so it must carry no credentials,
     * tokens, personal data, or raw request or response values.
     *
     * @param event the completed-request event, identical to what the safe listeners received;
     *              never {@code null}
     * @param rc    the live routing context for the completed request; never {@code null}. The
     *              response has already been written — do not attempt to write to it.
     */
    void onRequestCompletion(RestRequestCompletedEvent event, RoutingContext rc);
}
