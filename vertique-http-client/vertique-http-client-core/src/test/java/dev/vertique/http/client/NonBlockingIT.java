// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.http.client.HttpClientItSupport.RecordingServer;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.codec.SseEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Integration test that a large server-sent event stream never blocks an event loop.
 *
 * <p>The shared support runs Vert.x with a blocked-thread check interval of 100 ms and a 200 ms
 * event-loop execution limit and captures every warning the checker logs; a captured warning fails
 * the test.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class NonBlockingIT {

    private static final long TEN_MIB = 10L * 1024 * 1024;
    private static final long STREAM_WAIT_SECONDS = 120;

    private static HttpClientItSupport support;

    @BeforeAll
    static void startVertx() {
        support = HttpClientItSupport.start();
    }

    @AfterAll
    static void stopVertx() {
        support.stop();
    }

    /** The bytes the server will write, and how many events they hold. */
    private record Payload(byte[] bytes, int events) {}

    /**
     * Builds at least 10 MiB of numbered events: a comment line now and then, a multi-line event now and
     * then, and one event of 512 KiB, so that events and chunk boundaries fall everywhere.
     */
    private static Payload tenMebibytes() {
        StringBuilder sb = new StringBuilder((int) TEN_MIB + 1024);
        int events = 0;
        while (sb.length() < TEN_MIB) {
            int n = events++;
            if (n % 97 == 0) {
                sb.append(": keep-alive ").append(n).append('\n');
            }
            if (n == 5_000) {
                sb.append("data: ")
                        .append(n)
                        .append(' ')
                        .append("w".repeat(512 * 1024))
                        .append("\n\n");
            } else if (n % 11 == 0) {
                sb.append("data: ")
                        .append(n)
                        .append("\ndata: second line ")
                        .append("m".repeat(40))
                        .append("\n\n");
            } else {
                sb.append("data: ")
                        .append(n)
                        .append(' ')
                        .append("e".repeat(40 + n % 160))
                        .append("\n\n");
            }
        }
        return new Payload(sb.toString().getBytes(StandardCharsets.UTF_8), events);
    }

    /**
     * Chunk sizes drawn log-uniformly from 1 byte to 64 KiB with a fixed seed, after a burst of single-byte
     * chunks: every size class from one byte to 64 KiB occurs, and the 10 MiB take a few thousand writes.
     */
    private static int[] chunkSizes(int totalBytes) {
        Random random = new Random(20_260_618L);
        List<Integer> sizes = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < 4_096; i++) {
            sizes.add(1);
            sum++;
        }
        double logMax = Math.log(64 * 1024);
        while (sum < totalBytes) {
            int size = (int) Math.min(64 * 1024, Math.max(1, Math.round(Math.exp(random.nextDouble() * logMax))));
            sizes.add(size);
            sum += size;
        }
        sizes.add(64 * 1024);
        sizes.add(64 * 1024);
        return sizes.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Writes the payload in the given chunk sizes, pausing while the write queue is full. */
    private static final class Writer {
        private final HttpServerResponse response;
        private final byte[] bytes;
        private final int[] sizes;
        private int index;
        private int offset;
        final AtomicLong written = new AtomicLong();

        Writer(HttpServerResponse response, byte[] bytes, int[] sizes) {
            this.response = response;
            this.bytes = bytes;
            this.sizes = sizes;
        }

        void run() {
            while (offset < bytes.length && !response.closed()) {
                if (response.writeQueueFull()) {
                    response.drainHandler(v -> run());
                    return;
                }
                int size = Math.min(sizes[Math.min(index++, sizes.length - 1)], bytes.length - offset);
                response.write(Buffer.buffer(java.util.Arrays.copyOfRange(bytes, offset, offset + size)));
                offset += size;
                written.addAndGet(size);
            }
            if (!response.closed()) {
                response.end();
            }
        }
    }

    /**
     * AC-072: 10 MiB of server-sent events, written in chunks from 1 byte to 64 KiB, is parsed completely and
     * in order while the blocked-thread checker watches every event loop; no warning is captured.
     */
    @Test
    @DisplayName("a 10 MiB stream raises no blocked-thread warning")
    void tenMebibyteStreamRaisesNoBlockedThreadWarning() throws Exception {
        // Given a server writing at least 10 MiB of numbered events in chunks of 1 byte to 64 KiB
        Payload payload = tenMebibytes();
        assertThat(payload.bytes().length).isGreaterThanOrEqualTo((int) TEN_MIB);
        int[] sizes = chunkSizes(payload.bytes().length);
        assertThat(sizes).contains(1, 64 * 1024);
        RecordingServer server = support.server((request, body) -> {
            HttpServerResponse response =
                    request.response().setChunked(true).putHeader("Content-Type", "text/event-stream");
            response.writeHead();
            new Writer(response, payload.bytes(), sizes).run();
        });
        VertiqueHttpClientBuilder builder = VertiqueHttpClientBuilder.create(support.vertx);
        support.track(builder::close);
        builder.baseUrl(server.origin());
        VertiqueHttpClient client = builder.build();
        support.track(client::close);

        // When the caller reads all events() of the stream
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger outOfOrder = new AtomicInteger();
        AtomicLong dataChars = new AtomicLong();
        Future<HttpStream> future = client.stream(CallOptions.defaults(), web -> web.get("/ten"), null);
        future.onSuccess(stream -> stream.events().handler((SseEvent event) -> {
            String data = event.data();
            int space = firstNonDigit(data);
            if (Integer.parseInt(data, 0, space, 10) != delivered.getAndIncrement()) {
                outOfOrder.incrementAndGet();
            }
            dataChars.addAndGet(data.length());
        }));
        HttpStream stream = future.toCompletionStage().toCompletableFuture().get(STREAM_WAIT_SECONDS, TimeUnit.SECONDS);
        awaitCompletion(stream.completion());

        // Then the stream is parsed completely: the event count equals the count the server wrote, in order
        assertThat(delivered.get()).isEqualTo(payload.events());
        assertThat(outOfOrder.get()).as("events out of order").isZero();
        assertThat(dataChars.get()).isGreaterThan(TEN_MIB / 2);
        // And no blocked-thread warning was captured
        support.assertNoBlockedThreadWarning();
    }

    private static int firstNonDigit(String data) {
        int i = 0;
        while (i < data.length() && Character.isDigit(data.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void awaitCompletion(Future<Void> completion) throws InterruptedException {
        try {
            completion.toCompletionStage().toCompletableFuture().get(STREAM_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("the stream failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("the stream did not complete within " + STREAM_WAIT_SECONDS + " s");
        }
    }
}
