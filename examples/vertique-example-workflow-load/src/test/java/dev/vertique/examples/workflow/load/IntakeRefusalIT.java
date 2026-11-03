// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.admin.LoadAdmin;
import dev.vertique.examples.workflow.load.support.InProcessNode;
import dev.vertique.examples.workflow.load.support.ItDatabase;
import dev.vertique.examples.workflow.load.support.NodeConfigs;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves that the dispatch callback does not depend on the intake limit.
 *
 * <p>Two nodes share one database. The executor node runs the instance's start segment and is then stopped, so no
 * executor is left to drain the queue. The handler node has no executor, a handler delay long enough to span the
 * stop, and an intake limit that refuses as soon as two commands are due. Starts offered to it pile up unrun, so it
 * refuses; the callback of the first instance arrives while it refuses, and is still recorded. The delivery is then
 * made again, and records no second signal.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class IntakeRefusalIT {

    private static final String HOST = "127.0.0.1";
    private static final long HANDLER_DELAY_MS = 3_000L;

    @TempDir
    static Path work;

    private static ItDatabase db;
    private static Vertx vertx;
    private static WebClient client;
    private static InProcessNode executorNode;
    private static InProcessNode handlerNode;

    @BeforeAll
    static void startNodes() throws Exception {
        db = new ItDatabase().start();
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);

        JsonObject adminConfig = new JsonObject().put("db", db.dbSection("load-admin-it", 4));
        assertEquals(
                0, LoadAdmin.run(new String[] {"migrate", "--conf", adminConfig.encode()}, System.out, System.err));

        Path executorPorts = work.resolve("executor.port");
        Path handlerPorts = work.resolve("handler.port");

        // The executor node runs the start segments. Its relay only polls, once an hour, so it never delivers.
        JsonObject executorOverlay = NodeConfigs.fastNode(db, "load-it-executor", executorPorts)
                .mergeIn(
                        new JsonObject()
                                .put(
                                        "workflow",
                                        new JsonObject()
                                                .put(
                                                        "recording",
                                                        new JsonObject()
                                                                .put("intake", new JsonObject().put("enabled", false))))
                                .put(
                                        "inboxOutbox",
                                        new JsonObject()
                                                .put(
                                                        "relay",
                                                        new JsonObject()
                                                                .put("strategy", "POLLING")
                                                                .put("pollingIntervalMs", 3_600_000L))),
                        true);

        // The handler node has no executor, delivers through the relay, and refuses from two due commands.
        JsonObject handlerOverlay = NodeConfigs.fastNode(db, "load-it-handler", handlerPorts)
                .mergeIn(
                        new JsonObject()
                                .put(
                                        "workflow",
                                        new JsonObject()
                                                .put("executor", new JsonObject().put("enabled", false))
                                                .put(
                                                        "recording",
                                                        new JsonObject()
                                                                .put(
                                                                        "intake",
                                                                        new JsonObject()
                                                                                .put("enabled", true)
                                                                                .put("highMark", 2)
                                                                                .put("lowMark", 1)
                                                                                .put("sampleIntervalMs", 100))))
                                .put("load", new JsonObject().put("handlerDelayMs", HANDLER_DELAY_MS)),
                        true);

        executorNode = InProcessNode.start(NodeConfigs.shippedWith(executorOverlay), executorPorts);
        handlerNode = InProcessNode.start(NodeConfigs.shippedWith(handlerOverlay), handlerPorts);
    }

    @AfterAll
    static void stopNodes() throws Exception {
        if (handlerNode != null) {
            handlerNode.close();
        }
        if (executorNode != null) {
            executorNode.close();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName(
            "the callback is recorded while the limit refuses, spends no delivery attempt, and a redelivery records nothing")
    void callbackIsRecordedWhileTheLimitRefuses() throws Exception {
        // Given: an instance whose start segment ran on the executor node, so its dispatch is in the outbox
        HttpResponse<Buffer> first = post(executorNode.port(), "/start?key=first");
        assertEquals(200, first.statusCode(), first.bodyAsString());
        UUID instance = UUID.fromString(first.bodyAsJsonObject().getString("workflowId"));
        long dispatches =
                db.awaitCount("SELECT count(*) FROM outbox WHERE aggregate_id = '" + instance + "'", 1, 5_000);
        assertEquals(1, dispatches, "the executor node ran the start segment and recorded the dispatch");

        // When: the executor is gone, and starts offered to the handler node pile up unrun
        executorNode.close();
        int refusals = 0;
        for (int i = 0; i < 20 && refusals == 0; i++) {
            HttpResponse<Buffer> offered = post(handlerNode.port(), "/start?key=pressure-" + i);
            if (offered.statusCode() == 503) {
                assertEquals("refused", offered.bodyAsJsonObject().getString("outcome"));
                refusals++;
                assertEquals(
                        0,
                        db.count("SELECT count(*) FROM workflow_dedup WHERE kind = 'start' AND key = 'pressure-" + i
                                + "'"),
                        "a refusal writes nothing, so its key stays free");
            } else {
                assertEquals(200, offered.statusCode(), offered.bodyAsString());
                Thread.sleep(150);
            }
        }
        assertEquals(1, refusals, "the handler node refuses once two commands are due");

        // Then: the callback of the first instance arrives while the node refuses, and is recorded
        long signals = db.awaitCount(
                "SELECT count(*) FROM workflow_commands WHERE kind = 'SIGNAL' AND workflow_id = '" + instance + "'",
                1,
                HANDLER_DELAY_MS + 5_000);
        assertEquals(1, signals, "the callback was recorded");
        assertEquals(503, post(handlerNode.port(), "/start?key=after-callback").statusCode(), "still refusing");
        assertEquals(
                0,
                db.count("SELECT count(*) FROM outbox WHERE aggregate_id = '" + instance
                        + "' AND (state <> 'PUBLISHED' OR attempt <> 0 OR last_error IS NOT NULL)"),
                "published at the first attempt: the refusal of new intake did not touch the delivery");

        // When: the same dispatch is delivered again
        long firstPublished =
                db.count("SELECT (extract(epoch FROM published_at) * 1000)::bigint FROM outbox WHERE aggregate_id = '"
                        + instance + "'");
        assertEquals(
                1,
                db.update("UPDATE outbox SET state = 'PENDING', published_at = NULL, claimed_at = NULL,"
                        + " claimed_by = NULL WHERE aggregate_id = '" + instance + "'"));
        db.awaitCount(
                "SELECT count(*) FROM outbox WHERE aggregate_id = '" + instance + "' AND state = 'PUBLISHED'",
                1,
                HANDLER_DELAY_MS + 5_000);

        // Then: it is published again, and the engine recorded no second signal
        long secondPublished =
                db.count("SELECT (extract(epoch FROM published_at) * 1000)::bigint FROM outbox WHERE aggregate_id = '"
                        + instance + "'");
        assertNotEquals(firstPublished, secondPublished, "the dispatch was delivered a second time");
        assertEquals(
                1,
                db.count("SELECT count(*) FROM workflow_commands WHERE kind = 'SIGNAL' AND workflow_id = '" + instance
                        + "'"),
                "a redelivered callback records no second signal");
        assertEquals(
                1,
                db.count("SELECT count(*) FROM workflow_dedup WHERE kind = 'signal' AND workflow_id = '" + instance
                        + "'"));
        assertTrue(
                db.count("SELECT count(*) FROM outbox WHERE aggregate_id = '" + instance + "' AND attempt = 0") == 1,
                "the redelivery spent no attempt either");
    }

    private static HttpResponse<Buffer> post(int port, String uri) throws Exception {
        Future<HttpResponse<Buffer>> response = client.post(port, HOST, uri).send();
        return response.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
