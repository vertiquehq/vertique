// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import io.vertx.core.Future;

/**
 * SPI for delivering outbox entries to a specific category of destination.
 *
 * <p>Implementations are registered via Dagger multibinding and selected by the relay
 * based on the {@link DestinationType} of each outbox entry. One implementation must be
 * provided per supported destination type.
 *
 * <p>Implementations must be non-blocking and return a {@link Future} that completes with
 * a {@link OutboxPublishResult} describing the delivery outcome. Throwing an exception is
 * treated as a retryable failure.
 *
 * <p>Example implementation for a custom destination type:
 * <pre>{@code
 * @Singleton
 * public class ServiceDestinationHandler implements OutboxDestinationHandler {
 *
 *     private final ServiceResolver resolver;
 *
 *     @Inject
 *     ServiceDestinationHandler(ServiceResolver resolver) {
 *         this.resolver = resolver;
 *     }
 *
 *     @Override
 *     public DestinationType destinationType() {
 *         return DestinationType.SERVICE;
 *     }
 *
 *     @Override
 *     public ClaimScope claimScope() {
 *         // Only claim rows whose destination is handled by this node's resolver.
 *         return ClaimScope.destinations(resolver::supportedTargetIds);
 *     }
 *
 *     @Override
 *     public Future<OutboxPublishResult> publish(OutboxEnvelope envelope) {
 *         return serviceProxy.dispatch(envelope.destination(), envelope.payload())
 *             .map(ignored -> OutboxPublishResult.success())
 *             .recover(err -> Future.succeededFuture(OutboxPublishResult.retryable(err.getMessage(), err)));
 *     }
 * }
 * }</pre>
 */
public interface OutboxDestinationHandler {

    /**
     * Returns the destination type that this handler is responsible for delivering.
     *
     * <p>The relay uses this value to select the appropriate handler for each outbox entry.
     *
     * @return the {@link DestinationType} this handler targets
     */
    DestinationType destinationType();

    /**
     * Declares which outbox rows of this handler's {@link #destinationType()} this node is
     * eligible to claim and deliver.
     *
     * <p>This method is mandatory — there is no default. Every implementation must explicitly
     * return either {@link ClaimScope#all()} or {@link ClaimScope#destinations(java.util.function.Supplier)}.
     * The design is fail-closed: a missing or incorrect scope prevents spurious claims rather than
     * causing silent over-claiming.
     *
     * <ul>
     *   <li>Return {@link ClaimScope#all()} for globally deliverable destination types (e.g. Kafka
     *       topics) where every relay node can handle every row of the type.</li>
     *   <li>Return {@link ClaimScope#destinations(java.util.function.Supplier) ClaimScope.destinations(resolver::supportedTargetIds)}
     *       for resolver-scoped types where only a subset of destinations are reachable from this
     *       node.</li>
     * </ul>
     *
     * @return the claim scope that controls which rows of {@link #destinationType()} this node
     *         may claim; never {@code null}
     */
    ClaimScope claimScope();

    /**
     * Attempts to deliver the given outbox envelope to its destination.
     *
     * <p>The returned {@link Future} must always complete successfully with an
     * {@link OutboxPublishResult} — never with a failed future. The result indicates whether
     * delivery succeeded, should be retried, or has permanently failed.
     *
     * @param envelope the outbox entry to deliver, including payload and metadata
     * @return a {@link Future} that completes with the delivery outcome
     */
    Future<OutboxPublishResult> publish(OutboxEnvelope envelope);
}
