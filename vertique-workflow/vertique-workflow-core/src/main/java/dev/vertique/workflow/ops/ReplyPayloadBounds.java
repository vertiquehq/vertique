// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2
package dev.vertique.workflow.ops;

import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.ViolationDetail;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import jakarta.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** INTERNAL bounded JSON validation shared by reply recording and completion. */
public final class ReplyPayloadBounds {
    private static final int MAX_DEPTH = 64;

    private ReplyPayloadBounds() {}

    /** Encodes one value once, refusing output that exceeds the configured limits. */
    public static @Nullable String encode(@Nullable Object payload, int maxPayloadBytes) {
        BoundedSink sink = new BoundedSink(maxPayloadBytes);
        try {
            VertiqueJson.mapper().writeValue(sink, payload);
            return payload == null ? null : sink.encoded();
        } catch (IOException | RuntimeException failure) {
            if (sink.tooLarge) throw invalid("payload exceeds maxPayloadBytes " + maxPayloadBytes);
            if (sink.tooDeep) throw invalid("payload exceeds depth " + MAX_DEPTH);
            throw invalid("payload cannot be encoded as JSON");
        }
    }

    /** Checks that a proposed duplicate value can be encoded within the limits. */
    public static void validate(@Nullable Object payload, int maxPayloadBytes) {
        encode(payload, maxPayloadBytes);
    }

    /** Counts encoded bytes and the codec's actual containers as they are written, and refuses once a bound is crossed. */
    private static final class BoundedSink extends OutputStream {

        private final int maxBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long written;
        private int depth;
        private boolean inString;
        private boolean escaped;
        private int unicodeDigits;
        private boolean zeroUnicode;
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
            bytes.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int index = 0; index < length; index++) {
                write(bytes[offset + index]);
            }
        }

        String encoded() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private void observe(byte value) throws IOException {
            if (inString) {
                if (unicodeDigits > 0) {
                    zeroUnicode &= value == '0';
                    if (--unicodeDigits == 0 && zeroUnicode) throw new IOException("payload cannot be encoded");
                } else if (escaped) {
                    escaped = false;
                    if (value == 'u') {
                        unicodeDigits = 4;
                        zeroUnicode = true;
                    }
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

    private static WorkflowInvalidPayloadException invalid(String message) {
        return new WorkflowInvalidPayloadException(message, List.of(ViolationDetail.unlocated(message, "payload")));
    }
}
