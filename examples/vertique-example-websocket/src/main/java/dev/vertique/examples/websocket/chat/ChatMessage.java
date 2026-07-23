// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.chat;

/**
 * Incoming chat message sent by a connected client.
 *
 * @param author display name of the sender
 * @param text   message body text
 */
public record ChatMessage(String author, String text) {}
