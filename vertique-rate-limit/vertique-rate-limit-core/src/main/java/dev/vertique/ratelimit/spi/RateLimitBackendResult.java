// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dev.vertique.ratelimit.RateLimitFailureCode;
import java.time.Duration;
import java.util.Optional;

/**
 * One backend's answer to a {@link RateLimitBackendRequest}, normalized from the engine's native
 * probe (contracts/rate-limit-runtime.md, "Bucket4j translation contract").
 *
 * @param consumed {@code true} when the request was admitted and tokens were consumed
 * @param remaining whole tokens available immediately after this decision
 * @param retryAfter present and positive only when {@code consumed} is {@code false}
 * @param resetAfter hint duration until the bucket is full again absent further consumption
 * @param failureCode present only when the backend itself failed, not on an ordinary rejection
 */
public record RateLimitBackendResult(
        boolean consumed,
        long remaining,
        Optional<Duration> retryAfter,
        Optional<Duration> resetAfter,
        Optional<RateLimitFailureCode> failureCode) {}
