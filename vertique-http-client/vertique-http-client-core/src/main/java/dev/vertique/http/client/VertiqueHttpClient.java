// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import io.vertx.core.Future;
import io.vertx.ext.web.client.WebClient;
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
     * Closes the client and its HTTP agent. Requests still open are reset. Closing again returns the
     * same outcome.
     *
     * @return a future that completes when the agent is closed
     */
    Future<Void> close();
}
