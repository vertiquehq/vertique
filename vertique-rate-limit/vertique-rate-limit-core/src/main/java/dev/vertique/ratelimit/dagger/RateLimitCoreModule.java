// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.dagger;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.Multibinds;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitPolicy;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitModeKey;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Set;

/**
 * Dagger configuration contribution for the application-scoped rate-limit runtime.
 *
 * <p>This task's wiring is deliberately minimal: only the {@code Map<RateLimitMode,
 * RateLimitBackend>}/{@code Set<RateLimitPolicy>} multibinding declarations, the {@link
 * RateLimiters} singleton provider, and the LOCAL backend's {@code @IntoMap} contribution. The
 * observer/subject-resolver bindings and the {@code @IntoSet ApplicationShutdownStep} that forces
 * eager construction at bootstrap are a later task's artifacts (contracts/rate-limit-runtime.md,
 * "Dagger wiring"; `plan.md` pre-flight finding 5).
 */
@Module
public abstract class RateLimitCoreModule {

    /** Prevents direct construction of the static binding module. */
    private RateLimitCoreModule() {}

    /** Declares the backend provider map every {@code @RateLimitModeKey}-annotated binding joins. */
    @Multibinds
    abstract Map<RateLimitMode, RateLimitBackend> rateLimitBackends();

    /** Declares the optional application/config-contributed policy set. */
    @Multibinds
    abstract Set<RateLimitPolicy> rateLimitPolicies();

    /**
     * Provides the one runtime owned by the application graph.
     *
     * @param policies declared policies from every Dagger {@code @IntoSet} contribution
     * @param backends the bound backend provider map
     * @param vertx application Vert.x instance
     * @return application-scoped rate-limit runtime
     */
    @Provides
    @Singleton
    static RateLimiters rateLimiters(
            Set<RateLimitPolicy> policies, Map<RateLimitMode, RateLimitBackend> backends, Vertx vertx) {
        return new RateLimiters(policies, backends, vertx);
    }

    /**
     * Contributes the LOCAL Bucket4j backend.
     *
     * @return the LOCAL {@link RateLimitBackend}
     */
    @Provides
    @IntoMap
    @RateLimitModeKey(RateLimitMode.LOCAL)
    static RateLimitBackend localRateLimitBackend() {
        return new LocalBucket4jRateLimitBackend();
    }
}
