// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.job.JobRepository;
import dev.vertique.job.cron.CronJobDefinition;
import dev.vertique.job.cron.CronJobRegistrar;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.CronTargetReference;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.OverlapPolicy;
import dev.vertique.job.cron.config.CronConfig;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiring test proving that {@link CronJobRegistrar} discovers both {@code @CronJob} methods on
 * {@link OutboxMaintenanceCron} when the impl is registered via {@code @Services}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Exactly two jobs registered: {@code outbox-stale-lease-recovery} and
 *       {@code outbox-cleanup}.</li>
 *   <li>Both are {@link ExecutionMode#SINGLE_INSTANCE} with {@link OverlapPolicy#SKIP}.</li>
 *   <li>Cron expressions match the documented defaults (matching prior {@code OutboxRelay}
 *       periodic cadences).</li>
 *   <li>Both targets are {@link CronTargetReference.ServiceTarget} — proves
 *       {@code @ServiceOperation} is wired correctly on the contract methods.</li>
 * </ul>
 */
@DisplayName("OutboxMaintenanceCron cron wiring")
class OutboxMaintenanceCronWiringTest {

    /** Captures every job registered with {@code register(...)} without needing live Vert.x. */
    private static final class CapturingScheduler extends CronScheduler {

        final List<CronJobDefinition> registered = new ArrayList<>();

        CapturingScheduler(ServiceTargetResolver resolver) {
            super(null, Set.of(), null, resolver, null, dev.vertique.context.DispatchEnvelopeBuilder.forTesting());
        }

        @Override
        public void register(CronJobDefinition job) {
            registered.add(job);
        }
    }

    private static ServiceTargetResolver stubTargetResolver() {
        ServiceTargetResolver resolver = mock(ServiceTargetResolver.class);
        when(resolver.resolve(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return new ResolvedServiceTarget(id, null, "", id, id, null, id);
        });
        return resolver;
    }

    @Test
    @DisplayName("scan() registers both maintenance jobs with the documented cron expressions")
    void scanRegistersBothMaintenanceJobs() {
        // The registrar reads annotations on the impl methods; the underlying service is not
        // needed for scan(). A no-op Provider keeps the wiring lightweight.
        OutboxMaintenanceCron impl = new OutboxMaintenanceCron(() -> null);
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(impl), new DefaultConfigParser(DefaultConfigMapper.lenient()));
        ServiceTargetResolver resolver = stubTargetResolver();
        CapturingScheduler scheduler = new CapturingScheduler(resolver);
        // SINGLE_INSTANCE requires a JobRepository binding. Stub saveSchedule so the
        // fire-and-forget persistence path inside scan() does not NPE.
        JobRepository repository = mock(JobRepository.class);
        when(repository.saveSchedule(any())).thenReturn(Future.succeededFuture());

        new CronJobRegistrar(
                        scheduler,
                        registry,
                        resolver,
                        CronConfig.fromConfig(new JsonObject(), new DefaultConfigParser(DefaultConfigMapper.lenient())),
                        repository)
                .scan();

        assertEquals(2, scheduler.registered.size(), "expected exactly two cron jobs registered");

        CronJobDefinition stale = findById(scheduler.registered, "outbox-stale-lease-recovery");
        assertEquals(ExecutionMode.SINGLE_INSTANCE, stale.mode());
        assertEquals(OverlapPolicy.SKIP, stale.overlapPolicy());
        assertEquals("*/30 * * * * *", stale.cronExpression().expression());
        CronTargetReference.ServiceTarget staleTarget =
                assertInstanceOf(CronTargetReference.ServiceTarget.class, stale.target());
        assertEquals("inboxoutbox.outbox-maintenance.recoverStaleLeases", staleTarget.stableTargetId());

        CronJobDefinition cleanup = findById(scheduler.registered, "outbox-cleanup");
        assertEquals(ExecutionMode.SINGLE_INSTANCE, cleanup.mode());
        assertEquals(OverlapPolicy.SKIP, cleanup.overlapPolicy());
        assertEquals("0 0 */6 * * *", cleanup.cronExpression().expression());
        CronTargetReference.ServiceTarget cleanupTarget =
                assertInstanceOf(CronTargetReference.ServiceTarget.class, cleanup.target());
        assertEquals("inboxoutbox.outbox-maintenance.cleanup", cleanupTarget.stableTargetId());
    }

    private static CronJobDefinition findById(List<CronJobDefinition> defs, String id) {
        Optional<CronJobDefinition> match =
                defs.stream().filter(d -> d.id().equals(id)).findFirst();
        assertTrue(match.isPresent(), "expected job id " + id + " in registered set");
        return match.get();
    }
}
