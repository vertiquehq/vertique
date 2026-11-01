// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import io.vertx.core.json.JsonObject;

/**
 * Something that happened to a dedicated listener, kept for the report whether or not it recovered.
 *
 * @param timeMs the time of the sample that showed it, in epoch milliseconds
 * @param type what happened
 * @param application the application name of the node
 * @param channel the channel
 * @param pid the backend PID concerned, or 0
 * @param detail a short explanation
 */
public record ListenerEvent(long timeMs, Type type, String application, String channel, int pid, String detail) {

    /** The kinds of event. */
    public enum Type {
        /** A listener session that was there is gone. */
        LOST,
        /** A listener session appeared where an earlier one had been lost: a reconnect. */
        RECONNECTED,
        /** A channel has fewer listeners than expected. */
        MISSING,
        /** A channel has more listeners than expected, or a listener nobody expects. */
        UNEXPECTED,
        /** A channel is back at its expected listener count after a gap. */
        RESTORED
    }

    /**
     * Renders the event.
     *
     * @return the event as JSON
     */
    public JsonObject toJson() {
        return new JsonObject()
                .put("timeMs", timeMs)
                .put("type", type.name())
                .put("application", application)
                .put("channel", channel)
                .put("pid", pid)
                .put("detail", detail);
    }
}
