// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobRepository;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link CronMisfireRecovery} sequencing. End-to-end FIRE_ALL dispatch coverage
 * lives in {@link CronSchedulerTest}; these tests pin the chain contract with gated callbacks.
 */
@DisplayName("CronMisfireRecovery")
@ExtendWith(VertxExtension.class)
class CronMisfireRecoveryTest {

    @Test
    @DisplayName("FIRE_ALL invokes catch-up fires sequentially, waiting for each completion future")
    void fireAllChainsMissedFiresSequentially(Vertx vertx, VertxTestContext ctx) {
        Instant lastFiredAt = Instant.now().minusSeconds(3 * 3600);
        CronExpression hourly = new CronExpression("0 0 * * * *");
        int minMissed = hourly.computeFireTimesBetween(
                        lastFiredAt, Instant.now(), ZoneId.of("UTC"), CronScheduler.MAX_MISFIRE_FIRES)
                .size();
        assertTrue(minMissed >= 2, "precondition: need at least two missed fires");

        JobRepository repo = scheduleRepo("seq-job", lastFiredAt);
        CronJobDefinition job = fireAllJob("seq-job", hourly);

        List<Instant> started = new CopyOnWriteArrayList<>();
        AtomicInteger openFires = new AtomicInteger();
        AtomicInteger maxOpen = new AtomicInteger();
        Checkpoint done = ctx.checkpoint();

        new CronMisfireRecovery(repo).recover(List.of(job), (def, scheduledAt) -> {
            // A synchronous loop would start fire N while fire N-1 is still open.
            assertEquals(
                    0,
                    openFires.get(),
                    "FIRE_ALL must not start the next catch-up while the previous completion future"
                            + " is still open");
            openFires.incrementAndGet();
            maxOpen.accumulateAndGet(openFires.get(), Math::max);
            started.add(scheduledAt);

            Promise<Void> gate = Promise.promise();
            vertx.setTimer(15, id -> {
                openFires.decrementAndGet();
                gate.complete();
            });
            return gate.future();
        });

        vertx.setTimer(
                2_000,
                id -> ctx.verify(() -> {
                    assertTrue(
                            started.size() >= minMissed,
                            "FIRE_ALL must invoke every missed fire, got " + started.size());
                    assertEquals(1, maxOpen.get(), "at most one catch-up fire may be open");
                    List<Instant> observed = new ArrayList<>(started);
                    assertEquals(
                            observed.stream().sorted().toList(),
                            observed,
                            "FIRE_ALL must invoke missed fires oldest-to-newest");
                    done.flag();
                }));
    }

    @Test
    @DisplayName("FIRE_ALL continues the chain when one catch-up future fails")
    void fireAllContinuesAfterFailedCatchUp(Vertx vertx, VertxTestContext ctx) {
        Instant lastFiredAt = Instant.now().minusSeconds(3 * 3600);
        CronExpression hourly = new CronExpression("0 0 * * * *");
        int minMissed = hourly.computeFireTimesBetween(
                        lastFiredAt, Instant.now(), ZoneId.of("UTC"), CronScheduler.MAX_MISFIRE_FIRES)
                .size();
        assertTrue(minMissed >= 2, "precondition: need at least two missed fires");

        JobRepository repo = scheduleRepo("fail-continue-job", lastFiredAt);
        CronJobDefinition job = fireAllJob("fail-continue-job", hourly);

        List<Instant> started = new CopyOnWriteArrayList<>();

        new CronMisfireRecovery(repo).recover(List.of(job), (def, scheduledAt) -> {
            started.add(scheduledAt);
            if (started.size() == 1) {
                return Future.failedFuture(new RuntimeException("simulated catch-up failure"));
            }
            return Future.succeededFuture();
        });

        vertx.setTimer(
                1_000,
                id -> ctx.verify(() -> {
                    assertTrue(
                            started.size() >= minMissed,
                            "a failed catch-up must not abort the remaining FIRE_ALL sequence, got " + started.size());
                    List<Instant> observed = new ArrayList<>(started);
                    assertEquals(
                            observed.stream().sorted().toList(),
                            observed,
                            "FIRE_ALL must keep chronological order after a failed catch-up");
                    ctx.completeNow();
                }));
    }

    private static JobRepository scheduleRepo(String jobId, Instant lastFiredAt) {
        JobRepository repo = mock(JobRepository.class);
        CronJobSchedule schedule = new CronJobSchedule(
                jobId,
                "0 0 * * * *",
                "test." + jobId + ".address",
                "eventbus:test." + jobId + ".address",
                "SINGLE_INSTANCE",
                "UTC",
                true,
                "SKIP",
                3,
                true,
                lastFiredAt,
                null);
        when(repo.findSchedule(anyString())).thenReturn(Future.succeededFuture(Optional.of(schedule)));
        return repo;
    }

    private static CronJobDefinition fireAllJob(String jobId, CronExpression expression) {
        return new CronJobDefinition(
                jobId,
                expression,
                new CronTargetReference.EventBusTarget("test." + jobId + ".address"),
                "test." + jobId + ".address",
                ExecutionMode.SINGLE_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.FIRE_ALL);
    }
}
