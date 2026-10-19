// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowTenantResolver;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Start-time tenant resolution tests for {@code WorkflowEngine#doCreateInstance}.
 *
 * <p>The engine is assembled through its package-private constructor over mocked repositories,
 * exactly as {@link WorkflowEngineFactory#create} does, with the optional {@link
 * WorkflowTenantResolver} as the trailing constructor parameter. The resolver reads the {@code
 * tenant} namespace of the captured durable context through the shared {@link TenantCtxCodec}
 * fixture, so the ambient value bound by each scenario is also the value the resolver returns.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class WorkflowEngineStartTenantTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-start-tenant-test";
    private static final String INITIAL_STEP_ID = "complete";
    private static final String FINGERPRINT = "definitionVersion=1";

    /** Context content that must never surface in a failure; every invalid scenario embeds it. */
    private static final String SECRET = "SECRET-CTX-7f3a";

    private static final String EMOJI = "😀";

    /** A resolver that returns the tenant bound in the captured context and records what it saw. */
    private static final class ContextTenantResolver implements WorkflowTenantResolver {

        final List<DurableMetadata> received = new ArrayList<>();
        Throwable thrown;
        boolean throwing;
        boolean sneakyChecked;

        @Override
        public String tenantOf(DurableMetadata captured) {
            received.add(captured);
            String tenantId = captured.body(TenantCtxCodec.NAMESPACE)
                    .map(body -> body.getString("tenantId"))
                    .orElse(null);
            if (throwing) {
                IllegalStateException failure = new IllegalStateException("resolver failed for " + tenantId);
                thrown = failure;
                throw failure;
            }
            if (sneakyChecked) {
                IOException failure = new IOException("resolver io failed for " + tenantId);
                thrown = failure;
                ContextTenantResolver.<RuntimeException>sneakyThrow(failure);
            }
            return tenantId;
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
            throw (E) failure;
        }
    }

    /** The assembled engine plus the mocked collaborators the scenarios assert on. */
    private record Rig(
            WorkflowEngine engine,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            TaskStore<SqlClient> taskStore) {}

    private static RuntimeWorkflow minimalRuntimeWorkflow() {
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID,
                1L,
                "plan-hash",
                Object.class.getName(),
                INITIAL_STEP_ID,
                List.of(new CompleteNode(INITIAL_STEP_ID)),
                null);
        WorkflowCallbackRegistry callbacks = mock();
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /**
     * Assembles an engine like {@link WorkflowEngineFactory#create} does, with the given propagator
     * (nullable), the given optional resolver and the dedup outcome the claim returns.
     */
    private static Rig rig(
            DurableContextPropagator propagator,
            Optional<WorkflowTenantResolver> resolver,
            StartDedupResult dedupOutcome) {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        when(registry.resolveCurrent(DEFINITION_ID)).thenReturn(minimalRuntimeWorkflow());
        when(dedup.claimOrResolveStart(eq(DEFINITION_ID), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(dedupOutcome));
        when(instances.insert(any(), any())).thenReturn(Future.succeededFuture());
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());

        Set<WorkflowSideEffectRecorder<SqlClient>> recorders = Set.of();
        WorkflowReminderComposeValidator complianceCheck = new WorkflowReminderComposeValidator(registry, recorders);
        RecorderRouter recorderRouter =
                new RecorderRouter(recorders, WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS, complianceCheck);
        WorkflowEventEmitter eventEmitter = new WorkflowEventEmitter(recorderRouter, FIXED_CLOCK);
        WorkflowHistoryRecorder historyRecorder = new WorkflowHistoryRecorder(history, eventEmitter, FIXED_CLOCK);
        ReminderScheduler reminderScheduler = new ReminderScheduler(history, recorderRouter, timerStore, FIXED_CLOCK);
        CompensationService compensationService =
                new CompensationService(instances, history, recorderRouter, eventEmitter, FIXED_CLOCK);
        InboundExecutionContextScope inboundExecScope = propagator == null
                ? null
                : new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of());
        BranchTransitionEngine branchEngine = inboundExecScope == null
                ? new BranchTransitionEngine(
                        branchTokens, dedup, history, recorderRouter, taskStore, FIXED_CLOCK, propagator)
                : new BranchTransitionEngine(
                        branchTokens,
                        dedup,
                        history,
                        recorderRouter,
                        taskStore,
                        FIXED_CLOCK,
                        propagator,
                        inboundExecScope);
        BranchCompensationOrchestrator branchCompensation =
                new BranchCompensationOrchestrator(history, recorderRouter, FIXED_CLOCK);

        WorkflowEngine engine = new WorkflowEngine(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                recorderRouter,
                timerStore,
                taskStore,
                new FingerprintCanonicalizer(),
                FIXED_CLOCK,
                branchTokens,
                joinStates,
                branchEngine,
                branchCompensation,
                propagator,
                Optional.empty(),
                eventEmitter,
                historyRecorder,
                reminderScheduler,
                compensationService,
                inboundExecScope,
                new PayloadConstraintCheck(Optional.empty()),
                resolver);
        return new Rig(engine, instances, history, taskStore);
    }

    private static StartDedupResult winner() {
        return new StartDedupResult(new WorkflowInstanceId(UUID.randomUUID()), true, FINGERPRINT);
    }

    private static StartCommand startCommand() {
        return new StartCommand(DEFINITION_ID, Map.of(), "idem-" + UUID.randomUUID(), null, null);
    }

    /**
     * Starts a workflow on a duplicated Vert.x context, with the given tenant bound as the ambient
     * context first (none when {@code null}), and blocks until the start future settles.
     */
    private static AsyncResult<WorkflowInstanceId> start(
            Vertx vertx, Rig rig, DefaultContextHolder holder, String ambientTenant) throws Exception {
        CompletableFuture<AsyncResult<WorkflowInstanceId>> settled = new CompletableFuture<>();
        ContextInternal duplicated = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicated.runOnContext(v -> {
            if (ambientTenant != null) {
                holder.bind(TenantCtx.class, new TenantCtx(ambientTenant));
            }
            rig.engine().start(startCommand(), ActiveTransactionClient.create()).onComplete(settled::complete);
        });
        return settled.get(5, TimeUnit.SECONDS);
    }

    private static WorkflowInstance insertedInstance(Rig rig) {
        ArgumentCaptor<WorkflowInstance> inserted = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(rig.instances()).insert(inserted.capture(), any());
        return inserted.getValue();
    }

    private static void assertNothingWritten(Rig rig) {
        verify(rig.instances(), never()).insert(any(), any());
        verify(rig.history(), never()).append(any(), any());
        verifyNoInteractions(rig.taskStore());
    }

    /** Asserts no message and no string form anywhere in the cause chain carries the context content. */
    private static void assertChainHoldsNoContextContent(Throwable failure, Throwable resolverFailure) {
        int depth = 0;
        for (Throwable link = failure; link != null && depth < 32; link = link.getCause(), depth++) {
            assertThat(String.valueOf(link.getMessage())).doesNotContain(SECRET);
            assertThat(link.toString()).doesNotContain(SECRET);
            if (resolverFailure != null) {
                assertThat(link).isNotSameAs(resolverFailure);
            }
        }
    }

    @Test
    @DisplayName("start stamps the resolved tenant and fails closed on an invalid one")
    void startStampsTheResolvedTenantAndFailsClosedOnAnInvalidOne(Vertx vertx) throws Exception {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);

        // Given a resolver and an ambient context carrying t1
        // When a workflow starts
        // Then the new instance carries t1 and the resolver saw the captured tenant namespace
        ContextTenantResolver stamping = new ContextTenantResolver();
        Rig stamped = rig(propagator, Optional.of(stamping), winner());
        AsyncResult<WorkflowInstanceId> stampedStart = start(vertx, stamped, holder, "t1");
        assertThat(stampedStart.succeeded())
                .as("start with a valid tenant: %s", stampedStart.cause())
                .isTrue();
        assertThat(insertedInstance(stamped).tenantKey()).isEqualTo("t1");
        assertThat(stamping.received).hasSize(1);
        assertThat(stamping.received
                        .get(0)
                        .body(TenantCtxCodec.NAMESPACE)
                        .orElseThrow()
                        .getString("tenantId"))
                .isEqualTo("t1");

        // Given a tenant of exactly 255 code points, written with supplementary characters
        // When a workflow starts
        // Then it is accepted: the bound counts code points, not UTF-16 units
        String longestValid = SECRET + EMOJI.repeat(255 - SECRET.length());
        assertThat(longestValid.codePointCount(0, longestValid.length())).isEqualTo(255);
        assertThat(longestValid.length()).isGreaterThan(255);
        Rig longest = rig(propagator, Optional.of(new ContextTenantResolver()), winner());
        assertThat(start(vertx, longest, holder, longestValid).succeeded()).isTrue();
        assertThat(insertedInstance(longest).tenantKey()).isEqualTo(longestValid);

        // Given a resolver that returns null for the captured context
        // When a workflow starts
        // Then the instance carries no tenant
        Rig nullTenant = rig(propagator, Optional.<WorkflowTenantResolver>of(captured -> null), winner());
        assertThat(start(vertx, nullTenant, holder, "t1").succeeded()).isTrue();
        assertThat(insertedInstance(nullTenant).tenantKey()).isNull();

        // Given an ambient context with no tenant namespace
        // When a workflow starts
        // Then the resolver is called once with an empty document and the instance carries no tenant
        ContextTenantResolver unscoped = new ContextTenantResolver();
        Rig noContext = rig(propagator, Optional.of(unscoped), winner());
        assertThat(start(vertx, noContext, holder, null).succeeded()).isTrue();
        assertThat(insertedInstance(noContext).tenantKey()).isNull();
        assertThat(unscoped.received).hasSize(1);
        assertThat(unscoped.received.get(0).isEmpty()).isTrue();

        // Given no resolver bound and an ambient tenant
        // When a workflow starts
        // Then the instance carries no tenant
        Rig noResolver = rig(propagator, Optional.empty(), winner());
        assertThat(start(vertx, noResolver, holder, "t1").succeeded()).isTrue();
        assertThat(insertedInstance(noResolver).tenantKey()).isNull();

        // Given no propagator at all
        // When a workflow starts
        // Then the resolver receives DurableMetadata.empty() and the instance carries no tenant
        ContextTenantResolver withoutPropagator = new ContextTenantResolver();
        Rig noPropagator = rig(null, Optional.of(withoutPropagator), winner());
        assertThat(start(vertx, noPropagator, holder, "t1").succeeded()).isTrue();
        assertThat(withoutPropagator.received).containsExactly(DurableMetadata.empty());
        assertThat(insertedInstance(noPropagator).tenantKey()).isNull();

        // Given a resolver that returns each kind of invalid value, every one carrying the context secret
        Map<String, String> invalidByReason = new LinkedHashMap<>();
        invalidByReason.put("blank", "   ");
        invalidByReason.put("256 code points", SECRET + EMOJI.repeat(256 - SECRET.length()));
        invalidByReason.put("control character", SECRET + "\u0007");
        invalidByReason.put("NUL", SECRET + "\u0000");
        invalidByReason.put("separator", SECRET + ":x");
        invalidByReason.put("lone surrogate", SECRET + (char) 0xD800);
        for (Map.Entry<String, String> invalid : invalidByReason.entrySet()) {
            // When a workflow starts under that context
            ContextTenantResolver resolver = new ContextTenantResolver();
            Rig rejected = rig(propagator, Optional.of(resolver), winner());
            AsyncResult<WorkflowInstanceId> outcome = start(vertx, rejected, holder, invalid.getValue());

            // Then the start fails, nothing is written, and no link of the cause chain holds the context content
            assertThat(outcome.failed())
                    .as("%s tenant must fail the start", invalid.getKey())
                    .isTrue();
            assertChainHoldsNoContextContent(outcome.cause(), null);
            assertThat(resolver.received)
                    .as("%s: resolved once", invalid.getKey())
                    .hasSize(1);
            assertNothingWritten(rejected);
        }

        // Given a resolver that throws an exception whose message carries the context secret
        ContextTenantResolver throwing = new ContextTenantResolver();
        throwing.throwing = true;
        Rig thrown = rig(propagator, Optional.of(throwing), winner());

        // When a workflow starts
        AsyncResult<WorkflowInstanceId> thrownOutcome = start(vertx, thrown, holder, SECRET);

        // Then the start fails, nothing is written, and the resolver's own exception is nowhere in the chain
        assertThat(thrownOutcome.failed()).isTrue();
        assertThat(throwing.thrown).isNotNull();
        assertChainHoldsNoContextContent(thrownOutcome.cause(), throwing.thrown);
        assertNothingWritten(thrown);

        // Given a resolver that throws a checked exception without declaring it, its message carrying the secret
        ContextTenantResolver sneaky = new ContextTenantResolver();
        sneaky.sneakyChecked = true;
        Rig sneakyRig = rig(propagator, Optional.of(sneaky), winner());

        // When a workflow starts
        AsyncResult<WorkflowInstanceId> sneakyOutcome = start(vertx, sneakyRig, holder, SECRET);

        // Then the start fails with the same fixed failure, nothing is written, and the exception is nowhere in the
        // chain
        assertThat(sneakyOutcome.failed()).isTrue();
        assertThat(sneaky.thrown).isInstanceOf(IOException.class);
        assertChainHoldsNoContextContent(sneakyOutcome.cause(), sneaky.thrown);
        assertNothingWritten(sneakyRig);

        // Given a dedup row that already exists for the idempotency key
        WorkflowInstanceId existing = new WorkflowInstanceId(UUID.randomUUID());
        ContextTenantResolver replayResolver = new ContextTenantResolver();
        Rig replay = rig(propagator, Optional.of(replayResolver), new StartDedupResult(existing, false, FINGERPRINT));

        // When the start is replayed
        AsyncResult<WorkflowInstanceId> replayOutcome = start(vertx, replay, holder, "t1");

        // Then the existing id is returned, the resolver is never called and nothing is written
        assertThat(replayOutcome.succeeded()).isTrue();
        assertThat(replayOutcome.result()).isEqualTo(existing);
        assertThat(replayResolver.received).isEmpty();
        assertNothingWritten(replay);
    }
}
