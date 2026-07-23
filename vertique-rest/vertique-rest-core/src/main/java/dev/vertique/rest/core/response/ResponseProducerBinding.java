// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.response;

/**
 * Pairs a result type with a {@link ResponseProducer} for that type.
 * Contributed to the framework via Dagger {@code Set<ResponseProducerBinding<?>>} multibinding.
 *
 * <p>Example usage in a Dagger module:
 * <pre>{@code
 * @Provides @IntoSet
 * static ResponseProducerBinding<?> myProducer() {
 *     return new ResponseProducerBinding<>(MyType.class, (ctx, result) -> { ... });
 * }
 * }</pre>
 *
 * @param <T>      the result type this binding handles
 * @param type     the class to register the producer for
 * @param producer the producer that handles the response
 */
public record ResponseProducerBinding<T>(Class<T> type, ResponseProducer<T> producer) {}
