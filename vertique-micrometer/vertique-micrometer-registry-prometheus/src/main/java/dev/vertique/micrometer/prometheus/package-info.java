// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Prometheus registry backend for Vertique Micrometer metrics.
 *
 * <p>This package provides:
 *
 * <ul>
 *   <li><b>Prometheus registry backend</b> — {@link PrometheusMeterRegistryProvider} implements
 *       {@link dev.vertique.micrometer.MeterRegistryProvider} to contribute a
 *       {@link io.micrometer.prometheusmetrics.PrometheusMeterRegistry} to the composite
 *       registry assembled by the core module.</li>
 *   <li><b>Management scrape endpoint</b> — {@link PrometheusScrapeEndpoint} registered on
 *       the management HTTP server (via {@link dev.vertique.management}) that exposes
 *       {@code /metrics} for Prometheus scraping.</li>
 * </ul>
 */
package dev.vertique.micrometer.prometheus;
