// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.jackson.VertxModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that the process codec's stream and reader entry points run on the installed mapper:
 * strict duplicate detection and disallowed comments reject, a record round-trips, the caller's
 * stream or writer is neither closed nor flushed, and an unknown property surfaces Jackson's
 * {@link UnrecognizedPropertyException} as the cause.
 */
class VertiqueJsonCodecStreamTest {

    private static final JsonProfileId PROFILE = JsonProfileId.of("codec-stream-test");
    private static final String REPEATED_KEY = "{\"a\":1,\"a\":2}";
    private static final String COMMENT = "{/*c*/\"a\":1}";

    /** Record target for typed reads and the round trip. */
    record Point(int x, String label) {}

    /** Record with a single property, used as the unknown-property target. */
    record Single(int a) {}

    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    private static ObjectMapper strictMapper() {
        return new ObjectMapper()
                .registerModule(new VertxModule())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .disable(JsonParser.Feature.ALLOW_COMMENTS);
    }

    private static InputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Records every close and flush call on the wrapped output stream. */
    private static final class TrackingOutputStream extends java.io.FilterOutputStream {
        int closes;
        int flushes;

        final ByteArrayOutputStream sink;

        TrackingOutputStream(ByteArrayOutputStream out) {
            super(out);
            this.sink = out;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
        }

        @Override
        public void flush() throws java.io.IOException {
            flushes++;
            super.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            closes++;
            super.close();
        }
    }

    /** Records every close and flush call on the wrapped writer. */
    private static final class TrackingWriter extends Writer {
        private final StringWriter delegate = new StringWriter();
        int closes;
        int flushes;

        @Override
        public void write(char[] cbuf, int off, int len) {
            delegate.write(cbuf, off, len);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    @Test
    @DisplayName("before install the stream and reader reads accept a repeated key and a comment")
    void beforeInstallReadsSucceed() {
        assertAll(
                () -> assertDoesNotThrow(() -> Json.CODEC.fromStream(stream(REPEATED_KEY), Object.class)),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromStream(stream(REPEATED_KEY))),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromReader(new StringReader(REPEATED_KEY), Object.class)),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromReader(new StringReader(REPEATED_KEY))),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromStream(stream(COMMENT), Object.class)),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromStream(stream(COMMENT))),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromReader(new StringReader(COMMENT), Object.class)),
                () -> assertDoesNotThrow(() -> Json.CODEC.fromReader(new StringReader(COMMENT))));
    }

    @Test
    @DisplayName("after install all four read forms reject a repeated key and a comment")
    void installedStrictParserRejectsInAllFourReadForms() {
        VertiqueJson.install(PROFILE, strictMapper());
        assertAll(
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromStream(stream(REPEATED_KEY), Object.class),
                        "fromStream(InputStream, Class) must reject the repeated key"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromStream(stream(REPEATED_KEY)),
                        "fromStream(InputStream) must reject the repeated key"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromReader(new StringReader(REPEATED_KEY), Object.class),
                        "fromReader(Reader, Class) must reject the repeated key"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromReader(new StringReader(REPEATED_KEY)),
                        "fromReader(Reader) must reject the repeated key"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromStream(stream(COMMENT), Object.class),
                        "fromStream(InputStream, Class) must reject the comment"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromStream(stream(COMMENT)),
                        "fromStream(InputStream) must reject the comment"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromReader(new StringReader(COMMENT), Object.class),
                        "fromReader(Reader, Class) must reject the comment"),
                () -> assertThrows(
                        DecodeException.class,
                        () -> Json.CODEC.fromReader(new StringReader(COMMENT)),
                        "fromReader(Reader) must reject the comment"));
    }

    @Test
    @DisplayName("a record written with toStream reads back equal and the stream is left open and unflushed")
    void recordRoundTripsThroughStreamLeavingCallerStreamOpen() {
        VertiqueJson.install(PROFILE, strictMapper());
        Point original = new Point(7, "seven");
        TrackingOutputStream out = new TrackingOutputStream(new ByteArrayOutputStream());

        Json.CODEC.toStream(original, out);
        byte[] written = out.sink.toByteArray();

        assertAll(
                () -> assertEquals(0, out.closes, "toStream must not close the caller's stream"),
                () -> assertEquals(0, out.flushes, "toStream must not flush the caller's stream"),
                () -> assertEquals(
                        original,
                        Json.CODEC.fromStream(new ByteArrayInputStream(written), Point.class),
                        "the record must read back equal through fromStream"));
    }

    @Test
    @DisplayName("a record written with toWriter reads back equal and the writer is left open and unflushed")
    void recordRoundTripsThroughWriterLeavingCallerWriterOpen() {
        VertiqueJson.install(PROFILE, strictMapper());
        Point original = new Point(7, "seven");
        TrackingWriter out = new TrackingWriter();

        Json.CODEC.toWriter(original, out);

        assertAll(
                () -> assertEquals(0, out.closes, "toWriter must not close the caller's writer"),
                () -> assertEquals(0, out.flushes, "toWriter must not flush the caller's writer"),
                () -> assertEquals(
                        original,
                        Json.CODEC.fromReader((Reader) new StringReader(out.toString()), Point.class),
                        "the record must read back equal through fromReader"));
    }

    @Test
    @DisplayName("an unknown property on a record target carries Jackson's UnrecognizedPropertyException as cause")
    void unknownPropertyCauseIsUnrecognizedPropertyException() {
        VertiqueJson.install(PROFILE, strictMapper());
        String json = "{\"a\":1,\"extra\":2}";

        DecodeException fromStream =
                assertThrows(DecodeException.class, () -> Json.CODEC.fromStream(stream(json), Single.class));
        DecodeException fromReader =
                assertThrows(DecodeException.class, () -> Json.CODEC.fromReader(new StringReader(json), Single.class));

        assertAll(
                () -> assertInstanceOf(
                        UnrecognizedPropertyException.class,
                        fromStream.getCause(),
                        "fromStream must surface the Jackson cause, not only DecodeException"),
                () -> assertInstanceOf(
                        UnrecognizedPropertyException.class,
                        fromReader.getCause(),
                        "fromReader must surface the Jackson cause, not only DecodeException"),
                () -> assertFalse(fromStream.getMessage().isEmpty()));
    }
}
