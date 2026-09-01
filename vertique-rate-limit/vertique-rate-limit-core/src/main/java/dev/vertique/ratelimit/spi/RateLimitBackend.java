// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

import io.vertx.core.Future;

/**
 * Framework-private engine seam. Public visibility only so Dagger can compose the LOCAL and
 * CLUSTERED implementations across modules; never a supported application extension point
 * (contracts/rate-limit-runtime.md, "Boundary" and "Backend seam").
 */
public interface RateLimitBackend {

    /**
     * Attempts to consume {@code request.cost()} tokens against {@code request.storageKey()}.
     *
     * @param request the admission request
     * @return the normalized backend result; never fails for an ordinary rejection
     */
    Future<RateLimitBackendResult> consume(RateLimitBackendRequest request);
}
