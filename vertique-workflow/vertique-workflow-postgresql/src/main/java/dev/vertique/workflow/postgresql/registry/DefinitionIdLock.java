// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Owns bounded transaction-scoped serialization for definition-id mutations. */
final class DefinitionIdLock {

    private DefinitionIdLock() {}

    /**
     * Sets the transaction-local wait bound before taking the exclusive advisory lock.
     *
     * @param tx the store-owned transaction connection
     * @param definitionId the exact definition id to serialize
     * @return a future completed after acquiring the advisory lock
     */
    static Future<Void> acquire(SqlConnection tx, String definitionId) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(definitionId, "definitionId");
        return tx.query("SET LOCAL lock_timeout = '30000ms'").execute().compose(ignored -> tx.preparedQuery(
                        "SELECT pg_advisory_xact_lock($1)")
                .execute(Tuple.of(lockKey(definitionId)))
                .mapEmpty());
    }

    /**
     * Maps a definition id to the signed 64-bit advisory key shared by the exclusive store mutations and
     * the shared pin admission, so that both modes address the same lock.
     */
    static long lockKey(String definitionId) {
        try {
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                            .digest(("vertique.workflow.definition:" + definitionId).getBytes(StandardCharsets.UTF_8)))
                    .getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
