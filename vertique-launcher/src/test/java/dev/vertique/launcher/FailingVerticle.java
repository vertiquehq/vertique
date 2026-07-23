// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import io.vertx.core.Future;
import io.vertx.core.VerticleBase;

/**
 * Test fixture verticle whose {@link #start()} always fails with a
 * {@link RuntimeException}.
 *
 * <p>Used in tests that verify launcher behaviour when a verticle fails to deploy.
 */
public final class FailingVerticle extends VerticleBase {

    /**
     * Fails with an {@link IllegalStateException}.
     *
     * @return a failed future
     */
    @Override
    public Future<?> start() {
        return Future.failedFuture(new IllegalStateException("intentional verticle failure"));
    }
}
