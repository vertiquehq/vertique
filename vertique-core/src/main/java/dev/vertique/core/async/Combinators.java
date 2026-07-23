// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.async;

import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reusable asynchronous control-flow combinators over pre-ordered lists.
 *
 * <p>This type captures the recurring fan-out / fold / recover patterns that interceptor and
 * observer pipelines re-implement by hand: a sequential value fold, an ordered failure-recovery
 * chain, an all-settled join that swallows per-item failures, a synchronous swallowing loop, and a
 * fire-and-forget dispatch. Each combinator works over a list whose iteration order is the caller's
 * responsibility — the combinators preserve it and never reorder.
 *
 * <p>Per-item failure routing is uniform: every combinator that admits failures converts a
 * synchronous throw from the supplied callback into the same channel as an asynchronous failure, so
 * a raw exception never escapes the combinator. The decision of whether a failure continues or
 * propagates lives in the caller-supplied lambda, not in the combinator.
 */
public final class Combinators {

    private Combinators() {}

    /**
     * Sequentially folds a value through each item's {@code step}, threading the running value from
     * one step to the next in list order.
     *
     * <p>The list is assumed pre-ordered; {@code seed} is the initial value passed to the first
     * step, and each step's resulting value is passed to the next. The first failing step
     * short-circuits the fold — no remaining step is invoked — and the returned future fails with
     * that cause. A step that wishes to continue despite a failure does so within its own lambda
     * (for example by calling {@code .recover(...)} to substitute the previous value); the
     * continue-vs-propagate policy is therefore the caller's, not this combinator's. A synchronous
     * throw from {@code step} is converted into a failed {@link Future} fed into the same chain, so
     * it is subject to the same per-step policy and never escapes as a raw exception. An empty list
     * yields {@link Future#succeededFuture(Object)} carrying {@code seed}.
     *
     * @param items the pre-ordered items to fold over
     * @param seed the initial value threaded into the first step
     * @param step maps {@code (item, runningValue)} to the next running value, asynchronously
     * @param <I> the item type
     * @param <T> the folded value type
     * @return a future of the final folded value, or the first failure that short-circuited the fold
     */
    public static <I, T> Future<T> foldSequential(List<I> items, T seed, BiFunction<I, T, Future<T>> step) {
        Future<T> chain = Future.succeededFuture(seed);
        for (I item : items) {
            chain = chain.compose(value -> step.apply(item, value));
        }
        return chain;
    }

    /**
     * Tries each item's recoverer in list order, starting from a failure, and returns the first
     * successful recovery.
     *
     * <p>The chain begins at {@link Future#failedFuture(Throwable)} carrying {@code error}. Before
     * every step — including before the seed error is offered to the first recoverer — the current
     * failure is tested against {@code nonRecoverable}; if it matches, the chain short-circuits and
     * the returned future fails with that current failure. Otherwise the current failure is offered
     * to the item's {@code step} recoverer: the first recoverer that succeeds wins, and all later
     * items are skipped. A recoverer that itself fails <em>replaces</em> the current failure with
     * its new failure, which is then threaded to the next item (and tested by {@code nonRecoverable}
     * before it). A synchronous throw from {@code step} is treated as such a replacing failure. When
     * no recoverer succeeds, the returned future fails with the latest (last-produced) failure, not
     * the original {@code error}.
     *
     * @param items the pre-ordered items whose recoverers are tried in turn
     * @param error the seed failure offered to the first recoverer
     * @param step maps {@code (item, currentFailure)} to a recovery attempt, asynchronously
     * @param nonRecoverable tested against the current failure before every step; {@code true}
     *     short-circuits to that failure
     * @param <I> the item type
     * @param <R> the recovery value type
     * @return a future of the first successful recovery, or the latest failure when none recovers
     */
    public static <I, R> Future<R> recoverFirstWins(
            List<I> items,
            Throwable error,
            BiFunction<I, Throwable, Future<R>> step,
            Predicate<Throwable> nonRecoverable) {
        Future<R> chain = Future.failedFuture(error);
        for (I item : items) {
            chain = chain.recover(
                    cause -> nonRecoverable.test(cause) ? Future.failedFuture(cause) : step.apply(item, cause));
        }
        return chain;
    }

