// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.config.LoadConfig;
import dev.vertique.examples.workflow.load.workflow.LoadCallback;
import dev.vertique.examples.workflow.load.workflow.LoadStart;
import dev.vertique.examples.workflow.load.workflow.LoadWorkflow;
import dev.vertique.workflow.exception.WorkflowIntakeRefusedException;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies what the driver relies on at the ingress: an accepted start, a refusal and any other failure are told
 * apart by status and body, a refusal does not read as a failure, and the bound port is reported.
 */
class IngressVerticleTest {

    private static final String HOST = "127.0.0.1";

    @TempDir
    Path directory;

    private Vertx vertx;
    private WebClient client;
    private final List<String> started = new CopyOnWriteArrayList<>();
    private volatile Function<LoadStart, Future<WorkflowInstanceId>> behaviour;
    private int port;
    private Path portFile;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = WebClient.create(vertx);
        behaviour = cmd -> Future.succeededFuture(
                new WorkflowInstanceId(UUID.nameUUIDFromBytes(cmd.key().getBytes())));
        portFile = directory.resolve("node.port");
        LoadConfig config = new LoadConfig(20L, 1_000L, new LoadConfig.Ingress(HOST, 0, portFile.toString()));
        LoadWorkflow workflow = new LoadWorkflow() {
            @Override
            public Future<WorkflowInstanceId> start(LoadStart cmd) {
                started.add(cmd.key());
                return behaviour.apply(cmd);
            }

            @Override
            public Future<Void> callback(WorkflowInstanceId id, LoadCallback signal) {
                return Future.failedFuture("the ingress never signals");
            }
        };
        await(vertx.deployVerticle(new IngressVerticle(config, workflow, TestConfigs.effective())));
        port = Integer.parseInt(Files.readString(portFile));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    @DisplayName("an accepted start answers 200 with the instance id")
    void acceptedStart() throws Exception {
        HttpResponse<Buffer> response = post("/start?key=run1-0001");

        assertEquals(200, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();
        assertEquals("accepted", body.getString("outcome"));
        assertEquals(UUID.nameUUIDFromBytes("run1-0001".getBytes()).toString(), body.getString("workflowId"));
        assertEquals(List.of("run1-0001"), started);
    }

    @Test
    @DisplayName("a refusal by the intake limit answers 503 and reads as refused")
    void refusedStart() throws Exception {
        behaviour = cmd -> Future.failedFuture(new WorkflowIntakeRefusedException());

        HttpResponse<Buffer> response = post("/start?key=k");

        assertEquals(503, response.statusCode());
        assertEquals("refused", response.bodyAsJsonObject().getString("outcome"));
    }

    @Test
    @DisplayName("a refusal wrapped in another failure still reads as refused")
    void wrappedRefusalIsStillARefusal() throws Exception {
        behaviour = cmd -> Future.failedFuture(new RuntimeException("wrapper", new WorkflowIntakeRefusedException()));

        assertEquals(503, post("/start?key=k").statusCode());
    }

    @Test
    @DisplayName("any other failure answers 500 and names the failure class, not a refusal")
    void failedStart() throws Exception {
        behaviour = cmd -> Future.failedFuture(new IllegalStateException("database stall"));

        HttpResponse<Buffer> response = post("/start?key=k");

        assertEquals(500, response.statusCode());
        JsonObject body = response.bodyAsJsonObject();
        assertEquals("failed", body.getString("outcome"));
        assertEquals("IllegalStateException", body.getString("error"));
        assertFalse(response.bodyAsString().contains("database stall"), "the message stays out of the answer");
    }

    @Test
    @DisplayName("a missing or malformed key answers 400 and starts nothing")
    void invalidKey() throws Exception {
        assertEquals(400, post("/start").statusCode());
        assertEquals(400, post("/start?key=").statusCode());
        assertEquals(400, post("/start?key=has%20space").statusCode());
        assertEquals(400, post("/start?key=" + "x".repeat(129)).statusCode());
        assertTrue(started.isEmpty());
    }

    @Test
    @DisplayName("only POST starts and only GET reads")
    void methodsAreChecked() throws Exception {
        assertEquals(405, get("/start?key=k").statusCode());
        assertEquals(405, post("/ready").statusCode());
        assertEquals(405, post("/effective").statusCode());
        assertTrue(started.isEmpty());
    }

    @Test
    @DisplayName("an unknown path answers 404")
    void unknownPath() throws Exception {
        assertEquals(404, get("/other").statusCode());
    }

    @Test
    @DisplayName("ready answers 200")
    void ready() throws Exception {
        HttpResponse<Buffer> response = get("/ready");

        assertEquals(200, response.statusCode());
        assertTrue(response.bodyAsJsonObject().getBoolean("ready"));
    }

    @Test
    @DisplayName("effective serves the configuration view")
    void effective() throws Exception {
        HttpResponse<Buffer> response = get("/effective");

        assertEquals(200, response.statusCode());
        assertEquals(
                "load-node-1", response.bodyAsJsonObject().getJsonObject("db").getString("applicationName"));
    }

    @Test
    @DisplayName("the bound port is written to the port file")
    void portFileHoldsTheBoundPort() {
        assertTrue(port > 0);
        assertFalse(Files.exists(Path.of(portFile + ".tmp")), "the temporary file is renamed away");
    }

    @Test
    @DisplayName("a key is valid when it holds one to 128 letters, digits or . _ : -")
    void keyRule() {
        assertTrue(IngressVerticle.isValidKey("a"));
        assertTrue(IngressVerticle.isValidKey("Run_1:node-3.0042"));
        assertTrue(IngressVerticle.isValidKey("x".repeat(128)));
        assertFalse(IngressVerticle.isValidKey(null));
        assertFalse(IngressVerticle.isValidKey(""));
        assertFalse(IngressVerticle.isValidKey("x".repeat(129)));
        assertFalse(IngressVerticle.isValidKey("a b"));
        assertFalse(IngressVerticle.isValidKey("a/b"));
        assertFalse(IngressVerticle.isValidKey("é"));
    }

    private HttpResponse<Buffer> post(String uri) throws Exception {
        return await(client.post(port, HOST, uri).send());
    }

    private HttpResponse<Buffer> get(String uri) throws Exception {
        return await(client.get(port, HOST, uri).send());
    }

    private static <T> T await(Future<T> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
