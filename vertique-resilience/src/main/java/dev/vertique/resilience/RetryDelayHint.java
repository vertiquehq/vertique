// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.time.Duration;
import java.util.Optional;

/**
 * Implemented by a failure that knows how long the caller should wait before trying again, for
 * example a server's {@code Retry-After} answer.
 *
 * <p>The hint is advisory and opt-in. It is read only when the retry configuration's
 * {@link RetryConfig#retryAfterMaxMs()} is above 0, it never makes a failure retryable, and it
 * never overrides the retry configuration's abort rules. The first {@code RetryDelayHint} found
 * in the failure's cause chain (the failure itself first) is used. An accessor that throws,
 * returns {@code null}, or returns a negative or overflowing duration counts as no hint.
 */
public interface RetryDelayHint {

    /**
     * Returns the delay the failure asks for before the next attempt.
     *
     * @return the requested delay, or empty when the failure has no preference
     */
    Optional<Duration> retryAfter();
}
