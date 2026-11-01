// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.services.source;

import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.InboxService;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An inbox over a set of ids, with the transaction behavior the delivery relies on: a first delivery inserts its row and
 * runs the work, a row that exists answers duplicate without running the work, and a row whose work fails is rolled
 * back with it, so the next attempt runs normally.
 */
final class FakeInbox implements InboxService {

    /** The committed ids, in the order they were committed. */
    final Set<String> committed = new LinkedHashSet<>();

    /** Every id offered, with the source it was offered for, in order. */
    final List<String> offered = new ArrayList<>();

    /** Fails the next insert, as a database that is down would. */
    RuntimeException failNextInsert;

    /** Lets this many inserts pass, then fails the one after, once. Zero turns it off. */
    int failNextInsertAfter;

    @Override
    public <T> Future<InboxResult<T>> processOnce(
            String messageId, String source, SqlClient tx, Supplier<Future<T>> work) {
        offered.add(messageId + "@" + source);
        if (failNextInsertAfter > 0 && --failNextInsertAfter == 0 && failNextInsert == null) {
            failNextInsert = new IllegalStateException("inbox unavailable");
        }
        if (failNextInsert != null) {
            RuntimeException failure = failNextInsert;
            failNextInsert = null;
            return Future.failedFuture(failure);
        }
        if (committed.contains(messageId)) {
            return Future.succeededFuture(new InboxResult.Duplicate<>());
        }
        committed.add(messageId);
        Future<T> done;
        try {
            done = work.get();
        } catch (RuntimeException thrown) {
            committed.remove(messageId);
            throw thrown;
        }
        return done.<InboxResult<T>>map(value -> new InboxResult.Processed<>(value))
                .onFailure(failure -> committed.remove(messageId));
    }
}
