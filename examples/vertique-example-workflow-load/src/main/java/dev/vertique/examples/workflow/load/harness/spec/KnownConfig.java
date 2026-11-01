// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The product settings a run may name in its {@code confOverride}.
 *
 * <p>Product configuration ignores keys it does not know, so a misspelled key leaves the default in place and the
 * node looks right. A run therefore checks the keys it passes on against this list before any process starts, and
 * rejects an unknown key whatever its value is. A setting that the specification already has an input for is not
 * on the list; it has to be set through that input.
 */
public final class KnownConfig {

    /** Each accepted setting with the place in the node's {@code GET /effective} answer where it is reported. */
    private static final Map<String, String> EFFECTIVE = Map.ofEntries(
            Map.entry("db.connectionTimeoutMs", "db.connectionTimeoutMs"),
            Map.entry("db.idleTimeoutMs", "db.idleTimeoutMs"),
            Map.entry("db.eventLoopSize", "db.eventLoopSize"),
            Map.entry("workflow.executor.leaseSeconds", "executor.leaseSeconds"),
            Map.entry("workflow.executor.maxAttempts", "executor.maxAttempts"),
            Map.entry("workflow.recording.lockTimeoutMs", "recording.lockTimeoutMs"),
            Map.entry("workflow.recording.maxOpenSignalsPerInstance", "recording.maxOpenSignalsPerInstance"),
            Map.entry("inboxOutbox.relay.leaseTimeoutMs", "relay.leaseTimeoutMs"),
            Map.entry("inboxOutbox.relay.maxAttempts", "relay.maxAttempts"));

    private static final Set<String> PATHS = EFFECTIVE.keySet();

    private KnownConfig() {}

    /**
     * Returns the keys that may be passed in an override.
     *
     * @return the dotted paths of the settings
     */
    public static Set<String> paths() {
        return PATHS;
    }

    /**
     * Returns where a setting is reported in the node's answer.
     *
     * @param path the dotted path of the setting in the node configuration
     * @return the dotted path in the answer of {@code GET /effective}, or {@code null} for an unknown setting
     */
    public static String effectivePath(String path) {
        return EFFECTIVE.get(path);
    }

    /**
     * Lists the leaves of an override.
     *
     * @param override the override tree
     * @return the dotted path of each leaf with its value
     */
    public static Map<String, Object> leaves(JsonObject override) {
        Map<String, Object> result = new java.util.TreeMap<>();
        flatten("", override, result);
        return result;
    }

    private static void flatten(String prefix, JsonObject object, Map<String, Object> leaves) {
        for (String key : object.fieldNames()) {
            Object value = object.getValue(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof JsonObject child && !child.isEmpty()) {
                flatten(path, child, leaves);
            } else {
                leaves.put(path, value);
            }
        }
    }

    /**
     * Checks an override.
     *
     * @param override the override tree
     * @throws ConfigurationException naming the first path that is not a known setting
     */
    public static void validate(JsonObject override) {
        Set<String> leaves = new TreeSet<>();
        collect("", override, leaves);
        for (String leaf : leaves) {
            if (!PATHS.contains(leaf)) {
                throw new ConfigurationException("Unknown input node.confOverride." + leaf
                        + "; a setting is accepted here only when it is one of " + new TreeSet<>(PATHS));
            }
        }
    }

    private static void collect(String prefix, JsonObject object, Set<String> leaves) {
        for (String key : object.fieldNames()) {
            Object value = object.getValue(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof JsonObject child) {
                if (child.isEmpty()) {
                    leaves.add(path);
                } else {
                    collect(path, child, leaves);
                }
            } else {
                leaves.add(path);
            }
        }
    }
}
