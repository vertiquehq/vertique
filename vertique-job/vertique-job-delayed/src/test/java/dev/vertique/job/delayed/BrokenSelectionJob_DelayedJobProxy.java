// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Generated-proxy stand-in whose constructor throws, used to verify that
 * {@link DelayedJobClientFactory} fails loudly (rather than silently falling back) when a generated
 * proxy is present but cannot be instantiated.
 */
public final class BrokenSelectionJob_DelayedJobProxy implements BrokenSelectionJob {

    /**
     * Always throws to simulate a broken generated class.
     *
     * @param jobService the job service (unused)
     * @param annotation the contract annotation (unused)
     * @param contractConfig the per-contract config (unused)
     */
    public BrokenSelectionJob_DelayedJobProxy(
            DelayedJobService jobService, DelayedJobContract annotation, JsonObject contractConfig) {
        throw new IllegalStateException("intentionally broken generated proxy");
    }

    @Override
    public Future<UUID> enqueue(String payload) {
        return Future.succeededFuture();
    }

    @Override
    public Future<UUID> enqueue(String payload, Instant runAt) {
        return Future.succeededFuture();
    }

    @Override
    public Future<UUID> enqueue(String payload, Duration delay) {
        return Future.succeededFuture();
    }

    @Override
    public Future<UUID> enqueue(String payload, SqlClient tx) {
        return Future.succeededFuture();
    }

    @Override
    public Future<UUID> enqueue(String payload, DelayedJobOptions options) {
        return Future.succeededFuture();
    }

    @Override
    public Future<UUID> enqueue(String payload, DelayedJobOptions options, SqlClient tx) {
        return Future.succeededFuture();
    }
}
