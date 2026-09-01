// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import java.util.function.ToLongFunction;

/**
 * Public construction seam for the LOCAL Bucket4j {@link RateLimitBackend}, whose concrete type
 * ({@link LocalBucket4jRateLimitBackend}) is package-private and never crosses this package's
 * boundary — this factory is the only way outside code reaches it.
 * {@code dev.vertique.ratelimit.dagger.RateLimitCoreModule} is this factory's only production
 * caller.
 *
 * <p><b>Framework-private, documented internal.</b> This class is {@code public} only because
 * {@code dev.vertique.ratelimit.dagger.RateLimitCoreModule} (a different package within this same
 * module) must reach it; it is not part of this module's application-facing public API and carries
 * no compatibility guarantee toward application code. An external deep review flagged this
 * public/internal tension (reviewer proportionality disposition, T018): the narrower fix —
 * relocating this factory or {@code RateLimitCoreModule} so the construction seam could be
 * package-private — was judged disproportionate to the actual risk for this task, so the class
 * stays public, with this javadoc note as the recorded, deliberate disposition instead.
 */
public final class LocalRateLimitBackendFactory {

    private LocalRateLimitBackendFactory() {}

    /**
     * Builds the LOCAL Bucket4j {@link RateLimitBackend}.
     *
     * @param maxTrackedKeysResolver resolves each policy's own {@code local.maxTrackedKeys} budget
     *     by policy name (default or per-policy override), already bounds-validated by config
     *     validation upstream
     * @param cleanupIntervalMs the resolved {@code rateLimit.local.cleanupIntervalMs}, already
     *     bounds-validated by config validation upstream
     * @return the LOCAL {@link RateLimitBackend}
     */
    public static RateLimitBackend local(ToLongFunction<String> maxTrackedKeysResolver, long cleanupIntervalMs) {
        return new LocalBucket4jRateLimitBackend(maxTrackedKeysResolver, cleanupIntervalMs);
    }
}
