// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.InboxService;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.util.function.Supplier;

/**
 * The inbox of a proof graph: the statement of the inbox module over the {@code inbox} table, so a source's idempotency
 * runs against a real database. The insert and the work share the caller's transaction, and a conflicting insert of
 * another transaction waits for it, as in the inbox module.
 */
final class TableInbox implements InboxService {

    @Override
    public <T> Future<InboxResult<T>> processOnce(
            String messageId, String source, SqlClient tx, Supplier<Future<T>> work) {
        return tx.preparedQuery("INSERT INTO inbox (message_id, source) VALUES ($1, $2) ON CONFLICT DO NOTHING")
                .execute(Tuple.of(messageId, source))
                .compose(rows -> rows.rowCount() > 0
                        ? work.get().map(value -> (InboxResult<T>) new InboxResult.Processed<>(value))
                        : Future.succeededFuture(new InboxResult.Duplicate<T>()));
    }
}
