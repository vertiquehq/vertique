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
import io.vertx.core.json.JsonArray;
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
 * Verifies that {@link PgJobRepository} reports a payload that does not serialize to a JSON object
 * through the returned future, before any statement runs, and that the failure does not quote the
 * payload.
 */
@DisplayName("PgJobRepository payload rejection")
class PgJobRepositoryPayloadRejectionTest {

    private static final String SECRET = "do-not-log-me";

    private final Pool pool = mock(Pool.class);
    private final PgJobRepository repository = new PgJobRepository(pool, new PgDbExceptionMapper());

    /** Serializes to JSON {@code null}, which {@code JsonObject.mapFrom} reports differently from a scalar. */
    record JsonNullPayload() {
        @JsonValue
        Object value() {
            return null;
        }
    }

    static Stream<Object> nonObjectPayloads() {
        return Stream.of(
                SECRET,
                42,
                42L,
                1.5d,
                true,
                UUID.randomUUID(),
                new JsonArray().add(SECRET),
                List.of(SECRET),
                new JsonNullPayload());
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
        assertTrue(failure.getMessage().contains("JSON object"), failure.getMessage());
        assertFalse(failure.getMessage().contains(SECRET), "the failure must not quote the payload");
        assertNull(failure.getCause(), "Jackson's message quotes the payload, so it must not be chained");
        verifyNoInteractions(pool);
    }

    @ParameterizedTest
    @MethodSource("nonObjectPayloads")
    @DisplayName("save fails the future")
    void saveFailsTheFuture(Object payload) {
        assertRejected(repository.save(executionWith(payload)));
    }

    @ParameterizedTest
    @MethodSource("nonObjectPayloads")
    @DisplayName("transactional save fails the future")
    void transactionalSaveFailsTheFuture(Object payload) {
        SqlClient client = mock(SqlClient.class);

        assertRejected(repository.save(executionWith(payload), client));

        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @MethodSource("nonObjectPayloads")
    @DisplayName("tryInsert fails the future")
    void tryInsertFailsTheFuture(Object payload) {
        assertRejected(repository.tryInsert(executionWith(payload)));
    }
}
