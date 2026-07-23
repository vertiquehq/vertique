// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.contract;

/**
 * Optional payload-side interface that provides the dedup key for a {@link WorkflowSignal}
 * operation.
 *
 * <p>If the signal payload implements this interface, the proxy will call {@link #dedupKey()} to
 * populate the signal dedup key. A parameter-level {@link SignalDedupKey} annotation takes
 * precedence if both are present.
 *
 * <p>Proxy creation fails if neither this interface nor {@code @SignalDedupKey} is available on
 * the {@code @WorkflowSignal} method.
 */
public interface SignalDedupKeyed {

    /**
     * Returns the dedup key for this signal payload.
     *
     * @return the dedup key; must be non-null and stable across retries for the same logical signal
     *     delivery
     */
    String dedupKey();
}
