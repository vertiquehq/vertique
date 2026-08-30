// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named start-state mappers to the
 * {@link StartStateMapperRegistry} at startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultStartStateMapperRegistry}
 * construction with a mutable {@link StartStateMapperRegistry.Builder}. After all contributors
 * run, the builder is sealed and the registry becomes read-only.
 *
 * <p>Example:
 * <pre>{@code
 * @Provides @IntoSet
 * static StartStateMapperContributor orderStartMapper() {
 *     return b -> b.register(new NamedStartStateMapper<>(
 *         "order.start", StartOrderPayload.class, OrderState.class,
 *         payload -> new OrderState(payload.orderId(), payload.customerId())));
 * }
 * }</pre>
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see StartStateMapperRegistry
 * @see DefaultStartStateMapperRegistry
 */
@FunctionalInterface
public interface StartStateMapperContributor {

    /**
     * Registers start-state mappers with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(StartStateMapperRegistry.Builder builder);
}
