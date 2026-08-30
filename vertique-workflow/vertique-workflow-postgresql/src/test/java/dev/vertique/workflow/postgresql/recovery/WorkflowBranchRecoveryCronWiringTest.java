// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiring test proving that {@link CronJobRegistrar} discovers the {@code @CronJob} on
 * {@link PgWorkflowBranchRecoveryCron#reconcile()} when the impl is registered via
 * {@code @Services} (PRD-WF-002 §A.4.3, FR-WF-PAR-039).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Exactly one job is registered with id {@code "workflow-branch-recovery"}.</li>
 *   <li>Mode is {@link ExecutionMode#SINGLE_INSTANCE} and overlap policy is
 *       {@link OverlapPolicy#SKIP} (required for cluster-singleton).</li>
 *   <li>Cron expression matches the documented default ({@code "*}{@code /30 * * * * *"}).</li>
 *   <li>Target reference is a {@link CronTargetReference.ServiceTarget} — proves
 *       {@code @ServiceOperation} is wired correctly on the contract method.</li>
 * </ul>
 *
 * <p>Independent of the production cron lifecycle verticle: invokes {@code scan()} directly with
 * a capturing scheduler so the test does not need a real Vert.x instance.
 */
@DisplayName("PgWorkflowBranchRecoveryCron cron wiring")
class WorkflowBranchRecoveryCronWiringTest {

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
    @DisplayName("scan() registers workflow-branch-recovery as SINGLE_INSTANCE/SKIP with the documented cron")
    void scanRegistersWorkflowBranchRecovery() {
        // The cron registrar reads annotations on the impl method; the recovery service itself is
        // not needed for scan(). A no-op Provider keeps the wiring lightweight.
        PgWorkflowBranchRecoveryCron impl = new PgWorkflowBranchRecoveryCron(
                () -> null, new WorkflowBranchRecoveryConfig(1, Duration.ofMinutes(5)));
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

        assertEquals(1, scheduler.registered.size(), "expected exactly one cron job registered");
        CronJobDefinition def = scheduler.registered.get(0);
        assertEquals("workflow-branch-recovery", def.id());
        assertEquals(ExecutionMode.SINGLE_INSTANCE, def.mode());
        assertEquals(OverlapPolicy.SKIP, def.overlapPolicy());
        assertEquals("*/30 * * * * *", def.cronExpression().expression());
        CronTargetReference.ServiceTarget target =
                assertInstanceOf(CronTargetReference.ServiceTarget.class, def.target());
        assertEquals("workflow.branch-recovery.reconcile", target.stableTargetId());
    }
}
