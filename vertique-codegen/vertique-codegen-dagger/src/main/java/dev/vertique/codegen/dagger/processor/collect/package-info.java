// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Collectors and scanners that discover types eligible for Dagger auto-wiring.
 *
 * <p>Two discovery modes are used:
 * <ul>
 *   <li><b>Generic registrations</b> — {@code RegistrationCollector} reads repeatable
 *       {@code @RegisterAs} and {@code @RegisterIntoSet} type declarations.</li>
 *   <li><b>Annotation-rooted</b> — {@link dev.vertique.codegen.dagger.processor.collect.RestClientCollector}
 *       and {@link dev.vertique.codegen.dagger.processor.collect.KafkaConsumerCollector} each query
 *       {@link javax.annotation.processing.RoundEnvironment#getElementsAnnotatedWith} for their
 *       respective marker annotation. The former {@code @Path}-resource collector,
 *       {@code PathResourceCollector}, was removed when JAX-RS binding moved to
 *       {@code vertique-codegen-jaxrs}'s {@code JaxRsPipelineProcessor} (CG-010).</li>
 *   <li><b>Root-element scan</b> —
 *       {@link dev.vertique.codegen.dagger.processor.collect.DelayedJobExecutorScanner} walks
 *       {@link javax.annotation.processing.RoundEnvironment#getRootElements()} to find concrete
 *       implementations of interfaces or abstract types.</li>
 * </ul>
 *
 * <p>Service-contract impl discovery is owned by {@code vertique-codegen-services} (CG-005),
 * not by this module.
 */
package dev.vertique.codegen.dagger.processor.collect;
