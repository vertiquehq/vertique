// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.resilience.ResiliencePipeline;
import io.vertx.core.Expectation;
import io.vertx.core.http.HttpResponseHead;
import jakarta.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Per-call options for {@link HttpClient#execute(CallOptions, java.util.function.Function)} and
 * {@link HttpClient#stream}.
 *
 * <p>Instances are immutable; every method returns a new instance. {@link #defaults()} selects the
 * client's own settings.
 */
public final class CallOptions {

    private static final CallOptions DEFAULTS = new CallOptions(null, null, null, null);

    /** Attempt timeout in milliseconds, {@code 0} for none, {@code null} to use the client's. */
    private final @Nullable Long readTimeoutMs;

    private final @Nullable CancellationSignal cancellation;

    private final @Nullable ResiliencePipeline pipeline;

    private final @Nullable Expectation<HttpResponseHead> expectation;

    private CallOptions(
            @Nullable Long readTimeoutMs,
            @Nullable CancellationSignal cancellation,
            @Nullable ResiliencePipeline pipeline,
            @Nullable Expectation<HttpResponseHead> expectation) {
        this.readTimeoutMs = readTimeoutMs;
        this.cancellation = cancellation;
        this.pipeline = pipeline;
        this.expectation = expectation;
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
        return new CallOptions(Math.max(1L, unit.toMillis(timeout)), cancellation, pipeline, expectation);
    }

    /**
     * Returns options that start no attempt timer for the call. Of this method and
     * {@link #readTimeout(long, TimeUnit)} the last call wins.
     *
     * @return the new options
     */
    public CallOptions noReadTimeout() {
        return new CallOptions(0L, cancellation, pipeline, expectation);
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
        return new CallOptions(readTimeoutMs, signal, pipeline, expectation);
    }

    /**
     * Returns options whose expectation decides whether the response head of a
     * {@link HttpClient#stream stream} is accepted. A refused head fails the attempt with the
     * expectation's failure, which {@link HttpExpectations#status} makes an
     * {@link dev.vertique.http.client.exception.HttpStatusRejectedException}. The default is
     * {@link HttpExpectations#success()}. Calls that are not streams ignore it.
     *
     * @param expectation decides whether a response head is accepted
     * @return the new options
     */
    public CallOptions expecting(Expectation<HttpResponseHead> expectation) {
        return new CallOptions(
                readTimeoutMs, cancellation, pipeline, Objects.requireNonNull(expectation, "expectation"));
    }

    /**
     * Returns options that run the call through a prebuilt pipeline, used as given: its own timeout,
     * retry and breaker concerns decide, and an attempt timeout stays a
     * {@link java.util.concurrent.TimeoutException} unless the pipeline has a timeout concern. Of this
     * method and {@link #noResilience()} the last call wins.
     *
     * @param pipeline the pipeline to execute the call in
     * @return the new options
     */
    public CallOptions resilience(ResiliencePipeline pipeline) {
        return new CallOptions(readTimeoutMs, cancellation, Objects.requireNonNull(pipeline, "pipeline"), expectation);
    }

    /**
     * Returns options that run the call as one attempt without a pipeline. Of this method and
     * {@link #resilience(ResiliencePipeline)} the last call wins.
     *
     * @return the new options
     */
    public CallOptions noResilience() {
        return new CallOptions(readTimeoutMs, cancellation, null, expectation);
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

    /** The expectation a stream head must meet. */
    Expectation<HttpResponseHead> expectation() {
        return expectation != null ? expectation : HttpExpectations.success();
    }
}
