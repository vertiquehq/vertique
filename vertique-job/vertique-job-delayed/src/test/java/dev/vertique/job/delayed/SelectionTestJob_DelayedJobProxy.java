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
 * Hand-written stand-in for the generated {@code SelectionTestJob_DelayedJobProxy}, used to verify
 * that {@link DelayedJobClientFactory} selects a present generated proxy by name. Mirrors the
 * generated constructor signature {@code (DelayedJobService, DelayedJobContract, JsonObject)}.
 */
public final class SelectionTestJob_DelayedJobProxy implements SelectionTestJob {

    /**
     * Matches the generated proxy constructor signature.
     *
     * @param jobService the job service (unused in this stand-in)
     * @param annotation the contract annotation (unused in this stand-in)
     * @param contractConfig the per-contract config (unused in this stand-in)
     */
    public SelectionTestJob_DelayedJobProxy(
            DelayedJobService jobService, DelayedJobContract annotation, JsonObject contractConfig) {
        // No-op stand-in.
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
