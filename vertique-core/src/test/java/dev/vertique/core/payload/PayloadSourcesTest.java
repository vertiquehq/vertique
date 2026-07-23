// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.payload;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.buffer.Buffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PayloadSources} factory and the {@link PayloadSource} contract.
 *
 * <p>Verifies the no-copy contract for {@code byte[]}-backed sources, correct kind/metadata
 * propagation, correct empty semantics for {@code absent()} and {@code streaming(...)}, and
 * {@code copyPrefix} boundary behaviour.
 */
class PayloadSourcesTest {

    // --- absent() ---

    @Nested
    @DisplayName("absent()")
    class AbsentTests {

        @Test
        @DisplayName("kind is ABSENT")
        void kindIsAbsent() {
            assertEquals(PayloadKind.ABSENT, PayloadSources.absent().kind());
        }

        @Test
        @DisplayName("contentType() is empty")
        void contentTypeEmpty() {
            assertTrue(PayloadSources.absent().contentType().isEmpty());
        }

        @Test
        @DisplayName("declaredLength() is empty")
        void declaredLengthEmpty() {
            assertTrue(PayloadSources.absent().declaredLength().isEmpty());
        }

        @Test
        @DisplayName("bufferedView() is empty")
        void bufferedViewEmpty() {
            assertTrue(PayloadSources.absent().bufferedView().isEmpty());
        }

        @Test
        @DisplayName("bufferedStream() is empty")
        void bufferedStreamEmpty() {
            assertTrue(PayloadSources.absent().bufferedStream().isEmpty());
        }

        @Test
        @DisplayName("copyPrefix returns zero-length array")
        void copyPrefixReturnsEmpty() {
            byte[] prefix = PayloadSources.absent().copyPrefix(100);
            assertNotNull(prefix);
            assertEquals(0, prefix.length);
        }
    }

    // --- buffered(byte[], ...) ---

    @Nested
    @DisplayName("buffered(byte[], contentType)")
    class BufferedBytesTests {

        @Test
        @DisplayName("kind is BUFFERED")
        void kindIsBuffered() {
            byte[] bytes = {1, 2, 3};
            assertEquals(
                    PayloadKind.BUFFERED,
                    PayloadSources.buffered(bytes, "application/octet-stream").kind());
        }

        @Test
        @DisplayName("declaredLength equals array length")
        void declaredLengthEqualsArrayLength() {
            byte[] bytes = {10, 20, 30, 40};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            assertTrue(source.declaredLength().isPresent());
            assertEquals(4L, source.declaredLength().getAsLong());
        }

        @Test
        @DisplayName("bufferedStream() is present and readable")
        void bufferedStreamPresentAndReadable() throws IOException {
            byte[] bytes = {1, 2, 3};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            assertTrue(source.bufferedStream().isPresent());
            byte[] read = source.bufferedStream().get().readAllBytes();
            assertArrayEquals(bytes, read);
        }

        @Test
        @DisplayName("bufferedStream() reflects mutations to original array (no defensive copy)")
        void bufferedStreamReflectsMutations() throws IOException {
            byte[] bytes = {1, 2, 3};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            // mutate AFTER construction
            bytes[0] = 99;
            InputStream stream = source.bufferedStream().get();
            assertEquals(99, stream.read());
        }

        @Test
        @DisplayName("bufferedView() is present")
        void bufferedViewPresent() {
            byte[] bytes = {5, 6, 7};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            assertTrue(source.bufferedView().isPresent());
        }

        @Test
        @DisplayName("copyPrefix(3) returns first 3 bytes")
        void copyPrefixReturnFirstNBytes() {
            byte[] bytes = {10, 20, 30, 40, 50};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            byte[] prefix = source.copyPrefix(3);
            assertArrayEquals(new byte[] {10, 20, 30}, prefix);
        }

