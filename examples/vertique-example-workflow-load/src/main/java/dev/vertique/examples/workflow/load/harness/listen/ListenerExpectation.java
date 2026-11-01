// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import dev.vertique.examples.workflow.load.config.LoadArm;
import dev.vertique.inboxoutbox.RelayStrategy;
import java.util.Map;
import java.util.TreeMap;

/**
 * The dedicated listeners an arm expects: per node and channel, how many sessions should be listening.
 *
 * <p>Every node holds one workflow listener, in every arm, because the product cannot switch it off; with the
 * workflow trigger disabled it listens and receives nothing. A node holds one outbox listener per relay instance
 * only when the relay strategy is {@code LISTEN_NOTIFY}.
 */
public final class ListenerExpectation {

    private final Map<String, Map<String, Integer>> byApplication;

    private ListenerExpectation(Map<String, Map<String, Integer>> byApplication) {
        this.byApplication = byApplication;
    }

    /**
     * Builds the expectation of a run.
     *
     * @param arm the arm
     * @param applicationNames the application names of the nodes
     * @param relayInstances the relay instances per node
     * @return the expectation
     */
    public static ListenerExpectation of(LoadArm arm, Iterable<String> applicationNames, int relayInstances) {
        boolean outbox = arm.relayStrategy() == RelayStrategy.LISTEN_NOTIFY;
        Map<String, Map<String, Integer>> map = new TreeMap<>();
        for (String name : applicationNames) {
            Map<String, Integer> channels = new TreeMap<>();
            channels.put("workflow", 1);
            if (outbox) {
                channels.put("outbox", relayInstances);
            }
            map.put(name, channels);
        }
        return new ListenerExpectation(map);
    }

    /**
     * Returns the expected sessions.
     *
     * @return application name to channel to count
     */
    public Map<String, Map<String, Integer>> byApplication() {
        return byApplication;
    }

    /**
     * Returns how many sessions are expected on a channel of an application.
     *
     * @param application the application name
     * @param channel the channel
     * @return the count; zero when none is expected
     */
    public int expected(String application, String channel) {
        return byApplication.getOrDefault(application, Map.of()).getOrDefault(channel, 0);
    }
}
