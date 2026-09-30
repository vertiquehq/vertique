// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.RestApplications;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit proof that the document store's single flight does not keep a failure: while one
 * composition's assembly of a document is in flight, a second composition joins it instead of
 * assembling, and when the assembly fails both fail with the same exception, the flight is removed,
 * and nothing is stored, so a later deployment in the same component assembles again and stores the
 * document.
 *
 * <p>The store is driven as the publication sink drives it: each caller calls {@code publish} on
 * its own event-loop context. Every wait is bounded, so a future left incomplete fails the test
 * instead of hanging it.
 *
 * <p>It also proves the sink's guard for a mount built on a thread without a Vert.x context: the
 * publication fails naming the application and the store is left untouched.
 */
class DocumentStoreTest {

    /** The documented application whose document the callers publish. */
    private static final String NAME = PublicApi.NAME;

    /** The longest the test waits for a future, a caller's call, or the gate. */
    private static final long TIMEOUT_SECONDS = 10;

    private Vertx vertx;

    @BeforeEach
    void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A failed single flight is removed, so a later deployment assembles again")
    void failedSingleFlightIsRemovedSoARedeployRetries() throws Exception {
        // Given: a store, and an assembler whose first call waits for the gate and then fails with
        // the sentinel, and whose later calls succeed.
        DocumentStore store = new DocumentStore();
        CountDownLatch gate = new CountDownLatch(1);
        RuntimeException sentinel = new IllegalStateException("the first assembly fails");
        FailFirstAssembler assembler = new FailFirstAssembler(gate, sentinel);
        Context firstCaller = vertx.getOrCreateContext();
        Context secondCaller = vertx.getOrCreateContext();
        Context thirdCaller = vertx.getOrCreateContext();
        try {
            // When: the first caller starts the single flight, which stays in flight behind the gate.
            Future<Void> first = publishOn(firstCaller, store, assembler);
            assertTrue(store.hasFlight(NAME), "the first caller's flight is not in the store");

            // When: the second caller publishes while the first assembly is gated, then the gate opens.
            Future<Void> second = publishOn(secondCaller, store, assembler);
            gate.countDown();

            // Then: both callers fail with the sentinel itself.
            assertSame(sentinel, failureOf(first), "the first caller's failure");
            assertSame(sentinel, failureOf(second), "the second caller's failure");

            // Then: the failed flight is gone and nothing was stored.
            assertFalse(store.hasFlight(NAME), "the failed flight is still in the store");
            assertTrue(store.lookup(NAME).isEmpty(), "a failed assembly stored a document");
            assertFalse(store.names().contains(NAME), "a failed assembly left a stored name");
            assertEquals(1, assembler.calls(), "the second caller assembled instead of joining the flight");

            // When: a third caller publishes the same application again.
            Future<Void> third = publishOn(thirdCaller, store, assembler);

            // Then: it assembles again, succeeds, and stores the document.
            awaitSuccess(third);
            assertEquals(2, assembler.calls(), "the third caller did not assemble again");
            assertTrue(store.lookup(NAME).isPresent(), "the retried assembly stored no document");
        } finally {
            gate.countDown();
        }
    }

    @Test
    @DisplayName("A mount built outside a Vert.x context fails naming its application and publishes nothing")
    void mountBuiltOutsideAVertxContextFailsAndStoresNothing() {
        // Given: a sink for the enabled public document, and this JUnit thread, which has no Vert.x context
        DocumentStore store = new DocumentStore();
        EnabledDocuments.EnabledDocument document = new EnabledDocuments.EnabledDocument(
                NAME,
                PublicApi.class,
                ApiDocs.Access.PUBLIC,
                PublicApi.MOUNT_PATH,
                RestApplications.ContractOrigin.ANNOTATION,
                new InfoConfig("Catalog", "1.0", null));
        DocsPublicationSink sink = new DocsPublicationSink(new EnabledDocuments(List.of(document)), store);
        assertNull(Vertx.currentContext(), "the JUnit thread must have no Vert.x context");

        // When: the sink is handed the public application's mount
        Future<Void> result = sink.mountBuilt(new SnapshotRendererTest.PublicationSpec().build());

        // Then: the future has already failed with a configuration exception naming the application
        assertTrue(result.failed(), "the publication of a mount built outside a context must fail");
        RestConfigurationException failure = assertInstanceOf(RestConfigurationException.class, result.cause());
        assertTrue(failure.getMessage().contains(NAME), failure.getMessage());

        // Then: no flight was started and nothing was stored
        assertFalse(store.hasFlight(NAME), "a flight was started without a context");
        assertTrue(store.names().isEmpty(), "a document was stored without a context");
    }

    /**
     * Calls {@code publish} for {@link #NAME} on {@code caller}, as the sink does from its mount's
     * event loop, and returns the future the store returned.
     */
    private static Future<Void> publishOn(Context caller, DocumentStore store, FailFirstAssembler assembler)
            throws Exception {
        CompletableFuture<Future<Void>> returned = new CompletableFuture<>();
        caller.runOnContext(ignored -> {
            try {
                returned.complete(store.publish(NAME, caller, assembler, DocumentStoreTest::ownSnapshot));
            } catch (Throwable t) {
                returned.completeExceptionally(t);
            }
        });
        return returned.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Waits for {@code future} to fail and returns its failure, unwrapped. */
    private static Throwable failureOf(Future<Void> future) throws InterruptedException {
        try {
            future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            return failed.getCause();
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the future did not complete", incomplete);
        }
        throw new AssertionError("the future succeeded instead of failing");
    }

    /** Waits for {@code future} to succeed. */
    private static void awaitSuccess(Future<Void> future) throws InterruptedException {
        try {
            future.toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError("the future failed instead of succeeding", failed.getCause());
        } catch (TimeoutException incomplete) {
            throw new AssertionError("the future did not complete", incomplete);
        }
    }

    /** The snapshot each caller renders of its own mount, equal to the stored document's. */
    private static Snapshot ownSnapshot() {
        return Snapshot.EMPTY;
    }

    /** The document the assembler produces once it succeeds; the only place one is constructed. */
    private static PublishedDocument document() {
        return new PublishedDocument(
                "{}".getBytes(StandardCharsets.UTF_8),
                "{}\n".getBytes(StandardCharsets.UTF_8),
                "\"json-tag\"",
                "\"yaml-tag\"",
                ownSnapshot());
    }

    /**
     * An assembler double that counts its calls: the first waits for the gate and then throws the
     * sentinel, and every later call returns a document.
     */
    static final class FailFirstAssembler implements Callable<PublishedDocument> {
        private final CountDownLatch gate;
        private final RuntimeException sentinel;
        private final AtomicInteger calls = new AtomicInteger();

        FailFirstAssembler(CountDownLatch gate, RuntimeException sentinel) {
            this.gate = gate;
            this.sentinel = sentinel;
        }

        @Override
        public PublishedDocument call() throws Exception {
            if (calls.incrementAndGet() == 1) {
                if (!gate.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the gate was never opened");
                }
                throw sentinel;
            }
            return document();
        }

        int calls() {
            return calls.get();
        }
    }
}
