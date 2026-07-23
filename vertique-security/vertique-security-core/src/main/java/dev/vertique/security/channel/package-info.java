// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Long-lived connection identity management SPI (PRD §7.12).
 *
 * <p>Provides the transport-neutral contract for tracking and refreshing channel-bound
 * {@link dev.vertique.security.SecurityContext} across WebSocket, SSE, and future
 * bidi-stream transports.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.security.channel.ChannelIdentityManager} — per-runtime channel
 *       registry; supports registration, identity refresh, and force-close.</li>
 *   <li>{@link dev.vertique.security.channel.ChannelBinding} — transport-owned per-channel
 *       callback the manager invokes for rebinds and forced closes.</li>
 * </ul>
 */
package dev.vertique.security.channel;
