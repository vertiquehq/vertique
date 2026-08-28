// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

/**
 * Resolves the effective {@link BackoffStrategy} from a {@link Retry} annotation.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>If the annotation specifies a custom {@link Retry#backoff()} class (not
 *       {@link BackoffStrategy.Default}), instantiate it via its no-arg constructor</li>
 *   <li>Otherwise, if a non-null {@code fallback} is provided, return it — this honours a
 *       builder-level or framework-level default when the annotation uses the sentinel</li>
 *   <li>Otherwise, build an {@link BackoffStrategy#exponential exponential} strategy from the
 *       annotation's inline parameters ({@link Retry#delayMs()}, {@link Retry#backoffMultiplier()},
 *       {@link Retry#maxDelayMs()})</li>
 * </ol>
 */
public final class BackoffStrategyResolver {

    private BackoffStrategyResolver() {}

    /**
     * Resolves the effective {@link BackoffStrategy} from a {@link Retry} annotation.
     *
     * <p>If the annotation specifies a custom backoff class, it is instantiated via reflection.
     * Otherwise, if a fallback is provided, the fallback is returned. If no fallback is provided,
     * an exponential strategy is built from the annotation's inline parameters.
     *
     * @param retry the retry annotation to resolve from
     * @param fallback an optional fallback strategy used when the annotation does not specify a
     *     custom class; may be {@code null} to fall back to the annotation's inline parameters
     * @return the resolved backoff strategy; never {@code null}
     * @throws IllegalStateException if the custom backoff class cannot be instantiated
     */
    public static BackoffStrategy resolve(Retry retry, BackoffStrategy fallback) {
        Class<? extends BackoffStrategy> backoffClass = retry.backoff();
        if (backoffClass != BackoffStrategy.Default.class) {
            try {
                return backoffClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate BackoffStrategy: " + backoffClass.getName(), e);
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return BackoffStrategy.exponential(retry.delayMs(), retry.backoffMultiplier(), retry.maxDelayMs());
    }
}
