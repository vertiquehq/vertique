// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Future;
import java.util.function.Supplier;

/**
 * INTERNAL framework seam — the framework implementation behind a contract this module documents;
 * not an application contract and outside the maturity promise.
 *
 * <p>Continuation nester that folds an ordered {@link MethodInterceptor} array around a terminal call.
 *
 * <p>The generated proxy invokes {@link #run(Object, MethodMetadata, Object[], MethodInterceptor[],
 * Supplier)} from each overridden method, passing the chain resolved once in the proxy constructor
 * and a terminal supplier that performs the direct {@code super.method(...)} call. The nester wraps
 * each interceptor around the next so that {@link Invocation#proceed()} re-enters the downstream
 * chain from the calling interceptor's own position — the continuation is captured per-call rather
 * than via a mutated shared index, which is what makes {@code proceed()} safely re-entrant.
 *
 * <p>This class holds no state and is not instantiable.
 */
public final class Invocations {

    private Invocations() {
        // static-only nester; no instances
    }

    /**
     * Runs the interceptor chain around the terminal call.
     *
     * <p>The ordered {@code chain} is folded right-to-left into a continuation: the outermost
     * interceptor (index {@code 0}) is entered first and wraps interceptor {@code 1}, which wraps
     * interceptor {@code 2}, and so on, until the innermost interceptor wraps the {@code terminal}
     * supplier that performs the real {@code super.method(...)} call. With an empty {@code chain},
     * the {@code terminal} supplier is invoked directly.
     *
     * <p>The fold is not materialized eagerly; instead, each position is represented by a
     * {@link PositionInvocation} whose {@link Invocation#proceed()} lazily constructs the
     * <em>next</em> position's invocation on the call stack. This is what makes {@code proceed()}
     * re-entrant: there is no shared mutable index or cursor. An interceptor that calls
     * {@code proceed()} twice triggers two independent downstream traversals — each builds its own
     * fresh chain of positions and reaches the terminal again — and the {@code arguments} array is
     * read live at each terminal dispatch, so a mutation between the two calls is visible to the
     * later one.
     *
     * <p>A <em>synchronous</em> throw from the {@code terminal} supplier (the underlying
     * {@code super.method(...)} call may throw before returning its {@link Future}) or from any
     * interceptor's {@link MethodInterceptor#intercept} is captured into a {@link Future#failedFuture
     * failed future} rather than being allowed to propagate. This upholds the SPI contract that
     * {@link Invocation#proceed()} and the chain always return a {@code Future} and never throw
     * synchronously, so a sync-throwing method surfaces uniformly as a failed outcome — letting an
     * aspect such as {@code @Timed} record it as an error and pass it through unchanged.
     *
     * @param instance the proxied bean instance the method was invoked on
     * @param target the reflection-free metadata of the intercepted method
     * @param arguments the live argument array (read at each terminal dispatch)
     * @param chain the ordered interceptors to fold around the terminal call (outermost first)
     * @param terminal supplies the future of the underlying {@code super.method(...)} call
     * @return a future of the (possibly transformed) invocation result
     */
    public static Future<Object> run(
            Object instance,
            MethodMetadata target,
            Object[] arguments,
            MethodInterceptor[] chain,
            Supplier<Future<Object>> terminal) {
        if (chain.length == 0) {
            return invokeTerminal(terminal);
        }
        return new PositionInvocation(instance, target, arguments, chain, terminal, 0).proceed();
    }

    /**
     * Invokes the terminal supplier, capturing a synchronous throw into a failed future.
     *
     * <p>The {@code super.method(...)} call a generated proxy supplies may throw before it returns
     * its {@link Future} (e.g. an argument-validation guard that throws on entry). Translating that
     * sync throw into {@link Future#failedFuture} keeps the chain's contract uniform — the result is
     * always a {@code Future}, never a thrown exception — so interceptors observe the failure through
     * the normal future-settle path.
     *
     * @param terminal supplies the future of the underlying {@code super.method(...)} call
     * @return the terminal future, or a failed future wrapping a synchronous throw
     */
    private static Future<Object> invokeTerminal(Supplier<Future<Object>> terminal) {
        try {
            return terminal.get();
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * An {@link Invocation} bound to a single position in the interceptor chain.
     *
     * <p>Instances are immutable: the shared call data ({@code instance}, {@code target},
     * {@code arguments}, {@code chain}, {@code terminal}) plus this position's {@code index} fully
     * determine the downstream behavior. {@link #proceed()} never mutates this object — it either
     * invokes the {@code terminal} supplier (when {@code index} is past the last interceptor) or
     * constructs a brand-new {@code PositionInvocation} for {@code index + 1} and hands it to the
     * next interceptor. Because the continuation is rebuilt on the stack at each call, calling
     * {@code proceed()} more than once re-enters the downstream chain independently each time
     * (FR-013-05).
     */
    private static final class PositionInvocation implements Invocation {

        private final Object instance;
        private final MethodMetadata target;
        private final Object[] arguments;
        private final MethodInterceptor[] chain;
        private final Supplier<Future<Object>> terminal;
        private final int index;

        private PositionInvocation(
                Object instance,
                MethodMetadata target,
                Object[] arguments,
                MethodInterceptor[] chain,
                Supplier<Future<Object>> terminal,
                int index) {
            this.instance = instance;
            this.target = target;
            this.arguments = arguments;
            this.chain = chain;
            this.terminal = terminal;
            this.index = index;
        }

        @Override
        public MethodMetadata target() {
            return target;
        }

        @Override
        public Object[] arguments() {
            return arguments;
        }

        @Override
        public Object instance() {
            return instance;
        }

        @Override
        public Future<Object> proceed() {
            if (index >= chain.length) {
                // Innermost position: dispatch the real method, reading the live argument array. A
                // synchronous throw from the terminal supplier is captured into a failed future.
                return invokeTerminal(terminal);
            }
            // Hand the interceptor at this position a fresh invocation for the next position; the
            // continuation is captured per-call on the stack, never via a mutated shared index. A
            // synchronous throw from the interceptor is captured into a failed future so the chain
            // never throws synchronously.
            Invocation next = new PositionInvocation(instance, target, arguments, chain, terminal, index + 1);
            try {
                return chain[index].intercept(next);
            } catch (Throwable t) {
                return Future.failedFuture(t);
            }
        }
    }
}