    /**
     * Launches every item's {@code hook} and completes only after all of them settle, swallowing
     * every per-item failure.
     *
     * <p>All hooks are launched in list order, but the returned future does not complete until every
     * launched hook has settled (succeeded or failed) — it never short-circuits on the first
     * failure the way {@link Future#all(List)} does. The returned future always succeeds: a per-item
     * failure, whether an asynchronous failure from the returned {@link Future}, a synchronous throw
     * from {@code hook.apply(item)}, or a {@code null} future returned by {@code hook.apply(item)}
     * (treated as a per-item {@link NullPointerException} failure), is routed to {@code onFailure}
     * and never aborts the fan-out or fails the join.
     *
     * @param items the pre-ordered items to fan out over
     * @param hook maps an item to the asynchronous work to launch
     * @param onFailure receives {@code (item, throwable)} for each item that fails
     * @param <I> the item type
     * @return a future that succeeds once every hook has settled
     */
    public static <I> Future<Void> joinAllSwallow(
            List<I> items, Function<I, Future<?>> hook, BiConsumer<I, Throwable> onFailure) {
        List<Future<Object>> futures = new ArrayList<>(items.size());
        for (I item : items) {
            Future<Object> f;
            try {
                // Settle reads only failure/cause, so the erased value type is irrelevant here.
                @SuppressWarnings("unchecked")
                Future<Object> launched = (Future<Object>) hook.apply(item);
                // A null future is treated as a per-item failure: substitute a failed future so it is
                // added to the settled list AND routed to onFailure exactly like any other failure.
                f = launched != null
                        ? launched
                        : Future.failedFuture(new NullPointerException("hook returned a null Future"));
            } catch (Exception e) {
                f = Future.failedFuture(e);
            }
            // Route each per-item failure eagerly, the moment that item settles, so a failure is
            // observable before the slowest hook completes the join. onFailure is a side-effect
            // handler that does not consume the failure, so settle still sees it below.
            f.onFailure(cause -> onFailure.accept(item, cause));
            futures.add(f);
        }
        // settle waits for ALL futures (never short-circuiting on an early failure) and never fails,
        // so the join completes only once every hook has settled and always succeeds.
        return Futures.settle(futures).mapEmpty();
    }

    /**
     * Iterates the items synchronously, invoking {@code hook} for each and swallowing per-item
     * failures.
     *
     * <p>For each item in list order the {@code hook} is invoked; if it throws an
     * {@link Exception} the throwable is routed to {@code onFailure(item, throwable)} and iteration
     * continues with the next item. An {@link Error} still propagates and aborts iteration. This
     * method never throws an {@code Exception}. All logging or other reporting of a failure is the
     * caller's responsibility via {@code onFailure} — this combinator takes no logger or label.
     *
     * @param items the pre-ordered items to iterate
     * @param hook the synchronous side-effecting callback invoked per item
     * @param onFailure receives {@code (item, throwable)} for each item whose hook throws
     * @param <I> the item type
     */
    public static <I> void forEachSwallowSync(List<I> items, Consumer<I> hook, BiConsumer<I, Throwable> onFailure) {
        for (I item : items) {
            try {
                hook.accept(item);
            } catch (Exception e) {
                onFailure.accept(item, e);
            }
        }
    }

    /**
     * Fires every item's {@code hook} and returns immediately without joining on their completion.
     *
     * <p>Each item's {@code hook} is launched in list order; this method does not wait for any of
     * them to settle and returns as soon as all have been dispatched. A per-item failure — an
     * asynchronous failure from the returned {@link Future}, a synchronous throw from
     * {@code hook.apply(item)}, or a {@code null} future returned by {@code hook.apply(item)} (treated
     * as a per-item {@link NullPointerException} failure) — is routed to {@code onFailure}. Because
     * there is no join, the caller receives no aggregate completion signal; this is the
     * fire-and-forget variant of {@link #joinAllSwallow(List, Function, BiConsumer)}.
     *
     * @param items the pre-ordered items to dispatch
     * @param hook maps an item to the asynchronous work to launch
     * @param onFailure receives {@code (item, throwable)} for each item that fails
     * @param <I> the item type
     */
    public static <I> void dispatchNoJoin(
            List<I> items, Function<I, Future<?>> hook, BiConsumer<I, Throwable> onFailure) {
        for (I item : items) {
            try {
                Future<?> f = hook.apply(item);
                if (f != null) {
                    f.onFailure(t -> onFailure.accept(item, t));
                } else {
                    // A null future is treated as a per-item failure routed to onFailure.
                    onFailure.accept(item, new NullPointerException("hook returned a null Future"));
                }
            } catch (Exception e) {
                onFailure.accept(item, e);
            }
        }
    }
}
