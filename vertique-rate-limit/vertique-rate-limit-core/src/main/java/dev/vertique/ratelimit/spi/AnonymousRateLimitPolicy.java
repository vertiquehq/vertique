// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

/**
 * Governs an anonymous caller (no {@code SecurityIdentity} at all) when
 * {@link RateLimitAdapterSupport#subjectKey} is asked to frame an identity dimension
 * (contracts/rate-limit-runtime.md, "Framework adapter seam"; {@code spec.md} §5.2).
 */
public enum AnonymousRateLimitPolicy {

    /** Frames one shared anonymous component; every anonymous caller shares one bucket. */
    SHARED_BUCKET,

    /** Yields an empty key; the caller proceeds unlimited. */
    BYPASS
}
