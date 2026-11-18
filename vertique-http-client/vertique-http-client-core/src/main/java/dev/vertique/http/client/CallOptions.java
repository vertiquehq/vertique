// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.resilience.ResiliencePipeline;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Per-call options for {@link VertiqueHttpClient#execute(CallOptions, java.util.function.Function)}.
 *
 * <p>Instances are immutable; every method returns a new instance. {@link #defaults()} selects the
 * client's own settings.
 */
public final class CallOptions {

    private static final CallOptions DEFAULTS = new CallOptions(null, null, null, null, false);

    /** Attempt timeout in milliseconds, {@code 0} for none, {@code null} to use the client's. */
    private final @Nullable Long readTimeoutMs;

    private final @Nullable CancellationSignal cancellation;

    private final @Nullable ResiliencePipeline pipeline;
    private final @Nullable String tier;
    private final boolean noResilience;

    private CallOptions(
            @Nullable Long readTimeoutMs,
            @Nullable CancellationSignal cancellation,
            @Nullable ResiliencePipeline pipeline,
            @Nullable String tier,
            boolean noResilience) {
        this.readTimeoutMs = readTimeoutMs;
        this.cancellation = cancellation;
        this.pipeline = pipeline;
        this.tier = tier;
        this.noResilience = noResilience;
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
        return new CallOptions(Math.max(1L, unit.toMillis(timeout)), cancellation, pipeline, tier, noResilience);
    }

    /**
     * Returns options that start no attempt timer for the call. Of this method and
     * {@link #readTimeout(long, TimeUnit)} the last call wins.
     *
     * @return the new options
     */
    public CallOptions noReadTimeout() {
        return new CallOptions(0L, cancellation, pipeline, tier, noResilience);
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
        return new CallOptions(readTimeoutMs, signal, pipeline, tier, noResilience);
    }

    /**
     * Returns options that run the call through a prebuilt pipeline, used as given: its own timeout,
     * retry and breaker concerns decide, and an attempt timeout stays a
     * {@link java.util.concurrent.TimeoutException} unless the pipeline has a timeout concern. Of this
     * method, {@link #resiliencePolicy(String)} and {@link #noResilience()} the last call wins.
     *
     * @param pipeline the pipeline to execute the call in
     * @return the new options
     */
    public CallOptions resilience(ResiliencePipeline pipeline) {
        return new CallOptions(readTimeoutMs, cancellation, Objects.requireNonNull(pipeline, "pipeline"), null, false);
    }

    /**
     * Returns options that run the call in a named resilience policy ({@code resilience.policies.<tier>}).
     * The client builds the policy's pipeline once and uses the policy's timeout as the attempt timeout
     * unless {@link #readTimeout(long, TimeUnit)} or {@link #noReadTimeout()} says otherwise. Of this
     * method, {@link #resilience(ResiliencePipeline)} and {@link #noResilience()} the last call wins.
     *
     * @param tier the policy name
     * @return the new options
     */
    public CallOptions resiliencePolicy(String tier) {
        return new CallOptions(readTimeoutMs, cancellation, null, Objects.requireNonNull(tier, "tier"), false);
    }

    /**
     * Returns options that run the call as one attempt without a pipeline, even when the client has a
     * default policy. Of this method, {@link #resilience(ResiliencePipeline)} and
     * {@link #resiliencePolicy(String)} the last call wins.
     *
     * @return the new options
     */
    public CallOptions noResilience() {
        return new CallOptions(readTimeoutMs, cancellation, null, null, true);
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

    @Nullable
    ResiliencePipeline pipeline() {
        return pipeline;
    }

    @Nullable
    String tier() {
        return tier;
    }

    boolean isNoResilience() {
        return noResilience;
    }
}
