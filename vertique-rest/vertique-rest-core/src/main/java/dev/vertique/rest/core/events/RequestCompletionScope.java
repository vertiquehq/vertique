// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.events;

import io.vertx.ext.web.RoutingContext;

/**
 * SPI for establishing an ambient scope around the synchronous completion-listener dispatch loop
 * in {@link RestRequestCompletionEmitter}.
 *
 * <p>Integrations implement this interface to re-establish a thread- or context-local at
 * completion time — for example, re-making the request's traced span current so that
 * Micrometer exemplar samplers can attach the trace id to recorded timers.
 *
 * <p><strong>Set multibinding.</strong> Any number of implementations may be active at once.
 * The emitter consumes them via {@code Set<RequestCompletionScope>} declared as
 * {@code @Multibinds} in {@link dev.vertique.rest.core.dagger.RestCoreModule}. Scopes are
 * opened in iteration order before listener dispatch and closed in reverse order after. When
 * no implementation is bound the set is empty and the emitter's behavior is identical to the
 * pre-SPI baseline (no bracket overhead).
 *
 * <p><strong>Contract.</strong>
 * <ul>
 *   <li>{@link #open(RoutingContext)} is called once before the first listener dispatches.
 *       It must be cheap, non-blocking, and should not throw checked or unchecked
 *       {@link Exception}s — if one occurs the emitter logs a {@code WARN} (class name only),
 *       skips that scope's bracket, and continues with the remaining scopes. {@link Error}s
 *       (e.g. {@link OutOfMemoryError}) are <em>not</em> caught by the emitter and propagate
 *       as fatal — this is consistent with standard event-loop practice.</li>
 *   <li>The returned {@link AutoCloseable} is closed (via {@code try/finally}) after all
 *       listeners and capture coordinators have run. Its {@code close()} should also not throw
 *       {@link Exception}s — the emitter guards with its own {@code try/catch} (WARN + swallow)
 *       but good implementations do not rely on that guard. {@link Error}s from {@code close()}
 *       likewise propagate as fatal.</li>
 *   <li>Both {@code open} and {@code close} run on the Vert.x event loop — do not block.</li>
 * </ul>
 *
 * @see RestRequestCompletionEmitter
 * @see dev.vertique.rest.core.dagger.RestCoreModule
 */
public interface RequestCompletionScope {

    /**
     * Opens an ambient scope for the duration of synchronous completion-listener dispatch.
     *
     * <p>Implementations should re-establish a context-local here (e.g. re-make the
     * request's traced span current so metric exemplars can sample it). Must be cheap
     * and non-blocking. Implementations should not throw {@link Exception}s — the emitter
     * isolates {@code Exception} from completion dispatch (WARN + skip that scope's bracket)
     * but {@link Error}s (e.g. OOM) propagate as fatal and are consistent with standard
     * event-loop practice. Return a no-op {@code () -> {}} when nothing needs to be scoped.
     *
     * @param rc the routing context for the completed request; may be used to retrieve
     *           previously stashed request-scoped values (e.g. a captured span)
     * @return a closeable that will be closed after all listeners run; never {@code null}
     *         (return {@code () -> {}} when nothing to scope)
     */
    AutoCloseable open(RoutingContext rc);
}
