// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.delayedjob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.job.delayed.DelayedJobClient;
import dev.vertique.job.delayed.DelayedJobTargetResolver;
import dev.vertique.job.delayed.ResolvedDelayedJobTarget;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies that {@link TransactionalDelayedJobPublisher} records entries in the scope the caller selects. */
@DisplayName("TransactionalDelayedJobPublisher")
class TransactionalDelayedJobPublisherTest {

    private interface ScopedJob extends DelayedJobClient<String> {}

    @Test
    @DisplayName(
            "records on the caller's transaction, rejects null and blank scopes, and keeps the default without a scope")
    void selectsScope() {
        DelayedJobTargetResolver resolver = mock(DelayedJobTargetResolver.class);
        when(resolver.resolve(ScopedJob.class))
                .thenReturn(new ResolvedDelayedJobTarget(
                        "scoped-job", "scoped-job", "job/scoped-job/execute", "default", 0, 3));
        List<OutboxEntry> recorded = new ArrayList<>();
        List<SqlClient> transactions = new ArrayList<>();
        OutboxService outbox = (client, entry) -> {
            transactions.add(client);
            recorded.add(entry);
            return Future.succeededFuture((long) recorded.size());
        };
        TransactionalDelayedJobPublisher publisher = new TransactionalDelayedJobPublisher(resolver, outbox);
        SqlClient tx = mock(SqlClient.class);
        Instant scheduledAt = Instant.parse("2026-11-01T00:00:00Z");

        // A selected scope reaches the entry
        Future<Long> selected = publisher.publish(ScopedJob.class, "payload", scheduledAt, "acme", tx);
        assertTrue(selected.succeeded());
        assertEquals("acme", recorded.get(0).scopeKey());
        assertEquals(scheduledAt, recorded.get(0).scheduledAt());
        assertSame(tx, transactions.get(0));

        // A null scope fails without recording an entry
        Future<Long> rejected = publisher.publish(ScopedJob.class, "payload", scheduledAt, null, tx);
        assertTrue(rejected.failed());
        assertEquals(1, recorded.size());

        // A blank scope fails the same way, and the failure does not repeat the key
        String blank = "   ";
        Future<Long> blankRejected = publisher.publish(ScopedJob.class, "payload", scheduledAt, blank, tx);
        assertTrue(blankRejected.failed());
        assertFalse(blankRejected.cause().getMessage().contains(blank));
        assertEquals(1, recorded.size());

        // A literal null scheduledAt still compiles against the form without a scope, and stays in default
        Future<Long> unscoped = publisher.publish(ScopedJob.class, "payload", null, tx);
        assertTrue(unscoped.succeeded());
        assertEquals("default", recorded.get(1).scopeKey());
        assertNull(recorded.get(1).scheduledAt());
        assertSame(tx, transactions.get(1));
    }
}
