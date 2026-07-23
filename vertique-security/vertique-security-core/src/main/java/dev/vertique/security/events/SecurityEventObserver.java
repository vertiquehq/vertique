// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import io.vertx.core.Future;

/**
 * Observer SPI for security lifecycle events emitted by the Vertique framework.
 *
 * <p>All methods are {@code default} returning {@link Future#succeededFuture()} so implementations
 * opt in only to the event types they care about without having to stub the rest.
 *
 * <p><b>Naming rationale:</b> {@link #onCredentialAccepted(CredentialAcceptedEvent)} and
 * {@link #onCredentialRejected(CredentialRejectedEvent)} are request-ingress credential-verification
 * events, not interactive login events. {@code CredentialAcceptedEvent} fires when the framework
 * accepts verified inbound authentication evidence; {@code CredentialRejectedEvent} fires when the
 * framework rejects presented credential material before identity resolution. Login-flow events
 * (session creation, MFA challenges, account lockouts) are reserved for a future auth-server/login
 * feature and will be modelled as separate event types.
 *
 * <p><b>Failure isolation:</b> The emitter invokes each registered observer asynchronously and
 * isolates failures — one observer's failure MUST NOT prevent other observers from receiving the
 * event AND MUST NOT alter the authentication or authorization result that produced it.
 *
 * <p><b>Ordering:</b> Observer invocation ordering is not guaranteed. Observers must be idempotent
 * and self-ordered if ordering within a downstream pipeline matters.
 *
 * <p><b>Long-running work:</b> All methods run on the Vert.x event loop. Blocking or CPU-intensive
 * work must be offloaded:
 *
 * <pre>{@code
 * @Override
 * public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
 *     return vertx.executeBlocking(() -> {
 *         auditDatabase.insert(event);
 *         return null;
 *     });
 * }
 * }</pre>
 *
 * @see CredentialAcceptedEvent
 * @see CredentialRejectedEvent
 * @see AuthorizationDecisionEvent
 * @see ChannelLifecycleEvent
 * @see IdentitySnapshotDegradationEvent
 * @see CapturedAuthorityActivatedEvent
 */
public interface SecurityEventObserver {

    /**
     * Called when the framework successfully accepts verified inbound credential material and
     * resolves a {@link dev.vertique.security.SecurityIdentity}.
     *
     * @param event the accepted-credential event; never null
     * @return a {@link Future} that completes when the observer has finished processing;
     *         failures are isolated by the emitter and do not affect the authentication result
     */
    default Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
        return Future.succeededFuture();
    }

    /**
     * Called when the framework rejects presented credential material before identity resolution.
     *
     * @param event the rejected-credential event; never null
     * @return a {@link Future} that completes when the observer has finished processing;
     *         failures are isolated by the emitter and do not affect the authentication result
     */
    default Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
        return Future.succeededFuture();
    }

    /**
     * Called after an authorization policy has produced a permit or deny decision.
     *
     * @param event the authorization-decision event; never null
     * @return a {@link Future} that completes when the observer has finished processing;
     *         failures are isolated by the emitter and do not affect the authorization decision
     */
    default Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
        return Future.succeededFuture();
    }

    /**
     * Called when a persistent channel transitions through its lifecycle (opened, identity
     * refreshed, or closed).
     *
     * <p>Use {@code instanceof} pattern-matching or a sealed {@code switch} to dispatch on the
     * specific subtype:
     *
     * <pre>{@code
     * switch (event) {
     *     case ChannelOpenedEvent e            -> handleOpened(e);
     *     case ChannelIdentityRefreshedEvent e -> handleRefreshed(e);
     *     case ChannelClosedEvent e            -> handleClosed(e);
     * }
     * }</pre>
     *
     * @param event the channel-lifecycle event; never null
     * @return a {@link Future} that completes when the observer has finished processing;
     *         failures are isolated by the emitter
     */
    default Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
        return Future.succeededFuture();
    }

    /**
     * Called when a durably-carried identity snapshot is present but cannot be reconstructed
     * (HMAC verification failure, unknown signing key, decode failure, key unavailability, or an
     * incompatible schema version).
     *
     * @param event the identity-snapshot-degradation event; never null
     * @return a {@link Future} that completes when the observer has finished processing;
     *         failures are isolated by the emitter and do not affect the degradation policy outcome
     */
    default Future<Void> onIdentitySnapshotDegradation(IdentitySnapshotDegradationEvent event) {
        return Future.succeededFuture();
    }

    /**
     * Called when the consuming infrastructure activates Mode-3 captured authority — a durably
     * captured identity snapshot's frozen authorization claims are presented as a reconstructed
     * context's current authority.
     *
     * <p>Emitted and awaited by the activation seam itself (not by
     * {@link dev.vertique.security.CapturedAuthorityReconstruction}, which stays event-silent per
     * FR-ID-CA-007) — the returned {@link Future} resolving signals delivery to this observer
     * before the activation caller receives its reconstructed context.
     *
     * @param event the captured-authority-activated event; never null
     * @return a {@link Future} that completes when the observer has finished processing;
     *         failures are isolated by the emitter and do not affect the activation outcome
     */
    default Future<Void> onCapturedAuthorityActivated(CapturedAuthorityActivatedEvent event) {
        return Future.succeededFuture();
    }
}
