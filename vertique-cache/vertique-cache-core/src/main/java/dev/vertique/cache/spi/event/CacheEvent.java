// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

/**
 * Redacted cache observation event emitted by the shared cache runtime.
 *
 * <p>Events carry only bounded, non-sensitive data: the logical cache or namespace
 * label, provider id, typed operation/outcome vocabulary, and durations or counters.
 * They never contain selectors, identity material, keys, values, or exception
 * payloads.
 */
public sealed interface CacheEvent permits CacheOperationCompleted, CacheLateCompletion, CacheCleanupCompleted {}
