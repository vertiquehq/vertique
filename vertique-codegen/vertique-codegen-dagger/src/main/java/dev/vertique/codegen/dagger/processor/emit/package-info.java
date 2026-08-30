// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Emitters that produce Dagger {@code @Module} source files from collected {@link dev.vertique.codegen.dagger.processor.Binding} lists.
 *
 * <p>Two emitter shapes are supported:
 * <ul>
 *   <li>{@code RegistrationModuleEmitter} — generates abstract {@code @Binds} and
 *       {@code @Binds @IntoSet} declarations for generic registration annotations.</li>
 *   <li>{@link dev.vertique.codegen.dagger.processor.emit.MultibindingModuleEmitter} — generates
 *       {@code @Provides @IntoSet @Qualifier} methods for multibinding qualifiers
 *       ({@code @Services}, {@code @JaxRsResources}, {@code @KafkaConsumers},
 *       {@code @DelayedJobs}).</li>
 *   <li>{@link dev.vertique.codegen.dagger.processor.emit.RestClientModuleEmitter} — generates
 *       {@code @Provides @Singleton InterfaceType provideXxx(RestClientFactory)} methods
 *       for {@code @RestClient}-annotated interfaces.</li>
 * </ul>
 */
package dev.vertique.codegen.dagger.processor.emit;
