// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import io.vertx.core.Promise;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * {@link AspectProvider} for the {@link Deferring @Deferring} custom aspect that deliberately defers
 * completion — used to prove the FR-013-05 sync-defer guard end-to-end.
 *
 * <p>The interceptor it builds calls {@link dev.vertique.aop.Invocation#proceed() proceed()} (so the
 * downstream chain and the terminal {@code super} call actually run) but discards that result and
 * returns a {@link Promise#promise() promise}'s {@link Promise#future() future} that is
 * <em>never completed</em>. Because the returned future never settles synchronously, applying this
 * aspect to a synchronous-returning method leaves the generated proxy's around-chain incomplete by
 * the time the override must produce a plain value — which is exactly the condition the generated
 * sync guard ({@code AopProxyEmitter.emitSyncGuard}) converts into an {@link IllegalStateException}.
 *
 * <p>This is the opposite of a framework built-in: {@code @Timed} observes and passes the outcome
 * through unchanged (completing synchronously when the method does), so it never trips the guard;
 * {@code @Deferring} returns a deferred result, so it always trips the guard on a sync method.
 */
@Singleton
public final class DeferringAspect implements AspectProvider<Deferring> {

    /**
     * Creates the deferring aspect provider.
     *
     * <p>The {@code @Inject} constructor lets Dagger supply this provider for the
     * {@code AspectProvider<Deferring>} dependency the generated {@code DeferringBean$AopProxy}
     * injects.
     */
    @Inject
    public DeferringAspect() {}

    /**
     * Builds the deferring interceptor for one {@link Deferring @Deferring}-annotated method.
     *
     * @param target the reflection-free metadata of the intercepted method (unused; the aspect defers
     *     regardless of the method)
     * @param annotation the {@link Deferring @Deferring} instance present on the method (unused; the
     *     annotation carries no attributes)
     * @return an interceptor that runs the downstream chain but returns a never-completing future
     */
    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Deferring annotation) {
        return invocation -> {
            // Run the downstream chain (and the terminal super call) for real, then deliberately
            // discard its result and return a future that NEVER settles synchronously. On a
            // sync-returning method this makes the around-chain fail to complete in time, tripping
            // the FR-013-05 guard the generated proxy emits.
            invocation.proceed();
            Promise<Object> neverCompletes = Promise.promise();
            return neverCompletes.future();
        };
    }
}
