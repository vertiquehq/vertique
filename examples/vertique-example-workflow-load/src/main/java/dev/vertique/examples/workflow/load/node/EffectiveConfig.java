// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.config.WorkflowIntakeConfig;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The configuration a node actually runs on, as the typed product configuration objects see it.
 *
 * <p>Product configuration ignores keys it does not know, so a file or an override that has a typo looks like a
 * working node. A run reads this view from every node before it offers load and compares it with what the run
 * intended; a value that the typed object did not pick up shows as the default. The view carries no secret: it never
 * holds a password or the connection properties other than the application name.
 *
 * <p>It also reports how many executor and outbox-publish observers the graph binds. Observers add database reads and
 * callbacks to the measured path, so a baseline run expects both counts to be zero.
 */
@Singleton
public final class EffectiveConfig {

    private final LoadConfig load;
    private final WorkflowExecutorConfig executor;
    private final WorkflowRecordingConfig recording;
    private final OutboxRelayConfig relay;
    private final DbPoolConfig db;
    private final FlywayConfig flyway;
    private final int executorObservers;
    private final int outboxPublishObservers;

    /**
     * Creates the view.
     *
     * @param load the harness settings
     * @param executor the typed {@code workflow.executor} configuration
     * @param recording the typed {@code workflow.recording} configuration
     * @param relay the typed {@code inboxOutbox.relay} configuration
     * @param db the typed {@code db} configuration
     * @param flyway the typed {@code flyway} configuration
     * @param executorObservers the executor observers the graph binds
     * @param outboxPublishObservers the outbox publish observers the graph binds
     */
    @Inject
    public EffectiveConfig(
            LoadConfig load,
            WorkflowExecutorConfig executor,
            WorkflowRecordingConfig recording,
            OutboxRelayConfig relay,
            DbPoolConfig db,
            FlywayConfig flyway,
            Set<WorkflowExecutorObserver> executorObservers,
            Set<OutboxPublishObserver> outboxPublishObservers) {
        this.load = load;
        this.executor = executor;
        this.recording = recording;
        this.relay = relay;
        this.db = db;
        this.flyway = flyway;
        this.executorObservers = executorObservers.size();
        this.outboxPublishObservers = outboxPublishObservers.size();
    }

    /**
     * Returns the application name the node's database connections report, as the typed {@code db} configuration
     * holds it.
     *
     * @param db the typed {@code db} configuration
     * @return the {@code application_name} connection property, or {@code null} when it is not set
     */
    static String applicationName(DbPoolConfig db) {
        return db.properties() == null ? null : db.properties().get("application_name");
    }

    /**
     * Renders the view.
     *
     * @return a JSON object with the sections {@code db}, {@code flyway}, {@code executor}, {@code recording},
     *     {@code relay}, {@code load} and {@code observers}
     */
    public JsonObject toJson() {
        WorkflowIntakeConfig intake = recording.intake();
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("applicationName", applicationName(db))
                                .put("maxPoolSize", db.maxPoolSize()))
                .put("flyway", new JsonObject().put("mode", flyway.mode().name()))
                .put(
                        "executor",
                        new JsonObject()
                                .put("enabled", executor.enabled())
                                .put("instances", executor.instances())
                                .put("concurrency", executor.concurrency())
                                .put("pollIntervalMs", executor.pollIntervalMs())
                                .put("leaseSeconds", executor.leaseSeconds())
                                .put("maxAttempts", executor.maxAttempts()))
                .put(
                        "recording",
                        new JsonObject()
                                .put("lockTimeoutMs", recording.lockTimeoutMs())
                                .put("maxOpenSignalsPerInstance", recording.maxOpenSignalsPerInstance())
                                .put(
                                        "intake",
                                        new JsonObject()
                                                .put("enabled", intake.enabled())
                                                .put("highMark", intake.highMark())
                                                .put("lowMark", intake.lowMark())
                                                .put("sampleIntervalMs", intake.sampleIntervalMs())))
                .put(
                        "relay",
                        new JsonObject()
                                .put("strategy", relay.strategy().name())
                                .put("pollingIntervalMs", relay.pollingIntervalMs())
                                .put("batchSize", relay.batchSize())
                                .put("leaseTimeoutMs", relay.leaseTimeoutMs())
                                .put("maxAttempts", relay.maxAttempts())
                                .put("instances", relay.instances()))
                .put(
                        "load",
                        new JsonObject()
                                .put("handlerDelayMs", load.handlerDelayMs())
                                .put("timerDelayMs", load.timerDelayMs()))
                .put(
                        "observers",
                        new JsonObject()
                                .put("executor", executorObservers)
                                .put("outboxPublish", outboxPublishObservers));
    }
}
