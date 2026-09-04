// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// EUPL-1.2

package dev.vertique.resilience.config;

import dev.vertique.resilience.annotation.Bulkhead;

/** Complete named-tier bulkhead configuration shape.
 *
 * @param maxConcurrentCalls the positive number of active calls; required
 * @param mode reject or bounded queue admission; defaults to {@link Bulkhead.Mode#REJECT}
 * @param maxQueueSize the queue capacity in queue mode
 * @param queueTimeoutMs the queue wait bound in milliseconds in queue mode
 */
public record BulkheadPolicyConfig(
        Integer maxConcurrentCalls, Bulkhead.Mode mode, Integer maxQueueSize, Long queueTimeoutMs) {

    /** Applies the reject-mode default when the external mode property is omitted. */
    public BulkheadPolicyConfig {
        mode = mode == null ? Bulkhead.Mode.REJECT : mode;
    }
}
