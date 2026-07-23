// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Security lifecycle event records and observer SPI for the Vertique framework.
 *
 * <p>This package contains the event types emitted during authentication and authorization
 * processing, as well as the {@link dev.vertique.security.events.SecurityEventObserver}
 * SPI that consumers implement to receive those events. See PRD §7.13 for the full contract.
 *
 * <p>Event categories:
 * <ul>
 *   <li><b>Credential events</b> — fired at request-ingress credential verification:
 *       {@link dev.vertique.security.events.CredentialAcceptedEvent} and
 *       {@link dev.vertique.security.events.CredentialRejectedEvent}.</li>
 *   <li><b>Authorization events</b> — fired after an authorization policy decision:
 *       {@link dev.vertique.security.events.AuthorizationDecisionEvent}.</li>
 *   <li><b>Channel lifecycle events</b> — fired on persistent-channel transitions; sealed under
 *       {@link dev.vertique.security.events.ChannelLifecycleEvent}.</li>
 * </ul>
 */
package dev.vertique.security.events;
