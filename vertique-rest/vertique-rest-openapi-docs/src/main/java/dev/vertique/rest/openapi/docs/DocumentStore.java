// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the published documents of one component, keyed by application name, and assembles each
 * document once.
 *
 * <p>The first composition to publish an application installs a promise in the map and assembles the
 * document on a worker thread of its own context. Every later composition joins that promise
 * without blocking: once it completes, the later composition renders its own snapshot on a worker
 * thread of its own context and compares it with the stored one. A failed assembly removes its
 * promise before it fails the joined compositions, so a later publication assembles again.
 * Every future returned by {@link #publish} completes on the context that called it.
 */
@Singleton
final class DocumentStore {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentStore.class);

    /** Where a stored document comes from, as the stored line names it. */
    enum Source {

        /** A document assembled from the running code. */
        GENERATED("generated"),

        /** An application's own contract, served as parsed. */
        SERVED_CONTRACT("served contract");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        /**
         * Returns how the stored line names the source.
         *
         * @return the label
         */
        String label() {
            return label;
        }
    }

    /** The promise of each application: incomplete while assembling, then the stored document. */
    private final ConcurrentHashMap<String, Future<PublishedDocument>> flights = new ConcurrentHashMap<>();

    @Inject
    DocumentStore() {}

    /**
     * Looks up a published document.
     *
     * @param name the application name
     * @return the document, or empty when none is stored
     */
    Optional<PublishedDocument> lookup(String name) {
        Future<PublishedDocument> flight = flights.get(name);
        return flight != null && flight.succeeded() ? Optional.of(flight.result()) : Optional.empty();
    }

    /**
     * The application names with a stored document.
     *
     * @return the names, in sorted order
     */
    Set<String> names() {
        Set<String> names = new TreeSet<>();
        flights.forEach((name, flight) -> {
            if (flight.succeeded()) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * Reports whether an assembly is in flight for the name.
     *
     * @param name the application name
     * @return {@code true} when an assembly is in flight
     */
    boolean hasFlight(String name) {
        Future<PublishedDocument> flight = flights.get(name);
        return flight != null && !flight.isComplete();
    }

    /**
     * Publishes the document of an application, or compares a later composition's snapshot with
     * the stored one.
     *
     * <p>The first caller for a name assembles the document with {@code assemble} on a worker thread
     * of {@code caller} and stores it. Every other caller waits for that document without blocking,
     * then renders its own snapshot with {@code ownSnapshot} on a worker thread of {@code caller} and
     * compares it with the stored snapshot. When the assembly fails, every caller fails with the
     * failure of the assembly itself, and the next call for the name assembles again.
     *
     * @param name the application name
     * @param caller the context that called, on which the returned future completes
     * @param assemble assembles the document
     * @param ownSnapshot renders the caller's own snapshot
     * @return a future completing when the document is stored and the snapshot matches; it fails
     *     with a {@link RestConfigurationException} when the snapshot differs
     */
    Future<Void> publish(
            String name, Context caller, Callable<PublishedDocument> assemble, Callable<Snapshot> ownSnapshot) {
        return publish(name, caller, Source.GENERATED, assemble, ownSnapshot);
    }

    /**
     * Publishes the document of an application from the given source, or compares a later
     * composition's snapshot with the stored one, as {@link #publish(String, Context, Callable,
     * Callable)} does.
     *
     * <p>The stored line names the source. For a generated document the store also logs, at {@code
     * DEBUG}, how long the assembly took; a served contract's step logs its own timing line.
     *
     * @param name the application name
     * @param caller the context that called, on which the returned future completes
     * @param source where the document comes from
     * @param assemble produces the document
     * @param ownSnapshot renders the caller's own snapshot
     * @return a future completing when the document is stored and the snapshot matches; it fails
     *     with a {@link RestConfigurationException} when the snapshot differs
     */
    Future<Void> publish(
            String name,
            Context caller,
            Source source,
            Callable<PublishedDocument> assemble,
            Callable<Snapshot> ownSnapshot) {
        Promise<PublishedDocument> mine = Promise.promise();
        Future<PublishedDocument> existing = flights.putIfAbsent(name, mine.future());
        if (existing == null) {
            return assembleAndStore(name, caller, source, assemble, mine);
        }
        return compareWithStored(name, caller, ownSnapshot, existing);
    }

    private Future<Void> assembleAndStore(
            String name,
            Context caller,
            Source source,
            Callable<PublishedDocument> assemble,
            Promise<PublishedDocument> mine) {
        return caller.executeBlocking(() -> {
                    long start = System.nanoTime();
                    PublishedDocument document = assemble.call();
                    if (source == Source.GENERATED) {
                        Snapshot.MountPart mount = document.snapshot().mountPart();
                        LOG.debug(
                                "Assembled the document of application '{}' at mount '{}' in {} ms",
                                name,
                                mount.mountPath(),
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                    return document;
                })
                .transform(assembled -> {
                    if (assembled.failed()) {
                        // The promise leaves the map before the joined compositions fail, so a caller
                        // that observes the failure never finds the failed flight.
                        flights.remove(name, mine.future());
                        mine.fail(assembled.cause());
                        return Future.failedFuture(assembled.cause());
                    }
                    PublishedDocument document = assembled.result();
                    mine.complete(document);
                    LOG.info(
                            "The document of application '{}' at mount '{}' is stored (source: {})",
                            name,
                            document.snapshot().mountPart().mountPath(),
                            source.label());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> compareWithStored(
            String name, Context caller, Callable<Snapshot> ownSnapshot, Future<PublishedDocument> stored) {
        Promise<Void> result = Promise.promise();
        // The stored future completes on the context of the composition that assembled: hop to this
        // caller's own context before doing anything else, so this caller's future never completes
        // on another composition's event loop.
        stored.onComplete(assembled -> caller.runOnContext(ignored -> {
            if (assembled.failed()) {
                result.fail(assembled.cause());
                return;
            }
            caller.executeBlocking(() -> {
                        Snapshot own = ownSnapshot.call();
                        Snapshot storedSnapshot = assembled.result().snapshot();
                        LOG.debug(
                                "Compared the snapshot of application '{}' at mount '{}' with the stored document",
                                name,
                                own.mountPart().mountPath());
                        failOnDifference(name, own, storedSnapshot);
                        return null;
                    })
                    .onComplete(compared -> {
                        if (compared.succeeded()) {
                            result.complete();
                        } else {
                            result.fail(compared.cause());
                        }
                    });
        }));
        return result.future();
    }

    /**
     * Throws when the snapshots differ. The message names the application, its declaring interface,
     * its mount, and the first differing operation, and carries no digest, schema, or value.
     */
    private static void failOnDifference(String name, Snapshot own, Snapshot stored) {
        boolean mountDiffers = !own.mountPart().equals(stored.mountPart());
        Optional<String> operation = own.firstDifferingOperation(stored);
        if (!mountDiffers && operation.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("The mount of application '")
                .append(name)
                .append("' (declared by ")
                .append(own.mountPart().declaringType())
                .append(") at '")
                .append(own.mountPart().mountPath())
                .append("' differs from the document already published for the application");
        operation.ifPresent(id -> message.append(": operation '").append(id).append("' differs"));
        throw new RestConfigurationException(message.toString());
    }
}
