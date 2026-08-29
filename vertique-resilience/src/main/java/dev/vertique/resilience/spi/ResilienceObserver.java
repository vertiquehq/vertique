// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.spi;

import dev.vertique.resilience.spi.event.ResilienceEvent;

/** Synchronous observer of redacted resilience lifecycle events. Observers must remain bounded and non-blocking. */
public interface ResilienceObserver {

    /**
     * Receives one resilience event.
     *
     * @param event immutable redacted event
     */
    void onEvent(ResilienceEvent event);
}
