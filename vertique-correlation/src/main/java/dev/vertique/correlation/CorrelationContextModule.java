// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ServiceDispatchCodecs;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.InboundContextInitializer;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationContextSnapshot;
import dev.vertique.core.correlation.CorrelationIdGenerator;

/**
 * Dagger module that wires the {@code CorrelationContext} runtime.
 *
 * <p>{@link CorrelationContextFactory} and {@link CorrelationContextMutator} are resolved via
 * their {@code @Inject} constructors and {@code @Singleton} annotations — no explicit provider
 * method is required. Likewise, {@link CorrelationContextValueAdapter} is discovered by the
 * substrate via Java {@link java.util.ServiceLoader} from
 * {@code META-INF/services/dev.vertique.core.context.ContextValueAdapter}, not via Dagger.
 *
 * <p>Module contributions:
 * <ul>
 *   <li>{@code @BindsOptionalOf CorrelationIdGenerator} — applications can override the
 *       framework default ({@link Uuid4CorrelationIdGenerator}) without ambiguity, matching
 *       the convention used elsewhere (see {@code AuthModule.optionalSecurityClaimMapper}).
 *       Apps opt in by providing {@code @Provides @Singleton CorrelationIdGenerator ...} in
 *       their {@code AppModule}.</li>
 *   <li>{@code @Provides @IntoSet ServiceDispatchContextEncoder/Decoder} pair for
 *       {@link CorrelationContext} via the {@code ServiceDispatchCodecs.snapshotEncoder/Decoder}
 *       helpers — copies the live snapshot into outgoing
 *       {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext()} and rebuilds a
 *       fresh live context on the receive side. Registered as multibinding contributions so the
 *       substrate's {@code ServiceDispatchContextRegistry} picks them up automatically.</li>
 *   <li>{@code @Provides @IntoSet} for the bespoke {@link CorrelationContextDurableEncoder} and
 *       {@link CorrelationContextDurableDecoder} into the durable multibinding set (Context
 *       contribution model level 4 — bespoke is justified by {@code durableSafe} filtering,
 *       schemaVersion enforcement, and header re-validation on decode).</li>
 *   <li>{@code @Provides @IntoSet InboundContextInitializer} for {@link CorrelationContextSeeder}
 *       so the substrate's {@code InboundExecutionContextScope} seeds a fresh
 *       {@link CorrelationContext} on every inbound boundary that arrives without one
 *       (FR-COR-125).</li>
 * </ul>
 *
 * <p>REST-specific ingress wiring (middleware, ProtocolCorrelationSpec/Contributor multibinds,
 * config) lives in {@code vertique-rest-core}'s {@code CorrelationIngressModule}.
 */
@Module
public abstract class CorrelationContextModule {

    /**
     * Declares an optional binding for {@link CorrelationIdGenerator}. When the application
     * graph supplies a concrete binding, {@link CorrelationContextFactory} sees it via
     * {@code Optional<CorrelationIdGenerator>}; otherwise the factory falls back to
     * {@link Uuid4CorrelationIdGenerator#INSTANCE}.
     *
     * @return the optional binding declaration (never invoked directly)
     */
    @BindsOptionalOf
    abstract CorrelationIdGenerator optionalCorrelationIdGenerator();

    /**
     * Declares an optional binding for {@link TraceReferenceResolver}. When the application graph
     * supplies a concrete binding (e.g. from the OpenTelemetry integration module), the REST
     * ingress middleware sees it via {@code Optional<TraceReferenceResolver>} and calls
     * {@link CorrelationContextMutator#setTrace} with the resolved {@link dev.vertique.core.correlation.TraceReference}.
     * When absent (no tracer wired) the middleware skips the trace enrichment step entirely.
     *
     * <p>At most one implementation may be on the graph — tracing is OpenTelemetry-only (see
     * ADR-0098 / PRD D3).
     *
     * @return the optional binding declaration (never invoked directly)
     */
    @BindsOptionalOf
    abstract TraceReferenceResolver optionalTraceReferenceResolver();

    // --- Service-dispatch propagation (Context contribution model level 3, helper-based) ---

    /**
     * Service-dispatch encoder for {@link CorrelationContext}, built via
     * {@link ServiceDispatchCodecs#snapshotEncoder}. The live mutable context must not cross the
     * dispatch boundary; the helper snapshots it at send time and returns an immutable
     * {@link CorrelationContextSnapshot} which travels inside
     * {@code DispatchMetadata.dispatchContext()} under {@code CorrelationContext.class.getName()}.
     *
     * @return the service-dispatch encoder contribution
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextEncoder<?> correlationServiceDispatchEncoder() {
        return ServiceDispatchCodecs.snapshotEncoder(CorrelationContext.class, CorrelationContext::snapshot);
    }

    /**
     * Service-dispatch decoder for {@link CorrelationContext}, built via
     * {@link ServiceDispatchCodecs#snapshotDecoder}. Restores a fresh
     * {@link MutableCorrelationContext} from the incoming {@link CorrelationContextSnapshot} on
     * the receive side; the rebuild path goes through {@link CorrelationContextFactory#fromSnapshot}.
     *
     * @param factory the singleton factory used to rebuild the live context
     * @return the service-dispatch decoder contribution
     */
    @Provides
    @IntoSet
    static ServiceDispatchContextDecoder<?> correlationServiceDispatchDecoder(CorrelationContextFactory factory) {
        return ServiceDispatchCodecs.snapshotDecoder(
                CorrelationContext.class, CorrelationContextSnapshot.class, factory::fromSnapshot);
    }

    // --- Durable propagation (Context contribution model level 4 — bespoke encoder/decoder) ---

    /**
     * Durable metadata encoder for {@link CorrelationContext}. Justified as bespoke (rather than
     * the generic {@code DurableJsonContextCodecs} helper) because it must filter the session
     * block and protocol-correlation list by their {@code durableSafe} flags and stamp the
     * envelope's {@code schemaVersion} (FR-COR-143 / FR-COR-144 / FR-COR-148).
     *
     * @param impl the singleton encoder
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static DurableContextMetadataEncoder<?> correlationDurableEncoder(CorrelationContextDurableEncoder impl) {
        return impl;
    }

    /**
     * Durable metadata decoder for {@link CorrelationContext}. Pairs with
     * {@link CorrelationContextDurableEncoder}; enforces schemaVersion and re-validates protocol
     * header names/values via {@code CorrelationHeaderValidator} so a corrupted or hostile
     * upstream cannot smuggle invalid values into the holder.
     *
     * @param impl the singleton decoder
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static DurableContextMetadataDecoder<?> correlationDurableDecoder(CorrelationContextDurableDecoder impl) {
        return impl;
    }

    // --- First-ingress seed (Context contribution model level 5) ---

    /**
     * First-ingress {@link InboundContextInitializer} that mints a fresh
     * {@link CorrelationContext} when no upstream-decoded value is present (FR-COR-125).
     * {@code InboundExecutionContextScope} invokes registered initializers after the inbound
     * install step; if a decoder already produced a context, this seeder returns
     * {@code ContextScopes.noop()}.
     *
     * @param impl the singleton seeder
     * @return the multibinding contribution
     */
    @Provides
    @IntoSet
    static InboundContextInitializer correlationContextSeeder(CorrelationContextSeeder impl) {
        return impl;
    }
}
