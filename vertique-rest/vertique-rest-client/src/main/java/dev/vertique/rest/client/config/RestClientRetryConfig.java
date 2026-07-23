// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.resilience.BackoffStrategy;

/**
 * Typed per-client retry override read from {@code restClient.{name}.retry}.
 *
 * <p>Only the backoff strategy is configurable at the client level; {@code maxRetries}/{@code
 * retryOn}/{@code abortOn} remain per-method via the {@code @Retry} annotation. The
 * {@code backoffStrategy} is the fully-qualified class name of a {@link BackoffStrategy}
 * implementation with a public no-arg constructor. It is validated at parse time — the class must be
 * loadable and assignable to {@link BackoffStrategy} — so a typo or wrong type fails fast at startup
 * rather than at first client build.
 *
 * @param backoffStrategy the fully-qualified class name of a {@link BackoffStrategy} implementation,
 *     or {@code null}/blank when not overridden (the builder-level strategy applies)
 */
public record RestClientRetryConfig(String backoffStrategy) {

    /**
     * Compact validator: when {@code backoffStrategy} is present and non-blank, the named class must
     * be loadable and implement {@link BackoffStrategy}. The class is only resolved (not
     * instantiated) here; instantiation happens when the client is built.
     *
     * @throws ConfigurationException if the named class cannot be loaded or does not implement
     *     {@link BackoffStrategy}
     */
    public RestClientRetryConfig {
        if (backoffStrategy != null && !backoffStrategy.isBlank()) {
            Class<?> cls;
            try {
                cls = Class.forName(backoffStrategy);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new ConfigurationException(
                        "restClient.<name>.retry.backoffStrategy class '" + backoffStrategy + "' could not be loaded",
                        e);
            }
            if (!BackoffStrategy.class.isAssignableFrom(cls)) {
                throw new ConfigurationException("restClient.<name>.retry.backoffStrategy class '" + backoffStrategy
                        + "' does not implement BackoffStrategy");
            }
        }
    }
}
