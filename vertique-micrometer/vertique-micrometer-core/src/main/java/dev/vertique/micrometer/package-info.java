// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer metrics core for the Vertique framework.
 *
 * <p>This package provides the backend-agnostic building blocks for application metrics:
 *
 * <ul>
 *   <li><b>Registry provider SPI</b> — {@code MicrometerRegistryProvider} contributes one or more
 *       {@link io.micrometer.core.instrument.MeterRegistry} instances to the composite registry
 *       assembled at startup.</li>
 *   <li><b>Composite assembly</b> — the {@code CompositeMeterRegistryFactory} combines all
 *       contributed registries into a single {@link io.micrometer.core.instrument.composite.CompositeMeterRegistry}
 *       that is bound as the application-wide registry.</li>
 *   <li><b>Bootstrap contributor</b> — the {@code MicrometerBootstrapContributor} installs Vert.x
 *       Micrometer integration ({@code VertxPrometheusOptions} / {@code MicrometerMetricsOptions})
 *       during {@link dev.vertique.launcher.VertiqueApplication} startup.</li>
 * </ul>
 */
package dev.vertique.micrometer;
