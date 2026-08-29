// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.eventbus.Result;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.services.ServiceContractRegistry.ContractEntry;
import dev.vertique.services.config.ServicesConfig;
import dev.vertique.services.resilience.ServiceResilienceConfigAdapter;
import dev.vertique.services.resilience.ServiceResiliencePipelineFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@Timeout(20)
class DelayedJobResilienceBoundaryIT {

    @DelayedJobContract(name = "boundary-job")
    interface BoundaryJob extends DelayedJobClient<String> {}

    static class BoundaryJobExecutor implements DelayedJobExecutor<String, BoundaryJob> {
        @Override
        public Future<Void> execute(String payload, dev.vertique.job.JobContext context) {
            return Future.succeededFuture();
        }
    }

    @Test
    void preservesDurableRetryWatchdogAfterVocabularyMove(Vertx vertx) {
        List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
        Resilience resilience = Resilience.create(vertx, Set.of(events::add));
        ServiceResilienceConfigAdapter adapter =
                new ServiceResilienceConfigAdapter(resilience, new ServicesConfig(null, List.of()), Map.of());
        ServiceResiliencePipelineFactory pipelineFactory = new ServiceResiliencePipelineFactory(adapter, resilience);
        try {
            ContractEntry<?> entry = new DelayedJobContractContributor(Set.of(new BoundaryJobExecutor()))
                    .contribute(new JsonObject())
                    .get(0);

            assertEquals(
                    ResilienceAnnotations.NONE,
                    entry.operations().get("execute").resilienceAnnotations());
            assertNull(pipelineFactory.pipeline(entry.operations().get("execute")));

            AtomicInteger backoffAttempt = new AtomicInteger(-1);
            BackoffStrategy backoff = attempt -> {
                backoffAttempt.set(attempt);
                return 25L;
            };
            JobRepository repository = mock(JobRepository.class);
            when(repository.failAndScheduleRetry(any(), any(), any(), any(), any(), anyInt()))
                    .thenReturn(Future.succeededFuture(java.util.Optional.empty()));

            UUID executionId = UUID.randomUUID();
            JobExecution execution = new JobExecution(
                    executionId,
                    "boundary-job-id",
                    JobType.DELAYED,
                    "jobs/delayed/boundary-job/execute",
                    "default",
                    JobState.PROCESSING,
                    0,
                    2,
                    "payload",
                    0,
                    "worker",
                    Instant.now(),
                    Instant.now(),
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    null);

            new JobCompletionHandler(repository)
                    .handleCompletion(execution, Result.failure(new IllegalStateException("boundary-failure")), backoff)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .join();

            assertEquals(1, backoffAttempt.get());
            verify(repository)
                    .failAndScheduleRetry(
                            eq(executionId),
                            eq("boundary-failure"),
                            eq(IllegalStateException.class.getName()),
                            any(),
                            any(),
                            eq(1));
            assertEquals(List.of(), events, "durable delayed-job work must not enter resilience runtime");
        } finally {
            pipelineFactory.close().toCompletionStage().toCompletableFuture().join();
            resilience.close().toCompletionStage().toCompletableFuture().join();
        }
    }
}
