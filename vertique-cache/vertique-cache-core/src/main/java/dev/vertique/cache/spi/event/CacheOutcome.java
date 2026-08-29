// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

/** The closed outcome vocabulary for cache runtime operations. */
public enum CacheOutcome {
    /** A provider lookup returned a stored value. */
    HIT,
    /** A provider lookup found no stored value. */
    MISS,
    /** The provider acknowledged the operation. */
    SUCCESS,
    /** The provider operation failed; the runtime recovered fail-open. */
    ERROR,
    /** The provider operation exceeded the backend deadline. */
    TIMEOUT,
    /** Caching is disabled; the loader ran directly. */
    DISABLED,
    /** A successful null load was returned without a write. */
    SKIPPED_NULL,
    /** An invalid selector bypassed the provider fail-open. */
    BYPASS_SELECTOR,
    /** Missing or anonymous identity bypassed an identity-scoped cache. */
    BYPASS_IDENTITY,
    /** The complete canonical key exceeded the configured byte bound. */
    BYPASS_KEY_SIZE,
    /** An exact annotation eviction named a logical cache with no registered definition. */
    UNRESOLVED_TARGET
}
