// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SelfSignedCertificate;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;

/**
 * Shared support for the integration tests of this module.
 *
 * <p>Owns one Vert.x instance that runs the blocked-thread checker with a 100 ms interval and a
 * 200 ms event-loop execution limit, and captures every warning the checker logs. A test class
 * calls {@link #assertNoBlockedThreadWarning()} after each test and again after closing everything;
 * a captured warning fails the class. Servers bind {@code 127.0.0.1} on a dynamic port and every
 * server, client and builder registered through {@link #track(Supplier)} is closed before Vert.x.
 */
final class HttpClientItSupport {

    /** Logger name Vert.x uses for blocked-thread reports. */
    static final String BLOCKED_THREAD_LOGGER = "io.vertx.core.impl.BlockedThreadChecker";

    /** The event-loop execution limit of the guard, in milliseconds. */
    static final long MAX_EVENT_LOOP_EXECUTE_MS = 200;

    /** How long a test waits for something that must happen. */
    static final long WAIT_MS = 10_000;

    final Vertx vertx;

    private final Logger blockedThreadLogger;
    private final EventLoopStallClassifier classifier = new EventLoopStallClassifier(MAX_EVENT_LOOP_EXECUTE_MS);
    private final List<EventLoopStallClassifier.Report> blockedThreadReports = new ArrayList<>();
    private final AppenderBase<ILoggingEvent> blockedThreadEvents = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                EventLoopStallClassifier.Report report;
                try {
                    report = classify(event);
                } catch (RuntimeException e) {
                    report = new EventLoopStallClassifier.Report(
                            event.getFormattedMessage(), true, "classification failed: " + e);
                }
                synchronized (blockedThreadReports) {
                    blockedThreadReports.add(report);
                }
            }
        }

        /** Classifies on the checker's thread, while the loop thread's CPU time is current. */
        private EventLoopStallClassifier.Report classify(ILoggingEvent event) {
            var proxy = event.getThrowableProxy();
            StackTraceElement top = proxy == null || proxy.getStackTraceElementProxyArray().length == 0
                    ? null
                    : proxy.getStackTraceElementProxyArray()[0].getStackTraceElement();
            return classifier.classify(event.getFormattedMessage(), top);
        }
    };
    private final List<Supplier<Future<?>>> closers = new CopyOnWriteArrayList<>();

    private HttpClientItSupport(Vertx vertx, Logger blockedThreadLogger) {
        this.vertx = vertx;
        this.blockedThreadLogger = blockedThreadLogger;
    }

    /** Starts Vert.x with the blocked-thread checker tuned for the integration suite. */
    static HttpClientItSupport start() {
        Logger logger = (Logger) LoggerFactory.getLogger(BLOCKED_THREAD_LOGGER);
        // Loads the JSON mapper outside the event loop so first-use class loading under a coverage agent
        // and CPU load does not trip the blocked-thread guard.
        new JsonObject().put("warm", "up").encode();
        HttpClientItSupport support = new HttpClientItSupport(
                Vertx.vertx(new VertxOptions()
                        .setBlockedThreadCheckInterval(100)
                        .setBlockedThreadCheckIntervalUnit(TimeUnit.MILLISECONDS)
                        .setMaxEventLoopExecuteTime(MAX_EVENT_LOOP_EXECUTE_MS)
                        .setMaxEventLoopExecuteTimeUnit(TimeUnit.MILLISECONDS)
                        // a stack with every report, so the classifier can see where the thread is stopped
                        .setWarningExceptionTime(1)
                        .setWarningExceptionTimeUnit(TimeUnit.MILLISECONDS)),
                logger);
        try {
            support.warmUp();
        } catch (RuntimeException | Error e) {
            support.classifier.close();
            throw e;
        }
        support.blockedThreadEvents.start();
        logger.addAppender(support.blockedThreadEvents);
        return support;
    }

    /**
     * Runs one plain and one TLS exchange with a body before the guard is armed, so first-use class
     * loading, TLS trust-store loading and the pooled buffer allocator's first chunk happen here and
     * not on an event loop inside a test.
     */
    private void warmUp() {
        try {
            Class.forName("dev.vertique.http.client.exception.HttpBodyLimitException");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        WebClientOptions trustAll =
                new WebClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false);
        for (boolean tls : new boolean[] {false, true}) {
            SelfSignedCertificate certificate = tls ? SelfSignedCertificate.create() : null;
            HttpServerOptions serverOptions = tls
                    ? new HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions())
                    : new HttpServerOptions();
            HttpServer warm = vertx.createHttpServer(serverOptions)
                    .requestHandler(r -> r.body().onSuccess(b -> r.response().end("ok")));
            await(warm.listen(0, "127.0.0.1"));
            WebClient client = WebClient.create(vertx, tls ? trustAll : new WebClientOptions());
            try {
                await(client.post(warm.actualPort(), "127.0.0.1", "/warm").sendBuffer(Buffer.buffer("warm-up")));
            } finally {
                client.close();
                await(warm.close());
                if (certificate != null) {
                    certificate.delete();
                }
            }
        }
    }

    /** Registers a close action; closers run in reverse registration order. */
    void track(Supplier<Future<?>> closer) {
        closers.add(closer);
    }

    /** Registers a synchronous cleanup action. */
    void track(Runnable cleanup) {
        closers.add(() -> {
            cleanup.run();
            return Future.succeededFuture();
        });
    }

    /** Closes every tracked resource, then fails if the blocked-thread checker warned. */
    void closeResources() {
        List<Supplier<Future<?>>> reversed = new ArrayList<>(closers);
        java.util.Collections.reverse(reversed);
        closers.clear();
        List<Future<?>> pending = new ArrayList<>();
        for (Supplier<Future<?>> closer : reversed) {
            try {
                pending.add(closer.get());
            } catch (RuntimeException e) {
                pending.add(Future.failedFuture(e));
            }
        }
        for (Future<?> f : pending) {
            try {
                await(f);
            } catch (AssertionError | RuntimeException ignored) {
                // a close that fails must not hide the test outcome; the blocked-thread check still runs
            }
        }
    }

    /**
     * Fails when the blocked-thread checker reported a turn that product code spent on the loop since the
     * previous check, then forgets the reports, so one test's report does not fail the tests after it. A turn
     * that only the host stalled (see {@link EventLoopStallClassifier}) is printed and does not fail.
     */
    void assertNoBlockedThreadWarning() {
        List<EventLoopStallClassifier.Report> reports;
        synchronized (blockedThreadReports) {
            reports = new ArrayList<>(blockedThreadReports);
            blockedThreadReports.clear();
        }
        List<String> warnings = new ArrayList<>();
        for (EventLoopStallClassifier.Report report : reports) {
            if (report.blocking()) {
                warnings.add(report.message() + " (" + report.reason() + ")");
            } else {
                System.err.println("Event-loop turn not counted: " + report.message() + " (" + report.reason() + ")");
            }
        }
        if (!warnings.isEmpty()) {
            throw new AssertionError("Vert.x reported a blocked thread during the test: " + warnings);
        }
    }

    /** Forgets the blocked-thread reports so far, for a test that provokes them on purpose. */
    void discardBlockedThreadReports() {
        synchronized (blockedThreadReports) {
            blockedThreadReports.clear();
        }
    }

    /** Whether the checker reported a turn that has not been checked yet. */
    boolean hasBlockedThreadReport() {
        synchronized (blockedThreadReports) {
            return !blockedThreadReports.isEmpty();
        }
    }

    /** Closes tracked resources and Vert.x, then reports any blocked-thread warning. */
    void stop() {
        try {
            closeResources();
            await(vertx.close());
        } finally {
            blockedThreadLogger.detachAppender(blockedThreadEvents);
            blockedThreadEvents.stop();
            classifier.close();
        }
        assertNoBlockedThreadWarning();
    }

    /** Starts a recording server on {@code 127.0.0.1} with a dynamic port. */
    RecordingServer server(RecordingServer.Responder responder) {
        RecordingServer server = RecordingServer.start(vertx, responder, new HttpServerOptions());
        track(server::close);
        return server;
    }

    /**
     * Starts a recording HTTPS server on {@code 127.0.0.1} with a fresh self-signed certificate that
     * no client trusts unless told to.
     */
    RecordingServer tlsServer(RecordingServer.Responder responder) {
        SelfSignedCertificate certificate = SelfSignedCertificate.create();
        RecordingServer server = RecordingServer.start(
                vertx, responder, new HttpServerOptions().setSsl(true).setKeyCertOptions(certificate.keyCertOptions()));
        track(certificate::delete);
        track(server::close);
        return server;
    }

    /** Waits for a future and returns its value; a failure becomes an {@link AssertionError}. */
    static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("future failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("future did not complete within " + WAIT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a future", e);
        }
    }

    /** Waits for a future that must fail and returns its failure. */
    static Throwable awaitFailure(Future<?> future) {
        try {
            Object value = future.toCompletionStage().toCompletableFuture().get(WAIT_MS, TimeUnit.MILLISECONDS);
            throw new AssertionError("future succeeded with " + value + " but must fail");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("future did not fail within " + WAIT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a future", e);
        }
    }

    /** Polls until the condition holds or {@code timeoutMs} passes; returns the final state. */
    static boolean awaitCondition(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    /** One request as the server saw it. */
    record RecordedRequest(String method, String uri, MultiMap headers, Buffer body) {

        String target() {
            return method + " " + uri;
        }
    }

    /**
     * A real Vert.x HTTP server bound to {@code 127.0.0.1} that records each request after its body
     * arrived, counts connections, and answers through a {@link Responder}.
     */
    static final class RecordingServer {

        /** Decides the response; may answer later or never. */
        @FunctionalInterface
        interface Responder {
            void respond(HttpServerRequest request, Buffer body);
        }

        final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger openedConnections = new AtomicInteger();
        final AtomicInteger closedConnections = new AtomicInteger();
        final AtomicInteger openConnections = new AtomicInteger();
        final AtomicInteger peakOpenConnections = new AtomicInteger();

        private final HttpServer http;

        private final String scheme;

        private RecordingServer(HttpServer http, String scheme) {
            this.http = http;
            this.scheme = scheme;
        }

        static RecordingServer start(Vertx vertx, Responder responder, HttpServerOptions options) {
            HttpServer http = vertx.createHttpServer(options);
            RecordingServer server = new RecordingServer(http, options.isSsl() ? "https" : "http");
            Handler<HttpServerRequest> handler = request -> request.body().onSuccess(body -> {
                MultiMap headers = MultiMap.caseInsensitiveMultiMap().addAll(request.headers());
                server.requests.add(new RecordedRequest(request.method().name(), request.uri(), headers, body));
                responder.respond(request, body);
            });
            http.connectionHandler(connection -> {
                server.openedConnections.incrementAndGet();
                int open = server.openConnections.incrementAndGet();
                server.peakOpenConnections.accumulateAndGet(open, Math::max);
                connection.closeHandler(v -> {
                    server.openConnections.decrementAndGet();
                    server.closedConnections.incrementAndGet();
                });
            });
            http.requestHandler(handler);
            await(http.listen(0, "127.0.0.1"));
            return server;
        }

        int port() {
            return http.actualPort();
        }

        String origin() {
            return scheme + "://127.0.0.1:" + port();
        }

        Future<Void> close() {
            return http.close();
        }

        /** Requests recorded for one path (the URI without its query), in arrival order. */
        List<RecordedRequest> requestsTo(String path) {
            return requests.stream()
                    .filter(r -> r.uri().equals(path) || r.uri().startsWith(path + "?"))
                    .toList();
        }
    }
}
