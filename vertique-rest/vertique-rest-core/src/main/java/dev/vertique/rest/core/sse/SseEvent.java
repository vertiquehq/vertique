// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Immutable representation of a single Server-Sent Event (SSE).
 *
 * <p>Each instance maps to one event block on the SSE wire format. Fields that are {@code null}
 * are omitted from the serialized output:
 *
 * <pre>
 * id: 42
 * event: user-updated
 * data: {"id":"42","name":"Alice"}
 * retry: 5000
 * </pre>
 *
 * <p>When {@link #data()} is a {@link String} it is emitted verbatim; when it is a structured
 * object it is serialized to JSON by the framework before emission. The {@link #comment()} field,
 * when set, is prefixed with {@code ": "} on the wire and is often used for keep-alive heartbeat
 * frames.
 *
 * <p>Use the static factory methods for the most common cases:
 * <pre>{@code
 * SseEvent.of(myPayloadObject);          // data only
 * SseEvent.comment("heartbeat");         // comment only
 * SseEvent.builder().event("created").data(entity).id(id).build();  // full control
 * }</pre>
 */
@Getter
@Builder
@Accessors(fluent = true)
@EqualsAndHashCode
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class SseEvent {

    /**
     * Optional event identifier. When set, the browser updates its {@code Last-Event-ID} header
     * on reconnect so the server can resume from where the stream left off.
     */
    @Nullable
    private final String id;

    /**
     * Optional event type name. Clients can listen for specific event types using
     * {@code addEventListener("user-updated", handler)}. Defaults to {@code "message"} per the
     * SSE specification when absent.
     */
    @Nullable
    private final String event;

    /**
     * Event payload. {@link String} values are emitted verbatim on the {@code data:} line.
     * Structured objects are serialized to JSON by the framework. Multi-line strings are
     * automatically split across multiple {@code data:} lines per the SSE specification.
     */
    @Nullable
    private final Object data;

    /**
     * Optional SSE comment. Emitted as a {@code ": text"} line on the wire. Comments are
     * invisible to JavaScript {@code EventSource} listeners and are commonly used as heartbeat
     * frames to keep the connection alive through idle proxies.
     */
    @Nullable
    private final String comment;

    /**
     * Optional client reconnection hint in milliseconds. When set, the browser uses this value
     * as the reconnection delay after the SSE connection drops. Maps to the {@code retry:} field
     * on the wire.
     */
    @Nullable
    private final Long retryMs;

    // --- Static factories ---

    /**
     * Creates an event carrying only the given data payload. All other fields are {@code null}.
     *
     * @param data the event payload; {@link String} values are emitted verbatim, structured
     *             objects are JSON-serialized by the framework
     * @return a new {@link SseEvent} with {@code data} set and all other fields {@code null}
     */
    public static SseEvent of(Object data) {
        return builder().data(data).build();
    }

    /**
     * Creates a comment-only event suitable for keep-alive heartbeats. The text is emitted as a
     * {@code ": text"} line and is invisible to {@code EventSource} data listeners.
     *
     * @param text the comment text to emit (without the leading {@code ": "} prefix)
     * @return a new {@link SseEvent} with {@code comment} set and all other fields {@code null}
     */
    public static SseEvent comment(String text) {
        return builder().comment(text).build();
    }
}
