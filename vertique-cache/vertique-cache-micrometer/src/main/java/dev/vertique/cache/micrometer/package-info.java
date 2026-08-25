// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer cache metrics adapter.
 *
 * <p>This package bridges provider-neutral cache observations into Micrometer meters. It is
 * observe-only: it records cache operation and cleanup metrics but never modifies cache behavior.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.cache.micrometer.MicrometerCacheModule} — Dagger module contributing
 *       the cache metrics observer via multibinding; install alongside
 *       {@link dev.vertique.micrometer.MicrometerModule} and the application's cache modules</li>
 *   <li>{@link dev.vertique.cache.micrometer.CacheMetricsObserver} — records bounded operation
 *       timers and cleanup counters in the injected {@link io.micrometer.core.instrument.MeterRegistry}</li>
 * </ul>
 */
package dev.vertique.cache.micrometer;
