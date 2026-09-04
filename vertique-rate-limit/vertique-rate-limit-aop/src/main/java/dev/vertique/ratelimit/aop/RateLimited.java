// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.aop;

import dev.vertique.aop.Aspect;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Transport-neutral per-operation admission annotation, usable identically on services, JAX-RS
 * resources, and future MCP tools (contracts/rate-limit-aop.md).
 *
 * <p>{@link #policy()} names a policy declared in {@code rateLimit.policies.<name>} and/or a
 * {@code RateLimitPolicy} Dagger contribution; no inline numeric policy definition is permitted on
 * the annotation. {@link #key()} is an ordered list of selector paths using the same grammar as
 * {@code @Cacheable} (root parameter by name or position, dot-separated record/bean accessor
 * chain, terminal scalar allowlist); an empty array means the key is derived from the subject
 * alone. Selector-path length/segment bounds are enforced at compile time by
 * {@code vertique-codegen-rate-limit}, not by this annotation or its aspect.
 *
 * <p><b>Self-invocation and direct construction bypass interception</b>, identical to and
 * documented identically to {@code @Cacheable}: a method calling another {@code @RateLimited}
 * method on {@code this} within the same class bypasses the generated proxy, and constructing the
 * bean directly (rather than resolving it through the Dagger-provided {@code Provider.get()})
 * bypasses interception entirely.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Aspect(ordering = 300) // outside @Cacheable(200)/@CacheEvict(100), inside @Timed(1000)
public @interface RateLimited {

    /**
     * The name of an existing, declared policy. No inline numeric policy definition is permitted
     * here; an unknown name fails application startup (see {@link RateLimitedAspect}).
     *
     * @return the policy name
     */
    String policy();

    /**
     * Ordered selector paths resolved against the live method arguments at invocation time. An
     * empty array is valid and means the key is derived from the subject alone.
     *
     * @return the ordered selector paths
     */
    String[] key() default {};

    /**
     * The identity dimension the key is framed around.
     *
     * @return the subject dimension
     */
    RateLimitSubject subject() default RateLimitSubject.EFFECTIVE_PRINCIPAL;

    /**
     * The policy governing an anonymous caller (no identity at all).
     *
     * @return the anonymous-caller policy
     */
    AnonymousRateLimitPolicy anonymous() default AnonymousRateLimitPolicy.SHARED_BUCKET;

    /**
     * Tokens this invocation attempts to consume. Must be {@code >= 1}.
     *
     * @return the request cost
     */
    long cost() default 1;
}
