// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.aop;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.aop.SelectorPaths;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitAdapterSupport;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds the transport-neutral per-operation admission interceptor for {@code @RateLimited}
 * (contracts/rate-limit-aop.md).
 *
 * <p>The aspect never inspects the intercepted target's transport: the same logic applies whether
 * the annotated method belongs to a plain service or a JAX-RS resource, because it only ever
 * consumes {@link MethodMetadata} and the live invocation arguments.
 */
@Singleton
final class RateLimitedAspect implements AspectProvider<RateLimited> {

    private final RateLimitAdapterSupport adapterSupport;

    /**
     * Dagger-resolved constructor. {@link RateLimitAdapterSupport} is not itself a bound Dagger
     * type (contracts/rate-limit-runtime.md, "Framework adapter seam"); it is reached through the
     * already-bound {@link RateLimiters#adapterSupport()} instead of introducing a new binding.
     */
    @Inject
    RateLimitedAspect(RateLimiters rateLimiters) {
        this(rateLimiters.adapterSupport());
    }

    /** Direct construction hook for isolated aspect tests. */
    RateLimitedAspect(RateLimitAdapterSupport adapterSupport) {
        this.adapterSupport = Objects.requireNonNull(adapterSupport, "adapterSupport");
    }

    @Override
    public MethodInterceptor interceptor(MethodMetadata target, RateLimited annotation) {
        // Constructor time (once per generated-proxy-constructor call, per method site): an
        // unknown policy name fails here, synchronously, before any invocation is ever admitted —
        // the free startup-fail-fast behavior RateLimiters#limiter(String) already provides.
        RateLimiter limiter = adapterSupport.limiter(annotation.policy());
        String[] selectorPaths = annotation.key();
        RateLimitSubject subject = annotation.subject();
        AnonymousRateLimitPolicy anonymous = annotation.anonymous();
        long cost = annotation.cost();
        return invocation -> {
            List<Object> extraComponents = resolveExtraComponents(selectorPaths, target, invocation.arguments());
            Optional<RateLimitKey> key = adapterSupport.subjectKey(subject, anonymous, extraComponents);
            return key.isEmpty() ? invocation.proceed() : limiter.execute(key.get(), cost, invocation::proceed);
        };
    }

    /**
     * Resolves the ordered selector-path components against the live arguments, at invocation
     * time. An empty {@code key()} means the key is derived from the subject alone.
     */
    private static List<Object> resolveExtraComponents(
            String[] selectorPaths, MethodMetadata target, Object[] arguments) {
        if (selectorPaths.length == 0) {
            return List.of();
        }
        // A fixed-size view over resolve()'s freshly allocated, never-mutated array — no
        // second copy or redundant null-check pass on this per-invocation hot path.
        return Arrays.asList(SelectorPaths.resolve("rate-limit key", selectorPaths, target, arguments));
    }
}
