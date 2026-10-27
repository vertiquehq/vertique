// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableEncodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextDurableDecoder;
import dev.vertique.correlation.CorrelationContextDurableEncoder;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextSeeder;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WorkflowContextBinder} (PRD FR-WF-CTX-012 / FR-WF-CTX-020 /
 * FR-WF-CTX-024, AC-9, NFR-WF-CTX-008).
 *
 * <p>Uses the {@code runOnDuplicated} pattern plus the shared {@link TenantCtx} /
 * {@link TenantCtxCodec} test fixture to bind ambient context, and the real
 * {@link CorrelationContext} type / {@link CorrelationContextSeeder} initializer for the AC-9
 * fill-before-seeding ordering tests — a test double is deliberately not used there.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowContextBinderTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    /** Builds a minimal {@link WorkflowInstance} carrying the given metadata. */
    private static WorkflowInstance instanceWithMetadata(DurableMetadata metadata) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                "wf-context-binder-test",
                1L,
                "hash",
                0L,
                WorkflowStatus.RUNNING,
                null,
                null,
                "step-1",
                null,
                null,
                null,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                metadata);
    }

    /**
     * Builds a {@link DurableContextPropagator} + {@link InboundExecutionContextScope} pair wired
     * with the {@link TenantCtx} codec only, over the given holder, with no registered
     * initializers.
     */
    private static InboundExecutionContextScope scopeWithTenantOnly(DefaultContextHolder holder) {
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(TenantCtxCodec.encoder()), Set.of(TenantCtxCodec.decoder()));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        return new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of());
    }

    // --- Recorded base of an executor drive ---

    private static final long COMMAND_SEQ = 4217L;

    private static final String IDENTITY_NAMESPACE = "identity-snapshot";

    /** What an operation saw bound while it ran. */
    private record Effective(Optional<String> correlation, Optional<String> tenant, Optional<String> identity) {}

    /** The identity snapshot a durable document can carry. */
    private record IdentityCtx(String subject) implements ContextValue {}

    /** A binder over real codecs for correlation, tenant and identity, with an ambient context to ignore. */
    private static final class Harness {
        final DefaultContextHolder holder = new DefaultContextHolder();
        final CorrelationContextFactory correlations = new CorrelationContextFactory(Optional.empty());
        final InboundExecutionContextScope scope;
        final WorkflowContextBinder binder;

        Harness() {
            DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                    Set.of(TenantCtxCodec.encoder(), new CorrelationContextDurableEncoder(), identityEncoder()),
                    Set.of(
                            TenantCtxCodec.decoder(),
                            new CorrelationContextDurableDecoder(correlations),
                            identityDecoder()));
            DurableContextPropagator propagator =
                    new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
            this.scope = spy(new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of()));
            this.binder = new WorkflowContextBinder(propagator, scope, holder);
        }

        DurableMetadata correlation(String id) {
            return new CorrelationContextDurableEncoder()
                    .encode(
                            correlations.create(
                                    new CorrelationIdentifier("req-" + id, "test"),
                                    new CorrelationIdentifier(id, "test")),
                            new DurableEncodeContext("test"));
        }

        /** Binds the ambient context on the current context, as a dirty caller would have it. */
        void bindAmbient() {
            holder.bind(
                    CorrelationContext.class,
                    correlations.create(
                            new CorrelationIdentifier("req-A", "test"), new CorrelationIdentifier("A", "test")));
            holder.bind(TenantCtx.class, new TenantCtx("AMBIENT"));
        }

        Effective observed() {
            return new Effective(
                    holder.current(CorrelationContext.class)
                            .map(c -> c.correlationId().value()),
                    holder.current(TenantCtx.class).map(TenantCtx::tenantId),
                    holder.current(IdentityCtx.class).map(IdentityCtx::subject));
        }

        /**
         * Runs an executor bind on a context that has the ambient correlation A and tenant AMBIENT bound, and returns
         * what the operation saw.
         */
        Effective bind(
                Vertx vertx,
                @Nullable DurableMetadata explicit,
                @Nullable DurableMetadata recorded,
                @Nullable DurableMetadata instanceCarrier)
                throws Exception {
            CompletableFuture<Effective> result = new CompletableFuture<>();
            runOnDuplicated(vertx, v -> {
                bindAmbient();
                try {
                    binder.withRecordedBase(
                                    instanceWithMetadata(instanceCarrier),
                                    COMMAND_SEQ,
                                    explicit,
                                    recorded,
                                    () -> Future.succeededFuture(observed()))
                            .onComplete(ar -> {
                                if (ar.succeeded()) {
                                    result.complete(ar.result());
                                } else {
                                    result.completeExceptionally(ar.cause());
                                }
                            });
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            return result.get(10, TimeUnit.SECONDS);
        }
    }

    private static DurableMetadata tenant(String id) {
        return DurableMetadata.of(TenantCtxCodec.NAMESPACE, JsonObject.of("tenantId", id));
    }

    private static DurableMetadata identity(String subject) {
        return DurableMetadata.of(IDENTITY_NAMESPACE, JsonObject.of("subject", subject));
    }

    private static DurableContextMetadataEncoder<IdentityCtx> identityEncoder() {
        return new DurableContextMetadataEncoder<>() {
            @Override
            public Class<IdentityCtx> type() {
                return IdentityCtx.class;
            }

            @Override
            public String namespace() {
                return IDENTITY_NAMESPACE;
            }

            @Override
            public DurableMetadata encode(IdentityCtx value, DurableEncodeContext context) {
                return identity(value.subject());
            }
        };
    }

    private static DurableContextMetadataDecoder<IdentityCtx> identityDecoder() {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<IdentityCtx> type() {
                return IdentityCtx.class;
            }

            @Override
            public String namespace() {
                return IDENTITY_NAMESPACE;
            }

            @Override
            public ContextDecodeResult<IdentityCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(IDENTITY_NAMESPACE)
                        .map(body -> body.getString("subject"))
                        .map(IdentityCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }
        };
    }

    private static SecurityContext securityContext() {
        return SecurityContexts.unauthenticated(new SecurityIdentity(
                new PrincipalRef(PrincipalType.USER, "user-77", Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    @DisplayName("an executor bind takes the explicit carrier, else the recorded context, else the instance carrier")
    void recordedBasePrecedenceAndGate(Vertx vertx) throws Exception {
        // Given recorded context {corr:X}, explicit carrier {corr:E}, instance carrier {corr:I, tenant:T} and an
        // ambient context {corr:A, tenant:AMBIENT} that must never be a base
        Harness h = new Harness();
        DurableMetadata explicit = h.correlation("E");
        DurableMetadata recorded = h.correlation("X");
        DurableMetadata instance = h.correlation("I").merge(tenant("T"), DurableMetadata.MergePolicy.FAIL_ON_CONFLICT);

        // When the drive runs with explicit, recorded and instance; recorded and instance; and instance only
        Effective withAll = h.bind(vertx, explicit, recorded, instance);
        Effective withRecorded = h.bind(vertx, null, recorded, instance);
        Effective withInstance = h.bind(vertx, null, null, instance);

        // Then the effective correlation is E, X and I, the instance fills tenant T each time, ambient never appears
        assertThat(withAll.correlation())
                .as("explicit, recorded, instance: corr")
                .contains("E");
        assertThat(withAll.tenant()).as("explicit, recorded, instance: tenant").contains("T");
        assertThat(withRecorded.correlation()).as("recorded, instance: corr").contains("X");
        assertThat(withRecorded.tenant()).as("recorded, instance: tenant").contains("T");
        assertThat(withInstance.correlation()).as("instance only: corr").contains("I");
        assertThat(withInstance.tenant()).as("instance only: tenant").contains("T");

        // And with no explicit carrier, recorded context or instance carrier nothing is bound and nothing is seeded
        Harness dark = new Harness();
        Effective none = dark.bind(vertx, null, null, null);
        assertThat(none.correlation()).as("no source: ambient left as it was").contains("A");
        assertThat(none.tenant()).as("no source: ambient left as it was").contains("AMBIENT");
        verify(dark.scope, never()).installDurable(any(), any());
        verify(dark.scope, never()).installDurableAndRun(any(), any(), any());
    }

    @Test
    @DisplayName(
            "an executor bind fails closed when a security context or a deferred-execution origin is already bound")
    void executorBindFailsClosedWhenSecurityContextOrOriginIsBound(Vertx vertx) throws Exception {
        // Given recorded context {corr:X}, and three contexts: one with a security context bound, one with a
        // deferred-execution origin bound and a clean one
        DeferredExecutionOrigin origin = DeferredExecutionOrigin.of("delayed-job", "order-42");
        Harness onSecurity = new Harness();
        Harness onOrigin = new Harness();
        Harness onClean = new Harness();

        // When the executor entry point binds and runs a counting operation on each
        BindOutcome securityCase =
                bindOn(vertx, onSecurity, h -> h.holder.bind(SecurityContext.class, securityContext()));
        BindOutcome originCase = bindOn(vertx, onOrigin, h -> h.holder.bind(DeferredExecutionOrigin.class, origin));
        BindOutcome cleanCase = bindOn(vertx, onClean, h -> {});

        // Then on the security context the future fails naming instance and sequence, the operation never ran, no
        // scope was installed and the security context is still the one that was bound
        assertThat(securityCase.failure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(securityCase.instanceId())
                .hasMessageContaining(String.valueOf(COMMAND_SEQ))
                .hasMessageNotContaining("user-77");
        assertThat(securityCase.invocations()).isZero();
        assertThat(securityCase.securityAfter()).isPresent();
        assertThat(securityCase.during().correlation()).as("nothing installed").isEmpty();
        verify(onSecurity.scope, never()).installDurable(any(), any());
        verify(onSecurity.scope, never()).installDurableAndRun(any(), any(), any());

        // And likewise on the origin, which is still the bound one
        assertThat(originCase.failure())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(originCase.instanceId())
                .hasMessageContaining(String.valueOf(COMMAND_SEQ))
                .hasMessageNotContaining("order-42");
        assertThat(originCase.invocations()).isZero();
        assertThat(originCase.originAfter()).contains(origin);
        assertThat(originCase.during().correlation()).as("nothing installed").isEmpty();
        verify(onOrigin.scope, never()).installDurable(any(), any());
        verify(onOrigin.scope, never()).installDurableAndRun(any(), any(), any());

        // And on the clean context the operation ran once with corr X
        assertThat(cleanCase.failure()).isNull();
        assertThat(cleanCase.invocations()).isEqualTo(1);
        assertThat(cleanCase.during().correlation()).contains("X");
    }

    @Test
    @DisplayName(
            "an executor bind strips the identity snapshot from the explicit carrier, the recorded context and the instance")
    void executorBindStripsTheIdentitySnapshotFromEverySource(Vertx vertx) throws Exception {
        // Given each source in turn carrying an identity snapshot next to tenant T
        Harness h = new Harness();
        DurableMetadata withSnapshot =
                tenant("T").merge(identity("user-77"), DurableMetadata.MergePolicy.FAIL_ON_CONFLICT);

        // When the drive binds it as the explicit carrier, as the recorded context and as the instance carrier
        Effective viaExplicit = h.bind(vertx, withSnapshot, null, null);
        Effective viaRecorded = h.bind(vertx, null, withSnapshot, null);
        Effective viaInstance = h.bind(vertx, null, null, withSnapshot);

        // Then tenant T is bound and no identity is
        for (Effective seen : java.util.List.of(viaExplicit, viaRecorded, viaInstance)) {
            assertThat(seen.tenant()).contains("T");
            assertThat(seen.identity()).isEmpty();
        }
    }

    /** What an executor bind did on one context. */
    private record BindOutcome(
            @Nullable Throwable failure,
            int invocations,
            Effective during,
            Optional<SecurityContext> securityAfter,
            Optional<DeferredExecutionOrigin> originAfter,
            String instanceId) {}

    /** Binds recorded context {corr:X} through the executor entry point on a context prepared by {@code prepare}. */
    private static BindOutcome bindOn(Vertx vertx, Harness h, java.util.function.Consumer<Harness> prepare)
            throws Exception {
        CompletableFuture<BindOutcome> result = new CompletableFuture<>();
        AtomicInteger invocations = new AtomicInteger();
        WorkflowInstance instance = instanceWithMetadata(null);
        runOnDuplicated(vertx, v -> {
            prepare.accept(h);
            Effective[] during = {new Effective(Optional.empty(), Optional.empty(), Optional.empty())};
            Future<Effective> bound;
            try {
                bound = h.binder.withRecordedBase(instance, COMMAND_SEQ, null, h.correlation("X"), () -> {
                    invocations.incrementAndGet();
                    during[0] = h.observed();
                    return Future.succeededFuture(during[0]);
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
                return;
            }
            bound.onComplete(ar -> result.complete(new BindOutcome(
                    ar.cause(),
                    invocations.get(),
                    ar.succeeded() ? ar.result() : h.observed(),
                    h.holder.current(SecurityContext.class),
                    h.holder.current(DeferredExecutionOrigin.class),
                    instance.id().value().toString())));
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    // --- Null-object noop() ---

    @Test
    @DisplayName("noop() runs the drive unbound even with non-null instance metadata and an explicit carrier")
    void noop_runsDriveUnbound_evenWithNonNullMetadataAndExplicitCarrier(Vertx vertx, VertxTestContext ctx) {
        WorkflowContextBinder binder = WorkflowContextBinder.noop();

        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);
        DurableMetadata explicitCarrier =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "CARRIER"));

        runOnDuplicated(vertx, v -> binder.withBound(instance, explicitCarrier, () -> Future.succeededFuture("driven"))
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(ar.result())
                            .as("noop() must run the drive unconditionally, ignoring both instance"
                                    + " metadata and the explicit carrier")
                            .isEqualTo("driven");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("noop() always returns the same shared instance")
    void noop_returnsSameSharedInstance() {
        assertThat(WorkflowContextBinder.noop()).isSameAs(WorkflowContextBinder.noop());
    }

    // --- Bind gate ---

    @Test
    @DisplayName("null instance metadata and null explicit carrier skips the bind entirely")
    void withBound_nullInstanceMetadataAndNullExplicitCarrier_skipsBindEntirely(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(TenantCtxCodec.encoder()), Set.of(TenantCtxCodec.decoder()));
        DurableContextPropagator propagator =
                spy(new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder)));
        InboundExecutionContextScope inboundExecScope =
                spy(new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of()));
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        WorkflowInstance instance = instanceWithMetadata(null);

        runOnDuplicated(vertx, v -> binder.withBound(instance, null, () -> Future.succeededFuture("driven"))
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(ar.result()).isEqualTo("driven");
                    verify(inboundExecScope, never()).installDurable(any(), any());
                    ctx.completeNow();
                })));
    }

    // --- Base/fill computation ---

    @Test
    @DisplayName("non-null instance metadata with null explicit carrier: empty base fully filled from instance")
    void withBound_nonNullInstanceMetadataNullExplicitCarrier_capturesAmbientThenFillsFromInstance(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        InboundExecutionContextScope inboundExecScope = scopeWithTenantOnly(holder);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        runOnDuplicated(vertx, v -> binder.withBound(
                        instance, null, () -> Future.succeededFuture(holder.current(TenantCtx.class)))
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(ar.result()).contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("ambient bound and instance metadata present: ambient (base) wins per namespace")
    void withBound_ambientPresentAndInstanceMetadataPresent_ambientWinsPerNamespace(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        InboundExecutionContextScope inboundExecScope = scopeWithTenantOnly(holder);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "INSTANCE"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        runOnDuplicated(vertx, v -> {
            holder.bind(TenantCtx.class, new TenantCtx("AMBIENT"));
            binder.withBound(instance, null, () -> Future.succeededFuture(holder.current(TenantCtx.class)))
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        assertThat(ar.result()).contains(new TenantCtx("AMBIENT"));
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("explicit carrier wins over ambient and instance; instance fills namespaces absent from carrier")
    void withBound_explicitCarrierProvided_carrierWinsOverAmbientAndInstanceFillsAbsentNamespaces(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                Set.of(TenantCtxCodec.encoder(), new CorrelationContextDurableEncoder()),
                Set.of(TenantCtxCodec.decoder(), new CorrelationContextDurableDecoder(correlationFactory)));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        InboundExecutionContextScope inboundExecScope =
                new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of());
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        DurableMetadata carrier =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "CARRIER"));
        CorrelationContext instanceCorrelation = correlationFactory.create(
                new CorrelationIdentifier("req-instance", "test"), new CorrelationIdentifier("corr-instance", "test"));
        DurableMetadata instanceMetadata = new CorrelationContextDurableEncoder()
                .encode(instanceCorrelation, new dev.vertique.core.context.DurableEncodeContext("test"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        runOnDuplicated(vertx, v -> {
            holder.bind(TenantCtx.class, new TenantCtx("AMBIENT"));
            binder.withBound(
                            instance,
                            carrier,
                            () -> Future.succeededFuture(new Object[] {
                                holder.current(TenantCtx.class), holder.current(CorrelationContext.class)
                            }))
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        Object[] result = ar.result();
                        @SuppressWarnings("unchecked")
                        Optional<TenantCtx> tenant = (Optional<TenantCtx>) result[0];
                        @SuppressWarnings("unchecked")
                        Optional<CorrelationContext> correlation = (Optional<CorrelationContext>) result[1];
                        assertThat(tenant)
                                .as("explicit carrier must win over both ambient and instance for the shared namespace")
                                .contains(new TenantCtx("CARRIER"));
                        assertThat(correlation)
                                .as("instance metadata must fill a namespace absent from the carrier")
                                .isPresent();
                        assertThat(correlation.get().correlationId().value()).isEqualTo("corr-instance");
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("empty base and non-null instance metadata: instance context bound in full")
    void withBound_emptyBaseNonNullInstanceMetadata_bindsInstanceContextInFull(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                Set.of(TenantCtxCodec.encoder(), new CorrelationContextDurableEncoder()),
                Set.of(TenantCtxCodec.decoder(), new CorrelationContextDurableDecoder(correlationFactory)));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        InboundExecutionContextScope inboundExecScope =
                new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of());
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        DurableMetadata tenantPart =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        CorrelationContext instanceCorrelation = correlationFactory.create(
                new CorrelationIdentifier("req-instance", "test"), new CorrelationIdentifier("corr-instance", "test"));
        DurableMetadata correlationPart = new CorrelationContextDurableEncoder()
                .encode(instanceCorrelation, new dev.vertique.core.context.DurableEncodeContext("test"));
        DurableMetadata instanceMetadata =
                tenantPart.merge(correlationPart, DurableMetadata.MergePolicy.FAIL_ON_CONFLICT);
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        runOnDuplicated(vertx, v -> binder.withBound(
                        instance,
                        null,
                        () -> Future.succeededFuture(
                                new Object[] {holder.current(TenantCtx.class), holder.current(CorrelationContext.class)
                                }))
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    Object[] result = ar.result();
                    @SuppressWarnings("unchecked")
                    Optional<TenantCtx> tenant = (Optional<TenantCtx>) result[0];
                    @SuppressWarnings("unchecked")
                    Optional<CorrelationContext> correlation = (Optional<CorrelationContext>) result[1];
                    assertThat(tenant).contains(new TenantCtx("T1"));
                    assertThat(correlation).isPresent();
                    assertThat(correlation.get().correlationId().value()).isEqualTo("corr-instance");
                    ctx.completeNow();
                })));
    }

    // --- Fill-before-seeding ordering (AC-9) ---

    @Test
    @DisplayName("instance fill happens before initializers run, so instance correlation beats seeding")
    void withBound_fillHappensBeforeInitializersRun_soPriorNamespaceBeatsSeeding(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                Set.of(new CorrelationContextDurableEncoder()),
                Set.of(new CorrelationContextDurableDecoder(correlationFactory)));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        Set<InboundContextInitializer> initializers = Set.of(new CorrelationContextSeeder(holder, correlationFactory));
        InboundExecutionContextScope inboundExecScope =
                new InboundExecutionContextScope(new InboundDispatchScope(), propagator, initializers);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        CorrelationContext instanceCorrelation = correlationFactory.create(
                new CorrelationIdentifier("req-A", "test"), new CorrelationIdentifier("corr-A", "test"));
        DurableMetadata instanceMetadata = new CorrelationContextDurableEncoder()
                .encode(instanceCorrelation, new dev.vertique.core.context.DurableEncodeContext("test"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        runOnDuplicated(vertx, v -> binder.withBound(
                        instance, null, () -> Future.succeededFuture(holder.current(CorrelationContext.class)))
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    Optional<CorrelationContext> observed = ar.result();
                    assertThat(observed)
                            .as(
                                    "instance fill must occur before the seeder runs, so it observes a non-absent namespace")
                            .isPresent();
                    assertThat(observed.get().correlationId().value())
                            .as("the seeder must not overwrite the pre-filled instance correlation")
                            .isEqualTo("corr-A");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("no correlation fill and no ambient correlation: initializer seeds a fresh correlation")
    void withBound_fillAbsentAndNoAmbientCorrelation_initializerSeedsFreshCorrelation(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                Set.of(TenantCtxCodec.encoder(), new CorrelationContextDurableEncoder()),
                Set.of(TenantCtxCodec.decoder(), new CorrelationContextDurableDecoder(correlationFactory)));
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));
        Set<InboundContextInitializer> initializers = Set.of(new CorrelationContextSeeder(holder, correlationFactory));
        InboundExecutionContextScope inboundExecScope =
                new InboundExecutionContextScope(new InboundDispatchScope(), propagator, initializers);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        // Gate opened via a non-correlation instance namespace (tenant); no correlation namespace
        // present on the instance and no ambient correlation bound.
        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        runOnDuplicated(vertx, v -> binder.withBound(
                        instance, null, () -> Future.succeededFuture(holder.current(CorrelationContext.class)))
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    Optional<CorrelationContext> observed = ar.result();
                    assertThat(observed)
                            .as("the seeder must fire because the effective doc has no correlation namespace")
                            .isPresent();
                    assertThat(observed.get().correlationId().source())
                            .as("a freshly seeded correlation carries the seeded:<boundary> source marker")
                            .startsWith("seeded:");
                    ctx.completeNow();
                })));
    }

    // --- NFR-WF-CTX-008 debug log ---

    @Test
    @DisplayName("a fill that contributes a namespace emits a debug-level log naming it")
    void withBound_fillContributesNamespace_emitsDebugLog(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        InboundExecutionContextScope inboundExecScope = scopeWithTenantOnly(holder);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        Logger logbackLogger = (Logger) org.slf4j.LoggerFactory.getLogger(WorkflowContextBinder.class);
        Level originalLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);

        // Note: runOnDuplicated schedules asynchronously (dup.runOnContext), so the appender must
        // be detached and the level restored inside the completion callback — not in a `finally`
        // block around the scheduling call, which would run before the binder ever logs.
        runOnDuplicated(vertx, v -> binder.withBound(instance, null, () -> Future.succeededFuture("driven"))
                .onComplete(ar -> ctx.verify(() -> {
                    try {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        boolean foundDebugLine = appender.list.stream()
                                .anyMatch(event -> event.getLevel() == Level.DEBUG
                                        && event.getFormattedMessage().contains(TenantCtxCodec.NAMESPACE));
                        assertThat(foundDebugLine)
                                .as("a debug log naming the filled namespace must be emitted (NFR-WF-CTX-008)")
                                .isTrue();
                        ctx.completeNow();
                    } finally {
                        logbackLogger.detachAppender(appender);
                        logbackLogger.setLevel(originalLevel);
                    }
                })));
    }

    // --- Scope lifecycle ---

    @Test
    @DisplayName("the durable scope closes via Future.eventually even when the drive fails")
    void withBound_scopeClosedViaFutureEventually_evenOnDriveFailure(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        InboundExecutionContextScope inboundExecScope = scopeWithTenantOnly(holder);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);
        RuntimeException driveFailure = new RuntimeException("drive boom");

        runOnDuplicated(vertx, v -> {
            // No ambient TenantCtx bound before the call, so a post-close absence proves the scope
            // actually restored the pre-bind holder state (rather than leaking the T1 binding).
            binder.withBound(instance, null, () -> Future.<String>failedFuture(driveFailure))
                    .onComplete(ar -> ctx.verify(() -> {
                        assertThat(ar.failed()).isTrue();
                        assertThat(ar.cause()).isSameAs(driveFailure);
                        assertThat(holder.current(TenantCtx.class))
                                .as("the durable scope must close and restore prior (absent) holder state")
                                .isEmpty();
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("the durable scope closes when the drive supplier throws synchronously (CORE-001 sync-throw lesson)")
    void withBound_driveThrowsSynchronously_scopeStillClosed(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        InboundExecutionContextScope inboundExecScope = scopeWithTenantOnly(holder);
        WorkflowContextBinder binder = new WorkflowContextBinder(propagator, inboundExecScope);

        DurableMetadata instanceMetadata =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T1"));
        WorkflowInstance instance = instanceWithMetadata(instanceMetadata);
        RuntimeException syncThrow = new RuntimeException("drive threw synchronously");

        runOnDuplicated(vertx, v -> {
            // No ambient TenantCtx bound before the call, so a post-close absence proves the scope
            // actually restored the pre-bind holder state (rather than leaking the T1 binding) even
            // though the drive supplier never returned a Future at all — it threw before returning.
            Future<String> result;
            try {
                result = binder.withBound(instance, null, () -> {
                    throw syncThrow;
                });
            } catch (RuntimeException e) {
                ctx.failNow(new AssertionError(
                        "withBound must convert a synchronous drive-supplier throw into a failed Future, not"
                                + " propagate it as a thrown exception",
                        e));
                return;
            }
            result.onComplete(ar -> ctx.verify(() -> {
                assertThat(ar.failed()).isTrue();
                assertThat(ar.cause()).isSameAs(syncThrow);
                assertThat(holder.current(TenantCtx.class))
                        .as("the durable scope must close and restore prior (absent) holder state even on a"
                                + " synchronous drive-supplier throw")
                        .isEmpty();
                ctx.completeNow();
            }));
        });
    }
}
