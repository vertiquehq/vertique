// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

/**
 * Governs an anonymous caller (an empty resolver result or the canonical {@code
 * SecurityIdentity.anonymous()}) when
 * {@link RateLimitAdapterSupport#subjectKey} is asked to frame an identity dimension.
 */
public enum AnonymousRateLimitPolicy {

    /** Frames one shared anonymous component; every anonymous caller shares one bucket. */
    SHARED_BUCKET,

    /** Yields an empty key; the caller proceeds unlimited. */
    BYPASS
}
