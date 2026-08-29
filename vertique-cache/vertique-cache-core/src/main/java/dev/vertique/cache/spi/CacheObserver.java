// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi;

import dev.vertique.cache.spi.event.CacheEvent;

/**
 * Optional provider-neutral observation seam for the sealed cache event vocabulary.
 *
 * <p>Events are emitted synchronously by the shared cache runtime (and by provider
 * maintenance jobs for cleanup events). Implementations must remain bounded and
 * non-blocking; observer failures are logged-and-suppressed by the emitter and never
 * alter cache or business outcomes.
 */
@FunctionalInterface
public interface CacheObserver {
    /**
     * Observes one redacted cache event.
     *
     * @param event the sealed, immutable event payload
     */
    void onEvent(CacheEvent event);
}
