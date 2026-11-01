// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.support;

import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builders for the per-process configuration overlays the integration tests give to nodes. */
public final class NodeConfigs {

    private NodeConfigs() {}

    /**
     * Returns the overlay of a fast test node: short polling and a short timer, a small pool, the named
     * connections, and an ingress on a system-chosen port reported through a file.
     *
     * @param db the database the node connects to
     * @param applicationName the application name of the node's connections
     * @param portFile the file the ingress writes its port to
     * @return the overlay
     */
    public static JsonObject fastNode(ItDatabase db, String applicationName, Path portFile) {
        return fastNode(db, applicationName, portFile, 20);
    }

    /**
     * Returns the overlay of a fast test node with a given handler delay.
     *
     * @param db the database the node connects to
     * @param applicationName the application name of the node's connections
     * @param portFile the file the ingress writes its port to
     * @param handlerDelayMs the delay of the dispatch handler, in milliseconds; zero skips the timer
     * @return the overlay
     */
    public static JsonObject fastNode(ItDatabase db, String applicationName, Path portFile, long handlerDelayMs) {
        return new JsonObject()
                .put("db", db.dbSection(applicationName, 8))
                .put("workflow", new JsonObject().put("executor", new JsonObject().put("pollIntervalMs", 100)))
                .put("inboxOutbox", new JsonObject().put("relay", new JsonObject().put("pollingIntervalMs", 100)))
                .put(
                        "load",
                        new JsonObject()
                                .put("handlerDelayMs", handlerDelayMs)
                                .put("timerDelayMs", 200)
                                .put(
                                        "ingress",
                                        new JsonObject()
                                                .put("host", "127.0.0.1")
                                                .put("port", 0)
                                                .put("portFile", portFile.toString())));
    }

    /**
     * Returns the shipped node configuration with an overlay merged over it, as the launcher resolves it for a
     * process that gets the overlay through {@code --conf}.
     *
     * @param overlay the overlay
     * @return the merged configuration
     * @throws java.io.IOException if the shipped file cannot be read
     */
    public static JsonObject shippedWith(JsonObject overlay) throws java.io.IOException {
        JsonObject merged = new JsonObject(Files.readString(Path.of("config", "application.json")));
        return merged.mergeIn(overlay, true);
    }
}
