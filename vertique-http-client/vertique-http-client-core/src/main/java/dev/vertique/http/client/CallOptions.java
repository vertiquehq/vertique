// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import jakarta.annotation.Nullable;
import java.util.concurrent.TimeUnit;

/**
 * Per-call options for {@link VertiqueHttpClient#execute(CallOptions, java.util.function.Function)}.
 *
 * <p>Instances are immutable; every method returns a new instance. {@link #defaults()} selects the
 * client's own settings.
 */
public final class CallOptions {

    private static final CallOptions DEFAULTS = new CallOptions(null, null);

    /** Attempt timeout in milliseconds, {@code 0} for none, {@code null} to use the client's. */
    private final @Nullable Long readTimeoutMs;

    private final @Nullable CancellationSignal cancellation;

    private CallOptions(@Nullable Long readTimeoutMs, @Nullable CancellationSignal cancellation) {
        this.readTimeoutMs = readTimeoutMs;
        this.cancellation = cancellation;
    }

    /**
     * Returns the options that apply the client's own settings.
     *
     * @return the default call options
     */
    public static CallOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options whose attempt timeout replaces the client's. The timeout covers the whole
     * attempt including the response body. Of this method and {@link #noReadTimeout()} the last call
     * wins.
     *
     * @param timeout the timeout, above zero
     * @param unit the unit of {@code timeout}
     * @return the new options
     * @throws IllegalArgumentException when {@code timeout} is not above zero
     */
    public CallOptions readTimeout(long timeout, TimeUnit unit) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("The read timeout must be above zero");
        }
        return new CallOptions(Math.max(1L, unit.toMillis(timeout)), cancellation);
    }

    /**
     * Returns options that start no attempt timer for the call. Of this method and
     * {@link #readTimeout(long, TimeUnit)} the last call wins.
     *
     * @return the new options
     */
    public CallOptions noReadTimeout() {
        return new CallOptions(0L, cancellation);
    }

    /**
     * Returns options that bind the call to {@code signal}: cancelling the signal resets the call's
     * open exchanges and fails it with
     * {@link dev.vertique.http.client.exception.HttpExchangeCancelledException}.
     *
     * @param signal the signal to bind the call to
     * @return the new options
     */
    public CallOptions cancellation(CancellationSignal signal) {
        return new CallOptions(readTimeoutMs, signal);
    }

    /** The call's attempt timeout in milliseconds: {@code 0} for none, {@code null} for the client's. */
    @Nullable
    Long readTimeoutMs() {
        return readTimeoutMs;
    }

    @Nullable
    CancellationSignal cancellationSignal() {
        return cancellation;
    }
}
