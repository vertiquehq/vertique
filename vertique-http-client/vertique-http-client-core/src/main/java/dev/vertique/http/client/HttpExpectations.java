// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpStatusRejectedException;
import dev.vertique.http.client.internal.RetryAfter;
import io.vertx.core.Expectation;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpResponseHead;
import io.vertx.ext.web.client.HttpResponse;
import java.time.Clock;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Status checks for use inside an attempt, for example
 * {@code web.post(uri).sendBuffer(body).expecting(HttpExpectations.success())}. A refused response
 * fails with an {@link HttpStatusRejectedException}.
 */
public final class HttpExpectations {

    private static final RetryAfter RETRY_AFTER = new RetryAfter(Clock.systemUTC());
    private static final Expectation<HttpResponseHead> SUCCESS = status(code -> code >= 200 && code < 300);

    private HttpExpectations() {}

    /**
     * Accepts the responses whose status code the predicate accepts.
     *
     * @param accepted decides whether a status code is accepted
     * @return an expectation that fails a refused response with an {@link HttpStatusRejectedException}
     * @throws NullPointerException if {@code accepted} is null
     */
    public static Expectation<HttpResponseHead> status(IntPredicate accepted) {
        Objects.requireNonNull(accepted, "accepted");
        return new Expectation<>() {
            @Override
            public boolean test(HttpResponseHead head) {
                return accepted.test(head.statusCode());
            }

            @Override
            public Throwable describe(HttpResponseHead head) {
                Buffer body = head instanceof HttpResponse<?> response && response.body() instanceof Buffer buffer
                        ? buffer
                        : null;
                return new HttpStatusRejectedException(
                        head.statusCode(),
                        head.headers(),
                        RETRY_AFTER.parse(head.headers().get("Retry-After")).orElse(null),
                        body);
            }
        };
    }

    /**
     * Accepts the responses with a 2xx status code.
     *
     * @return an expectation that fails any other response with an {@link HttpStatusRejectedException}
     */
    public static Expectation<HttpResponseHead> success() {
        return SUCCESS;
    }
}
