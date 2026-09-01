// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import dagger.MapKey;
import dev.vertique.ratelimit.RateLimitMode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The Dagger map key a {@link RateLimitBackend} provider uses to contribute its binding for one
 * {@link RateLimitMode} (contracts/rate-limit-runtime.md, "Backend seam — framework-private,
 * provider map"). Public visibility only so framework modules can compose LOCAL and CLUSTERED
 * backends; not a supported application extension point.
 */
@MapKey
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimitModeKey {
    RateLimitMode value();
}
