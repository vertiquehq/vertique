// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.config;

import dev.vertique.inboxoutbox.RelayStrategy;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Locale;

/**
 * One operating configuration of a load run: which of the two notification triggers fire, and the
 * configuration that goes with that choice.
 *
 * <p>An arm changes only configuration and trigger state. It adds no product code and binds no observer.
 *
 * <table>
 *   <caption>The arms</caption>
 *   <tr><th>Arm</th><th>Workflow trigger</th><th>Outbox trigger</th><th>Workflow poll</th><th>Outbox strategy, poll</th></tr>
 *   <tr><td>ON</td><td>on</td><td>on</td><td>5000 ms</td><td>LISTEN_NOTIFY, 1000 ms</td></tr>
 *   <tr><td>WF_OFF</td><td>off</td><td>on</td><td>100 ms</td><td>LISTEN_NOTIFY, 1000 ms</td></tr>
 *   <tr><td>OUT_OFF</td><td>on</td><td>off</td><td>5000 ms</td><td>POLLING, 50 ms</td></tr>
 *   <tr><td>OFF</td><td>off</td><td>off</td><td>100 ms</td><td>POLLING, 50 ms</td></tr>
 *   <tr><td>ON_MATCHED</td><td>on</td><td>on</td><td>100 ms</td><td>POLLING, 50 ms</td></tr>
 * </table>
 *
 * <p>The trigger state is set once, while no node runs, by {@code LoadAdmin arm}. The configuration is applied to
 * every node of the run through {@link #configOverlay()}.
 */
public enum LoadArm {

    /** Both triggers on; the design as it ships. */
    ON(true, true, 5_000L, RelayStrategy.LISTEN_NOTIFY, 1_000L),

    /** The workflow trigger off, the outbox trigger on. */
    WF_OFF(false, true, 100L, RelayStrategy.LISTEN_NOTIFY, 1_000L),

    /** The outbox trigger off, the workflow trigger on. */
    OUT_OFF(true, false, 5_000L, RelayStrategy.POLLING, 50L),

    /** Both triggers off. */
    OFF(false, false, 100L, RelayStrategy.POLLING, 50L),

    /** Both triggers on with the polling of {@link #OFF}, to compare with {@link #OFF} at equal polling. */
    ON_MATCHED(true, true, 100L, RelayStrategy.POLLING, 50L);

    /** Name of the trigger on {@code workflow_commands}. */
    public static final String WORKFLOW_TRIGGER = "trg_workflow_commands_notify";

    /** Table that carries {@link #WORKFLOW_TRIGGER}. */
    public static final String WORKFLOW_TABLE = "workflow_commands";

    /** Name of the trigger on {@code outbox}. */
    public static final String OUTBOX_TRIGGER = "transactional_outbox_notify";

    /** Table that carries {@link #OUTBOX_TRIGGER}. */
    public static final String OUTBOX_TABLE = "outbox";

    private final boolean workflowTrigger;
    private final boolean outboxTrigger;
    private final long executorPollIntervalMs;
    private final RelayStrategy relayStrategy;
    private final long relayPollingIntervalMs;

    LoadArm(
            boolean workflowTrigger,
            boolean outboxTrigger,
            long executorPollIntervalMs,
            RelayStrategy relayStrategy,
            long relayPollingIntervalMs) {
        this.workflowTrigger = workflowTrigger;
        this.outboxTrigger = outboxTrigger;
        this.executorPollIntervalMs = executorPollIntervalMs;
        this.relayStrategy = relayStrategy;
        this.relayPollingIntervalMs = relayPollingIntervalMs;
    }

    /**
     * Returns whether the trigger on {@code workflow_commands} fires in this arm.
     *
     * @return {@code true} when the workflow trigger is enabled
     */
    public boolean workflowTriggerEnabled() {
        return workflowTrigger;
    }

    /**
     * Returns whether the trigger on {@code outbox} fires in this arm.
     *
     * @return {@code true} when the outbox trigger is enabled
     */
    public boolean outboxTriggerEnabled() {
        return outboxTrigger;
    }

    /**
     * Returns the relay strategy of this arm.
     *
     * @return {@code LISTEN_NOTIFY} or {@code POLLING}
     */
    public RelayStrategy relayStrategy() {
        return relayStrategy;
    }

    /**
     * Returns the executor's polling interval in this arm.
     *
     * @return the interval in milliseconds
     */
    public long executorPollIntervalMs() {
        return executorPollIntervalMs;
    }

    /**
     * Returns the relay's polling interval in this arm.
     *
     * @return the interval in milliseconds
     */
    public long relayPollingIntervalMs() {
        return relayPollingIntervalMs;
    }

    /**
     * Returns the configuration that every node of a run in this arm needs, as a partial configuration tree.
     *
     * @return {@code workflow.executor.pollIntervalMs}, {@code inboxOutbox.relay.strategy} and
     *     {@code inboxOutbox.relay.pollingIntervalMs}
     */
    public JsonObject configOverlay() {
        return new JsonObject()
                .put(
                        "workflow",
                        new JsonObject()
                                .put("executor", new JsonObject().put("pollIntervalMs", executorPollIntervalMs)))
                .put(
                        "inboxOutbox",
                        new JsonObject()
                                .put(
                                        "relay",
                                        new JsonObject()
                                                .put("strategy", relayStrategy.name())
                                                .put("pollingIntervalMs", relayPollingIntervalMs)));
    }

    /**
     * Returns the statements that put the two triggers into the state of this arm.
     *
     * @return one {@code ALTER TABLE} statement per trigger, workflow trigger first
     */
    public List<String> triggerStatements() {
        return List.of(
                alter(WORKFLOW_TABLE, WORKFLOW_TRIGGER, workflowTrigger),
                alter(OUTBOX_TABLE, OUTBOX_TRIGGER, outboxTrigger));
    }

    private static String alter(String table, String trigger, boolean enabled) {
        return "ALTER TABLE " + table + (enabled ? " ENABLE" : " DISABLE") + " TRIGGER " + trigger;
    }

    /**
     * Resolves an arm by name, ignoring case and accepting a hyphen for an underscore.
     *
     * @param name the arm name, such as {@code WF-OFF} or {@code on_matched}
     * @return the arm
     * @throws IllegalArgumentException if no arm has that name
     */
    public static LoadArm parse(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An arm name is required; known arms are " + List.of(values()));
        }
        String normalized = name.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (LoadArm arm : values()) {
            if (arm.name().equals(normalized)) {
                return arm;
            }
        }
        throw new IllegalArgumentException("Unknown arm '" + name + "'; known arms are " + List.of(values()));
    }
}
