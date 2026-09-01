// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed, Vertique-owned admission algorithm family (contracts/rate-limit-runtime.md, "Policy
 * model"). v1 permits only {@link TokenBucketRateLimit} — no application-defined member is
 * permitted.
 *
 * <p>Jackson polymorphic typing is configured via the {@code "type"} property discriminator so
 * {@code rateLimit.policies.<name>.algorithm.type} selects the concrete implementation during
 * typed config binding (same idiom as {@code dev.vertique.security.verification.VerificationSource}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = TokenBucketRateLimit.class, name = "TOKEN_BUCKET")})
public sealed interface RateLimitAlgorithm permits TokenBucketRateLimit {

    /**
     * @return the admission algorithm family this instance implements
     */
    RateLimitAlgorithmType type();
}
