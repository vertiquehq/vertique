// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.aop;

import dagger.Binds;
import dagger.Module;
import dev.vertique.aop.AspectProvider;

/** Dagger bindings for the {@code @RateLimited} annotation adapter. */
@Module
public abstract class RateLimitAopModule {
    @Binds
    abstract AspectProvider<RateLimited> bindRateLimitedAspect(RateLimitedAspect aspect);
}
