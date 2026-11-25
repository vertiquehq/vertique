// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static dev.vertique.http.client.HttpClientItSupport.await;
import static dev.vertique.http.client.HttpClientItSupport.awaitCondition;
import static dev.vertique.http.client.HttpClientItSupport.awaitFailure;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.exception.HttpBodyLimitException.Direction;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.multipart.MultipartForm;
import io.vertx.uritemplate.UriTemplate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration tests of the request and response body bounds: a request body over {@code
 * maxRequestBytes} is refused before anything is sent, and a response read through any body codec
 * over {@code maxResponseBytes}, or declaring a larger {@code Content-Length}, fails with a body-limit
 * failure and resets the exchange.
 *
 * <p>Every test runs against a real Vert.x server on {@code 127.0.0.1} and a dynamic port.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class BodyBoundsIT {

    private static final long LIMIT = 1024;

    /** The documented default response bound: 10 MiB. */
    private static final long DEFAULT_RESPONSE_LIMIT = 10_485_760;

    /** How long the server may take to see the connection closed after the caller saw the failure. */
    private static final long CLOSE_WITHIN_MS = 500;

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

    /** A client with the given bounds; a negative bound leaves that setting at its default. */
    private HttpClient client(RecordingServer server, long maxRequestBytes, long maxResponseBytes) {
        HttpClientBuilder builder = HttpClientBuilder.create(support.vertx);
        builder.baseUrl(server.origin());
        if (maxRequestBytes >= 0) {
            builder.maxRequestBytes(maxRequestBytes);
        }
        if (maxResponseBytes >= 0) {
            builder.maxResponseBytes(maxResponseBytes);
        }
        HttpClient client = builder.build();
        support.track(client::close);
        return client;
    }

    /** Counts the chunks a server wrote while the response was open. */
    private static final class ChunkWriter {
        final AtomicInteger chunks = new AtomicInteger();
        final AtomicBoolean sawClose = new AtomicBoolean();

        /**
         * Answers 200 chunked and writes {@code chunkBytes} of {@code 'x'} every few milliseconds
         * until the connection closes, or until {@code maxChunks} (negative: unlimited) were written,
         * after which the response ends.
         */
        void write(HttpServerRequest request, int chunkBytes, int maxChunks) {
            HttpServerResponse response = request.response().setChunked(true);
            Buffer chunk = Buffer.buffer("x".repeat(chunkBytes));
            response.closeHandler(v -> sawClose.set(true));
            support.vertx.setPeriodic(5, timer -> {
                if (response.closed() || response.ended()) {
                    support.vertx.cancelTimer(timer);
                } else if (maxChunks >= 0 && chunks.get() >= maxChunks) {
                    support.vertx.cancelTimer(timer);
                    response.end();
                } else {
                    chunks.incrementAndGet();
                    response.write(chunk);
                }
            });
        }
    }

    /** A pipe target that records the bytes it received. */
    private static final class RecordingSink implements WriteStream<Buffer> {
        final AtomicLong received = new AtomicLong();

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            received.addAndGet(data.length());
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> end() {
            return Future.succeededFuture();
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler) {
            return this;
        }
    }

    /** A read stream that delivers one buffer and ends. */
    private static final class OneBufferStream implements ReadStream<Buffer> {
        private final Buffer buffer;
        private Handler<Buffer> handler;
        private Handler<Void> endHandler;
        private boolean delivered;

        OneBufferStream(Buffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            if (!delivered && handler != null) {
                delivered = true;
                handler.handle(buffer);
                if (endHandler != null) {
                    endHandler.handle(null);
                }
            }
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return resume();
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }

    private static MultiMap formOf(String value) {
        return MultiMap.caseInsensitiveMultiMap().add("k", value);
    }

    private static RecordingServer.Responder ok() {
        return (request, body) -> request.response().end("ok");
    }

    private static void assertBodyLimit(Throwable failure, Direction direction, long limit) {
        assertThat(failure).isInstanceOf(HttpBodyLimitException.class);
        HttpBodyLimitException limitFailure = (HttpBodyLimitException) failure;
        assertThat(limitFailure.direction()).isEqualTo(direction);
        assertThat(limitFailure.limitBytes()).isEqualTo(limit);
        assertThat(limitFailure.getMessage()).isNotBlank();
        assertThat(limitFailure.getMessage()).doesNotContain("127.0.0.1").doesNotContain(String.valueOf(limit));
        assertThat(limitFailure.getCause()).isNull();
    }

    private static boolean serverClosedConnection(RecordingServer server) {
        return awaitCondition(() -> server.closedConnections.get() >= 1, CLOSE_WITHIN_MS);
    }

    private static void assertNoRequestArrives(RecordingServer server) {
        assertThat(awaitCondition(() -> !server.requests.isEmpty(), 300))
                .as("the server must not receive any request")
                .isFalse();
    }

    private static void assertWritesStop(ChunkWriter writer) throws InterruptedException {
        int atClose = writer.chunks.get();
        Thread.sleep(200);
        assertThat(writer.chunks.get())
                .as("the server keeps writing after the close")
                .isEqualTo(atClose);
    }

    /** A body over {@code maxRequestBytes} is refused before sending; one at the bound is sent. */
    @Test
    @DisplayName("an oversized request body is rejected before sending")
    void oversizedRequestBodyIsRejectedBeforeSending() {
        // Given a client with maxRequestBytes(1024) and a server that counts requests
        RecordingServer server = support.server(ok());
        HttpClient client = client(server, LIMIT, -1);

        // When the caller posts a 1025-byte buffer
        Throwable failure = awaitFailure(
                client.execute(web -> web.post("/upload").sendBuffer(Buffer.buffer(new byte[(int) LIMIT + 1]))));

        // Then the future fails with a REQUEST body limit of 1024 and the server received nothing
        assertBodyLimit(failure, Direction.REQUEST, LIMIT);
        assertNoRequestArrives(server);

        // When the caller then posts a 1024-byte buffer
        HttpResponse<Buffer> response =
                await(client.execute(web -> web.post("/upload").sendBuffer(Buffer.buffer(new byte[(int) LIMIT]))));

        // Then the body is sent in full and it is the only request the server ever received
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(server.requests).hasSize(1);
        assertThat(server.requests.get(0).body().length()).isEqualTo((int) LIMIT);
    }

    /** A response body over {@code maxResponseBytes} fails the call and resets the exchange. */
    @Test
    @DisplayName("a response over the bound is cut off and the exchange reset")
    void responseOverTheBoundIsCutOffAndTheExchangeReset() throws InterruptedException {
        // Given a client with maxResponseBytes(1024) and a server that streams 512-byte chunks until closed
        ChunkWriter writer = new ChunkWriter();
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/big")) {
                writer.write(request, 512, -1);
            } else {
                request.response().end("ok");
            }
        });
        HttpClient client = client(server, -1, LIMIT);

        // When the caller runs the call
        Throwable failure = awaitFailure(client.execute(web -> web.get("/big").send()));

        // Then it fails with a RESPONSE body limit of 1024
        assertBodyLimit(failure, Direction.RESPONSE, LIMIT);

        // And the server observes the connection closed within 500 ms and its writes stop
        assertThat(serverClosedConnection(server))
                .as("the server must observe the connection closed")
                .isTrue();
        assertThat(awaitCondition(writer.sawClose::get, CLOSE_WITHIN_MS)).isTrue();
        assertWritesStop(writer);

        // And the client stays usable: the next call succeeds
        HttpResponse<Buffer> next = await(client.execute(web -> web.get("/ok").send()));
        assertThat(next.statusCode()).isEqualTo(200);

        // When a server writes one single 1500-byte chunk and keeps the response open, and the caller
        // pipes it into a sink
        RecordingServer oneChunkServer = support.server(
                (request, body) -> request.response().setChunked(true).write(Buffer.buffer(new byte[1500])));
        HttpClient oneChunkClient = client(oneChunkServer, -1, LIMIT);
        RecordingSink sink = new RecordingSink();
        Throwable chunkFailure = awaitFailure(oneChunkClient.execute(
                web -> web.get("/one").as(BodyCodec.pipe(sink)).send()));

        // Then it fails with a RESPONSE body limit of 1024, the sink holds at most 1024 bytes and the
        // server observes the connection closed
        assertBodyLimit(chunkFailure, Direction.RESPONSE, LIMIT);
        assertThat(sink.received.get()).isLessThanOrEqualTo(LIMIT);
        assertThat(serverClosedConnection(oneChunkServer)).isTrue();
    }

    /** A declared {@code Content-Length} over the bound fails before any body byte arrives. */
    @Test
    @DisplayName("a declared Content-Length over the bound fails before reading")
    void declaredContentLengthOverTheBoundFailsBeforeReading() {
        // Given a client with maxResponseBytes(1024) and a server that declares 2048 bytes and writes none
        RecordingServer server = support.server((request, body) -> {
            if (request.path().equals("/declared")) {
                request.response().putHeader("Content-Length", "2048").writeHead();
            } else {
                request.response().end("ok");
            }
        });
        HttpClient client = client(server, -1, LIMIT);

        // When the caller runs the call
        Throwable failure =
                awaitFailure(client.execute(web -> web.get("/declared").send()));

        // Then it fails with a RESPONSE body limit of 1024 although no body byte was written
        assertBodyLimit(failure, Direction.RESPONSE, LIMIT);

        // And the server observes the connection closed
        assertThat(serverClosedConnection(server))
                .as("the server must observe the connection closed")
                .isTrue();

        // And the client stays usable: the next call succeeds
        HttpResponse<Buffer> next = await(client.execute(web -> web.get("/ok").send()));
        assertThat(next.statusCode()).isEqualTo(200);

        // When a HEAD is answered with Content-Length 5000 and no body (a HEAD never has one)
        RecordingServer headServer = support.server((request, body) -> {
            request.response().putHeader("Content-Length", "5000").end();
        });
        HttpClient headClient = client(headServer, -1, LIMIT);
        HttpResponse<Buffer> head =
                await(headClient.execute(web -> web.head("/head").send()));

        // Then it passes with status 200 and no body
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isNull();
    }

    /** The bound holds whatever body codec the caller chose. */
    @Test
    @DisplayName("a caller-chosen body codec is bounded too")
    void aCallerChosenBodyCodecIsBoundedToo() throws InterruptedException {
        // Given a client with maxResponseBytes(1024) and a recording pipe target
        RecordingSink sink = new RecordingSink();

        // When the caller pipes an endless chunked response into the sink
        ChunkWriter bigWriter = new ChunkWriter();
        RecordingServer bigServer = support.server((request, body) -> bigWriter.write(request, 512, -1));
        HttpClient bigClient = client(bigServer, -1, LIMIT);
        Throwable pipeFailure = awaitFailure(bigClient.execute(
                web -> web.get("/big").as(BodyCodec.pipe(sink)).send()));

        // Then it fails with a RESPONSE body limit of 1024, the sink received at most 1024 bytes, the
        // server observes the connection closed and its writes stop
        assertBodyLimit(pipeFailure, Direction.RESPONSE, LIMIT);
        assertThat(sink.received.get()).isLessThanOrEqualTo(LIMIT);
        assertThat(serverClosedConnection(bigServer)).isTrue();
        assertThat(awaitCondition(bigWriter.sawClose::get, CLOSE_WITHIN_MS)).isTrue();
        assertWritesStop(bigWriter);

        // When the caller reads 4096 chunked bytes as a string
        ChunkWriter textWriter = new ChunkWriter();
        RecordingServer textServer = support.server((request, body) -> textWriter.write(request, 1024, 4));
        HttpClient textClient = client(textServer, -1, LIMIT);
        Throwable stringFailure = awaitFailure(textClient.execute(
                web -> web.get("/text").as(BodyCodec.string()).send()));

        // Then it fails with a RESPONSE body limit of 1024 and the server observes the connection closed
        assertBodyLimit(stringFailure, Direction.RESPONSE, LIMIT);
        assertThat(serverClosedConnection(textServer)).isTrue();

        // When the caller reads a body of exactly 1024 bytes as a string
        RecordingServer fitsServer =
                support.server((request, body) -> request.response().end("y".repeat((int) LIMIT)));
        HttpClient fitsClient = client(fitsServer, -1, LIMIT);
        HttpResponse<String> fits = await(fitsClient.execute(
                web -> web.get("/fits").as(BodyCodec.string()).send()));

        // Then it succeeds with the 1024-character string
        assertThat(fits.body()).isEqualTo("y".repeat((int) LIMIT));

        // When one HttpRequest with a caller-chosen codec is sent five times in sequence
        ChunkWriter resendWriter = new ChunkWriter();
        RecordingServer resendServer = support.server((request, body) -> {
            if (request.path().equals("/big")) {
                resendWriter.write(request, 512, -1);
            } else {
                request.response().end("y".repeat((int) LIMIT));
            }
        });
        HttpClient resendClient = client(resendServer, -1, LIMIT);
        HttpRequest<String> req = resendClient.webClient().get("/fits").as(BodyCodec.string());
        BodyCodec<String> callerCodec = req.bodyCodec();

        // Then every send returns the 1024-character body and the request still holds the caller's
        // codec instance: the counting delegate is not stacked on the reused request
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> again = await(req.send());
            assertThat(again.body()).as("send " + i).isEqualTo("y".repeat((int) LIMIT));
            assertThat(req.bodyCodec()).as("codec after send " + i).isSameAs(callerCodec);
        }

        // When a reused request for the endless response is sent twice
        HttpRequest<String> bigReq = resendClient.webClient().get("/big").as(BodyCodec.string());
        BodyCodec<String> bigCodec = bigReq.bodyCodec();

        // Then both sends fail with a RESPONSE body limit of 1024 and the codec is still the caller's
        assertBodyLimit(awaitFailure(bigReq.send()), Direction.RESPONSE, LIMIT);
        assertThat(bigReq.bodyCodec()).isSameAs(bigCodec);
        assertBodyLimit(awaitFailure(bigReq.send()), Direction.RESPONSE, LIMIT);
        assertThat(bigReq.bodyCodec()).isSameAs(bigCodec);

        // Given a client with the default response bound and a server that answers with the URI it saw
        RecordingServer templateServer =
                support.server((request, body) -> request.response().end(request.uri()));
        HttpClient templateClient = client(templateServer, -1, -1);

        // When the caller sends a URI-template request with a variable and a caller-chosen codec
        HttpResponse<String> item = await(templateClient
                .webClient()
                .get(UriTemplate.of("/items/{id}"))
                .setTemplateParam("id", "42")
                .as(BodyCodec.string())
                .send());

        // Then the server receives /items/42 and the call returns that body
        assertThat(templateServer.requests.get(0).uri()).isEqualTo("/items/42");
        assertThat(item.body()).isEqualTo("/items/42");

        // When the caller sends a template with a list variable
        HttpResponse<String> query = await(templateClient
                .webClient()
                .get(UriTemplate.of("/q{?tags*}"))
                .setTemplateParam("tags", List.of("a", "b"))
                .as(BodyCodec.string())
                .send());

        // Then the server receives /q?tags=a&tags=b
        assertThat(templateServer.requests.get(1).uri()).isEqualTo("/q?tags=a&tags=b");
        assertThat(query.body()).isEqualTo("/q?tags=a&tags=b");
    }

    /** A body exactly at a bound passes, and a bound of zero means no bound. */
    @Test
    @DisplayName("bodies exactly at the limit pass and zero means no bound")
    void bodiesExactlyAtTheLimitPassAndZeroMeansNoBound() {
        // Given a server that echoes the request length and answers /exact with 1024 bytes and /huge
        // with one byte more than the default response bound
        int hugeBytes = (int) DEFAULT_RESPONSE_LIMIT + 1;
        RecordingServer server = support.server((request, body) -> {
            switch (request.path()) {
                case "/exact" -> request.response().end(Buffer.buffer(new byte[(int) LIMIT]));
                case "/huge" -> request.response().end(Buffer.buffer(new byte[hugeBytes]));
                default -> request.response().end(String.valueOf(body.length()));
            }
        });

        // When a client with both bounds at 1024 posts 1024 bytes and reads a 1024-byte response
        HttpClient bounded = client(server, LIMIT, LIMIT);
        HttpResponse<Buffer> echoed =
                await(bounded.execute(web -> web.post("/echo").sendBuffer(Buffer.buffer(new byte[(int) LIMIT]))));
        HttpResponse<Buffer> exact =
                await(bounded.execute(web -> web.get("/exact").send()));

        // Then both pass in full
        assertThat(echoed.bodyAsString()).isEqualTo(String.valueOf(LIMIT));
        assertThat(exact.body().length()).isEqualTo((int) LIMIT);

        // When a client with both bounds at 0 posts 5000 bytes and reads a body over the default bound
        HttpClient unbounded = client(server, 0, 0);
        HttpResponse<Buffer> bigEcho =
                await(unbounded.execute(web -> web.post("/echo").sendBuffer(Buffer.buffer(new byte[5000]))));
        HttpResponse<Buffer> huge =
                await(unbounded.execute(web -> web.get("/huge").send()));

        // Then both pass: zero means no bound
        assertThat(bigEcho.bodyAsString()).isEqualTo("5000");
        assertThat(huge.body().length()).isEqualTo(hugeBytes);

        // When a client with the default bounds reads the same body
        HttpClient defaults = client(server, -1, -1);
        Throwable failure =
                awaitFailure(defaults.execute(web -> web.get("/huge").send()));

        // Then the default response bound of 10485760 bytes applies
        assertBodyLimit(failure, Direction.RESPONSE, DEFAULT_RESPONSE_LIMIT);

        // Given a client with maxRequestBytes(1024) and an unbounded client for calibration
        HttpClient requestBounded = client(server, LIMIT, -1);
        HttpClient calibrating = client(server, 0, -1);

        // When a JSON body of exactly 1024 encoded UTF-8 bytes (2-byte characters) is posted
        JsonObject json1024 = new JsonObject().put("k", "\u00e9".repeat(508));
        assertThat(json1024.toBuffer().length()).isEqualTo((int) LIMIT);
        int before = server.requests.size();
        HttpResponse<Buffer> jsonOk =
                await(requestBounded.execute(web -> web.post("/echo").sendJsonObject(json1024)));

        // Then it passes and the server receives 1024 bytes
        assertThat(jsonOk.bodyAsString()).isEqualTo(String.valueOf(LIMIT));

        // When the same JSON body is one encoded byte longer
        JsonObject json1025 = new JsonObject().put("k", "\u00e9".repeat(508) + "a");
        Throwable jsonOver =
                awaitFailure(requestBounded.execute(web -> web.post("/echo").sendJsonObject(json1025)));

        // Then it fails with a REQUEST limit of 1024 and nothing is sent
        assertBodyLimit(jsonOver, Direction.REQUEST, LIMIT);
        assertThat(server.requests).hasSize(before + 1);

        // When an unbounded client posts calibration forms of spaces and asterisks, the server's
        // received lengths give the wire cost of the empty form and of one " *" pair
        await(calibrating.execute(web -> web.post("/calibrate").sendForm(formOf(""))));
        await(calibrating.execute(web -> web.post("/calibrate").sendForm(formOf(" *".repeat(100)))));
        int baseWire = server.requestsTo("/calibrate").get(0).body().length();
        int pairWire = (server.requestsTo("/calibrate").get(1).body().length() - baseWire) / 100;

        // Then a form value of whole pairs plus filler reaches exactly 1024 wire bytes
        int pairs = ((int) LIMIT - baseWire) / pairWire;
        int filler = (int) LIMIT - baseWire - pairs * pairWire;
        String value1024 = " *".repeat(pairs) + "a".repeat(filler);
        int beforeForm = server.requests.size();
        HttpResponse<Buffer> formOk =
                await(requestBounded.execute(web -> web.post("/echo").sendForm(formOf(value1024))));
        assertThat(formOk.bodyAsString()).as("wire length the server received").isEqualTo(String.valueOf(LIMIT));

        // When the form is one wire byte longer
        Throwable formOver =
                awaitFailure(requestBounded.execute(web -> web.post("/echo").sendForm(formOf(value1024 + "a"))));

        // Then it fails with a REQUEST limit of 1024 and nothing is sent
        assertBodyLimit(formOver, Direction.REQUEST, LIMIT);
        assertThat(server.requests).hasSize(beforeForm + 1);
    }

    /** Measurement is of the encoded bytes, and unmeasurable bodies are refused while a bound is set. */
    @Test
    @DisplayName(
            "JSON and form bodies are measured encoded and stream and multipart bodies are refused while the bound is set")
    void jsonAndFormBodiesAreMeasuredEncodedAndStreamAndMultipartBodiesAreRefusedWhileTheBoundIsSet() {
        // Given a client with maxRequestBytes(1024) and a server that counts requests
        RecordingServer server = support.server(ok());
        HttpClient client = client(server, LIMIT, -1);

        // When the caller posts a JSON body of 600 two-byte characters: fewer than 1024 characters,
        // more than 1024 encoded bytes
        JsonObject wideJson = new JsonObject().put("k", "é".repeat(600));
        assertThat(wideJson.encode().length()).isLessThan((int) LIMIT);
        Throwable jsonFailure =
                awaitFailure(client.execute(web -> web.post("/json").sendJsonObject(wideJson)));

        // Then it is refused as a REQUEST body limit of 1024
        assertBodyLimit(jsonFailure, Direction.REQUEST, LIMIT);

        // When the caller posts a form of 200 two-byte characters: 200 characters, 1200 encoded bytes
        MultiMap wideForm = MultiMap.caseInsensitiveMultiMap().add("k", "é".repeat(200));
        Throwable formFailure =
                awaitFailure(client.execute(web -> web.post("/form").sendForm(wideForm)));

        // Then it is refused as a REQUEST body limit of 1024
        assertBodyLimit(formFailure, Direction.REQUEST, LIMIT);

        // When the caller sends a small stream body and a small multipart body
        Throwable streamFailure = awaitFailure(client.execute(
                web -> web.post("/stream").sendStream(new OneBufferStream(Buffer.buffer("0123456789")))));
        Throwable multipartFailure = awaitFailure(client.execute(web ->
                web.post("/multipart").sendMultipartForm(MultipartForm.create().attribute("a", "b"))));

        // Then both are refused as a REQUEST body limit of 1024
        assertBodyLimit(streamFailure, Direction.REQUEST, LIMIT);
        assertBodyLimit(multipartFailure, Direction.REQUEST, LIMIT);

        // And the server received none of the four
        assertNoRequestArrives(server);

        // When the caller posts a small JSON body and a small form
        HttpResponse<Buffer> json =
                await(client.execute(web -> web.post("/json").sendJsonObject(new JsonObject().put("k", "v"))));
        HttpResponse<Buffer> form = await(client.execute(web ->
                web.post("/form").sendForm(MultiMap.caseInsensitiveMultiMap().add("k", "v"))));

        // Then both pass and are the only requests the server received
        assertThat(json.statusCode()).isEqualTo(200);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(server.requests).hasSize(2);
    }
}
