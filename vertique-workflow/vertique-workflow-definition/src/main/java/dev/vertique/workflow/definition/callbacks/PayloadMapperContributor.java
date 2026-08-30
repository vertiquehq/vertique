// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.callbacks;

/**
 * Functional SPI for contributing named payload mappers to the {@link PayloadMapperRegistry} at
 * startup via Dagger multibinding.
 *
 * <p>Each contributor is invoked exactly once during {@link DefaultPayloadMapperRegistry}
 * construction with a mutable {@link PayloadMapperRegistry.Builder}. After all contributors run,
 * the builder is sealed and the registry becomes read-only.
 *
 * <p>Example:
 * <pre>{@code
 * @Provides @IntoSet
 * static PayloadMapperContributor orderPayloadMapper() {
 *     return b -> b.register(new NamedPayloadMapper<>(
 *         "order.payload", OrderState.class, state -> new OrderPayload(state.orderId())));
 * }
 * }</pre>
 *
 * <p>Conflicting declarations (same id, different record) throw {@link IllegalStateException} at
 * registry-build time.
 *
 * @see PayloadMapperRegistry
 * @see DefaultPayloadMapperRegistry
 */
@FunctionalInterface
public interface PayloadMapperContributor {

    /**
     * Registers payload mappers with {@code builder}.
     *
     * <p>Exceptions thrown by this callback propagate and are fatal to the enclosing operation;
     * processing does not continue.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(PayloadMapperRegistry.Builder builder);
}
