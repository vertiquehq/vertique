// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.resilience.BackoffStrategy;

/**
 * Typed per-client retry override read from {@code restClient.{name}.retry}.
 *
 * <p>The client-level retry block may override the retry count and backoff strategy. The
 * {@code retryOn}/{@code abortOn} filters remain on the {@code @Retry} annotation. The
 * {@code backoffStrategy} is the fully-qualified class name of a {@link BackoffStrategy}
 * implementation with a public no-arg constructor. It is validated at parse time — the class must be
 * loadable and assignable to {@link BackoffStrategy} — so a typo or wrong type fails fast at startup
 * rather than at first client build.
 *
 * @param maxRetries the client-level retry count override, or {@code null} when not overridden
 * @param backoffStrategy the fully-qualified class name of a {@link BackoffStrategy} implementation,
 *     or {@code null}/blank when not overridden
 */
public record RestClientRetryConfig(Integer maxRetries, String backoffStrategy) {

    /**
     * Compact validator: when {@code backoffStrategy} is present and non-blank, the named class must
     * be loadable and implement {@link BackoffStrategy}. The class is only resolved (not
     * instantiated) here; instantiation happens when the client is built.
     *
     * @throws ConfigurationException if the named class cannot be loaded or does not implement
     *     {@link BackoffStrategy}
     */
    public RestClientRetryConfig {
        if (maxRetries != null && (maxRetries < 0 || maxRetries > 100)) {
            throw new ConfigurationException(
                    "restClient.<name>.retry.maxRetries must be between 0 and 100, got " + maxRetries);
        }
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