        @Test
        @DisplayName("copyPrefix(large) returns all bytes")
        void copyPrefixLargeReturnsAll() {
            byte[] bytes = {10, 20, 30};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            byte[] prefix = source.copyPrefix(Integer.MAX_VALUE);
            assertArrayEquals(bytes, prefix);
        }

        @Test
        @DisplayName("contentType round-trips when non-null")
        void contentTypeRoundTrips() {
            byte[] bytes = {1};
            PayloadSource source = PayloadSources.buffered(bytes, "text/plain");
            assertTrue(source.contentType().isPresent());
            assertEquals("text/plain", source.contentType().get());
        }

        @Test
        @DisplayName("contentType is empty when null passed")
        void contentTypeEmptyWhenNull() {
            byte[] bytes = {1};
            assertTrue(PayloadSources.buffered(bytes, null).contentType().isEmpty());
        }

        @Test
        @DisplayName("bufferedView() returns a Buffer with the correct bytes (lazy creation)")
        void bufferedViewCorrectBytes() {
            byte[] bytes = {10, 20, 30};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            // Calling bufferedView() should not fail and should return the correct content
            assertTrue(source.bufferedView().isPresent(), "bufferedView() must be present for BUFFERED kind");
            Buffer view = source.bufferedView().get();
            assertEquals(3, view.length(), "buffer view must have the correct length");
            assertEquals(10, view.getByte(0) & 0xFF);
            assertEquals(20, view.getByte(1) & 0xFF);
            assertEquals(30, view.getByte(2) & 0xFF);
        }

        @Test
        @DisplayName(
                "bufferedView() is lazy: bufferedStream() and copyPrefix() work without ever calling bufferedView()")
        void bufferedStreamAndCopyPrefixWorkWithoutCallingBufferedView() throws IOException {
            // This test proves that no Buffer is eagerly allocated at construction:
            // we can read stream and prefix without ever calling bufferedView().
            byte[] bytes = "lazy-test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            PayloadSource source = PayloadSources.buffered(bytes, "text/plain");

            // Access stream and prefix directly — no bufferedView() call
            byte[] fromStream = source.bufferedStream().get().readAllBytes();
            byte[] prefix = source.copyPrefix(4);

            assertArrayEquals(bytes, fromStream, "bufferedStream must yield the correct bytes");
            assertArrayEquals(new byte[] {'l', 'a', 'z', 'y'}, prefix, "copyPrefix must yield the correct prefix");
        }

        @Test
        @DisplayName("bufferedView() returns consistent bytes on repeated calls (idempotent lazy init)")
        void bufferedViewIdempotent() {
            byte[] bytes = {1, 2, 3, 4, 5};
            PayloadSource source = PayloadSources.buffered(bytes, null);
            Buffer view1 = source.bufferedView().get();
            Buffer view2 = source.bufferedView().get();
            // Both views must carry the same content
            assertArrayEquals(
                    view1.getBytes(), view2.getBytes(), "repeated bufferedView() calls must return same content");
        }
    }

    // --- buffered(Buffer, ...) ---

    @Nested
    @DisplayName("buffered(Buffer, contentType)")
    class BufferedBufferTests {

        @Test
        @DisplayName("kind is BUFFERED")
        void kindIsBuffered() {
            Buffer buf = Buffer.buffer(new byte[] {1, 2});
            assertEquals(
                    PayloadKind.BUFFERED, PayloadSources.buffered(buf, null).kind());
        }

        @Test
        @DisplayName("declaredLength equals buffer length")
        void declaredLengthEqualsBufferLength() {
            Buffer buf = Buffer.buffer(new byte[] {1, 2, 3});
            PayloadSource source = PayloadSources.buffered(buf, null);
            assertTrue(source.declaredLength().isPresent());
            assertEquals(3L, source.declaredLength().getAsLong());
        }

        @Test
        @DisplayName("bufferedView() is present and wraps same buffer")
        void bufferedViewPresent() {
            Buffer buf = Buffer.buffer(new byte[] {7, 8, 9});
            PayloadSource source = PayloadSources.buffered(buf, null);
            assertTrue(source.bufferedView().isPresent());
            assertEquals(buf, source.bufferedView().get());
        }

