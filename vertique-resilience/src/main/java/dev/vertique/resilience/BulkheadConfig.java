// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import java.time.Duration;
import java.util.Objects;

/** Complete immutable reject-or-queue bulkhead configuration. */
public sealed interface BulkheadConfig permits BulkheadConfig.Reject, BulkheadConfig.Queue {

    static Reject reject(int maxConcurrentCalls) {
        return new Reject(maxConcurrentCalls);
    }

    static Queue queue(int maxConcurrentCalls, int maxQueueSize, Duration queueTimeout) {
        Objects.requireNonNull(queueTimeout, "queueTimeout");
        return new Queue(
                maxConcurrentCalls, maxQueueSize, TimeoutConfig.of(queueTimeout).timeoutMs());
    }

    record Reject(int maxConcurrentCalls) implements BulkheadConfig {
        public Reject {
            if (maxConcurrentCalls <= 0) {
                throw new IllegalArgumentException("maxConcurrentCalls must be positive");
            }
        }
    }

    record Queue(int maxConcurrentCalls, int maxQueueSize, long queueTimeoutMs) implements BulkheadConfig {
        public Queue {
            if (maxConcurrentCalls <= 0) {
                throw new IllegalArgumentException("maxConcurrentCalls must be positive");
            }
            if (maxQueueSize < 1 || maxQueueSize > 1_024) {
                throw new IllegalArgumentException("maxQueueSize must be between 1 and 1024");
            }
            if (queueTimeoutMs < 1 || queueTimeoutMs > 60_000L) {
                throw new IllegalArgumentException("queueTimeoutMs must be between 1 and 60000");
            }
        }
    }
}
