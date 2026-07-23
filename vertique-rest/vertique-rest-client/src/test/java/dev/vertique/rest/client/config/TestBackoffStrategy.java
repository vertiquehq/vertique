// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import dev.vertique.core.resilience.BackoffStrategy;

/**
 * Test fixture: a loadable {@link BackoffStrategy} with a public no-arg constructor, used to verify
 * that {@link RestClientRetryConfig} accepts a valid fully-qualified class name that implements
 * {@link BackoffStrategy} and can be instantiated reflectively.
 */
public class TestBackoffStrategy implements BackoffStrategy {

    /**
     * Returns a fixed zero delay regardless of the retry count.
     *
     * @param retryCount the current retry attempt (unused)
     * @return {@code 0L}
     */
    @Override
    public long delay(int retryCount) {
        return 0L;
    }
}
