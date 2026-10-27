// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Pure record-time bounds over encoded JSON: an iterative container walk before encoding, then a streaming encode that
 * counts bytes and container depth as they are produced and stops as soon as a bound is crossed, so an oversized value
 * is never fully encoded in memory.
 */
final class RecordTimePayloadValidator {

    private static final int MAX_DEPTH = 64;

    private final int maxPayloadBytes;

    /**
     * Creates a validator using the recording payload-size limit.
     *
     * @param config validated recording configuration
     */
    RecordTimePayloadValidator(WorkflowRecordingConfig config) {
        maxPayloadBytes = config.maxPayloadBytes();
    }

    /**
     * Checks encoded UTF-8 JSON size and container depth without I/O, locks or transactions.
     *
     * <p>Objects and arrays each count one level; scalars, including {@code null}, count none.
     * Refusals carry only framework messages, never submitted values or encoder causes.
     *
     * @param payload the JSON value to check, including {@code null}
     * @throws WorkflowInvalidPayloadException if the encoded value exceeds the configured byte limit,
     *     exceeds 64 container levels or cannot be encoded as JSON
     */
    void validate(@Nullable Object payload) {
        validateDepth(payload);
        BoundedSink sink = new BoundedSink(maxPayloadBytes);
        try {
            DatabindCodec.mapper().writeValue(sink, payload);
        } catch (IOException | RuntimeException failure) {
            if (sink.tooLarge) {
                throw invalid("payload exceeds maxPayloadBytes " + maxPayloadBytes);
            }
            if (sink.tooDeep) {
                throw invalid("payload exceeds depth " + MAX_DEPTH);
            }
            throw invalid("payload cannot be encoded as JSON");
        }
    }

    /** Rejects deep JSON containers before a recursive codec can visit them; retains only the ancestor iterators. */
    private static void validateDepth(@Nullable Object payload) {
        Deque<Iterator<?>> ancestors = new ArrayDeque<>();
        Object value = payload;
        while (true) {
            Iterator<?> children = children(value);
            if (children != null) {
                if (ancestors.size() >= MAX_DEPTH) {
                    throw invalid("payload exceeds depth " + MAX_DEPTH);
                }
                ancestors.push(children);
            }
            while (!ancestors.isEmpty() && !ancestors.peek().hasNext()) {
                ancestors.pop();
            }
            if (ancestors.isEmpty()) {
                return;
            }
            value = ancestors.peek().next();
        }
    }

    /** Returns children without copying containers or normalizing their nested values. */
    @Nullable
    private static Iterator<?> children(@Nullable Object value) {
        return switch (value) {
            case JsonObject object -> object.getMap().values().iterator();
            case JsonArray array -> array.getList().iterator();
            case Map<?, ?> map -> map.values().iterator();
            case Iterable<?> iterable -> iterable.iterator();
            case Object[] array -> Arrays.asList(array).iterator();
            case null, default -> null;
        };
    }

    /** Counts encoded bytes and the codec's actual containers as they are written, and refuses once a bound is crossed. */
    private static final class BoundedSink extends OutputStream {

        private final int maxBytes;
        private long written;
        private int depth;
        private boolean inString;
        private boolean escaped;
        boolean tooLarge;
        boolean tooDeep;

        BoundedSink(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            if (++written > maxBytes) {
                tooLarge = true;
                throw new IOException("payload exceeds the size bound");
            }
            observe((byte) value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int index = 0; index < length; index++) {
                write(bytes[offset + index]);
            }
        }

        private void observe(byte value) throws IOException {
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
            } else if (value == '"') {
                inString = true;
            } else if (value == '{' || value == '[') {
                if (++depth > MAX_DEPTH) {
                    tooDeep = true;
                    throw new IOException("payload exceeds the depth bound");
                }
            } else if (value == '}' || value == ']') {
                depth--;
            }
        }
    }

    /** Builds an unlocated, value-free refusal without retaining a submitted value in a cause. */
    private static WorkflowInvalidPayloadException invalid(String message) {
        return new WorkflowInvalidPayloadException(message, List.of(ViolationDetail.unlocated(message, "payload")));
    }
}
