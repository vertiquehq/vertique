// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.rest.core.config.SseConfig;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.ReadStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Adapter that converts a {@link ReadStream}{@code <SseEvent>} into a
 * {@link ReadStream}{@code <Buffer>} formatted according to the SSE wire protocol
 * (WHATWG Server-Sent Events specification).
 *
 * <p>Each {@link SseEvent} is serialized to a sequence of text lines:
 * <ul>
 *   <li>{@code id: <id>} — if {@link SseEvent#id()} is present</li>
 *   <li>{@code event: <type>} — if {@link SseEvent#event()} is present</li>
 *   <li>{@code retry: <ms>} — if {@link SseEvent#retryMs()} is present</li>
 *   <li>{@code : <comment>} — if {@link SseEvent#comment()} is present</li>
 *   <li>{@code data: <line>} — one line per newline in the serialized data; structured objects
 *       are JSON-serialized via the process JSON codec's mapper ({@link VertiqueJson#mapper()})</li>
 *   <li>A trailing blank line ({@code \n}) to terminate the event block</li>
 * </ul>
 *
 * <p>Optional keep-alive support: when {@link SseConfig#keepAliveEnabled()} is {@code true},
 * a {@code ": keep-alive\n\n"} comment is emitted every {@link SseConfig#keepAliveIntervalMs()}
 * milliseconds to prevent idle proxies from closing the connection.
 *
 * <p>Disconnect detection: a {@link HttpServerResponse#closeHandler(Handler)} is registered
 * to cancel the keep-alive timer and close the source stream when the client disconnects.
 *
 * <p>On the first call to {@link #handler(Handler)}, the required SSE response headers
 * ({@code Cache-Control: no-cache} and {@code Connection: keep-alive}) are written to the
 * HTTP response before any data flows.
 *
 * <p>Backpressure is forwarded to the source {@link ReadStream} via {@link #pause()},
 * {@link #resume()}, and {@link #fetch(long)}.
 */
@Slf4j
class SseReadStream implements ReadStream<Buffer> {

    // --- Fields ---

    private final ReadStream<SseEvent> source;
    private final Vertx vertx;
    private final SseConfig config;
    private final HttpServerResponse response;

    private Handler<Buffer> dataHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;

    /** Pre-allocated keep-alive buffer to avoid per-fire allocation. */
    private static final Buffer KEEP_ALIVE_BUFFER = Buffer.buffer(": keep-alive\n\n");

    private long keepAliveTimerId = -1;
    private boolean headersWritten = false;

    // --- Constructor ---

    /**
     * Creates a new SSE read stream that wraps the given source stream.
     *
     * @param source   the upstream {@link ReadStream} of {@link SseEvent} values
     * @param vertx    the Vert.x instance used for the keep-alive timer
     * @param config   the SSE configuration controlling keep-alive behaviour
     * @param response the HTTP server response used for disconnect detection and header injection
     */
    SseReadStream(ReadStream<SseEvent> source, Vertx vertx, SseConfig config, HttpServerResponse response) {
        this.source = source;
        this.vertx = vertx;
        this.config = config;
        this.response = response;

        // Wire source exception → our exception handler
        source.exceptionHandler(cause -> {
            log.debug("SSE source stream failed", cause);
            cancelKeepAlive();
            if (exceptionHandler != null) {
                exceptionHandler.handle(cause);
            }
        });

        // Disconnect detection: client closed the connection
        response.closeHandler(v -> {
            log.debug("SSE client disconnected");
            cancelKeepAlive();
            // Cancel the source stream — triggers DefaultSseChannel.onClose callbacks
            source.handler(null);
            if (endHandler != null) {
                endHandler.handle(null);
            }
        });
    }

    // --- ReadStream ---

    /**
     * Sets the data handler that receives formatted {@link Buffer} values. On the first call with
     * a non-null handler, the SSE-required response headers are written and the keep-alive timer
     * is started (if enabled).
     *
     * @param handler the handler to receive SSE-formatted {@link Buffer} values, or {@code null}
     *                to cancel
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        if (handler != null) {
            ensureSseHeaders();
            startKeepAlive();
            // Register endHandler BEFORE handler to avoid missing terminal signals
            // that replay synchronously when the source is already closed
            source.endHandler(v -> {
                cancelKeepAlive();
                if (endHandler != null) {
                    endHandler.handle(null);
                }
            });
            source.handler(event -> {
                Buffer buf = formatEvent(event);
                try {
                    handler.handle(buf);
                } catch (Exception e) {
                    log.warn("SSE data handler threw", e);
                }
            });
        } else {
            cancelKeepAlive();
            source.handler(null);
        }
        return this;
    }

    /**
     * Pauses this stream. Forwards the pause to the upstream source.
     *
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<Buffer> pause() {
        source.pause();
        return this;
    }

    /**
     * Resumes this stream. Forwards the resume to the upstream source.
     *
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<Buffer> resume() {
        source.resume();
        return this;
    }

    /**
     * Switches the stream to demand-based mode. Forwards the fetch to the upstream source.
     *
     * @param amount the number of additional events to request; must be {@code >= 0}
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<Buffer> fetch(long amount) {
        source.fetch(amount);
        return this;
    }

    /**
     * Registers a handler that is called when the stream ends normally.
     *
     * @param endHandler the end handler
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }

    /**
     * Registers a handler that is called when the stream or source encounters an error.
     *
     * @param exceptionHandler the exception handler
     * @return this stream for fluent chaining
     */
    @Override
    public ReadStream<Buffer> exceptionHandler(Handler<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    // --- Internal helpers ---

    /**
     * Writes the mandatory SSE response headers ({@code Cache-Control} and
     * {@code Connection}) to the HTTP response exactly once.
     */
    private void ensureSseHeaders() {
        if (!headersWritten) {
            headersWritten = true;
            response.setChunked(true);
            response.putHeader("Cache-Control", "no-cache");
            response.putHeader("Connection", "keep-alive");
        }
    }

    /**
     * Starts the keep-alive timer if enabled in {@link SseConfig}. The timer emits a
     * {@code ": keep-alive\n\n"} comment buffer to the data handler at the configured interval.
     */
    private void startKeepAlive() {
        if (!config.keepAliveEnabled()) {
            return;
        }
        keepAliveTimerId = vertx.setPeriodic(config.keepAliveIntervalMs(), id -> {
            if (dataHandler != null) {
                dataHandler.handle(KEEP_ALIVE_BUFFER);
            }
        });
    }

    /**
     * Cancels the keep-alive timer if one was started.
     */
    private void cancelKeepAlive() {
        if (keepAliveTimerId >= 0) {
            vertx.cancelTimer(keepAliveTimerId);
            keepAliveTimerId = -1;
        }
    }

    /**
     * Formats a single {@link SseEvent} into its SSE wire representation as a {@link Buffer}.
     *
     * <p>Field order follows the SSE specification: {@code id}, {@code event}, {@code retry},
     * {@code comment}, {@code data}. The event block is terminated with a blank line ({@code \n}).
     * Multi-line data values are split across multiple {@code data:} lines.
     *
     * @param event the event to format; must not be {@code null}
     * @return a buffer containing the formatted SSE frame
     */
    private Buffer formatEvent(SseEvent event) {
        StringBuilder sb = new StringBuilder(256);

        if (event.id() != null) {
            sb.append("id: ").append(sanitizeSingleLine(event.id())).append('\n');
        }
        if (event.event() != null) {
            sb.append("event: ").append(sanitizeSingleLine(event.event())).append('\n');
        }
        if (event.retryMs() != null) {
            sb.append("retry: ").append(event.retryMs()).append('\n');
        }
        if (event.comment() != null) {
            sb.append(": ").append(sanitizeSingleLine(event.comment())).append('\n');
        }
        if (event.data() != null) {
            String serialized = serializeData(event.data());
            appendDataLines(sb, serialized);
        }

        // Blank line terminates the event block
        sb.append('\n');
        return Buffer.buffer(sb.toString());
    }

    /**
     * Appends SSE {@code data:} lines to the builder, splitting on line endings per the WHATWG
     * SSE specification ({@code \n}, {@code \r\n}, or bare {@code \r}).
     *
     * @param sb         the target builder
     * @param serialized the serialized data string (may contain line-ending characters)
     */
    private static void appendDataLines(StringBuilder sb, String serialized) {
        int start = 0;
        for (int i = 0; i <= serialized.length(); i++) {
            if (i == serialized.length()) {
                sb.append("data: ").append(serialized, start, i).append('\n');
            } else if (serialized.charAt(i) == '\n') {
                sb.append("data: ").append(serialized, start, i).append('\n');
                start = i + 1;
            } else if (serialized.charAt(i) == '\r') {
                sb.append("data: ").append(serialized, start, i).append('\n');
                // Skip the following \n if this is a \r\n pair
                if (i + 1 < serialized.length() && serialized.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
    }

    /**
     * Strips newline and carriage-return characters from a single-line SSE field value.
     * The SSE specification requires {@code id} and {@code event} fields to be single-line.
     *
     * @param value the field value to sanitize
     * @return the value with {@code \n}, {@code \r} removed
     */
    private static String sanitizeSingleLine(String value) {
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value; // fast path: no newlines
        }
        return value.replace("\n", "").replace("\r", "");
    }

    /**
     * Serializes the event data to a string. {@link String} values are returned as-is;
     * structured objects are JSON-serialized via the process JSON codec's mapper
     * ({@link VertiqueJson#mapper()}), read at use time — SSE has no per-route profile of its own.
     *
     * @param data the data value to serialize; must not be {@code null}
     * @return the string representation of the data
     */
    private static String serializeData(Object data) {
        if (data instanceof String s) {
            return s;
        }
        try {
            return VertiqueJson.mapper().writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize SSE event data to JSON", e);
            return data.toString();
        }
    }
}
