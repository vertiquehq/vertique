// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that {@link PgJobRepository} reports a payload it cannot store through the returned
 * future, before any statement runs, and that the failure does not quote the payload.
 */
@DisplayName("PgJobRepository payload rejection")
class PgJobRepositoryPayloadRejectionTest {

    private static final String SECRET = "do-not-log-me";

    private final Pool pool = mock(Pool.class);
    private final PgJobRepository repository = new PgJobRepository(pool, new PgDbExceptionMapper());

    /** Serializes to JSON {@code null}, which the column would not read back as a value. */
    record JsonNullPayload() {
        @JsonValue
        Object value() {
            return null;
        }
    }

    /** Fails serialization with a message that quotes the payload's content. */
    static final class FailingPayload {
        public String getSecret() {
            throw new IllegalStateException(SECRET);
        }
    }

    static Stream<Object> unstorablePayloads() {
        return Stream.of(new Object(), new FailingPayload(), List.of(new FailingPayload()), new JsonNullPayload());
    }

    private static JobExecution executionWith(Object payload) {
        return new JobExecution(
                UUID.randomUUID(),
                "payload-rejection",
                JobType.DELAYED,
                "job.delayed.test",
                "default",
                JobState.ENQUEUED,
                0,
                5,
                payload,
                0,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    private void assertRejected(Future<?> result) {
        assertTrue(result.failed(), "the rejection must arrive through the returned future");
        IllegalArgumentException failure = assertInstanceOf(IllegalArgumentException.class, result.cause());
        assertTrue(failure.getMessage().contains("JSON value"), failure.getMessage());
        assertFalse(failure.getMessage().contains(SECRET), "the failure must not quote the payload");
        assertNull(failure.getCause(), "Jackson's message quotes the payload, so it must not be chained");
        verifyNoInteractions(pool);
    }

    @ParameterizedTest
    @MethodSource("unstorablePayloads")
    @DisplayName("save fails the future")
    void saveFailsTheFuture(Object payload) {
        assertRejected(repository.save(executionWith(payload)));
    }

    @ParameterizedTest
    @MethodSource("unstorablePayloads")
    @DisplayName("transactional save fails the future")
    void transactionalSaveFailsTheFuture(Object payload) {
        SqlClient client = mock(SqlClient.class);

        assertRejected(repository.save(executionWith(payload), client));

        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("unstorablePayloads")
    @DisplayName("tryInsert fails the future")
    void tryInsertFailsTheFuture(Object payload) {
        assertRejected(repository.tryInsert(executionWith(payload)));
    }
}
