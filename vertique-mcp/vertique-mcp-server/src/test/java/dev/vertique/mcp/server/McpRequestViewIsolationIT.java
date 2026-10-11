// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static dev.vertique.mcp.server.McpRequestViewITSupport.RESPONSE_MARKER;
import static dev.vertique.mcp.server.McpRequestViewITSupport.RETAINING_TOOL;
import static dev.vertique.mcp.server.McpRequestViewITSupport.await;
import static dev.vertique.mcp.server.McpRequestViewITSupport.callBody;
import static dev.vertique.mcp.server.McpRequestViewITSupport.post;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.payload.PayloadSource;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestView;
import dev.vertique.mcp.lifecycle.McpToolOutput;
import dev.vertique.mcp.server.McpRequestViewITSupport.RetainingTool;
import dev.vertique.mcp.server.McpRequestViewITSupport.Started;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A completion listener that reads everything the {@link McpRequestView} offers and tries every
 * writable-looking path through it changes nothing: not the response the caller received, not the
 * bytes another listener reads, and not what the view prints.
 *
 * <p>Modeled on {@code McpInputLifecycleObservationIT}: real port-0 servers bound and connected on
 * {@code 127.0.0.1}, every owned resource closed on every teardown path. The fixture tool keeps its
 * normalized arguments as a deliberately mutable tree, so the view's wrapper is the only thing between
 * a listener and the tool's live state.
 *
 * <p>The view's byte sources are only reachable after the response was written (the listener runs at
 * transport completion), so a byte-path write cannot alter an already-sent response; the response-
 * identity assertions therefore prove "the caller's response is unaffected" for the run, while the
 * recorded write attempts and re-reads prove that no write path took effect at all.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class McpRequestViewIsolationIT {

    private static final String AUTHORIZATION = "Bearer secret-token-xyz";
    private static final String ARGUMENT_VALUE = "ada-lovelace-distinct-777";
    private static final String ARGUMENTS =
            "{\"customer\":{\"name\":\"" + ARGUMENT_VALUE + "\"},\"tags\":[\"vip\",\"returning\"]}";
    private static final Map<String, String> CALLER_HEADERS = Map.of("Authorization", AUTHORIZATION);
    private static final int REQUEST_ID = 1;

    private final Vertx vertx = Vertx.vertx();
    private final List<HttpServer> servers = new ArrayList<>();
    private HttpClient rawClient;
    private WebClient client;

    @AfterEach
    void tearDown() throws Exception {
        List<Future<Void>> closes = new ArrayList<>();
        servers.forEach(server -> closes.add(server.close()));
        if (rawClient != null) {
            closes.add(rawClient.close());
        }
        Future.join(closes.toArray(Future[]::new))
                .compose(ignored -> vertx.close())
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
        servers.clear();
        rawClient = null;
        client = null;
    }

    @Test
    @DisplayName("a listener that tries every write path leaves the caller's responses identical to a baseline run")
    void shouldLeaveTheCallersResponsesIdenticalToABaselineRunWhenAListenerTriesEveryWritePath() throws Exception {
        // Given: a baseline server whose only listener records, and a second identical server whose
        // listener tries every writable-looking path through the view; the fixture tool of each retains
        // its (mutable) normalized arguments and reports the previous call's tree in the next result.
        McpRecordingCompletedListener baselineListener = new McpRecordingCompletedListener();
        Started baseline = startServer(Set.of(baselineListener));
        MutatingListener mutating = new MutatingListener();
        Started mutated = startServer(Set.of(mutating));
        String body = callBody(RETAINING_TOOL, ARGUMENTS, REQUEST_ID);
        openClient();

        // When: the same two calls (fixed request id) are made against each server, awaiting each
        // completion so the second call can only observe what the first listener left behind.
        WireResponse baselineFirst = capture(baseline, body);
        baselineListener.await(1);
        WireResponse baselineSecond = capture(baseline, body);
        baselineListener.await(2);
        WireResponse mutatedFirst = capture(mutated, body);
        mutating.await(1);
        WireResponse mutatedSecond = capture(mutated, body);
        mutating.await(2);

        // Then (DECISIVE): both responses are byte-identical to the baseline run's.
        assertThat(baselineSecond.bodyText())
                .as("the second call reports the first call's retained tree, so a leaked write would show")
                .contains("customer=")
                .contains(ARGUMENT_VALUE);
        assertThat(mutatedFirst).as("first response").isEqualTo(baselineFirst);
        assertThat(mutatedSecond)
                .as("second response, which reports the tree the first listener saw")
                .isEqualTo(baselineSecond);

        // Then: every attempt really ran, and every one of them was refused or had no effect.
        for (Observation observation : mutating.observations()) {
            assertThat(observation.failure()).as("the listener's own run").isNull();
            assertThat(observation.attempted())
                    .as("the listener must really have tried every write path, or the proof is vacuous")
                    .hasSize(MutatingListener.EXPECTED_ATTEMPTS);
            assertThat(observation.unblocked())
                    .as("a map or list write that was not refused")
                    .isEmpty();
            assertThat(text(observation.requestAfterSameSource())).isEqualTo(text(observation.requestStreamBefore()));
            assertThat(text(observation.responseAfterSameSource())).isEqualTo(text(observation.responseStreamBefore()));
            assertThat(text(observation.requestAfterFresh())).isEqualTo(text(observation.requestStreamBefore()));
            assertThat(text(observation.responseAfterFresh())).isEqualTo(text(observation.responseStreamBefore()));
        }
        assertThat(mutating.observations()).hasSize(2);
    }

    @Test
    @DisplayName("two listeners that each try every write path both read the original request and response bytes")
    void shouldLetEachListenerReadTheOriginalBytesWhenBothTryEveryWritePath() throws Exception {
        // Given: one server with two symmetric listeners; each records its copy of the bytes before it
        // tries every write path, and the listener set is unordered, so either may run second.
        MutatingListener first = new MutatingListener();
        MutatingListener second = new MutatingListener();
        Started started = startServer(Set.of(first, second));
        String body = callBody(RETAINING_TOOL, ARGUMENTS, REQUEST_ID);
        openClient();

        // When: one tool call is made.
        WireResponse response = capture(started, body);
        first.await(1);
        second.await(1);

        // Then (DECISIVE): whichever listener ran second still read exactly the bytes that were sent
        // and received, through every read path, before and after both listeners wrote.
        assertThat(response.status()).isEqualTo(200);
        for (MutatingListener listener : List.of(first, second)) {
            assertThat(listener.observations())
                    .as("one completion per listener")
                    .hasSize(1);
            Observation observation = listener.observations().get(0);
            assertThat(observation.failure()).as("the listener's own run").isNull();
            assertThat(text(observation.requestBefore()))
                    .as("request via bufferedView, before writes")
                    .isEqualTo(body);
            assertThat(text(observation.requestStreamBefore()))
                    .as("request via bufferedStream, before writes")
                    .isEqualTo(body);
            assertThat(text(observation.responseBefore()))
                    .as("response via bufferedView, before writes")
                    .isEqualTo(response.bodyText());
            assertThat(text(observation.responseStreamBefore()))
                    .as("response via bufferedStream, before writes")
                    .isEqualTo(response.bodyText());
            assertThat(text(observation.requestAfterFresh()))
                    .as("request re-read after writes")
                    .isEqualTo(body);
            assertThat(text(observation.responseAfterFresh()))
                    .as("response re-read after writes")
                    .isEqualTo(response.bodyText());
            assertThat(observation.unblocked())
                    .as("a write that was not refused")
                    .isEmpty();
            assertThat(observation.requestHeadersBefore().get("authorization")).containsExactly(AUTHORIZATION);
        }
    }

    @Test
    @DisplayName("the view's toString prints no header, body, argument or response text")
    void shouldPrintNoHeaderBodyArgumentOrResponseTextFromTheViewToString() throws Exception {
        // Given: a server whose listener captures the view's textual form, and a call that carries a
        // distinctive credential, argument value and response marker.
        CompletableFuture<String> printed = new CompletableFuture<>();
        McpRequestCompletedListener listener = new McpRequestCompletedListener() {
            @Override
            public void onCompleted(McpRequestCompletedEvent event) {}

            @Override
            public void onCompleted(McpRequestCompletedEvent event, McpRequestView request) {
                printed.complete(String.valueOf(request));
            }
        };
        Started started = startServer(Set.of(listener));
        String body = callBody(RETAINING_TOOL, ARGUMENTS, REQUEST_ID);
        openClient();

        // When: one tool call is made and the view is printed inside the callback.
        WireResponse response = capture(started, body);
        String text = printed.get(10, TimeUnit.SECONDS);

        // Then (DECISIVE): the sensitive values really were on the wire, and none is in the text.
        assertThat(response.bodyText()).as("the response carries the marker").contains(RESPONSE_MARKER);
        assertThat(text)
                .as("DECISIVE: a printed view must disclose no request, response or credential text")
                .isNotBlank()
                .doesNotContain("secret-token-xyz")
                .doesNotContain(AUTHORIZATION)
                .doesNotContain(ARGUMENT_VALUE)
                .doesNotContain(RESPONSE_MARKER)
                .doesNotContain(body)
                .doesNotContain("tools/call");
    }

    // --- harness ---

    private Started startServer(Set<McpRequestCompletedListener> listeners) throws Exception {
        RetainingTool tool = new RetainingTool(RETAINING_TOOL);
        Started started = McpRequestViewITSupport.start(vertx, listeners, Set.of(tool), new AtomicBoolean());
        servers.add(started.server());
        return started;
    }

    private void openClient() {
        rawClient = vertx.createHttpClient();
        client = WebClient.wrap(rawClient);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private WireResponse capture(Started started, String body) throws Exception {
        HttpResponse<Buffer> response = await(post(client, started.port(), RETAINING_TOOL, body, CALLER_HEADERS));
        Map<String, List<String>> headers = new TreeMap<>();
        response.headers().forEach(entry -> headers.computeIfAbsent(
                        entry.getKey().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                .add(entry.getValue()));
        headers.remove("date");
        return new WireResponse(response.statusCode(), headers, response.body().getBytes());
    }

    /** What the caller received: status, deterministic headers and body bytes. */
    private record WireResponse(int status, Map<String, List<String>> headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WireResponse that
                    && status == that.status
                    && headers.equals(that.headers)
                    && Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            return 31 * status + headers.hashCode() + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return status + " " + headers + " " + bodyText();
        }
    }

    /** One completion as a mutating listener saw it and left it. */
    private record Observation(
            Throwable failure,
            List<String> attempted,
            List<String> unblocked,
            Map<String, List<String>> requestHeadersBefore,
            byte[] requestBefore,
            byte[] requestStreamBefore,
            byte[] responseBefore,
            byte[] responseStreamBefore,
            byte[] requestAfterSameSource,
            byte[] responseAfterSameSource,
            byte[] requestAfterFresh,
            byte[] responseAfterFresh) {}

    /**
     * Reads the request and response through every read path, then tries every writable-looking path
     * through the view, then re-reads. Symmetric by construction: two instances behave identically
     * whichever runs second.
     */
    private static final class MutatingListener implements McpRequestCompletedListener {
        /** Header, tool-input and tool-output write attempts, counted so a silent skip is visible. */
        static final int EXPECTED_ATTEMPTS = 19;

        private final List<Observation> observations = new ArrayList<>();

        @Override
        public void onCompleted(McpRequestCompletedEvent event) {}

        @Override
        public void onCompleted(McpRequestCompletedEvent event, McpRequestView view) {
            List<String> attempted = new ArrayList<>();
            List<String> unblocked = new ArrayList<>();
            Throwable failure = null;
            Map<String, List<String>> headersBefore = Map.of();
            byte[] requestBefore = new byte[0];
            byte[] requestStreamBefore = new byte[0];
            byte[] responseBefore = new byte[0];
            byte[] responseStreamBefore = new byte[0];
            byte[] requestAfterSameSource = new byte[0];
            byte[] responseAfterSameSource = new byte[0];
            byte[] requestAfterFresh = new byte[0];
            byte[] responseAfterFresh = new byte[0];
            try {
                headersBefore = copyOf(view.requestHeaders());
                PayloadSource request = view.requestBody();
                PayloadSource response = view.responseBody();
                requestBefore = request.bufferedView().orElseThrow().getBytes();
                requestStreamBefore = request.bufferedStream().orElseThrow().readAllBytes();
                responseBefore = response.bufferedView().orElseThrow().getBytes();
                responseStreamBefore = response.bufferedStream().orElseThrow().readAllBytes();

                writeThroughMaps(view, attempted, unblocked);
                writeThroughBytes(request);
                writeThroughBytes(response);

                requestAfterSameSource = request.bufferedStream().orElseThrow().readAllBytes();
                responseAfterSameSource = response.copyPrefix(Integer.MAX_VALUE);
                requestAfterFresh =
                        view.requestBody().bufferedView().orElseThrow().getBytes();
                responseAfterFresh =
                        view.responseBody().bufferedStream().orElseThrow().readAllBytes();
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                Observation observation = new Observation(
                        failure,
                        attempted,
                        unblocked,
                        headersBefore,
                        requestBefore,
                        requestStreamBefore,
                        responseBefore,
                        responseStreamBefore,
                        requestAfterSameSource,
                        responseAfterSameSource,
                        requestAfterFresh,
                        responseAfterFresh);
                synchronized (this) {
                    observations.add(observation);
                    notifyAll();
                }
            }
        }

        private static void writeThroughMaps(McpRequestView view, List<String> attempted, List<String> unblocked) {
            Map<String, List<String>> requestHeaders = view.requestHeaders();
            attempt(attempted, unblocked, "requestHeaders.put", () -> requestHeaders.put("x", List.of("y")));
            attempt(attempted, unblocked, "requestHeaders.remove", () -> requestHeaders.remove("authorization"));
            attempt(attempted, unblocked, "requestHeaders.clear", requestHeaders::clear);
            attempt(attempted, unblocked, "requestHeaders.values.add", () -> requestHeaders
                    .get("authorization")
                    .add("x"));
            attempt(attempted, unblocked, "requestHeaders.values.set", () -> requestHeaders
                    .get("authorization")
                    .set(0, "x"));

            Map<String, List<String>> responseHeaders = view.responseHeaders();
            attempt(attempted, unblocked, "responseHeaders.put", () -> responseHeaders.put("x", List.of("y")));
            attempt(attempted, unblocked, "responseHeaders.clear", responseHeaders::clear);
            attempt(attempted, unblocked, "responseHeaders.values.add", () -> responseHeaders
                    .get("content-type")
                    .add("x"));

            Map<String, Object> input = view.toolInput().orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> customer = (Map<String, Object>) input.get("customer");
            @SuppressWarnings("unchecked")
            List<Object> tags = (List<Object>) input.get("tags");
            attempt(attempted, unblocked, "toolInput.put", () -> input.put("x", "y"));
            attempt(attempted, unblocked, "toolInput.remove", () -> input.remove("tags"));
            attempt(attempted, unblocked, "toolInput.clear", input::clear);
            attempt(attempted, unblocked, "toolInput.entry.setValue", () -> input.entrySet()
                    .iterator()
                    .next()
                    .setValue("x"));
            attempt(attempted, unblocked, "toolInput.nestedMap.put", () -> customer.put("name", "mallory"));
            attempt(attempted, unblocked, "toolInput.nestedMap.remove", () -> customer.remove("name"));
            attempt(attempted, unblocked, "toolInput.nestedList.add", () -> tags.add("x"));
            attempt(attempted, unblocked, "toolInput.nestedList.set", () -> tags.set(0, "x"));
            attempt(attempted, unblocked, "toolInput.nestedList.clear", tags::clear);

            McpToolOutput output = view.toolOutput().orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> structured =
                    (Map<String, Object>) output.structuredContent().orElseThrow();
            attempt(attempted, unblocked, "toolOutput.put", () -> structured.put("x", "y"));
            attempt(attempted, unblocked, "toolOutput.clear", structured::clear);
        }

        private static void writeThroughBytes(PayloadSource source) throws IOException {
            Buffer buffer = source.bufferedView().orElseThrow();
            buffer.setByte(0, (byte) 'X');
            buffer.appendString("junk");
            byte[] prefix = source.copyPrefix(Integer.MAX_VALUE);
            Arrays.fill(prefix, (byte) 0);
            source.bufferedStream().orElseThrow().readAllBytes();
        }

        private static void attempt(List<String> attempted, List<String> unblocked, String label, Runnable write) {
            attempted.add(label);
            try {
                write.run();
                unblocked.add(label);
            } catch (UnsupportedOperationException refused) {
                // The view refused the write: the expected result.
            }
        }

        private static Map<String, List<String>> copyOf(Map<String, List<String>> source) {
            Map<String, List<String>> copy = new TreeMap<>();
            source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            return copy;
        }

        synchronized List<Observation> observations() {
            return List.copyOf(observations);
        }

        /** Awaits until {@code count} completions have been fully processed, mutations included. */
        synchronized void await(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (observations.size() < count) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(
                            "expected " + count + " completion(s) but received " + observations.size());
                }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }
}