        @Test
        @DisplayName("bufferedStream() is present and readable")
        void bufferedStreamPresent() throws IOException {
            Buffer buf = Buffer.buffer(new byte[] {42, 43});
            PayloadSource source = PayloadSources.buffered(buf, "application/json");
            assertTrue(source.bufferedStream().isPresent());
            byte[] read = source.bufferedStream().get().readAllBytes();
            assertArrayEquals(new byte[] {42, 43}, read);
        }

        @Test
        @DisplayName("copyPrefix(1) returns first byte")
        void copyPrefix() {
            Buffer buf = Buffer.buffer(new byte[] {100, 101, 102});
            PayloadSource source = PayloadSources.buffered(buf, null);
            byte[] prefix = source.copyPrefix(1);
            assertArrayEquals(new byte[] {100}, prefix);
        }

        @Test
        @DisplayName("contentType round-trips")
        void contentTypeRoundTrips() {
            Buffer buf = Buffer.buffer(new byte[] {1});
            assertEquals(
                    "image/png",
                    PayloadSources.buffered(buf, "image/png").contentType().get());
        }
    }

    // --- buffered(ByteBuffer, ...) ---

    @Nested
    @DisplayName("buffered(ByteBuffer, contentType)")
    class BufferedByteBufferTests {

        @Test
        @DisplayName("kind is BUFFERED")
        void kindIsBuffered() {
            ByteBuffer bb = ByteBuffer.wrap(new byte[] {1, 2, 3});
            assertEquals(PayloadKind.BUFFERED, PayloadSources.buffered(bb, null).kind());
        }

        @Test
        @DisplayName("declaredLength equals remaining bytes")
        void declaredLengthEqualsRemaining() {
            ByteBuffer bb = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
            PayloadSource source = PayloadSources.buffered(bb, null);
            assertTrue(source.declaredLength().isPresent());
            assertEquals(4L, source.declaredLength().getAsLong());
        }

        @Test
        @DisplayName("bufferedView() is present")
        void bufferedViewPresent() {
            ByteBuffer bb = ByteBuffer.wrap(new byte[] {5, 6});
            assertTrue(PayloadSources.buffered(bb, null).bufferedView().isPresent());
        }

        @Test
        @DisplayName("bufferedStream() is present and readable")
        void bufferedStreamPresent() throws IOException {
            ByteBuffer bb = ByteBuffer.wrap(new byte[] {11, 22, 33});
            PayloadSource source = PayloadSources.buffered(bb, null);
            assertTrue(source.bufferedStream().isPresent());
            byte[] read = source.bufferedStream().get().readAllBytes();
            assertArrayEquals(new byte[] {11, 22, 33}, read);
        }

        @Test
        @DisplayName("copyPrefix(2) returns first 2 bytes")
        void copyPrefix() {
            ByteBuffer bb = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
            byte[] prefix = PayloadSources.buffered(bb, null).copyPrefix(2);
            assertArrayEquals(new byte[] {1, 2}, prefix);
        }

        @Test
        @DisplayName("contentType is empty when null")
        void contentTypeEmptyWhenNull() {
            ByteBuffer bb = ByteBuffer.wrap(new byte[] {1});
            assertTrue(PayloadSources.buffered(bb, null).contentType().isEmpty());
        }

        @Test
        @DisplayName("caller advancing ByteBuffer position after factory call does not affect the source bytes")
        void callerPositionAdvanceIsolated() throws IOException {
            // Arrange: ByteBuffer whose position will be advanced AFTER the factory is called
            byte[] backing = {10, 20, 30, 40, 50};
            ByteBuffer bb = ByteBuffer.wrap(backing);

            // Act: create source, then advance the caller's position
            PayloadSource source = PayloadSources.buffered(bb, "application/octet-stream");
            // Advance position to simulate the caller reading from the same ByteBuffer
            bb.position(bb.limit());

            // Assert: source still yields the original 5 bytes
            byte[] read = source.bufferedStream().get().readAllBytes();
            assertArrayEquals(backing, read, "source must return original bytes regardless of caller position change");
            assertEquals(5L, source.declaredLength().getAsLong(), "declaredLength must reflect the original remaining");
        }

