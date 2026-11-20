// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import io.vertx.ext.web.codec.SseEvent;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * An incremental parser of a {@code text/event-stream} body that follows the WHATWG event stream grammar.
 *
 * <p>The parser is a byte-at-a-time state machine: every byte is looked at once and never again, so the work
 * is linear in the input however the bytes are chunked. Line endings are LF, CRLF (also when split between
 * two {@link #feed} calls) and CR; one leading byte order mark is skipped; the fields are {@code data},
 * {@code event}, {@code id} and {@code retry} and any other field is ignored. Text is decoded as UTF-8 when an
 * event is dispatched, so a character split between chunks survives.
 *
 * <p>The bound counts the raw bytes of the event being assembled: every line since the last blank line, ignored
 * fields included, and the current partial line. A finished comment line stops counting when it ends and a blank
 * line restarts the count, so a stream of heartbeat comments never reaches the bound, while a single line or event
 * over it still does and the buffered bytes stay bounded. A count above the bound fails {@link #feed} with an
 * {@link HttpBodyLimitException} and leaves the parser unusable.
 *
 * <p>An instance is not thread safe.
 */
final class SseParser {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String DEFAULT_EVENT = "message";
    private static final long MAX_ARRAY = Integer.MAX_VALUE - 8;
    private static final int INITIAL_CAPACITY = 256;
    /** The longest field name that can be one of the four known ones ({@code retry}, {@code event}). */
    private static final int MAX_KNOWN_NAME = 5;

    private enum Phase {
        LINE_START,
        NAME,
        VALUE_START,
        VALUE,
        COMMENT
    }

    private enum Field {
        DATA,
        EVENT,
        ID,
        RETRY,
        IGNORED
    }

    private final int maxEventBytes;
    private final Consumer<SseEvent> sink;

    private long counted;
    /** The count when the current line began, so a finished comment line can be taken back out. */
    private long lineStart;

    private boolean failed;
    private int bomMatched;
    private boolean atStreamStart = true;
    private boolean skipLf;
    private Phase phase = Phase.LINE_START;
    private Field field = Field.IGNORED;

    private final byte[] name = new byte[MAX_KNOWN_NAME];
    private int nameLength;

    private byte[] data = new byte[INITIAL_CAPACITY];
    private int dataLength;
    private byte[] value = new byte[INITIAL_CAPACITY];
    private int valueLength;

    private @Nullable String eventName;
    private int retry;
    private @Nullable String lastEventId;

    /**
     * Creates a parser.
     *
     * @param maxEventBytes the most raw bytes of the event being assembled, above 0
     * @param sink receives each completed event, synchronously and in order
     */
    SseParser(int maxEventBytes, Consumer<SseEvent> sink) {
        if (maxEventBytes <= 0) {
            throw new IllegalArgumentException("maxEventBytes must be > 0");
        }
        this.maxEventBytes = maxEventBytes;
        this.sink = sink;
    }

    /**
     * Parses bytes and hands every event they complete to the sink before returning.
     *
     * @param bytes the array holding the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws HttpBodyLimitException when the raw bytes of the event being assembled, the current partial line
     *     included, exceed the bound; the parser accepts no more bytes afterwards
     */
    void feed(byte[] bytes, int offset, int length) {
        if (failed) {
            throw new IllegalStateException("The parser has failed");
        }
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (++counted > maxEventBytes) {
                failed = true;
                throw new HttpBodyLimitException(HttpBodyLimitException.Direction.EVENT, maxEventBytes);
            }
            accept(bytes[i]);
        }
    }

    /**
     * Returns the last event ID buffer after the bytes fed so far.
     *
     * @return the last event ID, or empty when no {@code id} field has set one
     */
    Optional<String> lastEventId() {
        return Optional.ofNullable(lastEventId);
    }

    // --- state machine ---

    private void accept(byte b) {
        if (atStreamStart) {
            if (b == BOM[bomMatched]) {
                if (++bomMatched == BOM.length) {
                    atStreamStart = false;
                }
                return;
            }
            atStreamStart = false;
            int matched = bomMatched;
            for (int k = 0; k < matched; k++) {
                step(BOM[k]);
            }
        }
        step(b);
    }

    private void step(byte b) {
        if (skipLf) {
            skipLf = false;
            if (b == '\n') {
                return;
            }
        }
        if (b == '\r' || b == '\n') {
            skipLf = b == '\r';
            endLine();
            return;
        }
        switch (phase) {
            case LINE_START -> {
                if (b == ':') {
                    phase = Phase.COMMENT;
                } else {
                    phase = Phase.NAME;
                    nameLength = 0;
                    appendName(b);
                }
            }
            case NAME -> {
                if (b == ':') {
                    field = resolveField();
                    phase = Phase.VALUE_START;
                } else {
                    appendName(b);
                }
            }
            case VALUE_START -> {
                phase = Phase.VALUE;
                if (b != ' ') {
                    appendValue(b);
                }
            }
            case VALUE -> appendValue(b);
            case COMMENT -> {
                // consumed, never surfaced
            }
        }
    }

    private void appendName(byte b) {
        if (nameLength < MAX_KNOWN_NAME + 1) {
            if (nameLength < MAX_KNOWN_NAME) {
                name[nameLength] = b;
            }
            nameLength++;
        }
    }

    private Field resolveField() {
        if (nameLength > MAX_KNOWN_NAME) {
            return Field.IGNORED;
        }
        if (nameIs("data")) {
            return Field.DATA;
        }
        if (nameIs("event")) {
            return Field.EVENT;
        }
        if (nameIs("id")) {
            return Field.ID;
        }
        if (nameIs("retry")) {
            return Field.RETRY;
        }
        return Field.IGNORED;
    }

    private boolean nameIs(String known) {
        if (nameLength != known.length()) {
            return false;
        }
        for (int i = 0; i < nameLength; i++) {
            if (name[i] != known.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void appendValue(byte b) {
        switch (field) {
            case DATA -> {
                data = ensure(data, dataLength + 2);
                data[dataLength++] = b;
            }
            case EVENT, ID, RETRY -> {
                value = ensure(value, valueLength + 1);
                value[valueLength++] = b;
            }
            case IGNORED -> {
                // an unknown field's value is dropped
            }
        }
    }

    private static byte[] ensure(byte[] buffer, int needed) {
        if (needed <= buffer.length) {
            return buffer;
        }
        long doubled = Math.min((long) buffer.length * 2, MAX_ARRAY);
        return Arrays.copyOf(buffer, (int) Math.max(needed, doubled));
    }

    /** A line ended: a blank line dispatches, any other line applies its field. */
    private void endLine() {
        Phase ended = phase;
        phase = Phase.LINE_START;
        switch (ended) {
            case LINE_START -> {
                // the event assembly ends here, whether or not it dispatches
                counted = 0;
                dispatch();
            }
            case NAME -> {
                field = resolveField();
                applyField();
            }
            case VALUE_START, VALUE -> applyField();
            case COMMENT -> {
                // a finished comment line, such as a heartbeat, no longer counts against the bound
                counted = lineStart;
            }
        }
        valueLength = 0;
        lineStart = counted;
    }

    private void applyField() {
        switch (field) {
            case DATA -> {
                data = ensure(data, dataLength + 1);
                data[dataLength++] = '\n';
            }
            case EVENT -> eventName = new String(value, 0, valueLength, StandardCharsets.UTF_8);
            case ID -> applyId();
            case RETRY -> applyRetry();
            case IGNORED -> {
                // unknown field
            }
        }
    }

    private void applyId() {
        for (int i = 0; i < valueLength; i++) {
            if (value[i] == 0) {
                return;
            }
        }
        lastEventId = valueLength == 0 ? null : new String(value, 0, valueLength, StandardCharsets.UTF_8);
    }

    private void applyRetry() {
        if (valueLength == 0) {
            return;
        }
        long parsed = 0;
        for (int i = 0; i < valueLength; i++) {
            int digit = value[i] - '0';
            if (digit < 0 || digit > 9) {
                return;
            }
            parsed = parsed * 10 + digit;
            if (parsed > Integer.MAX_VALUE) {
                return;
            }
        }
        retry = (int) parsed;
    }

    /** A blank line: dispatches the event when it has data, and starts the next event either way. */
    private void dispatch() {
        boolean hasData = dataLength > 0;
        SseEvent event = null;
        if (hasData) {
            String text = new String(data, 0, dataLength - 1, StandardCharsets.UTF_8);
            String type = eventName == null || eventName.isEmpty() ? DEFAULT_EVENT : eventName;
            event = new SseEvent(lastEventId, type, text, retry);
        }
        dataLength = 0;
        eventName = null;
        retry = 0;
        if (event != null) {
            sink.accept(event);
        }
    }
}
