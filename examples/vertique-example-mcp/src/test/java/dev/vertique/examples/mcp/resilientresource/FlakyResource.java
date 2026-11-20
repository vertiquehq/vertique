// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import io.vertx.core.Future;
import jakarta.inject.Inject;

/** Implementor of {@link FlakyEndpoint}: fails twice, then succeeds. */
public class FlakyResource implements FlakyEndpoint {

    private final Attempts attempts;

    /**
     * Creates the resource.
     *
     * @param attempts the attempt counter
     */
    @Inject
    public FlakyResource(Attempts attempts) {
        this.attempts = attempts;
    }

    @Override
    public Future<String> flaky() {
        int attempt = attempts.next();
        if (attempt < 3) {
            return Future.failedFuture(new IllegalStateException("transient failure " + attempt));
        }
        return Future.succeededFuture("ok after " + attempt);
    }
}
