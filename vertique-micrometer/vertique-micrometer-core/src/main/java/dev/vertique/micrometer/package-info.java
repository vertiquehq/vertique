// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Micrometer metrics core for the Vertique framework.
 *
 * <p>This package provides the backend-agnostic building blocks for application metrics:
 *
 * <ul>
 *   <li><b>Registry provider SPI</b> — {@link dev.vertique.micrometer.MeterRegistryProvider}
 *       contributes one or more {@link io.micrometer.core.instrument.MeterRegistry} instances, each
 *       described by a {@link dev.vertique.micrometer.MeterRegistryBackend}, to the composite
 *       assembled at startup.</li>
 *   <li><b>Composite assembly</b> — {@link dev.vertique.micrometer.MicrometerAssembly} combines the
 *       contributed registries into one
 *       {@link io.micrometer.core.instrument.composite.CompositeMeterRegistry} and publishes it
 *       through {@link dev.vertique.micrometer.MeterRegistryHolder}, which is what makes a registry
 *       reachable before {@code Vertx} exists.</li>
 *   <li><b>Bootstrap contributor</b> — {@link dev.vertique.micrometer.MicrometerMetricsContributor}
 *       installs the Vert.x Micrometer integration during application startup.</li>
 *   <li><b>Guards</b> — {@link dev.vertique.micrometer.CardinalityGuard} caps tag values per guarded
 *       key and, optionally, the total meter count;
 *       {@link dev.vertique.micrometer.TagPolicyValidator} rejects a tag policy the guards could not
 *       honor.</li>
 *   <li><b>Instrumentation</b> — {@link dev.vertique.micrometer.Timed @Timed} with its
 *       {@link dev.vertique.micrometer.TimedAspect} provider, and
 *       {@link dev.vertique.micrometer.SecurityMetricsObserver} for the security meters.</li>
 * </ul>
 */
package dev.vertique.micrometer;
