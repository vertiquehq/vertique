// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.support;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.examples.workflow.load.AppComponentVertiqueComponentFactory;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A load node started inside the test JVM, on a Vert.x instance of its own.
 *
 * <p>It is the same application component a launcher starts, so it runs the real engine, executor, relay and
 * sampler. It exists for tests that need to stop one node while the others keep running.
 */
public final class InProcessNode implements AutoCloseable {

    private final Vertx vertx;
    private final VertiqueApplicationHandle<?> handle;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean();

    private InProcessNode(Vertx vertx, VertiqueApplicationHandle<?> handle, int port) {
        this.vertx = vertx;
        this.handle = handle;
        this.port = port;
    }

    /**
     * Starts a node.
     *
     * @param config the complete configuration of the node
     * @param portFile the file the ingress writes its port to, which must be the {@code load.ingress.portFile} of
     *     {@code config}
     * @return the started node
     * @throws Exception if the node does not start within a minute
     */
    public static InProcessNode start(JsonObject config, Path portFile) throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            VertiqueApplicationHandle<?> handle = VertiqueApplicationBootstrap.start(
                            VertiqueRuntime.of(vertx, config), new AppComponentVertiqueComponentFactory())
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(60, TimeUnit.SECONDS);
            return new InProcessNode(vertx, handle, Integer.parseInt(Files.readString(portFile)));
        } catch (Exception e) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            throw e;
        }
    }

    /**
     * Returns the port of the ingress.
     *
     * @return the bound port
     */
    public int port() {
        return port;
    }

    /** Stops the node and closes its Vert.x instance, and waits for both. A second call does nothing. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            handle.shutdown().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("The node did not stop cleanly", e);
        }
    }
}
