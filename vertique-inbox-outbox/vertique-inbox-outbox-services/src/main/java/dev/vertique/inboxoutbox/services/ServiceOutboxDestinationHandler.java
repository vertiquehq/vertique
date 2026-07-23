// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.services;

import dev.vertique.context.DispatchEnvelopeBuilder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.Result;
import dev.vertique.inboxoutbox.ClaimScope;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxDestinationHandler;
import dev.vertique.inboxoutbox.OutboxEnvelope;
import dev.vertique.inboxoutbox.OutboxPermanentFailure;
import dev.vertique.inboxoutbox.OutboxPublishResult;
import dev.vertique.inboxoutbox.OutboxRelayControl;
import dev.vertique.inboxoutbox.PayloadCodec;
import dev.vertique.inboxoutbox.TransactionalMessageContext;
import dev.vertique.services.ResolvedServiceTarget;
import dev.vertique.services.ServiceRequestSender;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link OutboxDestinationHandler} implementation for {@link DestinationType#SERVICE} outbox
 * entries.
 *
 * <p>At relay time this handler resolves the stable target id stored in the outbox row back to a
 * live event bus address via {@link ServiceTargetResolver}, then forwards the payload to the
 * service handler through {@link ServiceRequestSender}.
 *
 * <p>Before constructing the outgoing {@link DispatchEnvelope} the handler decodes the outbox
 * headers into an FQCN-keyed dispatch-context map via
 * {@link DurableContextPropagator#decodeToDispatchContext} with the {@code "outbox-service"}
 * boundary (FR-CTX-176) and merges the decoded values directly into the caller-override map. The
 * holder is not written: the relay runs on the verticle's deployment context (not a duplicated
 * Vert.x context), so a holder write would fail the substrate's duplicated-context guard. The
 * decoded values — including any {@code DurablePropagationMetadata} — ride alongside the
 * payload in the outgoing envelope; the receiving {@code ServiceMethodInvoker} installs them
 * into the holder via the standard {@code InboundDispatchScope} path, where the event-bus
 * consumer dispatch is already on a duplicated context.
 *
 * <p><strong>Per-row carrier reproduction (PRD identity-002 §14.6/A9, F3b).</strong> The decode
 * above threads a {@link DurableCarrierDescriptor} reproduced from the row's first-class
 * {@code carrier_id} column — never from the app-writable {@link OutboxEnvelope#metadata()} —
 * projected onto the envelope as {@link OutboxRelayControl#carrierId()}. A durable identity snapshot
 * decodes verified only when its signed carrier matches this reproduced carrier for the SAME row; a
 * snapshot signed for a different row's carrier fails closed. The target kind
 * {@value #OUTBOX_RELAY_TARGET_KIND} matches the kind {@code DefaultOutboxService} signs on the
 * produce side and the {@link DeferredExecutionOrigin#kind()} bound below (F7a).
 *
 * <p>A {@link TransactionalMessageContext} is also added to the caller-override map so service
 * handlers can inspect message metadata (entry id, event type, aggregate identity, headers)
 * without coupling to transport details.
 *
 * <p>Failure classification:
 *
 * <ul>
 *   <li>If the target id cannot be resolved, the result is {@link OutboxPublishResult#unresolvable}
 *       — the entry is held and retried after a long delay.
 *   <li>If the handler throws an {@link OutboxPermanentFailure}, the result is
 *       {@link OutboxPublishResult#permanent} — the entry moves to dead-letter immediately.
 *   <li>All other handler failures, including transport errors, are classified as
 *       {@link OutboxPublishResult#retryable}.
 * </ul>
 *
 * <p>Instances are registered via the {@code TransactionalMessagingServiceModule} Dagger module.
 */
@Singleton
public class ServiceOutboxDestinationHandler implements OutboxDestinationHandler {

    private static final String BOUNDARY = dev.vertique.core.context.DispatchBoundary.OUTBOX_SERVICE;

    /**
     * Canonical durable-target kind for the outbox row-carrier (F7a) — matches the target kind
     * {@code DefaultOutboxService} signs on the produce side and the {@code "outbox-relay"} kind
     * bound into {@link DeferredExecutionOrigin} below, so the produce/consume carrier and the
     * origin kind — and any operator {@code identity.snapshot.carriageRequirements} entry keyed on
     * this string — all agree on one canonical value.
     */
    private static final String OUTBOX_RELAY_TARGET_KIND = "outbox-relay";

    private final ServiceTargetResolver resolver;
    private final ServiceRequestSender sender;
    private final DurableContextPropagator propagator;
    private final DispatchEnvelopeBuilder envelopeBuilder;

    /**
     * Creates a new handler.
     *
     * @param resolver        resolver used to translate a stable target id into a runtime service target
     * @param sender          sender used to dispatch the request over the event bus
     * @param propagator      durable context propagator used to decode outbox headers into an
     *                        FQCN-keyed dispatch-context map (no holder write — see class javadoc)
     * @param envelopeBuilder shared envelope builder; the decoded dispatch-context map is merged
     *                        into the envelope's caller-override map
     */
    @Inject
    ServiceOutboxDestinationHandler(
            ServiceTargetResolver resolver,
            ServiceRequestSender sender,
            DurableContextPropagator propagator,
            DispatchEnvelopeBuilder envelopeBuilder) {
        this.resolver = resolver;
        this.sender = sender;
        this.propagator = propagator;
        this.envelopeBuilder = envelopeBuilder;
    }

    @Override
    public DestinationType destinationType() {
        return DestinationType.SERVICE;
    }

    /**
     * Declares that this node may only claim {@code SERVICE} outbox rows whose {@code destination}
     * column value is a service target id locally registered with the injected
     * {@link ServiceTargetResolver}.
     *
     * <p>The supplier delegates to {@link ServiceTargetResolver#supportedTargetIds()}, which is
     * evaluated lazily at each claim cycle so it always reflects the targets registered at the time
     * of the claim. Service targets are node-local — only the node whose resolver knows about a
     * given target id can resolve and dispatch the request — so using {@link ClaimScope#all()} here
     * would cause every relay node to compete for rows it cannot deliver.
     *
     * @return a {@link ClaimScope.Destinations} scope backed by the resolver's supported target ids
     */
    @Override
    public ClaimScope claimScope() {
        return ClaimScope.destinations(resolver::supportedTargetIds);
    }

    /**
     * Delivers the outbox envelope to the resolved service handler.
     *
     * @param outboxEnvelope the outbox entry to deliver
     * @return a {@link Future} that always completes successfully with an {@link OutboxPublishResult}
     */
    @Override
    public Future<OutboxPublishResult> publish(OutboxEnvelope outboxEnvelope) {
        ResolvedServiceTarget target;
        try {
            target = resolver.resolve(outboxEnvelope.destination());
        } catch (IllegalArgumentException e) {
            return Future.succeededFuture(
                    OutboxPublishResult.unresolvable("Unknown service target: " + outboxEnvelope.destination()));
        }

        if (target.meta().oneWay()) {
            return Future.succeededFuture(OutboxPublishResult.permanent(
                    "@OneWay operation cannot be used as outbox destination: " + outboxEnvelope.destination(), null));
        }

        TransactionalMessageContext txCtx = new TransactionalMessageContext(
                outboxEnvelope.entryId(),
                outboxEnvelope.eventType(),
                outboxEnvelope.aggregateType(),
                outboxEnvelope.aggregateId(),
                outboxEnvelope.headers() != null ? outboxEnvelope.headers() : Map.of());

        Class<?> payloadType = target.meta().payloadType();
        Object deserializedPayload;
        if (outboxEnvelope.payload() instanceof JsonObject stored) {
            deserializedPayload =
                    payloadType != null ? PayloadCodec.decode(stored, payloadType) : PayloadCodec.decode(stored);
        } else {
            deserializedPayload = outboxEnvelope.payload();
        }

        // Decode the durable propagation context from the outbox metadata (not from headers) into
        // an FQCN-keyed dispatch-context map without touching the ContextHolder — the relay's
        // processRecord runs on the verticle's deployment context (not a duplicated context), so a
        // holder write would fail the substrate's duplicated-context guard. The decoded values ride
        // along inside the outgoing dispatch envelope; the receiving ServiceMethodInvoker installs
        // them into the holder there, where the event-bus consumer dispatch is already on a
        // duplicated context (FR-CTX-176).
        //
        // F3b per-row carrier reproduction: the expected carrier is rebuilt from the row's
        // first-class carrier_id column (via OutboxRelayControl, never from the app-writable
        // metadata JSONB) so a durable identity snapshot only verifies when decoded for the SAME
        // row it was signed for.
        Map<String, Object> decodedDurable = expectedCarrier(outboxEnvelope)
                .map(carrier -> propagator.decodeToDispatchContext(
                        outboxEnvelope.metadata().context(), BOUNDARY, carrier))
                .orElseGet(() -> propagator.decodeToDispatchContext(
                        outboxEnvelope.metadata().context(), BOUNDARY));
        Map<String, Object> callerOverrides = new java.util.HashMap<>(decodedDurable.size() + 2);
        callerOverrides.putAll(decodedDurable);
        callerOverrides.put(TransactionalMessageContext.class.getName(), txCtx);
        // Provenance proving this is deferred (outbox relay) execution so the receive-side identity
        // reconstruction initializer may mint a bounded SYSTEM context (W2/A6). Keyed by FQCN like
        // the TransactionalMessageContext entry; reinstated before InboundContextInitializers run.
        // The .of factory sanitizes (blank -> OUTBOX_RELAY_TARGET_KIND, length, control chars), so
        // the strict ctor cannot throw synchronously out of this async-contract path.
        callerOverrides.put(
                DeferredExecutionOrigin.class.getName(),
                DeferredExecutionOrigin.of(OUTBOX_RELAY_TARGET_KIND, txCtx.eventType()));

        DispatchEnvelope<?> dispatchEnvelope = envelopeBuilder.build(deserializedPayload, callerOverrides, BOUNDARY);

        return sender.send(target, dispatchEnvelope)
                .map(this::classifyResult)
                .recover(err -> Future.succeededFuture(OutboxPublishResult.retryable(err.getMessage(), err)));
    }

    // --- Helpers ---

    /**
     * Reproduces the expected durable row-carrier for the receiving dispatch from the row's
     * first-class {@code carrier_id} column (PRD identity-002 §14.6/A9, F3b).
     *
     * <p>The carrier id is read from {@link OutboxRelayControl#carrierId()} — projected by
     * {@code OutboxRelay} from the outbox row's {@code carrier_id} column, never from the
     * app-writable {@link OutboxEnvelope#metadata()} — so the carrier this handler checks against
     * cannot be forged by an attacker who only controls the {@code metadata} JSONB. Both
     * {@link DurableCarrierDescriptor#carrierId()} and the {@link DurableTarget#address()} are set to
     * the same carrier id string, mirroring the produce side ({@code DefaultOutboxService}) exactly so
     * the two carriers compare equal only for the same row.
     *
     * <p>Returns {@link Optional#empty()} only when the envelope carries no {@link OutboxRelayControl}
     * at all — a shape that cannot occur for a real relay-built envelope (the relay always projects
     * one), but which a hand-built test fixture may omit; the caller falls back to the carrier-less
     * decode overload in that case, preserving the F3a fail-closed sentinel behavior rather than
     * throwing.
     *
     * @param outboxEnvelope the outbox entry being delivered
     * @return the reproduced expected carrier, or empty when the envelope carries no relay control
     */
    private static Optional<DurableCarrierDescriptor> expectedCarrier(OutboxEnvelope outboxEnvelope) {
        return outboxEnvelope
                .metadata()
                .delivery()
                .outbox()
                .map(OutboxRelayControl::carrierId)
                .map(ServiceOutboxDestinationHandler::toCarrierDescriptor);
    }

    /**
     * Builds the {@link DurableCarrierDescriptor} for a reproduced carrier id: the carrier id and the
     * target address are both the carrier id string, and the target kind is
     * {@value #OUTBOX_RELAY_TARGET_KIND} — matching {@code DefaultOutboxService}'s produce-side
     * shape exactly.
     *
     * @param carrierId the row's first-class carrier id
     * @return the reconstructed carrier descriptor
     */
    private static DurableCarrierDescriptor toCarrierDescriptor(UUID carrierId) {
        return new DurableCarrierDescriptor(
                carrierId.toString(),
                new DurableTarget(OUTBOX_RELAY_TARGET_KIND, carrierId.toString(), Optional.empty()));
    }

    private OutboxPublishResult classifyResult(Result<?> result) {
        if (result.isSuccess()) {
            return OutboxPublishResult.success();
        }
        Throwable cause = result.cause();
        if (cause instanceof OutboxPermanentFailure) {
            return OutboxPublishResult.permanent(cause.getMessage(), cause);
        }
        return OutboxPublishResult.retryable(cause != null ? cause.getMessage() : "handler returned failure", cause);
    }
}
