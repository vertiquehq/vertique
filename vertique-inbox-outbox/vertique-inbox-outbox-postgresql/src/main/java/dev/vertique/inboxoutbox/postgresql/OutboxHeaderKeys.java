// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

/**
 * Makes an outbox header key safe to show in a log line or an exception message.
 *
 * <p>A header key is supplied by the caller of {@code OutboxService.publish} or read from a stored
 * row, so it can hold anything: a line break in it would forge a log line, and a very long key would
 * flood one. The header value is never shown at all.
 */
final class OutboxHeaderKeys {

    /** The number of characters of a header key that are shown; the rest is cut. */
    static final int MAX_SHOWN_LENGTH = 128;

    /** Prevent instantiation. */
    private OutboxHeaderKeys() {}

    /**
     * Returns the key with every control character — carriage return, line feed, tab and the other
     * ISO control characters — replaced by {@code _} and cut to {@link #MAX_SHOWN_LENGTH}
     * characters.
     *
     * @param key the header key as supplied or stored; must not be {@code null}
     * @return the key in the form shown in logs and messages
     */
    static String forDisplay(String key) {
        int length = Math.min(key.length(), MAX_SHOWN_LENGTH);
        StringBuilder shown = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            shown.append(Character.isISOControl(c) ? '_' : c);
        }
        return shown.toString();
    }
}
