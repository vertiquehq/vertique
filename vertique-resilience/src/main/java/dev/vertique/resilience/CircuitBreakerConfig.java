// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

/** Complete immutable circuit-breaker configuration. */
public final class CircuitBreakerConfig {

    private final int maxFailures;
    private final long resetTimeoutMs;

    private CircuitBreakerConfig(int maxFailures, long resetTimeoutMs) {
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("maxFailures must be positive");
        }
        if (resetTimeoutMs <= 0) {
            throw new IllegalArgumentException("resetTimeoutMs must be positive");
        }
        this.maxFailures = maxFailures;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxFailures() {
        return maxFailures;
    }

    public long resetTimeoutMs() {
        return resetTimeoutMs;
    }

    static CircuitBreakerConfig of(int maxFailures, long resetTimeoutMs) {
        return new CircuitBreakerConfig(maxFailures, resetTimeoutMs);
    }

    /** Single-use builder for a complete circuit-breaker configuration. */
    public static final class Builder {
        private int maxFailures = 5;
        private long resetTimeoutMs = 10_000L;
        private boolean built;

        private Builder() {}

        public Builder maxFailures(int value) {
            ensureMutable();
            maxFailures = value;
            return this;
        }

        public Builder resetTimeoutMs(long value) {
            ensureMutable();
            resetTimeoutMs = value;
            return this;
        }

        public CircuitBreakerConfig build() {
            ensureMutable();
            built = true;
            return new CircuitBreakerConfig(maxFailures, resetTimeoutMs);
        }

        private void ensureMutable() {
            if (built) {
                throw new IllegalStateException("circuit-breaker config builder has already been used");
            }
        }
    }
}
