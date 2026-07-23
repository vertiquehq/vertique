// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.async;

import dev.vertique.core.eventbus.Result;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Utilities for transforming Vert.x {@link Future} failure channels.
 *
 * <p>This type intentionally stays narrow: it covers conversions between asynchronous failures and
 * {@link Result} values, plus failure mapping and all-settled style aggregation.
 */
public final class Futures {

    private Futures() {}

    /**
     * Converts a {@link Result} into a Vert.x future.
     *
     * <p>Successful results become {@link Future#succeededFuture(Object)} and failed results become
     * {@link Future#failedFuture(Throwable)}.
     *
     * @param result the result to convert
     * @param <T> the success value type
     * @return a succeeded or failed future matching the result state
     */
    public static <T> Future<T> toFuture(Result<T> result) {
        if (result.isSuccess()) {
            return Future.succeededFuture(result.get());
        }
        return Future.failedFuture(result.cause());
    }

    /**
     * Reflects a future into the value channel by converting failure into
     * {@link Result#failure(Throwable)}.
     *
     * <p>The returned future always succeeds unless the reflection logic itself throws.
     *
     * @param future the future to reflect
     * @param <T> the success value type
     * @return a future containing either {@link Result#success(Object)} or {@link Result#failure(Throwable)}
     */
    public static <T> Future<Result<T>> reflect(Future<T> future) {
        return future.map(Result::success).recover(err -> Future.succeededFuture(Result.failure(err)));
    }

    /**
     * Waits for every future to settle and materializes each outcome as a {@link Result}, preserving
     * the input order.
     *
     * @param futures the futures to wait for
     * @param <T> the success value type
     * @return a succeeded future containing one {@link Result} per input future
     */
    public static <T> Future<List<Result<T>>> settle(Collection<? extends Future<T>> futures) {
        List<Future<Result<T>>> reflected =
                futures.stream().map(Futures::reflect).toList();
        return Future.all(new ArrayList<>(reflected))
                .map((CompositeFuture ignored) ->
                        reflected.stream().map(Future::result).toList());
    }

    /**
     * Maps the failure of a future without affecting successful values.
     *
     * @param future the future whose failures should be translated
     * @param mapper maps the original failure to a new failure
     * @param <T> the success value type
     * @return a future with the same success values and mapped failures
     */
    public static <T> Future<T> mapFailure(Future<T> future, Function<Throwable, Throwable> mapper) {
        return future.recover(err -> Future.failedFuture(mapper.apply(err)));
    }
}
