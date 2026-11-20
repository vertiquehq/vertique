// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.SseProtocolException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.codec.SseEvent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of {@code HttpStream.events()}: incremental parsing over real connections, the
 * idle timer, the per-event bound, back-pressure, the content-type check and the last event ID.
 *
 * <p>Every test runs against a real Vert.x server on {@code 127.0.0.1} and a dynamic port; waits are
 * bounded, nothing is left open and a captured blocked-thread warning fails the test.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SseStreamIT {

    private static final String EVENT_STREAM = "text/event-stream";

    private static HttpClientItSupport support;

    @BeforeAll
    static void startVertx() {
        support = HttpClientItSupport.start();
    }

    @AfterEach
    void closeResources() {
        support.closeResources();
        support.assertNoBlockedThreadWarning();
    }

    @AfterAll
    static void stopVertx() {
        support.stop();
    }

    // --- fixtures ---

    private HttpClient client(RecordingServer server, Consumer<HttpClientBuilder> customize) {
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx);
        builder.baseUrl(server.origin());
        customize.accept(builder);
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    private HttpClient client(RecordingServer server) {
        return client(server, b -> {});
    }

    /** One event reduced to its four observable values. */
    private record Ev(String id, String event, String data, int retry) {

        static Ev of(SseEvent event) {
            return new Ev(event.id(), event.event(), event.data(), event.retry());
        }
    }

    private static Ev ev(String id, String event, String data) {
        return new Ev(id, event, data, 0);
    }

    /** One {@code stream} call whose {@code events()} is read as soon as the head is delivered. */
    private static final class Reading {
        final List<Ev> events = new CopyOnWriteArrayList<>();
        final AtomicReference<HttpStream> stream = new AtomicReference<>();
        final AtomicReference<Context> context = new AtomicReference<>();
        final AtomicReference<ReadStream<SseEvent>> eventStream = new AtomicReference<>();
        volatile Optional<String> lastEventIdAtHead = Optional.of("unset");
        final Future<HttpStream> future;

        Reading(HttpClient client, String path) {
            future = client.stream(CallOptions.defaults(), web -> web.get(path), null);
            future.onSuccess(delivered -> {
                stream.set(delivered);
                context.set(Vertx.currentContext());
                ReadStream<SseEvent> read = delivered.events();
                lastEventIdAtHead = delivered.lastEventId();
                eventStream.set(read);
                read.handler(event -> events.add(Ev.of(event)));
            });
        }

        HttpStream head() {
            return await(future);
        }
    }

    private static Reading read(HttpClient client, String path) {
        return new Reading(client, path);
    }

    /** Writes {@code data} to the response in chunks whose sizes come from {@code sizes}, pausing at a full queue. */
    private static final class Pump {
        private static final int WRITES_PER_TURN = 256;
        private final HttpServerResponse response;
        private final byte[] data;
        private final IntUnaryOperator sizes;
        private final boolean end;
        private int offset;

        Pump(HttpServerResponse response, byte[] data, IntUnaryOperator sizes, boolean end) {
            this.response = response;
            this.data = data;
            this.sizes = sizes;
            this.end = end;
        }

        void run() {
            int turnWrites = 0;
            while (offset < data.length && !response.closed()) {
                if (response.writeQueueFull()) {
                    response.drainHandler(v -> run());
                    return;
                }
                if (++turnWrites > WRITES_PER_TURN) {
                    // The test server is on an event loop too: yield so its own write loop never blocks it.
                    io.vertx.core.Vertx.currentContext().runOnContext(v -> run());
                    return;
                }
                int size = Math.max(1, Math.min(sizes.applyAsInt(offset), data.length - offset));
                response.write(Buffer.buffer(Arrays.copyOfRange(data, offset, offset + size)));
                offset += size;
            }
            if (end && !response.closed()) {
                response.end();
            }
        }
    }

    private static HttpServerResponse eventStreamHead(io.vertx.core.http.HttpServerRequest request) {
        HttpServerResponse response = request.response().setChunked(true).putHeader("Content-Type", EVENT_STREAM);
        response.writeHead();
        return response;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean serverClosedConnections(RecordingServer server, int count) {
        return awaitCondition(() -> server.closedConnections.get() >= count, HttpClientItSupport.WAIT_MS);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /** Reads the number of events the stream holds, on the stream's context. */
    private static int heldEvents(Context context, HttpStream stream) {
        java.util.concurrent.CompletableFuture<Integer> held = new java.util.concurrent.CompletableFuture<>();
        context.runOnContext(v -> held.complete(((ActiveStream) stream).heldEvents()));
        try {
            return held.get(HttpClientItSupport.WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError("could not read the held event count", e);
        }
    }

    // --- AC-028 ---

    /**
     * AC-028: comment lines are consumed silently but every one of them restarts the stream idle timer.
     */
    @Test
    @DisplayName("comment lines reset the idle timer and are not surfaced")
    void commentLinesResetTheIdleTimerAndAreNotSurfaced() {
        // Given a 500 ms stream idle timeout and a server that writes the head, then ": ping" every 250 ms
        // for 2000 ms, then one event and the end
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            AtomicInteger pings = new AtomicInteger();
            support.vertx.setPeriodic(250, id -> {
                if (response.closed()) {
                    support.vertx.cancelTimer(id);
                } else if (pings.incrementAndGet() <= 8) {
                    response.write(": ping\n");
                } else {
                    support.vertx.cancelTimer(id);
                    response.write("data: x\n\n");
                    response.end();
                }
            });
        });
        HttpClient client = client(server, b -> b.streamIdleTimeout(500, TimeUnit.MILLISECONDS));

        // When the caller consumes events() of the stream for GET /sse
        long start = System.nanoTime();
        Reading reading = read(client, "/sse");
        HttpStream stream = reading.head();
        await(stream.completion());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // Then completion() succeeded and no idle timeout fired although more than 500 ms passed in total
        assertThat(elapsedMs).isGreaterThan(500);
        // And exactly one event was delivered, with data x, and no comment text is surfaced
        assertThat(reading.events).containsExactly(ev(null, "message", "x"));
        assertThat(reading.events.toString()).doesNotContain("ping");
    }

    // --- AC-029 ---

    private static byte[] repeat(String unit, int times) {
        return utf8(unit.repeat(times));
    }

    /**
     * AC-029: an oversized or endless event fails the stream with the EVENT bound and resets the exchange,
     * counting the raw bytes of the event being assembled; a long stream of small events or of heartbeat comments is
     * not bounded in total.
     */
    @Test
    @DisplayName("an oversized or endless event fails the stream")
    void oversizedOrEndlessEventFailsTheStream() {
        // Given a client with maxEventBytes(1024) and a server whose paths write 2000 bytes that never end an event
        // (a data line, a comment line and an unknown field) and keep the response open, plus a path with 100 events
        // of 100 bytes and one with 2200 bytes of heartbeats followed by one event
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            byte[] payload =
                    switch (request.path()) {
                        case "/data" -> utf8("data: " + "a".repeat(2000));
                        case "/comment" -> utf8(": " + "a".repeat(2000));
                        case "/unknown" -> utf8("foo: " + "a".repeat(2000));
                        case "/heartbeats" -> utf8(":ping\n".repeat(200) + ": c\n\n".repeat(200) + "data: x\n\n");
                        default -> repeat("data: " + "a".repeat(92) + "\n\n", 100);
                    };
            new Pump(
                            response,
                            payload,
                            offset -> 100,
                            !request.path().equals("/data")
                                    && !request.path().equals("/comment")
                                    && !request.path().equals("/unknown"))
                    .run();
        });
        HttpClient client = client(server, b -> b.maxEventBytes(1024));

        // When the caller consumes events() on each of the three failing paths
        int run = 0;
        for (String path : List.of("/data", "/comment", "/unknown")) {
            Reading reading = read(client, path);
            Throwable failure = awaitFailure(reading.head().completion());

            // Then completion() fails with HttpBodyLimitException, direction EVENT and limit 1024
            assertThat(failure).as(path).isInstanceOf(HttpBodyLimitException.class);
            HttpBodyLimitException limit = (HttpBodyLimitException) failure;
            assertThat(limit.direction()).as(path).isEqualTo(HttpBodyLimitException.Direction.EVENT);
            assertThat(limit.limitBytes()).as(path).isEqualTo(1024);
            assertThat(reading.events).as(path).isEmpty();
            // And the server observes the connection closed
            assertThat(serverClosedConnections(server, ++run))
                    .as("%s: connection closed", path)
                    .isTrue();
        }

        // And 100 events of 100 bytes, whose total exceeds 1024 bytes, complete normally
        Reading many = read(client, "/many");
        await(many.head().completion());
        assertThat(many.events).hasSize(100);

        // And 2200 bytes of heartbeat comments, also above 1024 bytes, complete with the one event after them
        Reading beats = read(client, "/heartbeats");
        await(beats.head().completion());
        assertThat(beats.events).hasSize(1);
    }

    // --- AC-030 ---

    /** The flooding server: numbered events packed into 64 KiB chunks until the client goes away. */
    private static final class EventFlood {
        final AtomicLong written = new AtomicLong();
        final AtomicInteger drains = new AtomicInteger();
        volatile boolean full;
        // A safety stop only: the test closes the client and server long before it, and a slow machine must not
        // reach it between the stall and the resume.
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        /** Every event is the same size: {@code data: } + 8 digits + a blank line. */
        static final int EVENT_BYTES = 16;

        /** Building a chunk takes milliseconds, so one event-loop turn writes only this many of them. */
        private static final int CHUNKS_PER_TURN = 2;

        private long nextSequence;

        Buffer nextChunk() {
            StringBuilder sb = new StringBuilder(64 * 1024);
            for (int i = 0; i < 64 * 1024 / EVENT_BYTES; i++) {
                // Padded by hand: String.format parses its pattern on every call and held the event loop.
                String digits = Long.toString(nextSequence++);
                sb.append("data: ")
                        .append("0".repeat(8 - digits.length()))
                        .append(digits)
                        .append("\n\n");
            }
            return Buffer.buffer(sb.toString());
        }

        void pump(HttpServerResponse response) {
            int chunks = 0;
            while (!response.closed() && System.nanoTime() < deadlineNanos) {
                if (response.writeQueueFull()) {
                    full = true;
                    response.drainHandler(v -> {
                        full = false;
                        drains.incrementAndGet();
                        pump(response);
                    });
                    return;
                }
                if (chunks++ == CHUNKS_PER_TURN) {
                    // The server shares an event loop with the client and the blocked-thread checker: yield, so a
                    // fast reader, or a client that has already gone away and whose close this loop would
                    // otherwise only see on returning, never keeps the loop in this write loop.
                    Vertx.currentContext().runOnContext(v -> pump(response));
                    return;
                }
                Buffer chunk = nextChunk();
                response.write(chunk);
                written.addAndGet(chunk.length());
            }
            if (!response.closed()) {
                response.end();
            }
        }
    }

    /**
     * AC-030: pausing events() pauses the response, so no further event arrives and the server's write queue
     * fills; after resume() events continue without a gap and the server's writes proceed.
     */
    @Test
    @DisplayName("paused events pause the response")
    void pausedEventsPauseTheResponse() {
        // Given a server writing numbered events in 64 KiB chunks and noting a full write queue
        EventFlood flood = new EventFlood();
        RecordingServer server = support.server((request, body) -> flood.pump(eventStreamHead(request)));
        HttpClient client = client(server);

        // When the caller sets a handler on events(), pauses after the first event and waits 1000 ms
        // Not copy-on-write: the handler appends thousands of events in one event-loop turn.
        Queue<Long> sequences = new ConcurrentLinkedQueue<>();
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<ReadStream<SseEvent>> eventStream = new AtomicReference<>();
        AtomicReference<Context> context = new AtomicReference<>();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/flood"), null);
        future.onSuccess(stream -> {
            context.set(Vertx.currentContext());
            ReadStream<SseEvent> events = stream.events();
            eventStream.set(events);
            events.handler(event -> {
                sequences.add(Long.parseLong(event.data()));
                if (delivered.incrementAndGet() == 1) {
                    events.pause();
                }
            });
        });
        HttpStream stream = await(future);
        assertThat(awaitCondition(() -> delivered.get() >= 1, HttpClientItSupport.WAIT_MS))
                .as("first event")
                .isTrue();
        sleep(1_000);

        // Then the handler received no further event during the pause
        assertThat(delivered.get()).as("events during the pause").isEqualTo(1);
        // And more than one server write is pending, yet at most one client read's events are held (Netty reads
        // at most 65536 bytes at a time, and every event is EVENT_BYTES long), at every sample
        assertThat(flood.written.get()).as("bytes written").isGreaterThan(2L * 64 * 1024);
        for (int i = 0; i < 10; i++) {
            assertThat(heldEvents(context.get(), stream))
                    .as("held events while paused, sample %d", i)
                    .isLessThanOrEqualTo(65536 / EventFlood.EVENT_BYTES + 1);
            sleep(50);
        }
        // And the server's write queue is full and its writes are stalled
        assertThat(flood.full).as("server write queue full while paused").isTrue();
        long writtenWhenStalled = flood.written.get();
        sleep(500);
        assertThat(flood.written.get())
                .as("server bytes written during a further 500 ms")
                .isEqualTo(writtenWhenStalled);
        assertThat(delivered.get()).isEqualTo(1);

        // And after resume() events flow again, none was lost while paused, and the server's writes proceed
        context.get().runOnContext(v -> eventStream.get().resume());
        assertThat(awaitCondition(() -> delivered.get() >= 1_000, HttpClientItSupport.WAIT_MS))
                .as("events after resume")
                .isTrue();
        assertThat(awaitCondition(() -> flood.written.get() > writtenWhenStalled, HttpClientItSupport.WAIT_MS))
                .as("server writes after resume")
                .isTrue();
        assertThat(flood.drains.get()).as("server drain events").isPositive();
        List<Long> seen = List.copyOf(sequences);
        for (int i = 0; i < seen.size(); i++) {
            assertThat(seen.get(i)).as("sequence number at position %d", i).isEqualTo(i);
        }

        stream.cancel();
        awaitFailure(stream.completion());
    }

    // --- AC-081 ---

    /**
     * AC-081: events() on a head that is not text/event-stream fails the stream with SseProtocolException
     * naming the media type only and resets the exchange; an event-stream head with parameters is read; the
     * client adds no Accept header.
     */
    @Test
    @DisplayName("events() on a non event-stream head fails the stream and resets the exchange")
    void eventsOnANonEventStreamHeadFailsTheStreamAndResetsTheExchange() {
        // Given a server whose /json answers application/json and keeps the body open, whose /plain answers
        // with no Content-Type and whose /sse answers text/event-stream; charset=utf-8 with one event
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = request.response().setChunked(true);
            switch (request.path()) {
                case "/json" -> {
                    response.putHeader("Content-Type", "application/json; charset=utf-8");
                    response.write("{}");
                }
                case "/plain" -> response.write(" ");
                default -> {
                    response.putHeader("Content-Type", "text/event-stream; charset=utf-8");
                    response.end("data: x\n\n");
                }
            }
        });
        HttpClient client = client(server);

        // When the caller opens stream for each path and calls events()
        Reading json = read(client, "/json");
        Throwable jsonFailure = awaitFailure(json.head().completion());
        // And the server observed the /json connection closed right after that failure, before the next path
        assertThat(serverClosedConnections(server, 1))
                .as("connection of /json closed")
                .isTrue();
        Reading plain = read(client, "/plain");
        Throwable plainFailure = awaitFailure(plain.head().completion());
        Reading sse = read(client, "/sse");
        await(sse.head().completion());

        // Then /json fails with SseProtocolException naming the media type only, and the exchange is reset
        assertThat(jsonFailure).isInstanceOf(SseProtocolException.class);
        assertThat(((SseProtocolException) jsonFailure).contentType()).isEqualTo(Optional.of("application/json"));
        assertThat(json.events).isEmpty();
        // And /plain fails with SseProtocolException with an empty content type
        assertThat(plainFailure).isInstanceOf(SseProtocolException.class);
        assertThat(((SseProtocolException) plainFailure).contentType()).isEmpty();
        assertThat(plain.events).isEmpty();
        // And /sse delivers one event with data x and completes
        assertThat(sse.events).containsExactly(ev(null, "message", "x"));
        // And no Accept header was added by stream on any of the three requests
        assertThat(server.requests).hasSize(3);
        for (HttpClientItSupport.RecordedRequest request : server.requests) {
            assertThat(request.headers().get("Accept"))
                    .as("Accept of %s", request.uri())
                    .isNull();
        }
    }

    // --- AC-082 ---

    /**
     * AC-082: an id persists to later events until changed, an id with NUL is ignored, and lastEventId()
     * exposes the buffer: empty before the first event, the last id after the last.
     */
    @Test
    @DisplayName("the last event ID persists across events and is exposed")
    void lastEventIdPersistsAcrossEventsAndIsExposed() {
        // Given a server whose /ids writes six events with ids absent, 7, none, NUL-containing, 9 and none
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            for (String block : List.of(
                    "data: a\n\n",
                    "id: 7\ndata: b\n\n",
                    "data: c\n\n",
                    "id: x\u0000y\ndata: d\n\n",
                    "id: 9\ndata: e\n\n",
                    "data: f\n\n")) {
                response.write(block);
            }
            response.end();
        });
        HttpClient client = client(server);

        // When the caller consumes events() and reads lastEventId() before the first event and after the last
        Reading reading = read(client, "/ids");
        HttpStream stream = reading.head();
        await(stream.completion());

        // Then the delivered id() values are null, 7, 7, 7, 9 and 9
        assertThat(reading.events).extracting(Ev::id).containsExactly(null, "7", "7", "7", "9", "9");
        assertThat(reading.events).extracting(Ev::data).containsExactly("a", "b", "c", "d", "e", "f");
        // And lastEventId() is empty before the first event and 9 after the last
        assertThat(reading.lastEventIdAtHead).isEmpty();
        assertThat(stream.lastEventId()).isEqualTo(Optional.of("9"));
    }

    // --- review follow-ups ---

    private static final String MANY_EVENTS = "data: x\n\n".repeat(500);

    /** The cancel failure and the throwing handler need the stream inside the handler. */
    private record Handled(Future<HttpStream> future, AtomicInteger delivered) {}

    private Handled handled(HttpClient client, String path, Consumer<HttpStream> onEvent) {
        AtomicInteger delivered = new AtomicInteger();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get(path), null);
        future.onSuccess(stream -> stream.events().handler(event -> {
            if (delivered.incrementAndGet() == 1) {
                onEvent.accept(stream);
            }
        }));
        return new Handled(future, delivered);
    }

    /**
     * AC-030 (supporting): cancelling from an event handler stops delivery at once, even though the rest of
     * the chunk's events were already parsed.
     */
    @Test
    @DisplayName("cancel from an event handler stops delivery at once")
    void cancelFromAnEventHandlerStopsDeliveryAtOnce() {
        // Given a server writing 500 small events in one write and keeping the response open
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            response.write(MANY_EVENTS);
        });
        HttpClient client = client(server);

        // When the handler calls cancel() on the first event
        Handled handled = handled(client, "/cancel", HttpStream::cancel);
        HttpStream stream = await(handled.future());
        Throwable failure = awaitFailure(stream.completion());

        // Then completion() fails with the cancel failure, and no second event is ever delivered
        assertThat(failure).isInstanceOf(dev.vertique.http.client.exception.HttpExchangeCancelledException.class);
        sleep(300);
        assertThat(handled.delivered().get()).isEqualTo(1);
        // And the server sees the connection closed
        assertThat(serverClosedConnections(server, 1)).isTrue();
    }

    /**
     * Cancelling from the handler of the last event fails the stream even though the body had already ended.
     */
    @Test
    @DisplayName("cancel on the last event fails the stream even after the body ended")
    void cancelOnTheLastEventFailsTheStreamEvenAfterTheBodyEnded() {
        // Given a server writing three events and ending the response in one write
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            response.end("data: 1\n\ndata: 2\n\ndata: 3\n\n");
        });
        HttpClient client = client(server);

        // When the handler calls cancel() on the third and last event
        AtomicInteger delivered = new AtomicInteger();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/last"), null);
        future.onSuccess(stream -> stream.events().handler(event -> {
            if (delivered.incrementAndGet() == 3) {
                stream.cancel();
            }
        }));
        HttpStream stream = await(future);
        Throwable failure = awaitFailure(stream.completion());

        // Then completion() fails with the cancel failure, not success, and exactly three events were delivered
        assertThat(failure).isInstanceOf(dev.vertique.http.client.exception.HttpExchangeCancelledException.class);
        sleep(300);
        assertThat(delivered.get()).isEqualTo(3);
    }

    /**
     * A handler that throws fails the stream with that exception and delivers nothing more.
     */
    @Test
    @DisplayName("a throwing event handler fails the stream")
    void aThrowingEventHandlerFailsTheStream() {
        // Given a server writing 500 small events in one write and keeping the response open
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            response.write(MANY_EVENTS);
        });
        HttpClient client = client(server);

        // When the handler throws on the first event
        RuntimeException thrown = new IllegalStateException("handler failure");
        Handled handled = handled(client, "/throw", stream -> {
            throw thrown;
        });
        HttpStream stream = await(handled.future());
        Throwable failure = awaitFailure(stream.completion());

        // Then completion() fails with that exception and no further event is delivered
        assertThat(failure).isSameAs(thrown);
        sleep(300);
        assertThat(handled.delivered().get()).isEqualTo(1);
        // And the server sees the connection closed
        assertThat(serverClosedConnections(server, 1)).isTrue();
    }

    /**
     * AC-082 (supporting): an id whose event has not been dispatched is not the last event ID.
     */
    @Test
    @DisplayName("an id of an undispatched event is not the last event ID")
    void lastEventIdIgnoresAnIdOfAnUndispatchedEvent() {
        // Given a server whose /partial dispatches one event with id 5 and then stalls inside an event with
        // id 10, and whose /none stalls inside its first event, which has id 10
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            response.write(
                    request.path().equals("/partial") ? "id: 5\ndata: a\n\nid: 10\ndata: par" : "id: 10\ndata: par");
        });
        HttpClient client = client(server);

        // When the caller reads events() and waits until the bytes have been parsed
        Reading partial = read(client, "/partial");
        HttpStream partialStream = partial.head();
        assertThat(awaitCondition(() -> partial.events.size() == 1, HttpClientItSupport.WAIT_MS))
                .isTrue();
        Reading none = read(client, "/none");
        HttpStream noneStream = none.head();
        sleep(500);

        // Then lastEventId() is the id of the last dispatched event, or empty if none, never 10
        assertThat(partialStream.lastEventId()).isEqualTo(Optional.of("5"));
        assertThat(noneStream.lastEventId()).isEmpty();
        assertThat(none.events).isEmpty();

        partialStream.cancel();
        noneStream.cancel();
        awaitFailure(partialStream.completion());
        awaitFailure(noneStream.completion());
    }

    /**
     * AC-082 (supporting): while the stream is paused with events held, lastEventId() is the id of the last
     * event delivered to the handler.
     */
    @Test
    @DisplayName("while paused the last event ID is that of the last delivered event")
    void lastEventIdWhilePausedIsThatOfTheLastDeliveredEvent() {
        // Given a server writing three events with ids 1, 2 and 3 in one write, then ending
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response = eventStreamHead(request);
            response.end("id: 1\ndata: a\n\nid: 2\ndata: b\n\nid: 3\ndata: c\n\n");
        });
        HttpClient client = client(server);

        // When the handler pauses after the first event and the caller waits
        List<String> ids = new CopyOnWriteArrayList<>();
        AtomicReference<ReadStream<SseEvent>> eventStream = new AtomicReference<>();
        AtomicReference<Context> context = new AtomicReference<>();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/p"), null);
        future.onSuccess(delivered -> {
            context.set(Vertx.currentContext());
            ReadStream<SseEvent> events = delivered.events();
            eventStream.set(events);
            events.handler(event -> {
                ids.add(event.id());
                if (ids.size() == 1) {
                    events.pause();
                }
            });
        });
        HttpStream stream = await(future);
        assertThat(awaitCondition(() -> ids.size() == 1, HttpClientItSupport.WAIT_MS))
                .isTrue();
        sleep(500);

        // Then lastEventId() is 1, the id of the last event delivered, and nothing more was delivered
        assertThat(stream.lastEventId()).isEqualTo(Optional.of("1"));
        assertThat(ids).containsExactly("1");
        // And after resume() the rest arrives and the id ends at 3
        context.get().runOnContext(v -> eventStream.get().resume());
        await(stream.completion());
        assertThat(ids).containsExactly("1", "2", "3");
        assertThat(stream.lastEventId()).isEqualTo(Optional.of("3"));
    }

    // --- FR-011 ---

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static byte[] wireStream(int bigEventLength) {
        String bigEvent = "x".repeat(bigEventLength);
        byte[] before = utf8("event: tick\r\ndata: one\r\ndata: two\r\n\r\n"
                + "retry: 1500\rdata:a\r\r"
                + "foo: bar\nevent: x\n\n"
                + "id: 5\n: comment\ndata: é€😀\n\n"
                + "id: x\u0000y\ndata: [DONE]\n\n"
                + "data: "
                + bigEvent
                + "\n\n"
                + "data: last\n\n");
        byte[] out = new byte[BOM.length + before.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(before, 0, out, BOM.length, before.length);
        return out;
    }

    private static List<Ev> wireExpected(int bigEventLength) {
        return List.of(
                ev(null, "tick", "one\ntwo"),
                new Ev(null, "message", "a", 1500),
                ev("5", "message", "é€😀"),
                ev("5", "message", "[DONE]"),
                ev("5", "message", "x".repeat(bigEventLength)),
                ev("5", "message", "last"));
    }

    /**
     * FR-011 (supporting): the grammar cases that matter on the wire, delivered by a real server a byte at
     * a time and in one large chunk, parse to the same events, including a multi-byte character split
     * across chunks and an event above Vert.x's own codec's practical limit.
     */
    @Test
    @DisplayName("a server delivers the grammar cases over the wire in one-byte and large chunks")
    void serverDeliversTheGrammarCasesOverTheWireInOneByteAndLargeChunks() {
        // Given one stream with a BOM, CRLF, CR, an unknown field, a comment, multi-byte text, an ignored NUL id
        // and a large event, served a byte at a time on /byte and in 256 KiB chunks on /large
        int smallBig = 8_192;
        int largeBig = 300_000;
        RecordingServer server = support.server((request, body) -> {
            boolean oneByte = request.path().equals("/byte");
            byte[] payload = wireStream(oneByte ? smallBig : largeBig);
            new Pump(eventStreamHead(request), payload, offset -> oneByte ? 1 : 256 * 1024, true).run();
        });
        HttpClient client = client(server);

        // When the caller consumes events() of each path
        Reading oneByte = read(client, "/byte");
        await(oneByte.head().completion());
        Reading large = read(client, "/large");
        await(large.head().completion());

        // Then both deliveries yield the same events in order
        assertThat(oneByte.events).isEqualTo(wireExpected(smallBig));
        assertThat(large.events).isEqualTo(wireExpected(largeBig));
        assertThat(oneByte.stream.get().lastEventId()).contains("5");
    }

    // --- FR-012 ---

    /**
     * FR-012 (supporting): the bound is per event, not per stream: thousands of small events whose total is
     * far above maxEventBytes complete, however the bytes are chunked.
     */
    @Test
    @DisplayName("many small events over the total bound complete because the bound is per event")
    void manySmallEventsOverTheTotalBoundCompleteBecauseTheBoundIsPerEvent() {
        // Given maxEventBytes(1024) and a server writing 2000 events of 100 bytes with an event of 1000 bytes
        // in the middle, chunked in 7 bytes and in 4000 bytes, and 150 small events a byte at a time
        int events = 2_000;
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < events; i++) {
            if (i == 1_000) {
                all.append("data: ").append("b".repeat(990)).append("\n\n");
            }
            all.append("data: ").append("a".repeat(92)).append("\n\n");
        }
        byte[] payload = utf8(all.toString());
        byte[] small = utf8(("data: " + "a".repeat(92) + "\n\n").repeat(150));
        RecordingServer server = support.server((request, body) -> {
            boolean oneByte = request.path().equals("/byte");
            int mode = oneByte ? 1 : request.path().equals("/seven") ? 7 : 4_000;
            new Pump(eventStreamHead(request), oneByte ? small : payload, offset -> mode, true).run();
        });
        HttpClient client = client(server, b -> b.maxEventBytes(1024));

        // When the caller consumes events() of each path
        Reading seven = read(client, "/seven");
        await(seven.head().completion());
        Reading big = read(client, "/big");
        await(big.head().completion());
        Reading oneByte = read(client, "/byte");
        await(oneByte.head().completion());

        // Then every one completes normally with every event delivered, the total being above 1024 bytes
        assertThat(payload.length).isGreaterThan(1024 * 100);
        assertThat(seven.events).hasSize(events + 1);
        assertThat(big.events).hasSize(events + 1);
        assertThat(oneByte.events).hasSize(150);
        assertThat(seven.events.get(1_000).data()).isEqualTo("b".repeat(990));
    }
}
