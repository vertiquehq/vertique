// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.inboxoutbox.OutboxPublishObserver;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.config.WorkflowIntakeConfig;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.executor.WorkflowExecutorObserver;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.internal.VertxInternal;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

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
    private final VertxOptions vertxOptions;
    private final Vertx vertx;
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
     * @param config the resolved application configuration, which holds the {@code vertx.options} section
     * @param vertx the running Vert.x instance, whose thread pools are counted
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
            Set<OutboxPublishObserver> outboxPublishObservers,
            @VertxConfig JsonObject config,
            Vertx vertx) {
        this.load = load;
        this.executor = executor;
        this.recording = recording;
        this.relay = relay;
        this.db = db;
        this.flyway = flyway;
        this.vertxOptions = new VertxOptions(JsonConfigPaths.navigateObject(config, "vertx", "options"));
        this.vertx = vertx;
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
     *     {@code relay}, {@code load}, {@code vertx}, {@code jvm} and {@code observers}
     */
    public JsonObject toJson() {
        WorkflowIntakeConfig intake = recording.intake();
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("applicationName", applicationName(db))
                                .put("maxPoolSize", db.maxPoolSize())
                                .put("connectionTimeoutMs", db.connectionTimeoutMs())
                                .put("idleTimeoutMs", db.idleTimeoutMs())
                                .put("eventLoopSize", db.eventLoopSize()))
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
                        "vertx",
                        new JsonObject()
                                .put("eventLoopPoolSize", vertxOptions.getEventLoopPoolSize())
                                .put("workerPoolSize", vertxOptions.getWorkerPoolSize())
                                .put("internalBlockingPoolSize", vertxOptions.getInternalBlockingPoolSize())
                                .put("actual", actualVertx()))
                .put(
                        "jvm",
                        new JsonObject()
                                .put("maxHeapMb", Runtime.getRuntime().maxMemory() >> 20)
                                .put("availableProcessors", Runtime.getRuntime().availableProcessors())
                                .put(
                                        "inputArguments",
                                        new io.vertx.core.json.JsonArray(
                                                new java.util.ArrayList<Object>(ManagementFactory.getRuntimeMXBean()
                                                        .getInputArguments()))))
                .put(
                        "observers",
                        new JsonObject()
                                .put("executor", executorObservers)
                                .put("outboxPublish", outboxPublishObservers));
    }

    /**
     * Counts the threads the running Vert.x instance has: its event loops and the largest sizes of its worker pools.
     * The configured values can differ from these when the launcher applies system properties or command-line options.
     * A value is -1 when the instance is not the standard implementation.
     */
    private JsonObject actualVertx() {
        int loops = -1;
        int workers = -1;
        int internal = -1;
        if (vertx instanceof VertxInternal internalVertx) {
            loops = 0;
            for (Object ignored : internalVertx.eventLoopGroup()) {
                loops++;
            }
            workers = largest(internalVertx.workerPool().executor());
            internal = largest(internalVertx.internalWorkerPool().executor());
        }
        return new JsonObject()
                .put("eventLoopPoolSize", loops)
                .put("workerPoolSize", workers)
                .put("internalBlockingPoolSize", internal);
    }

    private static int largest(ExecutorService executor) {
        return executor instanceof ThreadPoolExecutor pool ? pool.getMaximumPoolSize() : -1;
    }
}
