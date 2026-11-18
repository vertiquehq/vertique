// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.vertique.db.DbSchemas;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.job.JobState;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.sqlclient.Pool;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The claim-fenced recovery writes reject a missing claim owner instead of dropping the fence. */
class PgJobRepositoryClaimFenceTest {

    private final PgJobRepository repository =
            new PgJobRepository(mock(Pool.class), new PgDbExceptionMapper(), DbSchemas.empty(), "node");

    @Test
    @DisplayName("completeClaimedExecution rejects a null claim owner")
    void completeClaimedRejectsNullOwner() {
        assertThrows(
                NullPointerException.class,
                () -> repository.completeClaimedExecution(
                        UUID.randomUUID(), null, 0, JobState.ABANDONED, "m", "t", ProgressSnapshot.EMPTY));
    }

    @Test
    @DisplayName("abandonClaimedAndScheduleRetry rejects a null claim owner")
    void abandonClaimedRejectsNullOwner() {
        assertThrows(
                NullPointerException.class,
                () -> repository.abandonClaimedAndScheduleRetry(
                        UUID.randomUUID(), null, 0, "m", "t", ProgressSnapshot.EMPTY, Instant.now(), 1));
    }
}
