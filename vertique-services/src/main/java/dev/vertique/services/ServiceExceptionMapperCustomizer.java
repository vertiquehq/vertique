// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.extension.OrderedExtension;

/**
 * Extension point for contributing exception translations to the {@link ServiceExceptionMapper}.
 *
 * <p>Implementations are contributed via the Dagger {@code Set<ServiceExceptionMapperCustomizer>}
 * multibinding. {@link DispatchModule} collects all contributions, sorts them by the
 * {@link OrderedExtension} ordering contract — phase ascending, then {@link #priority()} ascending,
 * then {@link #orderKey()} as a stable tie-break — and applies them to the mapper in order.
 * Because each customizer's registrations are applied on top of all preceding ones (a last-wins fold),
 * a customizer that sorts <em>later</em> wins: a higher numeric priority (still in
 * {@link dev.vertique.core.extension.ExtensionPhase#APPLICATION APPLICATION}) is applied after a lower
 * one, and a {@link dev.vertique.core.extension.ExtensionPhase#SYSTEM_LAST SYSTEM_LAST} customizer is
 * applied after all {@code APPLICATION} ones regardless of its numeric priority.
 *
 * <p>Example contribution from an application module:
 * <pre>{@code
 * @Provides @IntoSet
 * static ServiceExceptionMapperCustomizer myCustomizer() {
 *     return mapper -> mapper.on(FooException.class, ex -> new BarException(ex));
 * }
 * }</pre>
 *
 * @see ServiceExceptionMapper
 * @see DispatchModule
 * @see OrderedExtension
 */
@FunctionalInterface
public interface ServiceExceptionMapperCustomizer extends OrderedExtension {

    /**
     * Applies custom exception translations to the given {@link ServiceExceptionMapper}.
     * Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param mapper the mapper to customize
     */
    void customize(ServiceExceptionMapper mapper);
}
