// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.chat;

import java.time.Instant;

/**
 * Event broadcast to chat room participants.
 *
 * @param type      event type — {@code "welcome"} on connect, {@code "message"} on peer message
 * @param author    originating user or {@code "server"} for system events
 * @param text      event payload text
 * @param timestamp wall-clock time when the event was created
 */
public record ChatEvent(String type, String author, String text, Instant timestamp) {}