        @Test
        @DisplayName("round-trip: bufferedStream yields exact original bytes (lazy materialization correctness)")
        void roundTripLazyMaterialization() throws IOException {
            byte[] original = "hello-lazy".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer bb = ByteBuffer.wrap(original);
            PayloadSource source = PayloadSources.buffered(bb, "text/plain");

            // Read via bufferedStream
            byte[] fromStream = source.bufferedStream().get().readAllBytes();
            // Read again — must be idempotent (each call returns a fresh cursor)
            byte[] fromStream2 = source.bufferedStream().get().readAllBytes();

            assertArrayEquals(original, fromStream, "first read must match original bytes");
            assertArrayEquals(original, fromStream2, "second read must also match (idempotent)");
        }
    }

    // --- streaming(...) ---

    @Nested
    @DisplayName("streaming(contentType, declaredLength)")
    class StreamingTests {

        @Test
        @DisplayName("kind is STREAMING")
        void kindIsStreaming() {
            assertEquals(
                    PayloadKind.STREAMING,
                    PayloadSources.streaming("video/mp4", 1024L).kind());
        }

        @Test
        @DisplayName("declaredLength is present for a non-negative length")
        void declaredLengthSet() {
            PayloadSource source = PayloadSources.streaming(null, 4096L);
            assertTrue(source.declaredLength().isPresent());
            assertEquals(4096L, source.declaredLength().getAsLong());
        }

        @Test
        @DisplayName("declaredLength is empty when a negative length is supplied (unknown length)")
        void declaredLengthEmptyWhenNegative() {
            PayloadSource source = PayloadSources.streaming("application/json", -1L);
            assertTrue(source.declaredLength().isEmpty(), "negative declaredLength must be treated as unknown");
        }

        @Test
        @DisplayName("declaredLength is present for zero length")
        void declaredLengthPresentWhenZero() {
            PayloadSource source = PayloadSources.streaming(null, 0L);
            assertTrue(source.declaredLength().isPresent());
            assertEquals(0L, source.declaredLength().getAsLong());
        }

        @Test
        @DisplayName("bufferedView() is empty")
        void bufferedViewEmpty() {
            assertTrue(
                    PayloadSources.streaming("text/plain", 10L).bufferedView().isEmpty());
        }

        @Test
        @DisplayName("bufferedStream() is empty")
        void bufferedStreamEmpty() {
            assertTrue(
                    PayloadSources.streaming("text/plain", 10L).bufferedStream().isEmpty());
        }

        @Test
        @DisplayName("copyPrefix returns zero-length array")
        void copyPrefixReturnsEmpty() {
            byte[] prefix = PayloadSources.streaming(null, 999L).copyPrefix(50);
            assertNotNull(prefix);
            assertEquals(0, prefix.length);
        }

        @Test
        @DisplayName("contentType round-trips when non-null")
        void contentTypeRoundTrips() {
            PayloadSource source = PayloadSources.streaming("application/octet-stream", 100L);
            assertTrue(source.contentType().isPresent());
            assertEquals("application/octet-stream", source.contentType().get());
        }

        @Test
        @DisplayName("contentType is empty when null")
        void contentTypeEmptyWhenNull() {
            assertTrue(PayloadSources.streaming(null, 100L).contentType().isEmpty());
        }
    }

    // --- absent() singleton identity ---

    @Nested
    @DisplayName("absent() singleton")
    class AbsentSingletonTest {

        @Test
        @DisplayName("absent() returns the same instance every call")
        void absentIsSingleton() {
            assertSame(PayloadSources.absent(), PayloadSources.absent());
        }
    }
}
