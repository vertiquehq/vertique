// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.vertique.http.client.exception.HttpBodyLimitException;
import dev.vertique.http.client.internal.SseParser;
import io.vertx.ext.web.codec.SseEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of the incremental server-sent event parser.
 */
class SseParserTest {

    private static final int DEFAULT_BOUND = 1_048_576;

    /** An event reduced to its four observable values. */
    private record Ev(String id, String event, String data, int retry) {}

    private static Ev ev(String id, String event, String data) {
        return new Ev(id, event, data, 0);
    }

    private static Ev of(SseEvent event) {
        return new Ev(event.id(), event.event(), event.data(), event.retry());
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** One parser run: the events the sink saw and the last event ID afterwards. */
    private record Run(List<Ev> events, Optional<String> lastEventId) {}

    private static Run parse(List<byte[]> chunks) {
        List<Ev> events = new ArrayList<>();
        SseParser parser = new SseParser(DEFAULT_BOUND, e -> events.add(of(e)));
        for (byte[] chunk : chunks) {
            parser.feed(chunk, 0, chunk.length);
        }
        return new Run(events, parser.lastEventId());
    }

    private static List<byte[]> whole(byte[] input) {
        return List.of(input);
    }

    private static List<byte[]> splitAt(byte[] input, int offset) {
        return List.of(Arrays.copyOfRange(input, 0, offset), Arrays.copyOfRange(input, offset, input.length));
    }

    private static List<byte[]> oneByteEach(byte[] input) {
        List<byte[]> chunks = new ArrayList<>();
        for (byte b : input) {
            chunks.add(new byte[] {b});
        }
        return chunks;
    }

    // --- AC-026 ---

    /**
     * AC-026: the stream parses to the same events however it is cut into chunks, at every byte
     * boundary and one byte at a time.
     */
    @Test
    @DisplayName("events split at every byte boundary parse identically")
    void eventsSplitAtEveryByteBoundaryParseIdentically() {
        // Given the stream id: 7, event: tick, two data lines, a comment and [DONE] as UTF-8 bytes
        byte[] input = utf8("id: 7\nevent: tick\ndata: one\ndata: two\n\n: keep\n\ndata: [DONE]\n\n");
        List<Ev> expected = List.of(ev("7", "tick", "one\ntwo"), ev("7", "message", "[DONE]"));

        // When it is fed in one chunk, split in two at each offset from 1 to length minus 1, and a byte at a time
        List<Run> runs = new ArrayList<>();
        runs.add(parse(whole(input)));
        for (int offset = 1; offset < input.length; offset++) {
            runs.add(parse(splitAt(input, offset)));
        }
        runs.add(parse(oneByteEach(input)));

        // Then every delivery yields the same two events in order, the comment yields none and [DONE] is an event
        assertThat(runs).hasSize(input.length + 1);
        for (Run run : runs) {
            assertThat(run.events()).isEqualTo(expected);
        }
        // And the second event carries the id of the first, which persisted
        assertThat(runs.get(0).lastEventId()).contains("7");
    }

    // --- AC-027 ---

    /** One grammar case: the chunks to feed (also fed whole and a byte at a time) and the expected events. */
    private record Case(String name, List<byte[]> chunks, List<Ev> expected) {

        static Case of(String name, byte[] input, Ev... expected) {
            return new Case(name, List.of(input), List.of(expected));
        }

        static Case of(String name, String input, Ev... expected) {
            return of(name, utf8(input), expected);
        }

        byte[] joined() {
            return concat(chunks.toArray(new byte[0][]));
        }
    }

    private static List<Case> grammarCases() {
        byte[] bom = bytes(0xEF, 0xBB, 0xBF);
        return List.of(
                Case.of("CRLF line endings", "data: a\r\n\r\n", ev(null, "message", "a")),
                Case.of("LF line endings", "data: a\n\n", ev(null, "message", "a")),
                Case.of("CR line endings", "data: a\r\r", ev(null, "message", "a")),
                Case.of("one leading BOM is stripped", concat(bom, utf8("data: a\n\n")), ev(null, "message", "a")),
                Case.of(
                        "a BOM later in the stream is not stripped, so its line names an unknown field",
                        concat(utf8("data: a\n\n"), bom, utf8("data: b\n\n")),
                        ev(null, "message", "a")),
                Case.of(
                        "a partial BOM is not stripped, so its line names an unknown field",
                        concat(bytes(0xEF, 0xBB), utf8("data: a\n\ndata: b\n\n")),
                        ev(null, "message", "b")),
                new Case(
                        "a partial BOM split across feeds is not stripped either",
                        List.of(bytes(0xEF), concat(bytes(0xBB), utf8("data: a\n\n")), utf8("data: b\n\n")),
                        List.of(ev(null, "message", "b"))),
                Case.of("an id with NUL is ignored", "id: x\u0000y\ndata: a\n\n", ev(null, "message", "a")),
                Case.of("a non-numeric retry is ignored", "retry: abc\ndata: a\n\n", ev(null, "message", "a")),
                Case.of(
                        "a 25-digit retry is ignored",
                        "retry: 1234567890123456789012345\ndata: a\n\n",
                        ev(null, "message", "a")),
                Case.of(
                        "a retry above the int range is ignored",
                        "retry: 2147483648\ndata: a\n\n",
                        ev(null, "message", "a")),
                Case.of("retry 1500 is surfaced", "retry: 1500\ndata: a\n\n", new Ev(null, "message", "a", 1500)),
                Case.of("an unknown field is ignored", "foo: bar\ndata: a\n\n", ev(null, "message", "a")),
                Case.of("a block with only an event field is not dispatched", "event: x\n\n"),
                Case.of("a block with only an unknown field is not dispatched", "foo: bar\n\n"),
                Case.of("a block with only an id and a retry is not dispatched", "id: 3\nretry: 5\n\n"),
                Case.of(
                        "multi-line data is joined with a newline",
                        "data: a\ndata: b\n\n",
                        ev(null, "message", "a\nb")),
                Case.of("an event field names the event", "event: tick\ndata: a\n\n", ev(null, "tick", "a")),
                Case.of(
                        "the event name does not carry to the next event",
                        "event: tick\ndata: a\n\ndata: b\n\n",
                        ev(null, "tick", "a"),
                        ev(null, "message", "b")),
                Case.of("data without a space after the colon", "data:a\n\n", ev(null, "message", "a")),
                Case.of("data with a space after the colon", "data: a\n\n", ev(null, "message", "a")),
                Case.of("only one leading space is removed", "data:  a\n\n", ev(null, "message", " a")),
                Case.of("multi-byte UTF-8 survives any chunking", "data: é€😀\n\n", ev(null, "message", "é€😀")),
                Case.of(
                        "comments between fields are consumed",
                        "data: a\n: note\ndata: b\n\n",
                        ev(null, "message", "a\nb")),
                Case.of(
                        "an event without a closing blank line is not dispatched",
                        "data: a\n\ndata: b\n",
                        ev(null, "message", "a")),
                Case.of(
                        "mixed line endings in one stream",
                        "data: a\r\n\r\ndata: b\n\ndata: c\r\r",
                        ev(null, "message", "a"),
                        ev(null, "message", "b"),
                        ev(null, "message", "c")),
                new Case(
                        "a CRLF split across two chunks is one line ending",
                        List.of(utf8("data: a\r"), utf8("\ndata: b\r\n\r\n")),
                        List.of(ev(null, "message", "a\nb"))));
    }

    /**
     * AC-027: each WHATWG edge case yields the specified events when fed in its own stream chunked one
     * byte at a time and in one piece; the split-CRLF case also keeps its stated chunk boundary.
     */
    @Test
    @DisplayName("WHATWG grammar edge cases parse as specified")
    void whatwgGrammarEdgeCasesParseAsSpecified() {
        for (Case grammarCase : grammarCases()) {
            // Given the case's input in a separate stream
            byte[] input = grammarCase.joined();

            // When it is parsed as given, in one piece and one byte at a time
            Run asGiven = parse(grammarCase.chunks());
            Run inOnePiece = parse(whole(input));
            Run byteByByte = parse(oneByteEach(input));

            // Then each delivery yields the specified events
            assertThat(asGiven.events()).as("%s (as given)", grammarCase.name()).isEqualTo(grammarCase.expected());
            assertThat(inOnePiece.events())
                    .as("%s (one piece)", grammarCase.name())
                    .isEqualTo(grammarCase.expected());
            assertThat(byteByByte.events())
                    .as("%s (one byte at a time)", grammarCase.name())
                    .isEqualTo(grammarCase.expected());
        }
    }

    /** The id buffer persists across events, a NUL id leaves it unchanged and an absent id leaves it unset. */
    @Test
    @DisplayName("the last event ID buffer follows the id field")
    void lastEventIdFollowsTheIdField() {
        // Given a stream whose ids are absent, 7, none, NUL-containing and 9
        byte[] input = utf8("data: a\n\nid: 7\ndata: b\n\ndata: c\n\nid: x\u0000y\ndata: d\n\nid: 9\ndata: e\n\n");

        // When it is parsed in one piece and a byte at a time
        // Then the event ids are null, 7, 7, 7, 9 and the buffer ends at 9
        for (List<byte[]> chunks : List.of(whole(input), oneByteEach(input))) {
            Run run = parse(chunks);
            assertThat(run.events()).extracting(Ev::id).containsExactly(null, "7", "7", "7", "9");
            assertThat(run.lastEventId()).contains("9");
        }
        // And the buffer is empty before any byte arrived
        assertThat(new SseParser(DEFAULT_BOUND, e -> {}).lastEventId()).isEmpty();
    }

    // --- AC-029 (parser level) ---

    /**
     * AC-029: the bound counts every raw byte since the last dispatched event, whatever the chunking, and
     * a count equal to the bound is allowed; it restarts at each dispatch.
     */
    @Test
    @DisplayName("the event bound counts raw bytes since the last dispatch")
    void eventBoundCountsRawBytesSinceTheLastDispatch() {
        int bound = 64;

        // Given a partial data line of exactly 64 bytes: no failure, no event
        List<Ev> events = new ArrayList<>();
        SseParser data = new SseParser(bound, e -> events.add(of(e)));
        byte[] atBound = utf8("data: " + "a".repeat(bound - 6));
        assertThatCode(() -> data.feed(atBound, 0, atBound.length)).doesNotThrowAnyException();
        assertThat(events).isEmpty();
        // When one more byte arrives, the parser fails with the EVENT bound and the configured limit
        assertThatThrownBy(() -> data.feed(utf8("a"), 0, 1)).isInstanceOfSatisfying(HttpBodyLimitException.class, e -> {
            assertThat(e.direction()).isEqualTo(HttpBodyLimitException.Direction.EVENT);
            assertThat(e.limitBytes()).isEqualTo(bound);
        });

        // And a comment line, an unknown field and blank lines without data count the same way
        for (String prefix : List.of(": ", "foo: ")) {
            SseParser parser = new SseParser(bound, e -> events.add(of(e)));
            byte[] full = utf8(prefix + "a".repeat(bound - prefix.length()));
            assertThatCode(() -> parser.feed(full, 0, full.length)).doesNotThrowAnyException();
            assertThatThrownBy(() -> parser.feed(utf8("a"), 0, 1)).isInstanceOf(HttpBodyLimitException.class);
        }
        SseParser blanks = new SseParser(bound, e -> events.add(of(e)));
        byte[] comment = utf8(": c\n\n");
        assertThatThrownBy(() -> {
                    for (int i = 0; i < 20; i++) {
                        blanks.feed(comment, 0, comment.length);
                    }
                })
                .as("bytes of blocks that dispatch nothing keep counting")
                .isInstanceOf(HttpBodyLimitException.class);

        // And the same bytes fail whether they arrive in one chunk or one byte at a time
        byte[] over = utf8("foo: " + "a".repeat(bound));
        assertThatThrownBy(() -> new SseParser(bound, e -> {}).feed(over, 0, over.length))
                .isInstanceOf(HttpBodyLimitException.class);
        assertThatThrownBy(() -> {
                    SseParser parser = new SseParser(bound, e -> {});
                    for (byte b : over) {
                        parser.feed(new byte[] {b}, 0, 1);
                    }
                })
                .isInstanceOf(HttpBodyLimitException.class);

        // And the count restarts at each dispatch: 100 events of 12 bytes pass a bound of 64
        List<Ev> many = new ArrayList<>();
        SseParser restart = new SseParser(bound, e -> many.add(of(e)));
        byte[] small = utf8("data: aaaa\n\n");
        for (int i = 0; i < 100; i++) {
            restart.feed(small, 0, small.length);
        }
        assertThat(many).hasSize(100);
    }

    // --- NFR-002 ---

    /**
     * NFR-002: parsing is linear in the input. Four mebibytes arrive one byte at a time as one line, and a
     * 200 000-line event arrives in one chunk; a parser that rescans buffered bytes from the start on
     * each call needs hours for the first and minutes for the second, so a 30-second ceiling that is
     * far above the linear cost cannot be met by a quadratic parser.
     */
    @Test
    @DisplayName("a large event is parsed in one pass without rescanning buffered bytes")
    void largeEventIsParsedInOnePassWithoutRescanningBufferedBytes() {
        int bound = 16 * 1024 * 1024;

        // Given a 4 MiB data line fed one byte at a time, then its line ending and the blank line
        int lineLength = 4 * 1024 * 1024;
        List<Ev> single = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            SseParser parser = new SseParser(bound, e -> single.add(of(e)));
            byte[] head = utf8("data: ");
            parser.feed(head, 0, head.length);
            byte[] one = {'x'};
            for (int i = 0; i < lineLength; i++) {
                parser.feed(one, 0, 1);
            }
            byte[] end = utf8("\n\n");
            parser.feed(end, 0, end.length);
        });
        // Then exactly one event with the whole line is dispatched
        assertThat(single).hasSize(1);
        assertThat(single.get(0).data()).hasSize(lineLength).matches("x+");

        // And an event of 200 000 data lines delivered in a single chunk is joined in one pass
        int lines = 200_000;
        byte[] many = utf8("data: y\n".repeat(lines) + "\n");
        List<Ev> joined = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            SseParser parser = new SseParser(bound, e -> joined.add(of(e)));
            parser.feed(many, 0, many.length);
        });
        assertThat(joined).hasSize(1);
        assertThat(joined.get(0).data()).hasSize(lines * 2 - 1);
        assertThat(joined.get(0).data()).isEqualTo(String.join("\n", java.util.Collections.nCopies(lines, "y")));
    }
}
