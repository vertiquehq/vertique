// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;

/**
 * Maintains a gauge ({@value #METER_NAME}) tracking the number of in-flight HTTP server requests.
 *
 * <p>Contributed to the {@link RequestInterceptor} multibinding by {@link MicrometerRestModule}.
 * On {@link #onRequest}, the gauge is incremented and an end-handler is registered to decrement it
 * when the response completes. The end-handler is idempotent: it decrements at most once per
 * routing context.
 *
 * <p>WebSocket upgrade requests (identified by the {@code Upgrade: websocket} header) are excluded:
 * they are not counted as active REST requests and no end-handler is registered.
 *
 * <p>When {@code metricsConfig} is present and {@link MetricsConfig#enabled()} returns
 * {@code false}, this interceptor does nothing. When the optional is empty (no binding present),
 * the gauge is active (default-enabled).
 *
 * <p>Throwing meter registries are isolated: any exception during gauge work is caught and swallowed.
 *
 * @see MicrometerRestModule
 */
@Slf4j
@Singleton
public final class RestServerActiveRequestsInterceptor implements RequestInterceptor {

    /** Micrometer meter name for the active-requests gauge. */
    public static final String METER_NAME = "vertique.rest.server.active";

    /** Routing-context key used to guard against double-increment on the same context. */
    static final String KEY_ACTIVE_REQUEST_COUNTED = "vertique.micrometer.rest.activeRequestCounted";

    private final boolean enabled;
    private final LongAdder adder;

    /**
     * Creates the interceptor with the given meter registry and optional metrics configuration.
     *
     * <p>When enabled, registers the active-requests gauge backed by a {@link LongAdder}
     * immediately at construction time. Any exception thrown by the registry during gauge
     * registration is caught and swallowed so that a misbehaving registry cannot break
     * construction.
     *
     * @param registry      the application-wide meter registry; never {@code null}
     * @param metricsConfig an optional {@link MetricsConfig}; when present and
     *                      {@link MetricsConfig#enabled()} is {@code false}, all gauge work is
     *                      skipped; when empty the gauge is active (default-enabled)
     */
    @Inject
    public RestServerActiveRequestsInterceptor(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
        this.adder = new LongAdder();
        if (this.enabled) {
            try {
                Gauge.builder(METER_NAME, adder, LongAdder::doubleValue)
                        .description("Number of in-flight HTTP server requests")
                        .register(registry);
            } catch (Exception e) {
                log.warn(
                        "RestServerActiveRequestsInterceptor failed to register gauge: {}",
                        e.getClass().getName());
            }
        }
    }

    /**
     * Increments the active-requests gauge and registers an idempotent end-handler that decrements
     * it when the response completes.
     *
     * <p>WebSocket upgrade requests are excluded. A guard key ({@link #KEY_ACTIVE_REQUEST_COUNTED})
     * prevents double-increment if this method is called more than once on the same routing context.
     * The end-handler uses a per-request {@link AtomicBoolean} to guarantee the decrement fires
     * at most once even if the end-handler fires multiple times.
     *
     * @param rc the Vert.x {@link RoutingContext} for the incoming request
     */
    @Override
    public void onRequest(RoutingContext rc) {
        if (!enabled) {
            return;
        }
        try {
            // --- WebSocket upgrade exclusion ---
            if ("websocket".equalsIgnoreCase(rc.request().getHeader("Upgrade"))) {
                return;
            }

            // --- Double-entry guard via routing context key ---
            if (Boolean.TRUE.equals(rc.get(KEY_ACTIVE_REQUEST_COUNTED))) {
                return;
            }
            rc.put(KEY_ACTIVE_REQUEST_COUNTED, Boolean.TRUE);

            adder.increment();

            // --- Idempotent end handler: decrements at most once per routing context ---
            AtomicBoolean decremented = new AtomicBoolean(false);
            rc.addEndHandler(v -> {
                if (decremented.compareAndSet(false, true)) {
                    adder.decrement();
                }
            });
        } catch (Exception e) {
            log.warn(
                    "RestServerActiveRequestsInterceptor.onRequest failed: {}",
                    e.getClass().getName());
        }
    }
}
