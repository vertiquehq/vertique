// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.config.WorkflowRecordingConfig;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Pure record-time bounds over encoded JSON, with an iterative container walk before encoding. */
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
        Buffer encoded;
        try {
            encoded = Json.encodeToBuffer(payload);
        } catch (EncodeException failure) {
            throw invalid("payload cannot be encoded as JSON");
        }
        if (encoded.length() > maxPayloadBytes) {
            throw invalid("payload exceeds maxPayloadBytes " + maxPayloadBytes);
        }
        validateEncodedDepth(encoded);
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

    /** Counts the codec's actual containers too, including values supplied through application serializers. */
    private static void validateEncodedDepth(Buffer encoded) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < encoded.length(); index++) {
            byte value = encoded.getByte(index);
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
                    throw invalid("payload exceeds depth " + MAX_DEPTH);
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
