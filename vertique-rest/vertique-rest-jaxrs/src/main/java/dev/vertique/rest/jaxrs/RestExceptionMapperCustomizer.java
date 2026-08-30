// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.extension.OrderedExtension;

/**
 * SPI for contributing exception translations to the REST error pipeline.
 *
 * <p>Implementations register one or more {@link dev.vertique.core.failure.FailureTranslator}
 * entries on the provided {@link RestExceptionMapper} using its fluent {@code on()} API.
 *
 * <p>Customizers are applied in {@link OrderedExtension} order — phase ascending, then priority
 * ascending, then {@link OrderedExtension#orderKey()} ascending — during {@link RestModule} assembly.
 * Because each customizer's registrations are applied on top of all preceding ones (a last-wins fold),
 * a customizer that sorts <em>later</em> wins: a higher numeric priority (still in
 * {@link dev.vertique.core.extension.ExtensionPhase#APPLICATION APPLICATION}) is applied after a lower
 * one, and a {@link dev.vertique.core.extension.ExtensionPhase#SYSTEM_LAST SYSTEM_LAST} customizer is
 * applied after all {@code APPLICATION} ones regardless of its numeric priority.
 *
 * <p>Application and extension modules contribute implementations via Dagger multibinding:
 * <pre>{@code
 * @Provides @IntoSet
 * static RestExceptionMapperCustomizer myCustomizer() {
 *     return mapper -> mapper.on(FooException.class, ex -> new BarException(ex));
 * }
 * }</pre>
 *
 * @see RestExceptionMapper
 * @see RestModule
 * @see OrderedExtension
 */
@FunctionalInterface
public interface RestExceptionMapperCustomizer extends OrderedExtension {

    /**
     * Registers one or more exception translations on the given mapper. Exceptions thrown by
     * this callback propagate and are fatal to the enclosing operation; processing does not continue.
     *
     * @param mapper the REST exception mapper to customize
     */
    void customize(RestExceptionMapper mapper);
}
