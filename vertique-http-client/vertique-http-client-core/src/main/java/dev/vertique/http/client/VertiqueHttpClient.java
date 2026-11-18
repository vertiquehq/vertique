// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.WebClient;
import jakarta.annotation.Nullable;
import java.util.function.Function;

/**
 * A governed outbound HTTP client. Requests are built and sent with the Vert.x {@link WebClient} API;
 * every {@code WebClient} this client hands out is a framework delegate whose {@code close()} does
 * nothing and whose {@code addInterceptor} is refused.
 *
 * <p>Create instances with {@link VertiqueHttpClientBuilder}. Futures returned by this client complete
 * on the caller's Vert.x context when it is called on one.
 */
public interface VertiqueHttpClient {

    /**
     * Returns the client name.
     *
     * @return the name given to the builder, {@code "default"} when none was set
     */
    String name();

    /**
     * Returns the governed {@code WebClient} for direct use. Every request sent through it runs one
     * attempt with the client's defaults. The instance stays valid until {@link #close()}; closing it
     * leaves this client usable.
     *
     * @return the governed web client
     */
    WebClient webClient();

    /**
     * Runs {@code attempt} once with a fresh governed {@code WebClient} scoped to this call, using the
     * client's default call options.
     *
     * <p>When the future returned by {@code attempt} settles, every exchange the attempt started that is
     * still open is reset and the scoped {@code WebClient} fails every later request with
     * {@link IllegalStateException}. A {@code null} argument fails the returned future with
     * {@link IllegalArgumentException}.
     *
     * @param attempt builds and sends the requests of the attempt and returns its result
     * @param <T> the result type
     * @return the attempt's result
     */
    <T> Future<T> execute(Function<WebClient, Future<T>> attempt);

    /**
     * Runs {@code attempt} once with a fresh governed {@code WebClient} scoped to this call.
     *
     * <p>Behaves like {@link #execute(Function)} with the given call options. A {@code null} argument
     * fails the returned future with {@link IllegalArgumentException}.
     *
     * @param options the options of this call
     * @param attempt builds and sends the requests of the attempt and returns its result
     * @param <T> the result type
     * @return the attempt's result
     */
    <T> Future<T> execute(CallOptions options, Function<WebClient, Future<T>> attempt);

    /**
     * Sends the request {@code request} builds and returns as soon as a response head the call's
     * expectation accepts has arrived, with the body still unread.
     *
     * <p>The call runs in the options' resilience pipeline and attempt timeout up to that head: a head
     * the expectation refuses fails the attempt with the expectation's failure (an
     * {@link dev.vertique.http.client.exception.HttpStatusRejectedException} for
     * {@link HttpExpectations#success()}) carrying at most 2048 body bytes, resets the exchange and is
     * retried like any rejection. From the accepted head on the call has left the pipeline: it is never
     * retried, the circuit breaker has recorded the head's outcome, a bulkhead permit is released, the
     * attempt timeout no longer applies and only the stream idle timeout governs the body. A bulkhead
     * therefore bounds how many heads are being obtained, not how many streams are open. A stream that
     * is not consumed holds its connection until it is cancelled, fails or the client closes.
     *
     * <p>The request must be created by the {@code WebClient} passed to {@code request}; any other
     * request fails the call with {@link IllegalArgumentException}. The body is read through the
     * returned stream and is not subject to {@code maxResponseBytes}. A {@code null} {@code options} or
     * {@code request} fails the returned future with {@link IllegalArgumentException}.
     *
     * @param options the options of this call
     * @param request builds the request from the attempt's web client
     * @param body the buffered request body, or {@code null} for none
     * @return the stream, at its accepted head
     */
    Future<HttpStream> stream(
            CallOptions options, Function<WebClient, HttpRequest<Buffer>> request, @Nullable Buffer body);

    /**
     * Closes the client and its HTTP agent. Requests still open are reset and open streams fail with
     * {@link dev.vertique.http.client.exception.HttpClientClosedException}. Closing again returns the
     * same outcome.
     *
     * @return a future that completes when the agent is closed
     */
    Future<Void> close();
}
