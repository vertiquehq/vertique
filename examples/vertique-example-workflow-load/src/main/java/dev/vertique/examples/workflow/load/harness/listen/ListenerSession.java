// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A database session whose last statement was a {@code LISTEN}, as seen from outside the node.
 *
 * <p>A node's pool and its dedicated listeners share one {@code application_name}, so a listener is told from pooled
 * work by what it ran: a session that issued {@code LISTEN} and nothing after it. The backend PID and the time the
 * backend started identify one connection; a reconnect shows as a new PID, or the same PID with a later start.
 *
 * @param pid the backend process id
 * @param backendStartMs the time the backend started, in epoch milliseconds
 * @param applicationName the application name of the connection, which names the node
 * @param channel {@code workflow}, {@code outbox} or the raw channel name of any other
 */
public record ListenerSession(int pid, long backendStartMs, String applicationName, String channel) {

    /** The channel the workflow commands' trigger notifies on. */
    public static final String WORKFLOW_CHANNEL = "vertique_workflow_commands";

    /** The channel the outbox trigger notifies on. */
    public static final String OUTBOX_CHANNEL = "transactional_outbox_channel";

    private static final Pattern LISTEN = Pattern.compile("(?is)^\\s*LISTEN\\s+\"?([A-Za-z0-9_]+)\"?\\s*;?\\s*$");

    /**
     * Returns the identity of the connection.
     *
     * @return the PID and start time
     */
    public String identity() {
        return pid + "@" + backendStartMs;
    }

    /**
     * Reads the channel out of a statement.
     *
     * @param statement the last statement of a session
     * @return {@code workflow}, {@code outbox} or the raw channel name; {@code null} when it is not a plain
     *     {@code LISTEN}
     */
    public static String channelOf(String statement) {
        if (statement == null) {
            return null;
        }
        Matcher matcher = LISTEN.matcher(statement);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1).toLowerCase(Locale.ROOT);
        if (name.equals(WORKFLOW_CHANNEL)) {
            return "workflow";
        }
        if (name.equals(OUTBOX_CHANNEL)) {
            return "outbox";
        }
        return name;
    }
}
