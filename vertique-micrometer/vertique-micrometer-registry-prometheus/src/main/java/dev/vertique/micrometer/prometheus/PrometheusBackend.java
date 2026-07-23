// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Package-private static holder for the singleton {@link PrometheusMeterRegistry} and its
 * associated {@link DeferredSpanContext}.
 *
 * <p>The holder uses {@link AtomicReference}s so that both the scrape endpoint and the backend
 * provider can access and update state safely across threads (e.g. event-loop thread vs. worker
 * thread).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link PrometheusMeterRegistryProvider#create(io.vertx.core.json.JsonObject)} calls
 *       {@link #publish(PrometheusMeterRegistry, DeferredSpanContext)} to register the live
 *       registry.</li>
 *   <li>{@link PrometheusScrapeEndpoint#contribute(io.vertx.ext.web.Router)} reads
 *       {@link #registry()} to decide whether to mount the scrape route.</li>
 *   <li>On shutdown or bootstrap rollback, the backend's {@code close()} calls {@link #clear()}.
 *   </li>
 * </ol>
 *
 * <p>This class is intentionally package-private so that only code within
 * {@code dev.vertique.micrometer.prometheus} can manipulate the static state; integration tests
 * may reach it via {@link #registry()} to record meters for scrape-body assertions.
 */
final class PrometheusBackend {

    private static final AtomicReference<PrometheusMeterRegistry> REGISTRY = new AtomicReference<>();

    private static final AtomicReference<DeferredSpanContext> SPAN_CONTEXT = new AtomicReference<>();

    /** Prevent instantiation. */
    private PrometheusBackend() {}

    // --- Accessors ---

    /**
     * Returns the currently published {@link PrometheusMeterRegistry}, or an empty optional when
     * {@link #publish} has not been called or {@link #clear()} has been called since the last
     * publish.
     *
     * @return an optional containing the registry if present; empty otherwise
     */
    static Optional<PrometheusMeterRegistry> registry() {
        return Optional.ofNullable(REGISTRY.get());
    }

    /**
     * Returns the currently published {@link DeferredSpanContext}, or an empty optional when no
     * registry has been published.
     *
     * @return an optional containing the span context if present; empty otherwise
     */
    static Optional<DeferredSpanContext> spanContext() {
        return Optional.ofNullable(SPAN_CONTEXT.get());
    }

    // --- Mutators ---

    /**
     * Publishes a {@link PrometheusMeterRegistry} and its {@link DeferredSpanContext} to the
     * holder so that the scrape endpoint can find them.
     *
     * <p>Any previously published registry is silently replaced. This is safe because
     * {@link PrometheusMeterRegistryProvider#create} is always called before
     * {@link PrometheusScrapeEndpoint#contribute}, and at most one prometheus backend is wired
     * per application.
     *
     * @param r  the registry to publish; must not be {@code null}
     * @param sc the deferred span context bound to {@code r}; must not be {@code null}
     */
    static void publish(PrometheusMeterRegistry r, DeferredSpanContext sc) {
        REGISTRY.set(r);
        SPAN_CONTEXT.set(sc);
    }

    /**
     * Removes the published registry and span context from the holder.
     *
     * <p>Called by the backend {@code close()} method so that the scrape endpoint stops serving
     * metrics after shutdown or bootstrap rollback.
     */
    static void clear() {
        REGISTRY.set(null);
        SPAN_CONTEXT.set(null);
    }
}
