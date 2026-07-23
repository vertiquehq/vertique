// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.plan;

/**
 * Functional SPI for declaring race-safe service-dispatch targets at startup.
 *
 * <p>Applications contribute one or more implementations into the
 * {@code Set<RaceSafetyTargetContributor>} Dagger multibinding. Each contributor is invoked
 * exactly once during {@link DefaultRaceSafetyTargetRegistry} construction with a mutable
 * {@link RaceSafetyTargetRegistry.Builder}; after all contributors run, the builder is sealed
 * and the registry becomes read-only.
 *
 * <p>Example:
 * <pre>{@code
 * @Provides @IntoSet
 * static RaceSafetyTargetContributor shippingQuoteSafety() {
 *     return b -> b
 *         .register("shipping.fastship.quote", RaceSafety.IGNORE_LATE_RESULT_SAFE)
 *         .register("shipping.northwind.quote", RaceSafety.IGNORE_LATE_RESULT_SAFE)
 *         .register("shipping.contoso.quote", RaceSafety.IGNORE_LATE_RESULT_SAFE);
 * }
 * }</pre>
 *
 * <p>Conflicting declarations (same {@code targetId}, different {@link RaceSafety}) throw
 * {@link IllegalStateException} at registry-build time; surface the conflict instead of silently
 * downgrading or upgrading the safety guarantee.
 */
@FunctionalInterface
public interface RaceSafetyTargetContributor {

    /**
     * Registers race-safe targets with {@code builder}.
     *
     * @param builder the mutable registry builder; non-null
     */
    void contribute(RaceSafetyTargetRegistry.Builder builder);
}
