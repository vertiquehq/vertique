// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.InboxResult;
import dev.vertique.inboxoutbox.exception.InboxOutboxPersistenceException;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link DefaultInboxService} — deduplication routing, work invocation,
 * failure propagation, and exception-mapper boundary placement (FR-IO-004).
 */
@ExtendWith(MockitoExtension.class)
class DefaultInboxServiceTest {

    @Mock
    InboxRepository repository;

    DefaultInboxService service;

    @BeforeEach
    void setUp() {
        service = new DefaultInboxService(repository, new PgInboxOutboxExceptionMapper());
    }

    @Nested
    class processOnce {

        @Test
        @DisplayName("new message executes work and returns Processed")
        void newMessageExecutesWorkAndReturnsProcessed() {
            SqlClient tx = mock(SqlClient.class);
            when(repository.tryInsert("msg-1", "source-a", tx)).thenReturn(Future.succeededFuture(true));

            AtomicBoolean workCalled = new AtomicBoolean(false);
            Future<InboxResult<String>> future = service.processOnce("msg-1", "source-a", tx, () -> {
                workCalled.set(true);
                return Future.succeededFuture("hello");
            });

            assertTrue(future.succeeded(), "future should succeed");
            InboxResult<String> result = future.result();
            assertInstanceOf(InboxResult.Processed.class, result, "should be Processed");
            assertTrue(workCalled.get(), "work supplier should have been called");

            InboxResult.Processed<String> processed = (InboxResult.Processed<String>) result;
            org.junit.jupiter.api.Assertions.assertEquals("hello", processed.value());
        }

        @Test
        @DisplayName("duplicate message skips work and returns Duplicate")
        void duplicateMessageSkipsWorkAndReturnsDuplicate() {
            SqlClient tx = mock(SqlClient.class);
            when(repository.tryInsert("msg-1", "source-a", tx)).thenReturn(Future.succeededFuture(false));

            AtomicBoolean workCalled = new AtomicBoolean(false);
            Future<InboxResult<String>> future = service.processOnce("msg-1", "source-a", tx, () -> {
                workCalled.set(true);
                return Future.succeededFuture("should not be called");
            });

            assertTrue(future.succeeded(), "future should succeed");
            assertInstanceOf(InboxResult.Duplicate.class, future.result(), "should be Duplicate");
            org.junit.jupiter.api.Assertions.assertFalse(workCalled.get(), "work supplier must NOT be called");
        }

        @Test
        @DisplayName("repository DataAccessException failure is wrapped as InboxOutboxPersistenceException")
        void repositoryDataAccessExceptionIsWrappedAsPersistenceException() {
            SqlClient tx = mock(SqlClient.class);
            DataAccessException dbFailure = new DataAccessException("connection lost", null, null, null, null);
            when(repository.tryInsert("msg-1", "source-a", tx)).thenReturn(Future.failedFuture(dbFailure));

            AtomicBoolean workCalled = new AtomicBoolean(false);
            Future<InboxResult<String>> future = service.processOnce("msg-1", "source-a", tx, () -> {
                workCalled.set(true);
                return Future.succeededFuture("should not be called");
            });

            assertTrue(future.failed(), "future should fail");
            assertInstanceOf(
                    InboxOutboxPersistenceException.class,
                    future.cause(),
                    "DataAccessException from repository must be wrapped in InboxOutboxPersistenceException");
            assertSame(
                    dbFailure, future.cause().getCause(), "original DataAccessException must be chained as the cause");
            org.junit.jupiter.api.Assertions.assertFalse(workCalled.get(), "work supplier must NOT be called");
        }

        @Test
        @DisplayName("work failure propagates UNCHANGED — not wrapped by exception mapper (FR-IO-004)")
        void workFailurePropagatesUnchanged() {
            SqlClient tx = mock(SqlClient.class);
            when(repository.tryInsert("msg-2", "source-b", tx)).thenReturn(Future.succeededFuture(true));

            RuntimeException workError = new RuntimeException("work exploded");
            Future<InboxResult<String>> future =
                    service.processOnce("msg-2", "source-b", tx, () -> Future.failedFuture(workError));

            assertTrue(future.failed(), "future should fail when work fails");
            // The work supplier's exception must pass through UNCHANGED — the mapper applies only
            // to the repository tryInsert failure, not to business logic failures inside compose.
            assertSame(
                    workError,
                    future.cause(),
                    "work failure must be the same instance — not wrapped by PgInboxOutboxExceptionMapper");
            verify(repository).tryInsert("msg-2", "source-b", tx);
        }
    }
}
