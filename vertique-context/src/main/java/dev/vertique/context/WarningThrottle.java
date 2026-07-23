// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Once-per-key WARN-log throttler used by the context substrate. Each unique key fires the
 * provided log action exactly once per process lifetime — useful for decode-warning logs that
 * would otherwise flood at one entry per record under a misbehaving encoder/decoder pair
 * (FR-CTX-132).
 *
 * <p>The key is a tuple identifier composed by the caller (typically
 * {@code "<boundary>|<decoderClass>|<reason>"}). The first call with a given key invokes
 * {@code logAction}; subsequent calls with the same key are silently dropped.
 *
 * <p><strong>Internal framework API.</strong>
 */
public final class WarningThrottle {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * Logs once per unique key. Subsequent calls with the same key are no-ops.
     *
     * @param key       the throttle key (e.g. {@code "kafka|MyDecoder|null-result"})
     * @param logAction the action invoked the first time the key is seen
     */
    public void once(String key, Consumer<String> logAction) {
        if (seen.add(key)) {
            logAction.accept(key);
        }
    }
}
