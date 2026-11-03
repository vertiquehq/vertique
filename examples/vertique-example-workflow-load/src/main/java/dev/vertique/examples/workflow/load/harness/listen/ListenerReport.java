// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * The outcome of checking the listeners of a run.
 *
 * @param before whether the expected set was complete and exact before the load
 * @param during whether it was complete and exact at every reading during the load
 * @param after whether it was complete and exact in the last reading after the load
 * @param beforeDescription what was wrong before the load, or empty
 * @param events everything that happened to a listener, in time order
 * @param readings the readings that were answered
 */
public record ListenerReport(
        boolean before,
        boolean during,
        boolean after,
        String beforeDescription,
        List<ListenerEvent> events,
        int readings) {

    /**
     * Returns whether any listener was lost at any time, even if it recovered.
     *
     * @return {@code true} when a loss was seen
     */
    public boolean anyLoss() {
        return events.stream().anyMatch(e -> e.type() == ListenerEvent.Type.LOST);
    }

    /**
     * Returns whether any listener reconnected.
     *
     * @return {@code true} when a reconnect was seen
     */
    public boolean anyReconnect() {
        return events.stream().anyMatch(e -> e.type() == ListenerEvent.Type.RECONNECTED);
    }

    /**
     * Returns the state of the listeners at the end.
     *
     * @return {@code lost} when a channel was short in the last reading, {@code reconnected} when every listener was
     *     back but some had been lost, {@code unchanged} otherwise
     */
    public String status() {
        if (!after) {
            return "lost";
        }
        return anyLoss() || anyReconnect() ? "reconnected" : "unchanged";
    }

    /**
     * Renders the report.
     *
     * @return the report as JSON
     */
    public JsonObject toJson() {
        JsonArray array = new JsonArray();
        events.forEach(e -> array.add(e.toJson()));
        return new JsonObject()
                .put("before", before)
                .put("during", during)
                .put("after", after)
                .put("beforeDescription", beforeDescription)
                .put("status", status())
                .put("anyLoss", anyLoss())
                .put("anyReconnect", anyReconnect())
                .put("readings", readings)
                .put("events", array);
    }
}
