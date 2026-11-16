// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron.dagger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.EventBusExceptionMapper;
import dev.vertique.job.JobCoordinatorConfig;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.CronExpression;
import dev.vertique.job.cron.CronJobDefinition;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.CronTargetReference;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.MisfirePolicy;
import dev.vertique.job.cron.OverlapPolicy;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * The persistence-backed cron scheduler stamps the bound node identity, the one the job coordinator
 * heartbeats under, as {@code locked_by} on the executions it persists.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("CronPersistenceModule node identity")
class CronPersistenceModuleNodeIdentityTest {

    @Test
    @DisplayName("the provided scheduler stamps the node identity it is given as lockedBy")
    void providedSchedulerStampsNodeIdentity(Vertx vertx) {
        JobRepository repository = mock(JobRepository.class);
        when(repository.tryInsert(any(JobExecution.class)))
                .thenReturn(Future.succeededFuture(Optional.of(UUID.randomUUID())));
        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(anyString())).thenAnswer(inv -> {
            String targetId = inv.getArgument(0);
            return new ResolvedServiceTarget(targetId, null, "", targetId, targetId, null, targetId);
        });

        CronScheduler scheduler = CronPersistenceModule.cronScheduler(
                vertx,
                Set.of(),
                repository,
                resolver,
                new EventBusClient(vertx, new EventBusExceptionMapper()),
                JobCoordinatorConfig.builder().build(),
                DispatchEnvelopeBuilder.forTesting(),
                "node-a-1234abcd");
        try {
            scheduler.register(new CronJobDefinition(
                    "node-identity-job",
                    new CronExpression("* * * * * *"),
                    new CronTargetReference.ServiceTarget("test.target"),
                    null,
                    ExecutionMode.SINGLE_INSTANCE,
                    ZoneId.of("UTC"),
                    3,
                    null,
                    OverlapPolicy.SKIP,
                    true,
                    Map.of(),
                    MisfirePolicy.SKIP));
            scheduler.start();

            ArgumentCaptor<JobExecution> captor = ArgumentCaptor.forClass(JobExecution.class);
            verify(repository, timeout(5000)).tryInsert(captor.capture());
            assertEquals("node-a-1234abcd", captor.getValue().lockedBy());
        } finally {
            scheduler.stop();
        }
    }
}
