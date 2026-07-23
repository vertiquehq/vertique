// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * A bean with a <em>synchronous-returning</em> method carrying the deliberately-deferring
 * {@link Deferring @Deferring} custom aspect — the runtime fixture for the FR-013-05 sync-defer guard
 * proof.
 *
 * <p>{@link #compute(String)} returns a plain {@link String} (not a {@code Future}), so the generated
 * {@code DeferringBean$AopProxy} override emits the sync-unwrap path plus the FR-013-05 guard. Because
 * {@link DeferringAspect} returns a never-completing future, calling {@code compute(...)} through the
 * proxy trips that guard and throws an {@link IllegalStateException} — the behavioral proof that a
 * sync-returning method cannot carry a deferring aspect.
 */
@Singleton
public class DeferringBean {

    /**
     * Creates the bean. The {@code @Inject} constructor is replicated by the generated
     * {@code DeferringBean$AopProxy}, which appends an {@code AspectProvider<Deferring>} parameter.
     */
    @Inject
    public DeferringBean() {}

    /**
     * Computes a value synchronously. Intercepted by {@link Deferring @Deferring}, whose aspect defers
     * completion; through the proxy this trips the FR-013-05 sync-defer guard and throws
     * {@link IllegalStateException}. Called directly (un-proxied) it simply returns the value.
     *
     * @param input the input to echo into the result
     * @return {@code "computed: <input>"} (only when invoked un-proxied; the proxy throws before
     *     returning)
     */
    @Deferring
    public String compute(String input) {
        return "computed: " + input;
    }
}
