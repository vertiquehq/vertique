// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.core.payload.PayloadKind;
import dev.vertique.core.payload.PayloadSource;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpRequestCompletedEvent;
import dev.vertique.mcp.lifecycle.McpRequestCompletedListener;
import dev.vertique.mcp.lifecycle.McpRequestView;
import dev.vertique.mcp.lifecycle.McpToolOutput;
import io.vertx.core.buffer.Buffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A completion listener for tests: it records, for every completed request, the completion event and
 * a snapshot of what the framework-owned {@link McpRequestView} reported.
 *
 * <p>Every fact is read from the view the server passed to {@link #onCompleted(McpRequestCompletedEvent,
 * McpRequestView)}, inside the callback, so an assertion on a recorded {@link Completion} is an
 * assertion on what the server bound, never on a value the test set itself. The listener completes
 * after transport completion, so a test that needs a completion awaits it with {@link #await}
 * instead of reading right after the client's response arrives.
 *
 * <p>Public because ITs in other packages reach it through their fixtures.
 */
public final class McpRecordingCompletedListener implements McpRequestCompletedListener {
    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    private final List<Completion> completions = new ArrayList<>();

    /** One completed request: the event plus a snapshot of the view's facts. */
    public record Completion(
            McpRequestCompletedEvent event,
            Optional<String> jsonRpcRequestId,
            Map<String, List<String>> requestHeaders,
            PayloadKind requestBodyKind,
            String requestBody,
            Map<String, List<String>> responseHeaders,
            PayloadKind responseBodyKind,
            String responseBody,
            Optional<McpToolInvocationContext> toolContext,
            Optional<Map<String, Object>> toolInput,
            Optional<McpToolOutput> toolOutput) {

        /** Returns the normalized structured result, when the result's own write won settlement. */
        public Optional<Object> structuredOutput() {
            return toolOutput.flatMap(McpToolOutput::structuredContent);
        }

        /** Returns the single value of a response header, matched by lower-cased name. */
        public Optional<String> responseHeader(String name) {
            List<String> values = responseHeaders.get(name);
            return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(values.size() - 1));
        }

        /** Reports whether the request carried a request body. */
        public boolean hasRequestBody() {
            return requestBodyKind != PayloadKind.ABSENT;
        }

        /** Reports whether a terminal write bound a response body. */
        public boolean hasResponseBody() {
            return responseBodyKind != PayloadKind.ABSENT;
        }
    }

    @Override
    public void onCompleted(McpRequestCompletedEvent event) {}

    @Override
    public void onCompleted(McpRequestCompletedEvent event, McpRequestView request) {
        Completion completion = new Completion(
                event,
                request.jsonRpcRequestId(),
                request.requestHeaders(),
                request.requestBody().kind(),
                textOf(request.requestBody()),
                request.responseHeaders(),
                request.responseBody().kind(),
                textOf(request.responseBody()),
                request.toolContext(),
                request.toolInput(),
                request.toolOutput());
        synchronized (this) {
            completions.add(completion);
            notifyAll();
        }
    }

    private static String textOf(PayloadSource source) {
        return source.bufferedView().map(Buffer::toString).orElse("");
    }

    /** Returns a snapshot of every completion received so far, in arrival order. */
    public synchronized List<Completion> completions() {
        return List.copyOf(completions);
    }

    /** Returns how many completions have been received so far. */
    public synchronized int count() {
        return completions.size();
    }

    /** Awaits at least {@code count} completions, and returns every completion received so far. */
    public List<Completion> await(int count) throws InterruptedException {
        return await(completion -> true, count);
    }

    /**
     * Awaits at least {@code count} completions matching {@code filter}, and returns the matching
     * ones received so far.
     *
     * @throws AssertionError when the completions do not arrive within the bounded wait
     */
    public synchronized List<Completion> await(Predicate<Completion> filter, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEFAULT_TIMEOUT_SECONDS);
        while (matching(filter).size() < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("expected " + count + " completion(s) but received "
                        + matching(filter).size());
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return matching(filter);
    }

    /**
     * Waits up to {@code window} for at least {@code count} completions, for a negative assertion
     * that something must not arrive.
     *
     * @return {@code true} when at least {@code count} completions had arrived by the end of the window
     */
    public synchronized boolean arrivesWithin(int count, Duration window) throws InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        while (completions.size() < count) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return true;
    }

    private List<Completion> matching(Predicate<Completion> filter) {
        return completions.stream().filter(filter).toList();
    }
}
