// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;

/**
 * The extension point of the governed HTTP client: it prepares each request before credentials are
 * applied and observes how each exchange ended.
 *
 * <p>Interceptors are registered per client on the builder and run in {@link OrderedExtension} order;
 * {@link #priority()} keeps its default. An exchange is one request sent through a governed
 * {@code WebClient}, whether the client hands the {@code WebClient} out directly or scopes it to one
 * call. An interceptor must be safe to call from any thread and concurrently for different exchanges.
 *
 * <p>An interceptor never sees a credential: the {@code CredentialSource} resolves after
 * {@link #beforeRequest} and its headers appear in neither the contexts nor the completion. Put a
 * secret in the credential source, not in a header an interceptor adds.
 */
public interface HttpClientInterceptor extends OrderedExtension {

    /**
     * Prepares a request before it is sent, once per exchange, in the request-preparation phase and
     * before credentials. Each interceptor receives the context the previous one returned, so the
     * interceptors fold in order.
     *
     * <p>The client copies the returned headers and URI into the request, checks the headers again for
     * CR, LF and NUL (an interceptor may add {@code vertique-} headers; the reserved-prefix rule covers
     * the caller's headers only) and judges a changed URI by the egress policy like any other target.
     * The {@code clientName}, {@code callId} and {@code method} of a returned context are ignored:
     * identity always comes from the client.
     *
     * <p>A failed future, a {@code null} future, a {@code null} result or a throw fails the exchange
     * with that failure before anything is sent, and {@link #onAttemptCompleted} is called once with it.
     * The remaining interceptors do not prepare the request.
     *
     * @param ctx the request as the previous interceptor left it, without credentials
     * @return the context to send; a failed future fails the exchange
     */
    default Future<HttpClientRequestContext> beforeRequest(HttpClientRequestContext ctx) {
        return Future.succeededFuture(ctx);
    }

    /**
     * Called exactly once per exchange on every terminal path once the exchange reached the client: the
     * response completed, a status expectation refused it, the response body bound was exceeded, a
     * timeout, a reset, a connection or TLS failure, a refused redirect hop, a cancellation, and every
     * refusal before sending (egress denial of the first target, credential failure, invalid header,
     * refused request body, failed {@link #beforeRequest}).
     *
     * <p>{@code request} is the client's immutable snapshot of the method, URI and headers as written,
     * taken after {@link #beforeRequest} ran and before credentials resolved, with framework identity.
     * {@code response} is a framework-built head, or {@code null} when no response head arrived.
     * {@code completion} carries the status when a head arrived, the failure as the caller sees it (or
     * {@code null} when the exchange completed with a response), the call id, the attempt ordinal, the
     * duration, the completion time and the final target (scheme, host and port only). For a call run
     * through {@code execute}, a status expectation the attempt applies to the response is reported
     * once the attempt ends; a request sent straight through {@code webClient()} reports its response
     * when it completes and cannot see an expectation the caller applies afterwards.
     *
     * <p>May run on any thread, and concurrently for different exchanges.
     *
     * <p>Stated limits: a call refused by an open circuit or a full bulkhead, a call on a closed client
     * and a call cancelled before its exchange began have no exchange and no notification. An exchange whose
     * {@link #beforeRequest} ran is always completed, even when it is cancelled before a request exists.
     * For a refusal before {@link #beforeRequest} (an invalid caller header, a refused request body) the
     * snapshot is the request as prepared before {@link #beforeRequest}. The header re-check after
     * {@link #beforeRequest} follows the client's header validation, which is off only for framework
     * clients built through the framework's client options.
     * An attempt whose future never completes leaves its completed exchanges without a notification
     * until the call ends.
     *
     * <p>Exceptions thrown by this callback are caught, logged, and swallowed; they do not affect the
     * enclosing operation. A {@code LinkageError}, an {@code AssertionError} and a
     * {@code StackOverflowError} are swallowed the same way, and a {@code LinkageError} is reported at
     * error level at a limited rate, once per five minutes for an interceptor class, with the number of
     * failures that went unreported in between. The next interceptor still runs.
     *
     * @param request the immutable snapshot of the request as written, without credentials
     * @param response the response head, or {@code null} when none arrived
     * @param completion the framework facts of how the exchange ended
     */
    default void onAttemptCompleted(
            HttpClientRequestContext request,
            @Nullable HttpClientResponseHead response,
            HttpClientAttemptCompletion completion) {}
}
